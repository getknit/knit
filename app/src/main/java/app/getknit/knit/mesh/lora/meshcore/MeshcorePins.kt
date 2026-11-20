package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardAddr

/**
 * What the session knows about the contacts Knit taught its board (ADR 2026-11.gsqu §3; binding rule "sends" 2), and the
 * route each was last read on. Pure bookkeeping; the session does the reads and writes.
 *
 * - **The key map is the taught set** (security B1, Stage 5b-2). A DM is addressed by a 6-byte prefix, a record by the
 *   32-byte key: the map is fed only by the keys Knit taught — the ranked list `MeshcoreContacts` hands it, and a teach this
 *   session wrote — never by a read of a contact or an advert, so a key that is someone else's contact on the board is never
 *   written or sent to. A send to a prefix with no taught key is refused ([keyFor] null: `NO_RECORD`).
 * - **One key a prefix** (security B3). The board takes the first contact a prefix matches, so a prefix another key holds
 *   is refused ([learn]), and one a listed contact of another key shares is contested for the connection ([contest]).
 * - **The route as last read** ([routeOf], ADR 2026-11.gsqu's 2026-11-19 amendment). The firmware routes a DM itself — by
 *   flood while the record's `out_path_len` is UNKNOWN, else direct along the path a PATH return taught it — and nothing
 *   pins it: the session's DM ladder only writes zero hops and resets. The cache holds each taught key's raw `out_path_len`
 *   as this connection last read or wrote it: set by the Ready's listing, a teach's read-back, the ladder's zero-hop write
 *   and reset, and the read a `0x81` queues; cleared by a deletion, a `NOT_FOUND` and every Ready. Empty is "not known",
 *   never "zero hops": what the hearing and our board's answer read only as a fact this connection saw.
 * - **Targeted jobs.** A path push (`0x81`) for a key Knit taught queues one read of its record (a BLE read, no air, no
 *   flash); a deletion (`0x8F`) one re-teach, at most once per [reteachGapMs], since a teach writes flash. A push for any
 *   other key is ignored: the user's own contacts are theirs to route.
 *
 * Every entry point `@Synchronized`, though only the session's actor reaches it.
 */
