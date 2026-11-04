package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.PresenceKeyPayload

/**
 * The presence key's rules (ADR 2026-11.xp6d, "The presence key"; knit-next #285), pure: which own key rotation is
 * due, what a linked contact is owed, what a received key does to the keys held for its sender, and which stored keys
 * may be listed. The key engine (`mesh/PresenceKeyEngine`) and the inbound arm (`InboundPipeline`) wire them; nothing
 * here reads a clock, a store or a link.
 *
 * **Own keys.** At most three rows: the previous key, the current one (the newest activated) and the next one (never
 * activated, never aired; handed to contacts ahead so a weekly rotation is seamless). Each row names its audience: the
 * direct contacts it may be handed to. [due] is a function of those rows, a fresh read of the direct contacts and the
 * wall clock alone — the stamps on a row never feed back as a generation, so a corrected clock rotates once, then is
 * quiet.
 *
 * **Generations.** `max(last + 1, now)` with the add checked ([nextGeneration]): an order only, never a time. A restored
 * `last` more than a year past the clock is clamped to the clock ([clampRestored]); every session re-roots on a restore,
 * so a receiver takes the lower generations that follow as a new era ([receive]).
 */
object PresenceKeyPolicy {
    /** A weekly rotation: the next key becomes the current one once the current one has been aired this long. */
    const val WEEK_MS = 7 * 24 * 60 * 60_000L

    /**
     * How long after its activation a current key may still take a new contact (security N3): a contact accepted later
     * never gets a key whose tags were aired for days before. Also the floor between two clock-or-mark advances, on the
     * elapsed clock (`PresenceKeyEngine`): a spoofed wall clock cannot burn a generation every heal.
     */
    const val DAY_MS = 24 * 60 * 60_000L

    /**
     * How far past the wall clock a current key's activation may sit before it reads as a clock that ran ahead and was
     * corrected (rule 4). An hour, so a minute of NTP correction never rotates.
     */
    const val CLOCK_SLACK_MS = 60 * 60_000L

    /** A restored last generation further than this past the clock is clamped to the clock ([clampRestored]). */
    const val CLAMP_AHEAD_MS = 365 * DAY_MS

    /** The generations kept per peer: the three newest. */
    const val PEER_GENERATIONS = 3

    /** One own key row as the policy reads it. [activatedAt] null is the next key. [markSlot] is the advertiser's. */
    class Own(
        val generation: Long,
        val key: ByteArray,
        val mintedAt: Long,
        val activatedAt: Long?,
        val audience: Set<String>,
        val markSlot: Long? = null,
    ) {
        fun copy(
            activatedAt: Long? = this.activatedAt,
            audience: Set<String> = this.audience,
        ): Own = Own(generation, key, mintedAt, activatedAt, audience, markSlot)

        // Never the key bytes or the audience (ADR 2026-11.xp6d, "Logging").
        override fun toString(): String = "Own(g=$generation, activated=${activatedAt != null}, audience=${audience.size})"
    }

    /** The rows read as the three roles. */
    class Keys(
        val previous: Own?,
        val current: Own?,
        val next: Own?,
    )

    /** [rows] as previous, current and next: the newest activated is current, the one before it previous. */
    fun keysOf(rows: List<Own>): Keys {
        val activated = rows.filter { it.activatedAt != null }.sortedByDescending { it.generation }
        return Keys(
            previous = activated.getOrNull(1),
            current = activated.firstOrNull(),
            next = rows.filter { it.activatedAt == null }.maxByOrNull { it.generation },
        )
    }

    /** Why an [Due.Advance] is due. */
    enum class AdvanceCause {
        /** The current key has been aired a week. */
        WEEKLY,

        /** The current key was activated more than [CLOCK_SLACK_MS] ahead of the clock: a clock that ran ahead, corrected. */
        CLOCK,

        /** The advertiser's mark under the current key sits past the contact window's lead (`TagSchedule`'s stale mark). */
        MARK,
    }

    /** What [due] asks the engine to do next. The engine applies one and asks again, until [None]. */
    sealed interface Due {
        /** Nothing: the keys are as they should be. */
        data object None : Due

        /** No current key: mint a current and a next key, each for the direct contacts read now. */
        data object Mint : Due

