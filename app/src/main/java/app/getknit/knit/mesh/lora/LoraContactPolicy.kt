package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.crypto.b64d
import java.util.Locale
import java.util.zip.CRC32

/**
 * Whether Knit may teach its own board a pinned contact's key with an admin `add_contact`, and what that write carries
 * (ADR 2026-10.aajj). Asked only after our board refused a compact DM with `PKI_SEND_FAIL_PUBLIC_KEY` (39): firmware 2.8
 * PKI-encrypts every unicast its phone originates, on any channel, and refuses it — nothing airs — when its NodeDB holds
 * no key for the destination (`Router.cpp:1347-1355` at v2.8.1). Stock clients send `add_contact` before every DM for
 * exactly this reason; Knit writes one per need, reactively, so only boards we actually DM are written and each write
 * is one NodeDB save to flash (`NodeDB::addFromContact`, `NodeDB.cpp:3717-3790`).
 *
 * **The write is a trust decision, so every guard holds or nothing is written.** A profile's `loraNode` and `loraKey`
 * are both self-asserted: a contact could name a third board V with their own key, and the write would make our board
 * encrypt to the claimant everything it sends V and drop V's signed packets. So a key is written only for a contact rank
 * holder (verified, an accepted conversation, a DM we wrote in), for a board number nobody else's profile claims, that
 * is not our own, whose key is 32 bytes and whose number is the key's CRC32 — the firmware's own first-contact rule
 * (`Router.cpp:714`; on 2.8 a node number is the CRC32 of its key, `NodeDB.cpp:537`). Grinding 2^32 keypairs to hit a
 * CRC already beats the firmware's bootstrap, so this adds no weakness. **The board's word wins:** a 39 says the board
 * holds no key for that node now, whatever the phone's copy of its NodeInfo stream remembers (the firmware never says
 * when it drops a node, and an over-the-air NodeInfo can be anybody's). Only a key streamed **after** the refused send
 * was issued — a `NodeInfo` that raced it — stops the write; a differing one is a conflict, counted. A node streamed in
 * ham mode in that window is never written either.
 *
 * **What the `User` carries.** `CopyUserToNodeInfoLite` overwrites the names, `hw_model`, `role`, `is_licensed` and both
 * unmessagable bits (`TypeConversions.cpp:108-128`). So the `User` is an allowlist — the id, the two names and the key —
 * with the names the board's stream last showed for that node when it has any, otherwise the ones a Knit-set-up board
 * calls itself (`Knit abcd`, `Knit`, unmessagable — ADR 049, 2026-09.emd7). Never the contact's display name, which
 * the next `NodeInfo` push would put into the room's name directory, and never `is_licensed`, `role`, `hw_model` or an
 * unknown field, any of which may have come off the air from anybody. The node's own next keyed `NodeInfo` replaces the
 * whole entry (`NodeDB.cpp:3846-3848`), so its full `User` comes back.
 *
 * **Memory.** In process only: one write per (our board, their board, their key); one more after a DM to them was
 * acked by their board since ([onDelivered]), because a board that took the key and later lost it (evicted, reflashed,
 * factory reset) must be repairable; and at most [maxKeysPerDay] distinct keys per peer a day, so a profile that keeps
 * changing its key cannot make our board rewrite its NodeDB on every change.
 *
 * Pure and clock-driven by the caller; every entry point is `@Synchronized` (the outcome collector and the pacer each
 * call in). Never called holding the pacer's monitor.
 */
