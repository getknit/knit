package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshcoreBoardHolder
import app.getknit.knit.mesh.MeshcoreRank
import app.getknit.knit.mesh.crypto.MeshcoreKey
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.LoraBoardHolder
import app.getknit.knit.mesh.lora.LoraContactPolicy
import java.nio.ByteBuffer

/**
 * What our MeshCore board's contact table holds (ADR 2026-11.gsqu §3, as the maintainer's Stage 5 decisions and the 5b-2
 * amendment leave it). **The board is Knit's alone**: setup erased every contact on it, so the keys Knit decides to teach
 * are the board's whole table. Pure and clock-driven by the caller; the hooks hold one per plane, every entry point
 * `@Synchronized`, and the reconcile's plan ([plan]) is a pure function the session runs on its actor.
 *
 * - **Which keys** ([wanted]): the board map's holders ([holdersOf], `MeshManager.meshcoreBoardMap`: direct contacts only,
 *   so never a stranger, a blocked or tag-blocked peer, or us), less a key two live contacts claim (Stage 4's contest), a
 *   key whose 6-byte prefix another key on the map shares (security B3: the board addresses a DM by the prefix and takes
 *   the first contact that matches, so whose it is cannot be told; neither is taught), our own board's key, and a key past
 *   a peer's [maxKeysPerDay] distinct keys in a day. Ranked verified, accepted, authored (the map's [MeshcoreRank]), and
 *   cut to the board's capacity (`DEVICE_INFO`'s `MAX_CONTACTS / 2`, doubled).
 * - **The reactive teach** ([judgeTeach]): a DM our board refused for want of the contact is taught only for a wanted key,
 *   and once per (our board, key) until a DM to that board was acked since ([onDelivered]) — `LoraContactPolicy`'s write
 *   memo. A contact deleted from another app is taught again at the next Ready, or by the session's targeted re-teach on
 *   its `0x8F`: there is no deletion-respect rule.
 * - **The reconcile at Ready** ([plan]): one `GET_CONTACTS` listing, then every contact on a key Knit does not teach is
 *   removed, every taught key's contact that does not route as Knit's ([isKnitRouting]: its name is the peer's own advert's
 *   since ADR 2026-11.f4d3) is overwritten to Knit's record, and a taught key
 *   missing is written while the board has room — both counted and bounded per Ready ([MAX_REMOVALS_PER_READY],
 *   [MAX_OVERWRITES_PER_READY]), so a second client that keeps rewriting the table cannot make Knit churn the board's
 *   flash without limit. A taught key whose prefix a listed contact of another key shares is written only once that
 *   contact's removal reads back; else it is contested for the connection and never sent to.
 *
 * Keys never reach a log line: everything here is counted by reason.
 */