        /**
         * A key's audience holds someone who is no longer a direct contact (a removal, a block, an un-verify): discard
         * the next key and mint a new current and next. Never rate-limited.
         */
        data object Fresh : Due

        /** The next key becomes the current one, and a new next key is minted. */
        data class Advance(
            val cause: AdvanceCause,
        ) : Due

        /** No next key: mint one. */
        data object MintNext : Due

        /**
         * New direct contacts join the next key's audience ([toNext]), and the current key's ([toCurrent]) only while it
         * was activated under a day ago. No rotation.
         */
        data class Grow(
            val toNext: Set<String>,
            val toCurrent: Set<String>,
        ) : Due
    }

    /**
     * The next own-key step, in the brief's order: no current → [Due.Mint]; a shrink → [Due.Fresh]; a week → weekly
     * [Due.Advance]; a corrected clock or a stale mark → [Due.Advance], only while [clockAdvanceAllowed] (the engine's
     * once-a-day floor on the elapsed clock); no next → [Due.MintNext]; a new contact → [Due.Grow]. [slotOf] maps the
     * wall clock to the advertiser's slot ([BlindTag.slotOf]); [windowAheadSlots] is the contact window's lead.
     */
    @Suppress("ReturnCount") // the brief's ordered rule list; each rule's early return is the readable form
    fun due(
        rows: List<Own>,
        direct: Set<String>,
        nowWall: Long,
        clockAdvanceAllowed: Boolean,
        slotOf: (Long) -> Long,
        windowAheadSlots: Long,
    ): Due {
        val keys = keysOf(rows)
        val current = keys.current ?: return Due.Mint
        val next = keys.next
        if ((current.audience - direct).isNotEmpty() || (next != null && (next.audience - direct).isNotEmpty())) return Due.Fresh
        val activatedAt = checkNotNull(current.activatedAt)
        if (nowWall >= activatedAt && nowWall - activatedAt >= WEEK_MS) return Due.Advance(AdvanceCause.WEEKLY)
        if (clockAdvanceAllowed) {
            if (activatedAt - nowWall > CLOCK_SLACK_MS) return Due.Advance(AdvanceCause.CLOCK)
            val mark = current.markSlot
            if (mark != null && mark - slotOf(nowWall) > windowAheadSlots) return Due.Advance(AdvanceCause.MARK)
        }
        if (next == null) return Due.MintNext
        val toNext = direct - next.audience
        if (toNext.isEmpty()) return Due.None
        val age = nowWall - activatedAt
        val toCurrent = if (age in -CLOCK_SLACK_MS until DAY_MS) toNext - current.audience else emptySet()
        return Due.Grow(toNext = toNext, toCurrent = toCurrent)
    }

    /** The generation a mint takes: `max(last + 1, now)`, or null when `last + 1` overflows (nothing is minted). */
    fun nextGeneration(
        last: Long,
        nowWall: Long,
    ): Long? {
        val after = runCatching { Math.addExact(last, 1L) }.getOrNull() ?: return null
        return maxOf(after, nowWall, 1L)
    }

    /**
     * A restored last generation, clamped to [nowWall] when it sits more than [CLAMP_AHEAD_MS] past it (a backup made
     * while the clock ran far ahead). Null when nothing changes. Only at a restore: every session re-roots there, so the
     * lower generations that follow are a new era to every receiver, never an out-of-order key.
     */
    fun clampRestored(
        last: Long,
        nowWall: Long,
    ): Long? {
        val bound = runCatching { Math.addExact(nowWall, CLAMP_AHEAD_MS) }.getOrDefault(Long.MAX_VALUE)
        return if (last > bound) nowWall else null
    }

    /**
     * The keys [peer] is owed under [keys]: the current key if the peer is in its audience, the next one if in its,
     * lowest generation first. Nothing for anyone else. The caller has checked the link, the direct-contact read, the
     * session and the capability (the brief's "owed, per live-linked peer").
     */
    fun owed(
        peer: String,
        keys: Keys,
    ): List<Own> = listOfNotNull(keys.current?.takeIf { peer in it.audience }, keys.next?.takeIf { peer in it.audience })

