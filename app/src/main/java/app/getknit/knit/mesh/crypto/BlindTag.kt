package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The rotating tag a blinding phone writes in bytes 1–16 of its Bluetooth presence advert in place of its node id
 * (ADR 2026-11.xp6d, knit-next #285; the iOS port mirrors it byte for byte). A direct contact holding the sender's
 * presence key resolves it; anyone else sees 16 bytes that change at every rotation. Pure: JCA's HMAC-SHA256, no
 * Android, so it runs unchanged on the JVM.
 *
 * ```
 * m = HMAC-SHA256(presenceKey, "knit/advert/v1/slot" (19 ASCII) ‖ owner (16 raw) ‖ u64be(slot) ‖ u8(restart))
 *                                                                                               (44-byte message)
 *
 * byte 0      0xCE                              ┐ bits 0..19: 11001 ×4, the `zzzz` prefix
 * byte 1      0x73                              │
 * byte 2      0x90 | (m[0] >>> 4)               ┘ high nibble 0x9; low nibble = m[0]'s HIGH nibble
 * bytes 3..15 m[1..13]                            13 bytes, byte-aligned; m[0]'s low nibble and m[14..31] unused
 * ```
 *
 * `owner` is the sender's own node id as its raw [NodeId.BYTES]; `slot` is [slotOf] the sender's wall clock (u64
 * big-endian, two's complement for the pre-1970 slots no phone has); `restart` is 0..[RESTARTS]−1. That is 108 MAC bits
 * per tag.
 *
 * Why this shape:
 *  - **The `zzzz` prefix.** `11001` is base32 `z`, so the tag read as a node id ([app.getknit.knit.identity.NodeId.fromBytes])
 *    starts `zzzz` and sorts above all but about 2⁻²⁰ of real ids. A shipped build dials only a smaller advertised
 *    id (`DialPlan.ordinaryCandidates`), so it leaves the dial to the blinding phone and the advert needs no new
 *    service UUID. [hasPrefix] refuses most clear ids on the fixed bits alone.
 *  - **The owner.** Without it two phones holding one key air the same tags, and a contact could darken another by
 *    re-sending that one's key as its own (the 2026-11-05 design review, finding 1). Under the owner's id a re-gifted
 *    key gives the re-sender tags of its own and leaves the owner's alone, so a key needs no uniqueness rule anywhere.
 *    The writer's id and the id a contact lists the key under are one: the sender of the ctl 11 that delivered it.
 *  - **Time slots, not a nonce (revised 2026-11-02).** The first cut drew a random nonce per tag, so a contact could
 *    only resolve a heard tag by HMACing it under every key it held, and every unknown tag cost that sweep. Each
 *    budget guarding the sweep (first come, second sighting, ranking, an LRU memo) gave a radio flooder a lever: air
 *    fresh tags and the budget goes to them, not to the contact standing next to you. A slotted tag is a function of
 *    (key, owner, slot, restart) only, so a contact precomputes every direct contact's expected tags for its window
 *    ([Expected], [window]) into a lookup table off the radio path, and resolving a heard tag is one hash lookup. An
 *    unknown tag, a flood of them included, costs nothing but the miss: there is no per-tag work left to exhaust.
 *  - **Restarts.** A bring-up inside one slot (a radio toggle, a process restart) must air a tag never aired before,
 *    or the two advertiser addresses would be tied together by it. A tag is a pure function of (key, owner, slot,
 *    restart), so it repeats whenever the advertiser asks for a pair it already aired: a fifth bring-up in one slot, or
 *    a wall clock set back or corrected across a slot edge. The advertiser's `TagSchedule` (mesh/bluetooth) therefore keeps
 *    a persisted high-water mark per key generation, never airs a (slot, restart) at or below it, and airs a fresh
 *    random prefixed tag (one no contact resolves) until the clock passes the mark. The resolver lists all [RESTARTS]
 *    per slot.
 *  - **108 MAC bits.** A forger without the key lands one tag in 2¹⁰⁸ per try; a table of N contacts × [window]
 *    × [RESTARTS] entries mistakes a stranger's tag for a contact's at about that many in 2¹⁰⁸.
 *  - **Domain separation.** The presence key is used for nothing but this MAC, and `knit/advert/v1/slot` shares no
 *    prefix with the other labels (`knit/link/v1/…`, `knit/dm/v2/…`, `knit/group/v1/…`, `knit/card/v1`), nor with the
 *    withdrawn nonce construction's `knit/advert/v1/tag`. The message is fixed-length (19 + 16 + 8 + 1), so
 *    no two inputs concatenate alike. The label stays `v1` beside the owner because no release carried the 28-byte
 *    message that came before it: there is no older tag to keep apart from.
 *
 * **Clocks.** The slot is the sender's wall clock and the window is the contact's, so a contact recognises a tag only
 * while the tag's slot falls in the contact's [window] (the last 8 hours and the next half hour). A phone whose clock
 * is off by more than that — most commonly one running ahead by more than half an hour — is not recognised before a
 * link: it falls back to linking as a stranger, and the link's proof names it. No skew is corrected here.
 *
 * **Replay.** A tag is a pure function of its slot, so anyone who heard it can re-air it, and a contact resolves the
 * replay for as long as that slot stays in its window: about 8 hours. A resolved tag is therefore a hint about who is
 * near, never proof; the link's HELLO proves the id (ADR 2026-11.xp6d, "Who dials" and the claim rules).
 *
 * [tag] throws on a caller's bug (a key, owner or restart out of range: our own key, id and counter). [Expected] and
 * [hasPrefix] are total, because their inputs are what a peer delivered or a stranger's radio put on the air.
 */
object BlindTag {
    /** A tag fills the node id's slot in the advert, so it is exactly `NodeId.BYTES` (pinned by `BlindTagTest`). */
    const val BYTES = 16

    /** A presence key: 32 bytes from [SecureRandom], one per generation. */
    const val KEY_BYTES = 32

    const val LABEL = "knit/advert/v1/slot"

    /** A slot is 15 minutes of wall clock: the rotation period. */
    const val SLOT_MS = 900_000L

    /** Restart indices per slot, 0..3 (see the class KDoc). */
    const val RESTARTS = 4

    /** The contact's window: the last 8 hours of slots (32), the current one, and the next half hour (2). */
    const val WINDOW_PAST_SLOTS = 32L
    const val WINDOW_AHEAD_SLOTS = 2L

    /** `11001` ×4 left-aligned in bytes 0..2: 0xCE 0x73 0x9_. */
    private const val PREFIX_0 = 0xCE.toByte()
    private const val PREFIX_1 = 0x73.toByte()
    private const val PREFIX_2_HIGH = 0x90
    private const val HIGH_NIBBLE = 0xF0
    private const val NIBBLE_BITS = 4

    /** Byte 2: the prefix's last nibble above the MAC's first. */
    private const val SPLIT_BYTE = 2

    private const val HMAC = "HmacSHA256"

    private val LABEL_BYTES = LABEL.toByteArray(Charsets.US_ASCII)

    /** The slot holding [wallMs]: floored, so a negative time lands in the slot below, not the one toward zero. */
    fun slotOf(wallMs: Long): Long = Math.floorDiv(wallMs, SLOT_MS)

    /** The slots a contact whose wall clock reads [wallMs] resolves: `slotOf(wallMs) − 32 .. slotOf(wallMs) + 2`. */
    fun window(wallMs: Long): LongRange = slotOf(wallMs).let { (it - WINDOW_PAST_SLOTS)..(it + WINDOW_AHEAD_SLOTS) }

    /** A fresh presence key. */
    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /**
     * The tag under [key] and [owner] (the raw node id of the phone airing it) for [slot] and [restart]. Deterministic.
     * Throws on a key that is not [KEY_BYTES], an owner that is not [NodeId.BYTES] or a restart outside 0..[RESTARTS]−1:
     * the advertiser's own key, id and counter, so a caller's bug.
     */
    fun tag(
        key: ByteArray,
        owner: ByteArray,
        slot: Long,
        restart: Int,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "a presence key is $KEY_BYTES bytes" }
        require(owner.size == NodeId.BYTES) { "an owner is a raw node id, ${NodeId.BYTES} bytes" }
        require(restart in 0 until RESTARTS) { "a restart index is 0..${RESTARTS - 1}" }
        return compute(newMac(key), owner, slot, restart)
    }

    /** [tag] is [BYTES] long and carries the 20 fixed bits. Says nothing about whose it is. Total. */
    fun hasPrefix(tag: ByteArray): Boolean =
        tag.size == BYTES &&
            tag[0] == PREFIX_0 &&
            tag[1] == PREFIX_1 &&
            (tag[SPLIT_BYTE].toInt() and HIGH_NIBBLE) == PREFIX_2_HIGH

    /** One expected tag: its bytes, and the slot and restart index that produced it. */
    class Slotted(
        val tag: ByteArray,
        val slot: Long,
        val restart: Int,
    )

    /**
     * One key's expected tags under one owner, its [Mac] initialised once, for a resolver building its lookup table (ADR
     * 2026-11.xp6d, `AdvertAliases`): every direct contact's key gets one, and the table maps each [Slotted.tag] to the
     * key's owner. Rebuilt per slot boundary by listing only the new slots; nothing here runs per heard tag.
     *
     * Total: a key that is not [KEY_BYTES] (a malformed key a peer delivered) or an owner that is not [NodeId.BYTES] (null
     * for an id with no canonical bytes) lists nothing and [tag] answers null, so it never throws out of a table rebuild.
     * Not thread-safe: a [Mac] holds state across `update` / `doFinal`, so one
     * builder belongs to one thread at a time. Each tag is computed whole inside one call, so two [tags] sequences of
     * one builder may interleave on that thread. Every returned array is fresh; the caller may keep it.
     */
    class Expected(
        key: ByteArray,
        owner: ByteArray?,
    ) {
        private val owner: ByteArray? = owner?.takeIf { it.size == NodeId.BYTES }?.copyOf()
        private val mac: Mac? = if (key.size == KEY_BYTES && this.owner != null) runCatching { newMac(key) }.getOrNull() else null

        /** The tag for [slot] and [restart], or null for a bad key or owner, or a restart outside 0..[RESTARTS]−1. */
        fun tag(
            slot: Long,
            restart: Int,
        ): ByteArray? {
            val mac = mac ?: return null
            val owner = owner ?: return null
            if (restart !in 0 until RESTARTS) return null
            return runCatching { compute(mac, owner, slot, restart) }.getOrNull()
        }

        /**
         * Every restart of every slot in [slots], slot-major (`slot, 0..3`), lazily: `|slots| × RESTARTS` HMACs if
         * drained. A window ([BlindTag.window]) is 35 slots, 140 tags. Empty for a bad key or owner.
         */
        fun tags(slots: LongRange): Sequence<Slotted> =
            if (mac == null) {
                emptySequence()
            } else {
                slots.asSequence().flatMap { slot ->
                    (0 until RESTARTS).asSequence().mapNotNull { restart -> tag(slot, restart)?.let { Slotted(it, slot, restart) } }
                }
            }
    }

    private fun newMac(key: ByteArray): Mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }

    /** The layout in the class KDoc. */
    private fun compute(
        mac: Mac,
        owner: ByteArray,
        slot: Long,
        restart: Int,
    ): ByteArray {
        mac.reset() // a builder whose last call threw mid-update must not carry its bytes into this one
        mac.update(LABEL_BYTES)
        mac.update(owner)
        for (shift in Long.SIZE_BITS - Byte.SIZE_BITS downTo 0 step Byte.SIZE_BITS) mac.update((slot ushr shift).toByte())
        mac.update(restart.toByte())
        val m = mac.doFinal()
        val tag = ByteArray(BYTES)
        tag[0] = PREFIX_0
        tag[1] = PREFIX_1
        tag[SPLIT_BYTE] = (PREFIX_2_HIGH or ((m[0].toInt() and HIGH_NIBBLE) ushr NIBBLE_BITS)).toByte()
        m.copyInto(tag, destinationOffset = SPLIT_BYTE + 1, startIndex = 1, endIndex = BYTES - SPLIT_BYTE)
        return tag
    }
}