internal class MeshcorePins(
    private val reteachGapMs: Long = RETEACH_GAP_MS,
) {
    /** What a queued job does to a taught key. */
    enum class Job {
        /** Read its record back and cache its route (a path push, or a confirmed flood's reciprocal PATH). Never a write. */
        READ,

        /** Write it again, after a free slot is confirmed (the board deleted it). */
        TEACH,
    }

    /** What a push for a key did here. */
    enum class PushAction {
        /** Not a key Knit taught: nothing. */
        IGNORED,

        /** Queued for a read or a re-teach. */
        QUEUED,

        /** A deletion inside the re-teach gap: its route forgotten; the send's `NOT_FOUND` or the next Ready teaches it. */
        MARKED,
    }

    private val keys = HashMap<BoardAddr.MeshCore, ByteArray>()
    private val taught = LinkedHashSet<BoardAddr.MeshCore>()

    /** Each taught key's raw `out_path_len` as this connection last read or wrote it ([routeOf]). */
    private val routes = HashMap<BoardAddr.MeshCore, Int>()
    private val contested = HashSet<BoardAddr.MeshCore>()

    /** Our own board the [contested] prefixes were listed on (the last [onReady]'s), or null before one. */
    private var contestBoard: BoardAddr.MeshCore? = null
    private val lastTeachAt = HashMap<BoardAddr.MeshCore, Long>()
    private val queued = LinkedHashMap<BoardAddr.MeshCore, Job>()

    /** Keys [learn] refused, their prefix held by another key: counted for the dump, never named. */
    @Volatile
    var refusedPrefixes: Long = 0L
        private set

    /**
     * Learns [key]'s prefix → key into the taught set: its address, or null for a key not 32 bytes or a prefix another key
     * already holds (security B3: the board would send that key's DMs to the first contact the prefix matches).
     */
    @Synchronized
    fun learn(key: ByteArray): BoardAddr.MeshCore? {
        val addr = addrOf(key) ?: return null
        val held = keys[addr]
        if (held != null && !held.contentEquals(key)) {
            refusedPrefixes++
            return null
        }
        keys[addr] = key.copyOf()
        taught += addr
        return addr
    }

    /**
     * The keys Knit taught this board, ranked most-wanted first (`MeshcoreContacts` decides): the only keys a DM is sent to
     * or a record written for, and the targets of a targeted read or re-teach. It replaces the set: a
     * key dropped from the list is forgotten, so a DM to it is refused unwritten. Answers the keys taught now that were not
     * before, ranked: what a connection already Ready writes at once ([queueTeach]).
     */
    @Synchronized
    fun setTaught(ranked: List<ByteArray>): List<ByteArray> {
        val before = HashSet(taught)
        taught.clear()
        keys.clear()
        ranked.forEach(::learn)
        routes.keys.retainAll(taught)
        queued.keys.retainAll(taught)
        return taught.filter { it !in before }.mapNotNull { keys[it]?.copyOf() }
    }

    /** What [queueTeach] did with a key. */
    enum class TeachQueue {
        /** Queued (or one was queued already). */
        QUEUED,

        /** Past the key's live writes for [day]: left to the next Ready's reconcile, which writes every taught key. */
        DEFERRED,

        /** Not a key the set holds, or contested on this connection (security B3): nothing to write. */
        REFUSED,
    }

    /** Live teach writes a key has had today: the UTC day they were counted on, and how many. */
    private val liveTeaches = HashMap<BoardAddr.MeshCore, Pair<Long, Int>>()

    /** Keys a failed live teach was re-queued for once since the last Ready or their last write. */
    private val teachRetried = HashSet<BoardAddr.MeshCore>()

    /**
     * Queues a write of [key], taught since the last Ready, unless a job for it is queued already (ADR 2026-11.gsqu's
     * Stage 5c-2b amendment): outside the deletion's re-teach gap, since nothing pushed. A key no longer taught, or contested on
     * this connection, queues nothing ([TeachQueue.REFUSED]); one past [MAX_LIVE_TEACHES_PER_DAY] live writes on UTC [day]
     * is left to the next Ready's reconcile ([TeachQueue.DEFERRED]) — a board map that flaps a key in and out costs its
     * flash a removal and a write each time, and the removal of a key that left the set is never bounded.
     */
    @Synchronized
    fun queueTeach(
        key: ByteArray,
        day: Long,
    ): TeachQueue {
        val addr = addrOf(key) ?: return TeachQueue.REFUSED
        if (addr !in taught || addr in contested) return TeachQueue.REFUSED
        val (counted, n) = liveTeaches[addr] ?: (day to 0)
        if (counted == day && n >= MAX_LIVE_TEACHES_PER_DAY) return TeachQueue.DEFERRED
        queued.putIfAbsent(addr, Job.TEACH)
        return TeachQueue.QUEUED
    }

    /**
     * A live teach of [key] cost the board a flash write on UTC [day] — written and read back ([written]), or answered OK and
     * then failed its read-back or found the key dropped (the 5c-2b review's N1) — counted toward [queueTeach]'s bound. Only
     * a whole write spends the key's retry anew, so a write that keeps failing its read-back is retried once, never looped.
     */
    @Synchronized
    fun onLiveTeachSpent(
        key: ByteArray,
        day: Long,
        written: Boolean,
    ) {
        val addr = addrOf(key) ?: return
        val (counted, n) = liveTeaches[addr] ?: (day to 0)
        liveTeaches[addr] = day to (if (counted == day) n + 1 else 1)
        if (written) teachRetried -= addr
    }

    /** A live teach of [key] failed: re-queued once, never looped (the next Ready's reconcile writes it otherwise). */
    @Synchronized
    fun retryTeachOnce(key: ByteArray): Boolean {
        val addr = addrOf(key) ?: return false
        if (addr !in taught || addr in contested || !teachRetried.add(addr)) return false
        queued.putIfAbsent(addr, Job.TEACH)
        return true
    }

    /** Whether [key] could be learned: 32 bytes, and its prefix held by no other key. */
    @Synchronized
    fun prefixFree(key: ByteArray): Boolean = addrOf(key)?.let { keys[it]?.contentEquals(key) ?: true } == true

    /** The taught keys, ranked. */
    @Synchronized
    fun taughtKeys(): List<ByteArray> = taught.mapNotNull { keys[it]?.copyOf() }

    /**
     * The key a DM to [addr] is written for: a taught key whose prefix no listed contact of another key shares on this
     * connection, or null — the send is refused unwritten.
     */
    @Synchronized
    fun keyFor(addr: BoardAddr.MeshCore): ByteArray? = keys[addr]?.takeIf { addr in taught && addr !in contested }?.copyOf()

    /** Whether [key] is taught, exactly (the same 32 bytes at its prefix). */
    @Synchronized
    fun teaches(key: ByteArray): Boolean = addrOf(key)?.let { keys[it]?.contentEquals(key) == true && it in taught } == true

    /** The contests a whole listing found, replacing the last ones. */
    @Synchronized
    fun setContested(addrs: List<BoardAddr.MeshCore>) {
        contested.clear()
        addrs.forEach(::contest)
    }

    /** A listed contact of another key shares [addr]'s prefix and is still on the board: nothing goes to it until a listing clears it. */
    @Synchronized
    fun contest(addr: BoardAddr.MeshCore) {
        contested += addr
    }

    @Synchronized
    fun isTaught(addr: BoardAddr.MeshCore): Boolean = addr in taught

    /**
     * [addr]'s record read (or written and read back) at the raw `out_path_len` [outPathLen] on this connection: kept for a
     * taught key only, so nothing about another's contact is remembered.
     */
    @Synchronized
    fun noteRoute(
        addr: BoardAddr.MeshCore,
        outPathLen: Int,
    ) {
        if (addr in taught) routes[addr] = outPathLen
    }

    /** [addr]'s raw `out_path_len` as last read on this connection, or null when nothing was: never read as zero hops. */
    @Synchronized
    fun routeOf(addr: BoardAddr.MeshCore): Int? = routes[addr]

    /** The board may hold no record for [addr] any more (a deletion, a `NOT_FOUND`, a removal): its route is not known. */
    @Synchronized
    fun forgetRoute(addr: BoardAddr.MeshCore) {
        routes -= addr
    }

    /**
     * A new connection is Ready: no route known (the board may have learned or lost one while we were away; its listing
     * fills them again), nothing queued from the last one.
     */
    @Synchronized
    fun onReady(board: BoardAddr.MeshCore? = null) {
        routes.clear()
        // The last listing's contests stand until this connection's listing replaces them ([setContested], lora N3), but only
        // on the board they were read on: our own [board] changed (a rebind), and they say nothing about this one.
        if (board != contestBoard) contested.clear()
        contestBoard = board
        queued.clear()
        teachRetried.clear()
    }

    /**
     * A path push (`0x81`, [deleted] false) or a deletion (`0x8F`) for [key] at [now]. A path push queues one read of a taught
     * key's record (never a write: the firmware's route is kept, ADR 2026-11.gsqu's 2026-11-19 amendment); a deletion forgets
     * the route and queues one re-teach when the gap allows, and outranks a queued read.
     */
    @Synchronized
    fun onPush(
        key: ByteArray,
        deleted: Boolean,
        now: Long,
    ): PushAction {
        val addr = addrOf(key) ?: return PushAction.IGNORED
        if (addr !in taught) return PushAction.IGNORED
        if (!deleted) {
            queued.putIfAbsent(addr, Job.READ)
            return PushAction.QUEUED
        }
        routes -= addr
        val last = lastTeachAt[addr]
        if (last != null && now - last < reteachGapMs) {
            // The contact is gone: a read of it can only find nothing.
            if (queued[addr] == Job.READ) queued.remove(addr)
            return PushAction.MARKED
        }
        lastTeachAt[addr] = now
        queued[addr] = Job.TEACH
        return PushAction.QUEUED
    }

    /** Ladder writes (a zero-hop write or a reset) each key has had: the UTC day they were counted on, and how many. */
    private val ladderWrites = HashMap<BoardAddr.MeshCore, Pair<Long, Int>>()

    /**
     * Spends one of [addr]'s ladder writes for UTC [day] — the DM ladder's zero-hop `ADD_UPDATE_CONTACT` or its `RESET_PATH`,
     * each a flash write (security NB3 on ADR 2026-11.gsqu's 2026-11-19 amendment) — at most [MAX_LADDER_WRITES_PER_DAY]; false
     * past it, when the session sends on the route as read. In memory, as the live teach's bound: a restart resets it.
     */
    @Synchronized
    fun spendLadderWrite(
        addr: BoardAddr.MeshCore,
        day: Long,
    ): Boolean {
        val (counted, n) = ladderWrites[addr] ?: (day to 0)
        val used = if (counted == day) n else 0
        if (used >= MAX_LADDER_WRITES_PER_DAY) return false
        ladderWrites[addr] = day to used + 1
        return true
    }

    /** Queues one read of [addr]'s record, unless a job for it is queued already (a confirmed flood's reciprocal PATH). */
    @Synchronized
    fun queueRead(addr: BoardAddr.MeshCore) {
        if (addr in taught) queued.putIfAbsent(addr, Job.READ)
    }

    /** The next queued job, oldest first, or null. */
    @Synchronized
    fun nextJob(): Pair<ByteArray, Job>? {
        val (addr, job) = queued.entries.firstOrNull() ?: return null
        queued.remove(addr)
        val key = keys[addr] ?: return null
        return key.copyOf() to job
    }

    /** Drops every queued job (a board not set up for Knit runs none). */
    @Synchronized
    fun clearJobs() = queued.clear()

    /** Whether any job is queued. */
    @Synchronized
    fun hasJobs(): Boolean = queued.isNotEmpty()

    companion object {
        /** At most one re-teach of a deleted key a minute (binding rule "sends" 2): a teach writes flash. */
        const val RETEACH_GAP_MS = 60_000L

        /**
         * Live teach writes a key may have a UTC day ([queueTeach], ADR 2026-11.gsqu's Stage 5c-2b amendment): what a flapping
         * board map can cost the board's flash per contact; past it the next Ready's reconcile writes the key.
         */
        const val MAX_LIVE_TEACHES_PER_DAY = 4

        /**
         * Ladder writes a key may cost the board's flash a UTC day ([spendLadderWrite]): at most two a flight (a zero-hop
         * write, a reset), so twelve flights. A dead neighbour's first day is about ten — the give-up, then re-probe rounds
         * ramping from `LoraReprobe.BASE_MS` (15 min) by doubling to the 4-h cap (15 min, 30, 1 h, 2 h, then every 4 h) —
         * leaving a little for the user's own DMs.
         */
        const val MAX_LADDER_WRITES_PER_DAY = 24

        /** A key's address: its first six bytes, or null for a key that is not 32. */
        fun addrOf(key: ByteArray): BoardAddr.MeshCore? =
            if (key.size == MeshcoreProto.KEY_BYTES) BoardAddr.MeshCore(key.copyOf(MeshcoreProto.PREFIX_BYTES)) else null
    }
}