    /**
     * Who left an own key's audience while a kept generation was live (step 5b's `TAG_NEXT` exclusion): everyone named in
     * a kept row's audience who is not in [direct], the direct contacts read now. Settled with own keys on, the current
     * and next audiences hold direct contacts only, so this is the previous key's audience less the direct contacts: the
     * departures that caused the last fresh pair. It empties when that generation goes (the next advance), and an id that
     * becomes a direct contact again leaves it at once. A leaver who held only a discarded next key (never aired) is not
     * named: no kept row records it, and it holds no key that ever aired. Read with own keys off too (rows an earlier
     * build minted): anyone they name who is no longer a direct contact.
     */
    fun departed(
        rows: List<Own>,
        direct: Set<String>,
    ): Set<String> = rows.flatMapTo(HashSet()) { it.audience } - direct

    /**
     * Whether a linked peer may be sent this phone's next advert tag (`TAG_NEXT`, ADR 2026-11.xp6d's step 5b, W5): not
     * [blocked] and not [departed]. A null input is one not read yet this session, and answers no: a send the predicate
     * refuses costs a linked peer one dial of an unannounced tag, while a send to someone just blocked or removed hands
     * them the tag the fresh rotation was for.
     */
    fun hearsTagNext(
        nodeId: String,
        blocked: Set<String>?,
        departed: Set<String>?,
    ): Boolean = blocked != null && departed != null && nodeId !in blocked && nodeId !in departed

    /** What a restore's link-owed reset does on a link ([linkReset]). */
    enum class LinkReset {
        /** Resend the kept frame verbatim: its root is still the session's and its init still against the pinned prekey. */
        RESEND,

        /** Seal it (through the reset's ordinary `AFTER_WIPE` plan): nothing kept, or the kept init's prekey moved on. */
        SEAL,

        /**
         * Seal the marker under our own pending reset init: nothing kept, yet the session is our own unanswered reset — a
         * frame sealed and lost before it was kept (a crash between the seal and the write, or before the send).
         */
        REMARK,

        /** Drop the entry: another reset of ours replaced the kept one and goes by its own route. */
        SUPERSEDED,
    }

    /**
     * The link-owed reset's step (crypto review of 2026-11-02, items 1 and 2), the session unconfirmed: [keptTag] is the
     * root the kept frame's seal committed (null with nothing kept), [currentTag] the session's root now, [ownPendingReset]
     * whether the session is our own unanswered reset, and [initOnPinnedPrekey] whether its init is against the prekey the
     * peer's profile pins. A kept frame whose root another reset replaced is never resent: the peer could adopt it after
     * the one that replaced it and refuse that one inside its floor, stranding the pair.
     */
    fun linkReset(
        keptTag: Long?,
        currentTag: Long?,
        ownPendingReset: Boolean,
        initOnPinnedPrekey: Boolean,
    ): LinkReset =
        when {
            keptTag != null && keptTag != currentTag -> LinkReset.SUPERSEDED
            keptTag != null && initOnPinnedPrekey -> LinkReset.RESEND
            keptTag != null -> LinkReset.SEAL
            ownPendingReset && initOnPinnedPrekey -> LinkReset.REMARK
            else -> LinkReset.SEAL
        }

    /** One key a peer handed us, as [receive] reads it. */
    class Held(
        val generation: Long,
        val key: ByteArray,
        val rootTag: Long,
    ) {
        override fun toString(): String = "Held(g=$generation)"
    }

    /** Why [receive] refused a frame. Every refusal is counted and changes nothing. */
    enum class Refusal {
        /** Not 32 bytes, a generation not positive, half a next pair, or a next generation not above the first. */
        MALFORMED,

        /** From a sender with no peer row, or from ourselves. */
        UNKNOWN_SENDER,

        /** A generation already held under this era, with other bytes. */
        CONFLICT,

        /** A generation lower than every key held, in the same era (or a drain open, which never starts one). */
        OLDER,
    }

    /** What [receive] decided. */
    sealed interface Verdict {
        /**
         * Write [rows] (the keys held for the peer afterwards, at most [PEER_GENERATIONS]). [replaced] is a new era:
         * every key held before goes. [added] counts the generations this frame added.
         */
        class Store(
            val rows: List<Held>,
            val replaced: Boolean,
            val added: Int,
        ) : Verdict

        /** Every generation it carries is held with the same bytes: a re-send. Not counted. */
        data object NoOp : Verdict

        class Refused(
            val why: Refusal,
        ) : Verdict
    }

