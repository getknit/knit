package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.BlindTag
import java.security.SecureRandom

/**
 * Which tag the advertiser airs at a rotation or a bring-up (ADR 2026-11.xp6d, "The tag"; the security pass of
 * 2026-11-02). A slotted tag is a pure function of (key, owner, slot, restart), so airing one (slot, restart) twice from
 * two advertiser addresses ties the addresses together. The wall clock alone cannot prevent that: a clock pushed forward
 * then corrected, pulled back, or stepped by NTP across a slot edge comes back to slots already aired, and a fifth
 * bring-up in one slot has no restart index left.
 *
 * So the schedule keeps a high-water mark per key generation, persisted through [MarkStore], and never returns a
 * (slot, restart) at or below it:
 *  - in a slot above the mark, restart 0; in the mark's own slot, the next restart;
 *  - when the clock's slot is behind the mark, or the mark's slot has used all [BlindTag.RESTARTS], a fresh random tag
 *    (the `zzzz` prefix and 108 random bits) that no contact resolves, until the clock passes the mark;
 *  - a new key generation has no mark, so it starts at the current slot, restart 0.
 *
 * **A stale mark.** A clock pushed ahead and then corrected leaves the mark in the future, and every tag would be
 * random for as long as the clock was pushed. When the mark is more than [BlindTag.WINDOW_AHEAD_SLOTS] slots ahead of
 * the clock, the answer is [Next.stale]: the tag is still random (and never repeats), and the wiring starts a new
 * presence-key generation, which has no mark. The weekly key turnover reads the same future-dated stamp, so it must
 * treat a creation time in the future as due.
 *
 * **One tag per rotation, announced ahead.** A `TAG_NEXT` must name the tag that is then aired. The wiring takes the
 * coming slot's tag with [reserve] when it announces, and airs exactly those bytes at the rotation with [take]; a
 * second [next] at the slot edge would air the next restart instead. A bring-up between the announcement and the
 * rotation finds the mark at the reserved slot, ahead of the clock, and airs a random tag: acceptable for that lead.
 *
 * **Whose tag.** A tag is minted under this phone's own node id ([localId]), as a contact's [ExpectedTagTable] lists it.
 * [localId] is read at each choice, never at construction: the transport builds its schedule in a field initializer,
 * before `start` has set the id. An id with no canonical bytes throws, as the reader lists nothing for one.
 *
 * **Durability and threads.** The tag is computed first and the mark written before it is returned, so the wiring airs a
 * tag only after its mark is durable: a crash or power loss between the two costs one unused (slot, restart), never a
 * repeat, and nothing that can refuse the tag runs after the mark, so no failure burns one. A store that fails throws, and
 * the throw propagates: nothing is returned, so nothing is aired. [next], [reserve] and [take] write
 * to storage, so the wiring calls them only on the transport's confined worker, never on the main thread (the
 * `ACTION_STATE_CHANGED` receiver runs there) and never under the transport's `lock`; `readvertise` takes the tag only
 * after the call returns. Marks are cached in memory per generation, so the store is read once per generation.
 *
 * **Diagnostics.** [Next.resolvable] and [Next.stale] are for diagnostics only. They never reach anything aired or
 * synced, and in the field log they are counts, never timestamped events: either one shows that the clock was moved.
 *
 * Only the tag is chosen here. When to rotate (the first wake after a slot edge, every bring-up, a rotation held for a
 * Coded fast window), announcing it and restarting both sets with it are the wiring's. Pure: no Android, the wall
 * clock passed in, single-threaded with its owner.
 */