internal class MeshcoreContacts(
    private val maxKeysPerDay: Int = LoraContactPolicy.MAX_KEYS_PER_DAY,
    private val dayMs: Long = LoraContactPolicy.DAY_MS,
) {
    /** Why a holder's key is not taught, each counted. */
    enum class Skip {
        /** No key, or not a 32-byte Ed25519 point [MeshcoreKey] accepts. */
        NO_KEY,

        /** Not a contact (the map carries only direct contacts; a holder that is not one is refused all the same). */
        NOT_CONTACT,

        /** Two live contacts claim the key (Stage 4), or two keys on the map share its 6-byte prefix (security B3). */
        CONTESTED,

        /** Our own board's key. */
        OWN_KEY,

        /** [maxKeysPerDay] distinct keys for this peer were taught in the last day. */
        KEY_CAP,

        /** Ranked past the board's capacity. */
        CAPACITY,
    }

    /** One wanted set: the ranked keys to teach, and the peer each belongs to. */
    class Wanted(
        val ranked: List<ByteArray>,
        private val peers: Map<ByteBuffer, String>,
        val skipped: Map<Skip, Int>,
    ) {
        /** Whether [key] is taught, for [peerId]. */
        fun teaches(
            peerId: String,
            key: ByteArray,
        ): Boolean = peers[ByteBuffer.wrap(key)] == peerId

        /** Whether [key] is taught, whoever's it is. */
        fun has(key: ByteArray): Boolean = ByteBuffer.wrap(key) in peers

        override fun toString(): String = "Wanted(${ranked.size} keys, skipped=$skipped)"
    }

    /** Why a reactive teach is not written. */
    enum class Refusal {
        /** Not in the wanted set: not a vetted key, or past the capacity. */
        NOT_WANTED,

        /** Written already for this board and key, and no DM to it has been acked since. */
        WRITTEN,
    }

    private data class Written(
        val board: ByteBuffer,
        val key: ByteBuffer,
    )

    /** Each write's (our board, key), true once a DM to that key was acked by its board since the write. */
    private val written = LinkedHashMap<Written, Boolean>()

    /** The keys each peer was taught, with when: the daily bound. */
    private val keysByPeer = HashMap<String, MutableList<Pair<ByteBuffer, Long>>>()

    private var last: Wanted = Wanted(emptyList(), emptyMap(), emptyMap())

    /** The last [wanted] answer. */
    @get:Synchronized
    val current: Wanted get() = last

    /**
     * The ranked keys to teach from [holders] (the plane's board map, [holdersOf]'s form), cut to [capacity] (null before
     * a board reported it: uncut), less [ownKey] (our board's, null before one identified). A key new for its peer is
     * charged against the peer's daily bound now, as [now] reads.
     */
    @Synchronized
    fun wanted(
        holders: Map<String, LoraBoardHolder>,
        ownKey: ByteArray?,
        capacity: Int?,
        now: Long,
    ): Wanted {
        val skipped = HashMap<Skip, Int>()

        fun skip(why: Skip) = skipped.merge(why, 1, Int::plus)
        val own = ownKey?.let(ByteBuffer::wrap)
        val vetted =
            holders.entries
                .mapNotNull { (peer, holder) ->
                    when (val v = vet(holder, own)) {
                        is Skip -> null.also { skip(v) }
                        else -> Triple(peer, holder, v as ByteArray)
                    }
                }.sortedWith(compareByDescending<Triple<String, LoraBoardHolder, ByteArray>> { it.second.rank }.thenBy { it.first })
        val kept = ArrayList<Pair<String, ByteArray>>()
        for ((peer, _, key) in vetted) {
            if (!chargeDay(peer, ByteBuffer.wrap(key), now)) skip(Skip.KEY_CAP) else kept += peer to key
        }
        val cut = capacity?.let { kept.take(it.coerceAtLeast(0)) } ?: kept
        repeat(kept.size - cut.size) { skip(Skip.CAPACITY) }
        val peers = LinkedHashMap<ByteBuffer, String>()
        cut.forEach { (peer, key) -> peers[ByteBuffer.wrap(key)] = peer }
        return Wanted(cut.map { it.second.copyOf() }, peers, skipped).also { last = it }
    }

    /** [holder]'s key when it may be taught, else why not ([Skip]). */
    private fun vet(
        holder: LoraBoardHolder,
        own: ByteBuffer?,
    ): Any {
        val key = holder.loraKey?.let(MeshcoreKey::bytesOrNull)
        return when {
            key == null -> Skip.NO_KEY
            !holder.contact -> Skip.NOT_CONTACT
            holder.contested -> Skip.CONTESTED
            ByteBuffer.wrap(key) == own -> Skip.OWN_KEY
            else -> key
        }
    }

    /** Whether [peer]'s [key] is inside its daily bound, charging it if new. */
    private fun chargeDay(
        peer: String,
        key: ByteBuffer,
        now: Long,
    ): Boolean {
        val keys = keysByPeer.getOrPut(peer) { mutableListOf() }
        keys.removeAll { now - it.second >= dayMs }
        if (keys.any { it.first == key }) return true
        if (keys.size >= maxKeysPerDay) return false
        keys += key to now
        return true
    }

    /**
     * Whether our board ([board], its own 6-byte address) may be taught [peerId]'s [key] now, after it refused a DM for want
     * of the contact: only a key in the current wanted set, and once per (board, key) until [onDelivered].
     */
    @Synchronized
    fun judgeTeach(
        peerId: String,
        key: ByteArray,
        board: BoardAddr.MeshCore,
    ): Refusal? {
        if (!last.teaches(peerId, key)) return Refusal.NOT_WANTED
        if (written[Written(ByteBuffer.wrap(board.prefix), ByteBuffer.wrap(key))] == false) return Refusal.WRITTEN
        return null
    }

    /** Our board [board] took [key] (a reactive teach read back). */
    @Synchronized
    fun onWritten(
        board: BoardAddr.MeshCore,
        key: ByteArray,
    ) {
        val entry = Written(ByteBuffer.wrap(board.prefix), ByteBuffer.wrap(key.copyOf()))
        written.remove(entry)
        written[entry] = false
        while (written.size > MAX_WRITTEN) written.remove(written.keys.first())
    }

    /** A compact DM to [to] was acked by its board, through ours ([board]): one more reactive teach of it is allowed. */
    @Synchronized
    fun onDelivered(
        board: BoardAddr.MeshCore,
        to: BoardAddr.MeshCore,
    ) {
        val own = ByteBuffer.wrap(board.prefix)
        written.keys
            .filter { it.board == own && MeshcorePins.addrOf(it.key.array()) == to }
            .forEach { written[it] = true }
    }

    /** What one Ready's reconcile does, in order: [removals], then [overwrites], then [teaches]. */
    class Plan(
        /** Listed contacts on a key Knit does not teach, at most [MAX_REMOVALS_PER_READY]. */
        val removals: List<ByteArray>,
        /** Taught keys listed with a record that is not Knit's, at most [MAX_OVERWRITES_PER_READY]. */
        val overwrites: List<ByteArray>,
        /** Taught keys not listed, ranked, as many as the board has room for once the removals read back. */
        val teaches: List<ByteArray>,
        /** Taught keys listed as Knit's record, zero-hop: pinned as read, nothing written. */
        val verified: List<ByteArray>,
        /** A taught key's prefix → the listed key of another that shares it, whose removal must read back first. */
        val blockedBy: Map<ByteBuffer, ByteArray>,
        /** Taught keys whose prefix a listed contact shares that is not removed this Ready: never sent to. */
        val contested: List<ByteArray>,
        /** Removals and overwrites left for the next Ready by the per-Ready bounds. */
        val deferred: Int,
    ) {
        override fun toString(): String =
            "Plan(remove=${removals.size}, overwrite=${overwrites.size}, teach=${teaches.size}, verified=${verified.size}, " +
                "contested=${contested.size}, deferred=$deferred)"
    }

    companion object {
        /** Removals one Ready's reconcile makes at most: after setup's erase there is nothing to remove. */
        const val MAX_REMOVALS_PER_READY = 16

        /** Overwrites one Ready's reconcile makes at most, a taught record another client changed each. */
        const val MAX_OVERWRITES_PER_READY = 16

        /** The most (board, key) writes remembered: past the contacts one board holds. */
        const val MAX_WRITTEN = 256

        /**
         * The plane's board map in the core's form, from `MeshManager.meshcoreBoardMap`: each holder addressed by its key's
         * 6-byte prefix, carrying the key ([LoraBoardHolder.loraKey]) and its rank. [LoraBoardHolder.direct] is the door's
         * claim, which on MeshCore is the key itself; it is false, and the holder [LoraBoardHolder.contested], when two
         * claims share the key or two keys share the prefix, so the core never offers a DM to either. A key the point check
         * refuses is left out.
         */
        fun holdersOf(map: Map<String, MeshcoreBoardHolder>): Map<String, LoraBoardHolder> {
            val keys = map.mapNotNull { (peer, holder) -> MeshcoreKey.bytesOrNull(holder.key)?.let { peer to it } }.toMap()
            val byPrefix = keys.values.groupBy { MeshcorePins.addrOf(it) }
            return keys.entries.associate { (peer, key) ->
                val holder = map.getValue(peer)
                val addr = checkNotNull(MeshcorePins.addrOf(key))
                val prefixShared = byPrefix[addr].orEmpty().any { !it.contentEquals(key) }
                val contested = holder.contested || prefixShared
                peer to
                    LoraBoardHolder(
                        addr = addr,
                        direct = !contested,
                        profileVersion = holder.latestProfileVersion,
                        loraKey = holder.key,
                        contact = true,
                        contested = contested,
                        rank = rankOf(holder.rank),
                    )
            }
        }

        private fun rankOf(rank: MeshcoreRank): Int = MeshcoreRank.entries.size - rank.ordinal

        /**
         * Whether [record] is exactly the contact Knit writes for its key: a CHAT favourite, `Knit xxxx`, pinned zero-hop. What
         * Restore identifies Knit's contacts by, what the setup's erase keeps and what a teach's read-back believes.
         */
        fun isKnitRecord(record: ContactRecord): Boolean = isKnitRouting(record) && record.named(MeshcoreNames.forKey(record.key))

        /**
         * Whether [record] routes as Knit's does — a CHAT favourite pinned zero-hop — whatever its name: what the reconcile's
         * overwrite and verify, and a teach's overwrite decision, read (ADR 2026-11.f4d3, the plan's §5). A heard advert
         * rewrites a contact's name to the peer's own (lora N4: `BaseChatMesh.cpp:191-193`), and with handles every peer's
         * board may carry one, so the name would cost a flash write per taught peer a Ready for nothing it routes by.
         */
        fun isKnitRouting(record: ContactRecord): Boolean =
            record.type == MeshcoreProto.ADV_TYPE_CHAT && record.flags == MeshcoreProto.CONTACT_FLAG_FAVOURITE &&
                record.outPathLen == 0

        /**
         * One Ready's reconcile over the board's own [listing] ([listedCount] the count its `CONTACTS_START` gave, which a
         * short listing undercounts): what to remove, overwrite and write so the table is [taught] (ranked, cut to
         * [capacity]), bounded per Ready. A listed contact on an untaught key is removed, the user's or another client's
         * alike (the board is Knit's); a taught key whose prefix a listed contact of another key shares is written only after
         * that removal ([Plan.blockedBy]), or contested when the bound defers it.
         */
        @Suppress("LongParameterList") // the taught keys, the listing, its count, the capacity and the two bounds
        fun plan(
            taught: List<ByteArray>,
            listing: List<ContactRecord>,
            listedCount: Int,
            capacity: Int,
            maxRemovals: Int = MAX_REMOVALS_PER_READY,
            maxOverwrites: Int = MAX_OVERWRITES_PER_READY,
        ): Plan {
            val kept = taught.take(capacity.coerceAtLeast(0))
            val keptKeys = kept.map(ByteBuffer::wrap).toSet()
            val listed = listing.associateBy { ByteBuffer.wrap(it.key) }
            val foreign = listing.filter { ByteBuffer.wrap(it.key) !in keptKeys }
            val removals = foreign.take(maxRemovals.coerceAtLeast(0)).map { it.key.copyOf() }
            val removed = removals.map(ByteBuffer::wrap).toSet()
            val overwriteWanted = kept.filter { key -> listed[ByteBuffer.wrap(key)]?.let { !isKnitRouting(it) } == true }
            val overwrites = overwriteWanted.take(maxOverwrites.coerceAtLeast(0))
            val verified = kept.filter { key -> listed[ByteBuffer.wrap(key)]?.let(::isKnitRouting) == true }
            val blockedBy = HashMap<ByteBuffer, ByteArray>()
            val contested = ArrayList<ByteArray>()
            kept.forEach { key ->
                val sharer = foreign.firstOrNull { MeshcorePins.addrOf(it.key) == MeshcorePins.addrOf(key) } ?: return@forEach
                if (ByteBuffer.wrap(sharer.key) in removed) {
                    blockedBy[ByteBuffer.wrap(key)] = sharer.key.copyOf()
                } else {
                    contested += key
                }
            }
            val contestedKeys = contested.map(ByteBuffer::wrap).toSet()
            // Room once the removals read back: the board's count (never below what was listed) less them, against capacity.
            val occupied = maxOf(listedCount, listing.size) - removals.size
            val room = (capacity - occupied).coerceAtLeast(0)
            val teaches =
                kept
                    .filter { ByteBuffer.wrap(it) !in listed && ByteBuffer.wrap(it) !in contestedKeys }
                    .take(room)
            return Plan(
                removals = removals,
                overwrites = overwrites.filter { ByteBuffer.wrap(it) !in contestedKeys },
                teaches = teaches,
                verified = verified.filter { ByteBuffer.wrap(it) !in contestedKeys },
                blockedBy = blockedBy,
                contested = contested,
                deferred = (foreign.size - removals.size) + (overwriteWanted.size - overwrites.size),
            )
        }
    }
}