    /** [payload]'s keys, lowest generation first, or null when malformed (the [Refusal.MALFORMED] rules). */
    fun keysIn(payload: PresenceKeyPayload): List<Pair<Long, ByteArray>>? {
        if (payload.key.size != BlindTag.KEY_BYTES || payload.generation <= 0L) return null
        val first = payload.generation to payload.key
        val nextGeneration = payload.nextGeneration
        val nextKey = payload.nextKey
        if ((nextGeneration == null) != (nextKey == null)) return null
        if (nextGeneration == null || nextKey == null) return listOf(first)
        if (nextKey.size != BlindTag.KEY_BYTES || nextGeneration <= payload.generation) return null
        return listOf(first, nextGeneration to nextKey)
    }

    /**
     * What a received ctl 11 does to the keys [held] for its sender, given the root it opened under ([openedTag]) and
     * whether that is the session's active root ([active]; the pz9g predicate, `RatchetSessions.OpenedUnder`).
     *
     *  - **Same era** (the held keys carry [openedTag]): a generation not held is added; an equal one with equal bytes
     *    is nothing; an equal one with other bytes, or one lower than every held one, refuses the frame.
     *  - **New era** (an active-root open, the held keys under another tag or none held): the frame's keys replace every
     *    held one, under [openedTag]. A restored sender whose clock-ahead generation went backwards lands here (B3).
     *  - **Drain** (not active, the held keys under another tag): ordered against the held keys as in the same era, and
     *    added under the held keys' tag — a drain never starts an era and never changes a tag. With nothing held, its
     *    keys are stored under [openedTag], and the next active-root open replaces them as a new era (crypto R3).
     *
     * The result keeps the [PEER_GENERATIONS] newest.
     */
    fun receive(
        held: List<Held>,
        frame: List<Pair<Long, ByteArray>>,
        openedTag: Long,
        active: Boolean,
    ): Verdict {
        if (held.isEmpty()) {
            return Verdict.Store(
                trim(frame.map { Held(it.first, it.second, openedTag) }),
                replaced = false,
                added = frame.size,
            )
        }
        val heldTag = held.first().rootTag
        val sameEra = held.all { it.rootTag == openedTag }
        if (!sameEra && active) {
            return Verdict.Store(trim(frame.map { Held(it.first, it.second, openedTag) }), replaced = true, added = frame.size)
        }
        // The same era, or a drain ordered against what is held: the held keys' own tag either way.
        val lowest = held.minOf { it.generation }
        val added = mutableListOf<Held>()
        for ((generation, key) in frame) {
            val same = held.firstOrNull { it.generation == generation }
            when {
                same != null && same.key.contentEquals(key) -> continue
                same != null -> return Verdict.Refused(Refusal.CONFLICT)
                generation < lowest -> return Verdict.Refused(Refusal.OLDER)
                else -> added += Held(generation, key, heldTag)
            }
        }
        if (added.isEmpty()) return Verdict.NoOp
        return Verdict.Store(trim(held + added), replaced = false, added = added.size)
    }

    private fun trim(rows: List<Held>): List<Held> = rows.sortedByDescending { it.generation }.take(PEER_GENERATIONS)

    /**
     * The read side's listing (security N7): per peer in [stored], its keys, leaving out — and counting in the second
     * value — every key byte-equal to a key another listed peer holds, or to one of [own]. Never "first stored wins": a
     * re-sealed copy that outran its owner's own ctl must not take the owner's tags, so a shared key is listed for no one.
     */
    fun listing(
        stored: Map<String, List<Held>>,
        own: List<ByteArray>,
    ): Pair<Map<String, List<Held>>, Int> {
        val owners = HashMap<KeyBytes, MutableSet<String>>()
        stored.forEach { (peer, keys) -> keys.forEach { owners.getOrPut(KeyBytes(it.key)) { HashSet() } += peer } }
        val ours = own.mapTo(HashSet()) { KeyBytes(it) }
        var excluded = 0
        val listed =
            stored.mapValues { (_, keys) ->
                keys.filter { held ->
                    val bytes = KeyBytes(held.key)
                    val shared = bytes in ours || (owners[bytes]?.size ?: 0) > 1
                    if (shared) excluded++
                    !shared
                }
            }
        return listed to excluded
    }

    /** Byte-content equality for a key in a set. */
    private class KeyBytes(
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is KeyBytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