internal class TagSchedule(
    private val store: MarkStore,
    private val random: SecureRandom = SecureRandom(),
    private val localId: () -> String,
) {
    /** The last (slot, restart) aired or reserved under one key generation. */
    data class Mark(
        val slot: Long,
        val restart: Int,
    )

    /**
     * Where the marks persist, per key generation. The contract, which the wiring's implementation keeps:
     *  - **Its own row.** The mark lives in the presence key's own local-only row: written and deleted with that key,
     *    pruned with its generation, and never in a backup (a restored mark from an older backup would let a slot
     *    repeat).
     *  - **[read] throws on failure** (I/O, decryption). It returns null only for a generation that has never aired a
     *    tag: a null on error would restart the generation at slot 0 of today and repeat what it aired.
     *  - **[write] throws on failure** and never swallows one; [TagSchedule] lets it propagate and returns no tag.
     *  - **Durable means power loss.** [write] returns once the mark survives losing power, not only a process crash:
     *    SQLite in WAL mode with `synchronous=NORMAL` can roll back the last commit on power loss, so the row's write
     *    runs at `synchronous=FULL` (or an equivalent sync).
     */
    interface MarkStore {
        fun read(generation: Long): Mark?

        fun write(
            generation: Long,
            mark: Mark,
        )
    }

    /**
     * The tag to air. [resolvable] is false for a random fallback; [stale] says the mark is ahead of the clock by more
     * than the contact window's lead, so the wiring should start a new key generation. Both are diagnostics (see the
     * class KDoc).
     */
    class Next(
        val tag: ByteArray,
        val resolvable: Boolean,
        val stale: Boolean,
    ) {
        override fun toString(): String = "Next(resolvable=$resolvable, stale=$stale)"
    }

    /** Each generation's mark once read from the store (a null value: the store had none). */
    private val marks = HashMap<Long, Mark?>()

    /** Each generation's reservation, with the slot it was reserved for. */
    private val reserved = HashMap<Long, Pair<Long, Next>>()

    /** The tag to air now (a bring-up, or a rotation nobody announced) under [key] of [generation]; its mark first. */
    fun next(
        key: ByteArray,
        generation: Long,
        wallMs: Long,
    ): Next = choose(key, generation, wallMs, BlindTag.slotOf(wallMs))

    /**
     * Reserves the tag for the slot after [wallMs]'s, to announce in `TAG_NEXT` and air with [take] at the rotation. Its
     * mark is written now. Only the first announcement for a coming slot reserves: another call for the same slot and
     * generation returns the same tag, so a link that comes up inside the lead is told the same tag and no restart is
     * spent. A call for a later slot replaces the reservation (the replaced tag stays unused, never aired).
     */
    fun reserve(
        key: ByteArray,
        generation: Long,
        wallMs: Long,
    ): Next {
        val slot = BlindTag.slotOf(wallMs) + 1
        reserved[generation]?.let { (held, next) -> if (held == slot) return next }
        return choose(key, generation, wallMs, slot).also { reserved[generation] = slot to it }
    }

    /**
     * The tag [reserve] announced for [generation], to air now, exactly, if it was reserved for the slot of [wallMs];
     * null otherwise, and the reservation is dropped either way. A rotation that comes slots late (a long sleep, an
     * adapter off) finds a stale reservation and falls back to [next]; its mark was written, so nothing repeats. The
     * wiring also drops reservations itself (a [take] it discards) on adapter OFF and on any key generation change.
     */
    fun take(
        generation: Long,
        wallMs: Long,
    ): Next? {
        val (slot, next) = reserved.remove(generation) ?: return null
        return next.takeIf { slot == BlindTag.slotOf(wallMs) }
    }

    /**
     * Drops every reservation, unaired (their marks stay written, so nothing repeats). The wiring calls it on the worker
     * step that receives a new own key and at adapter OFF, so a tag reserved under a key that has since gone is never
     * taken.
     */
    fun dropReservations() {
        reserved.clear()
    }

    /**
     * A fresh random prefixed tag that no contact resolves, with no mark written: what the writer airs while it holds no
     * key, when a mark write failed, or when the key it would air under is no longer the engine's current one.
     */
    fun random(): Next = Next(randomTag(), resolvable = false, stale = false)

    private fun choose(
        key: ByteArray,
        generation: Long,
        wallMs: Long,
        slot: Long,
    ): Next {
        val owner = checkNotNull(NodeId.toBytesOrNull(localId())) { "this phone's node id has no canonical bytes" }
        val mark = markOf(generation)
        val stale = mark != null && mark.slot > BlindTag.slotOf(wallMs) + BlindTag.WINDOW_AHEAD_SLOTS
        val chosen =
            when {
                mark == null || slot > mark.slot -> Mark(slot, 0)
                slot == mark.slot && mark.restart < BlindTag.RESTARTS - 1 -> Mark(slot, mark.restart + 1)
                else -> null
            }
        if (chosen == null) return Next(randomTag(), resolvable = false, stale = stale)
        val tag = BlindTag.tag(key, owner, chosen.slot, chosen.restart)
        store.write(generation, chosen)
        marks[generation] = chosen
        return Next(tag, resolvable = true, stale = false)
    }

    private fun markOf(generation: Long): Mark? =
        if (marks.containsKey(generation)) marks[generation] else store.read(generation).also { marks[generation] = it }

    /** The `zzzz` prefix (bits 0..19, as [BlindTag] lays it out) over 108 random bits: resolvable by no one. */
    private fun randomTag(): ByteArray {
        val tag = ByteArray(BlindTag.BYTES).also(random::nextBytes)
        tag[0] = PREFIX_0
        tag[1] = PREFIX_1
        tag[2] = (PREFIX_2_HIGH or (tag[2].toInt() and LOW_NIBBLE)).toByte()
        return tag
    }

    private companion object {
        const val PREFIX_0 = 0xCE.toByte()
        const val PREFIX_1 = 0x73.toByte()
        const val PREFIX_2_HIGH = 0x90
        const val LOW_NIBBLE = 0x0F
    }
}