internal class LoraContactPolicy(
    private val maxKeysPerDay: Int = MAX_KEYS_PER_DAY,
    private val dayMs: Long = DAY_MS,
) {
    /** Why no `add_contact` is written, each counted or logged by name. */
    enum class Refusal {
        /** Not a contact: neither verified, nor an accepted conversation, nor one we wrote a DM in. */
        NOT_CONTACT,

        /** Another pinned profile names the same board: whose key it is cannot be told. */
        CONTESTED,

        /** Not a number a board can hold (0 or the broadcast address). */
        NOT_A_BOARD,

        /** Our own board: it never needs its own key taught. */
        OWN_BOARD,

        /** The profile carries no `loraKey`, or not a 32-byte one. */
        NO_PROFILE_KEY,

        /** The board number is not the CRC32 of the profile's key: the profile does not bind the two. */
        CRC_MISMATCH,

        /** The board's NodeInfo stream reports the node in ham mode: it does no PKI. */
        LICENSED,

        /** The board's NodeInfo stream already shows the profile's key for it. */
        BOARD_HOLDS_KEY,

        /** The board's NodeInfo stream shows another key for it: the board's wins, and the conflict is counted. */
        BOARD_KEY_CONFLICT,

        /** Written already for this board and key, and no DM to it has been acked since. */
        WRITTEN,

        /** [maxKeysPerDay] distinct keys for this peer were written in the last day. */
        KEY_CAP,
    }

    sealed interface Verdict {
        /** Write [admin], an encoded `AdminMessage.add_contact` for board [node] carrying [key] (base64). */
        class Write(
            val node: UInt,
            val key: String,
            val admin: ByteArray,
        ) : Verdict

        data class Refused(
            val reason: Refusal,
        ) : Verdict
    }

    private data class Written(
        val ownBoard: UInt,
        val node: UInt,
        val key: String,
    )

    /** Each write's (our board, their board, key), true once a DM to that board was acked by it since the write. */
    private val written = LinkedHashMap<Written, Boolean>()

    /** The keys written for each peer, with when: the daily bound. */
    private val keysByPeer = HashMap<String, MutableList<Pair<String, Long>>>()

    /** `add_contact`s that timed out (no `queueStatus`): re-offered once, never written again until something settles. */
    private val timedOut = HashSet<Written>()

    /**
     * Whether an `add_contact` for [peerId]'s board goes to our board [ownBoard], and what it carries. [holder] is the
     * peer's board-map entry; [streamed] is the `User` our board's NodeInfo stream last showed for that board, if any,
     * and [sentAfter] the stream's stamp ([MeshtasticLink.userSeq]) when the refused send was issued.
     *
     * **The `39` is the board's word for "no key now".** The stream is not the NodeDB: the firmware never reports a node
     * it dropped (`--remove-node`, eviction, a reset), and an over-the-air NodeInfo can be a forger's. So a key, or ham
     * mode, in [streamed] is read only when it was streamed after the send was issued — the NodeInfo that raced the `39`,
     * which the board's word cannot cover. An older entry gives the names alone, and its key is set aside.
     */
    @Synchronized
    @Suppress("ReturnCount", "LongParameterList") // one guard per line, each its own refusal
    fun judge(
        peerId: String,
        holder: LoraBoardHolder,
        ownBoard: UInt,
        streamed: StreamedUser?,
        sentAfter: Long,
        now: Long,
    ): Verdict {
        val node = holder.addr.node
        refusal(holder, node, ownBoard)?.let { return Verdict.Refused(it) }
        val keyB64 = checkNotNull(holder.loraKey)
        val key = profileKey(keyB64) ?: return Verdict.Refused(Refusal.NO_PROFILE_KEY)
        if (crc32(key) != node) return Verdict.Refused(Refusal.CRC_MISMATCH)
        val raced = streamed?.takeIf { it.seq > sentAfter }?.user
        if (raced?.licensed == true) return Verdict.Refused(Refusal.LICENSED)
        raced?.owner?.publicKey?.let { held ->
            return Verdict.Refused(if (held == keyB64) Refusal.BOARD_HOLDS_KEY else Refusal.BOARD_KEY_CONFLICT)
        }
        val entry = Written(ownBoard, node, keyB64)
        if (written[entry] == false || entry in timedOut) return Verdict.Refused(Refusal.WRITTEN)
        if (overCap(peerId, keyB64, now)) return Verdict.Refused(Refusal.KEY_CAP)
        val user = contactUser(node, key, streamed?.user?.owner)
        return Verdict.Write(node, keyB64, MeshtasticProto.encodeAdminAddContact(node, user))
    }

    /**
     * The `add_contact` for [node] under [key] went out and no `queueStatus` came back: its frame is re-offered once, but
     * neither the write nor the daily bound is recorded, and the next `39` for the same key is terminal.
     */
    @Synchronized
    fun onTimedOut(
        ownBoard: UInt,
        node: UInt,
        key: String,
    ) {
        timedOut += Written(ownBoard, node, key)
    }

    /** The guards that read the holder alone: rank, a contested board, a board number, ours. */
    private fun refusal(
        holder: LoraBoardHolder,
        node: UInt,
        ownBoard: UInt,
    ): Refusal? =
        when {
            !holder.contact -> Refusal.NOT_CONTACT
            holder.contested -> Refusal.CONTESTED
            node == 0u || node == MeshtasticProto.BROADCAST -> Refusal.NOT_A_BOARD
            node == ownBoard -> Refusal.OWN_BOARD
            holder.loraKey == null -> Refusal.NO_PROFILE_KEY
            else -> null
        }

    /** The board took the `add_contact` for [peerId]'s board [node] under [key] (its `queueStatus` came back res 0). */
    @Synchronized
    fun onWritten(
        peerId: String,
        ownBoard: UInt,
        node: UInt,
        key: String,
        now: Long,
    ) {
        val entry = Written(ownBoard, node, key)
        timedOut -= entry
        written.remove(entry)
        written[entry] = false
        while (written.size > MAX_WRITTEN) written.remove(written.keys.first())
        val keys = keysByPeer.getOrPut(peerId) { mutableListOf() }
        keys.removeAll { it.first == key || now - it.second >= dayMs }
        keys += key to now
    }

    /**
     * A compact DM to board [node] was acked by that board, through [ownBoard]: the key reached it, so should our board
     * ever lose it again, one more write is allowed.
     */
    @Synchronized
    fun onDelivered(
        ownBoard: UInt,
        node: UInt,
    ) {
        written.keys.filter { it.ownBoard == ownBoard && it.node == node }.forEach { written[it] = true }
        timedOut.removeAll { it.ownBoard == ownBoard && it.node == node }
    }

    private fun overCap(
        peerId: String,
        key: String,
        now: Long,
    ): Boolean {
        val recent = keysByPeer[peerId].orEmpty().filter { now - it.second < dayMs }
        return recent.none { it.first == key } && recent.size >= maxKeysPerDay
    }

    companion object {
        /** Distinct keys one peer's board may be taught in a day: a reflash and its correction, never a stream. */
        const val MAX_KEYS_PER_DAY = 2
        const val DAY_MS = 24 * 60 * 60_000L

        /** The most (board, node, key) writes remembered: well past the pinned contacts one phone DMs over LoRa. */
        const val MAX_WRITTEN = 256

        /** `User.long_name`'s room (`char[40]`, `mesh.options`), less the terminator. */
        const val LONG_NAME_BYTES = 39

        /** `User.short_name`'s room (`char[5]`), less the terminator. */
        const val SHORT_NAME_BYTES = 4

        /** The CRC32 the firmware derives a node number from (`crc32Buffer`, ErriezCRC32: the IEEE polynomial). */
        fun crc32(key: ByteArray): UInt = CRC32().apply { update(key) }.value.toUInt()

        /** A profile's `loraKey` as the 32 raw bytes a key is, or null. */
        fun profileKey(b64: String): ByteArray? =
            runCatching { b64d(b64) }.getOrNull()?.takeIf { it.size == MeshtasticProto.NODE_PUBLIC_KEY_BYTES }

        /**
         * The `User` an `add_contact` for board [node] carries, built from an allowlist: the `!`-id, the two names and
         * [key]. The names are the board's stored ones for that node ([stored]) when it has them; else exactly what a
         * Knit-set-up board calls itself — `Knit abcd`, `Knit` — and then `is_unmessagable` too. Never `role`,
         * `hw_model`, `is_licensed` or a field this codec does not know, which may have come off the air from anybody:
         * the node's own next keyed NodeInfo restores its whole `User` (`NodeDB.cpp:3846-3848`).
         */
        fun contactUser(
            node: UInt,
            key: ByteArray,
            stored: BoardOwner?,
        ): ByteArray {
            val knit = BoardName.forNode(node, firmware = null)
            // Per field: a stored long name with a blank short one keeps the long and fills the short.
            val long = stored?.longName?.takeIf { it.isNotBlank() }
            val short = stored?.shortName?.takeIf { it.isNotBlank() }
            return ProtoWriter()
                .string(MeshtasticProto.USER_ID, "!%08x".format(Locale.ROOT, node.toLong()))
                .string(MeshtasticProto.USER_LONG_NAME, fitUtf8(long ?: knit.longName, LONG_NAME_BYTES))
                .string(MeshtasticProto.USER_SHORT_NAME, fitUtf8(short ?: knit.shortName, SHORT_NAME_BYTES))
                .bytes(MeshtasticProto.USER_PUBLIC_KEY, key)
                .bool(MeshtasticProto.USER_IS_UNMESSAGABLE, long == null)
                .toByteArray()
        }

        /** [name] cut to at most [maxBytes] of UTF-8 on a code-point boundary, so the firmware never splits a character. */
        fun fitUtf8(
            name: String,
            maxBytes: Int,
        ): String {
            if (LoraSizeHint.utf8Length(name) <= maxBytes) return name
            val out = StringBuilder()
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val next = String(Character.toChars(cp))
                if (LoraSizeHint.utf8Length(out) + LoraSizeHint.utf8Length(next) > maxBytes) break
                out.append(next)
                i += Character.charCount(cp)
            }
            return out.toString()
        }
    }
}
