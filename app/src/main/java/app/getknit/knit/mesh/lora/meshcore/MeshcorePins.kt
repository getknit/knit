package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardAddr

/**
 * What the session knows about the zero-hop pins on its board's contacts (ADR 2026-11.gsqu §3; binding rules "sends" 1
 * and 2). Pure bookkeeping; the session does the reads and writes.
 *
 * - **The key map is the taught set** (security B1, Stage 5b-2). A DM is addressed by a 6-byte prefix, a pin by the 32-byte
 *   key: the map is fed only by the keys Knit taught — the ranked list `MeshcoreContacts` hands it, and a teach this session
 *   wrote — never by a read of a contact or an advert, so a key that is someone else's contact on the board is never
 *   pinned or sent to. A send to a prefix with no taught key is refused ([keyFor] null: `NO_RECORD`).
 * - **One key a prefix** (security B3). The board takes the first contact a prefix matches, so a prefix another key holds
 *   is refused ([learn]), and one a listed contact of another key shares is contested for the connection ([contest]).
 * - **Verified.** A DM is written only after a pin that succeeded in this connection: the set empties at every Ready
 *   ([onReady]) and loses a key whenever the board may have moved its route — a path push, a contact deleted, a flooded
 *   send, an `Unreachable`.
 * - **Targeted re-pins.** A path push or a deletion for a key Knit taught queues one re-pin (or re-teach) of that key, at
 *   most once per [repinGapMs]; a later push in the window only unverifies it, so the next send pins it. A push for any
 *   other key is ignored: the user's own contacts are theirs to route. The full re-assert runs only at Ready, in the
 *   taught list's ranked order.
 *
 * Every entry point `@Synchronized`, though only the session's actor reaches it.
 */
internal class MeshcorePins(
    private val repinGapMs: Long = REPIN_GAP_MS,
) {
    /** What a queued job does to a taught key. */
    enum class Job {
        /** Read it back and pin it zero-hop (a path push). */
        PIN,

        /** Write it again, after a free slot is confirmed (the board deleted it). */
        TEACH,
    }

    /** What a push for a key did here. */
    enum class PushAction {
        /** Not a key Knit taught: nothing. */
        IGNORED,

        /** Queued for a re-pin or re-teach. */
        QUEUED,

        /** Inside the rate limit: unverified only, so the next send pins it. */
        MARKED,
    }

    private val keys = HashMap<BoardAddr.MeshCore, ByteArray>()
    private val taught = LinkedHashSet<BoardAddr.MeshCore>()
    private val verified = HashSet<BoardAddr.MeshCore>()
    private val contested = HashSet<BoardAddr.MeshCore>()

    /** Our own board the [contested] prefixes were listed on (the last [onReady]'s), or null before one. */
    private var contestBoard: BoardAddr.MeshCore? = null
    private val lastJobAt = HashMap<BoardAddr.MeshCore, Long>()
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
     * or a pin written for, the targets of a targeted re-pin and the order of the Ready re-assert. It replaces the set: a
     * key dropped from the list is forgotten, so a DM to it is refused unwritten. Answers the keys taught now that were not
     * before, ranked: what a connection already Ready writes at once ([queueTeach]).
     */
    @Synchronized
    fun setTaught(ranked: List<ByteArray>): List<ByteArray> {
        val before = HashSet(taught)
        taught.clear()
        keys.clear()
        ranked.forEach(::learn)
        verified.retainAll(taught)
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
     * Stage 5c-2b amendment): outside the push rate limit, since nothing pushed. A key no longer taught, or contested on
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
        verified -= addr
    }

    @Synchronized
    fun isVerified(addr: BoardAddr.MeshCore): Boolean = addr in verified

    @Synchronized
    fun isTaught(addr: BoardAddr.MeshCore): Boolean = addr in taught

    /** A pin of [addr] read back zero-hop in this connection. */
    @Synchronized
    fun onPinned(addr: BoardAddr.MeshCore) {
        verified += addr
    }

    /** The board may have moved [addr]'s route: the next send pins it first. */
    @Synchronized
    fun unverify(addr: BoardAddr.MeshCore) {
        verified -= addr
    }

    /**
     * A new connection is Ready: nothing is verified, nothing queued from the last one, and the ranked taught keys are the
     * full re-assert to run (each counted against the rate limit as of [now]).
     */
    @Synchronized
    fun onReady(
        now: Long,
        board: BoardAddr.MeshCore? = null,
    ): List<ByteArray> {
        verified.clear()
        // The last listing's contests stand until this connection's listing replaces them ([setContested], lora N3), but only
        // on the board they were read on: our own [board] changed (a rebind), and they say nothing about this one.
        if (board != contestBoard) contested.clear()
        contestBoard = board
        queued.clear()
        teachRetried.clear()
        return taught.mapNotNull { addr -> keys[addr]?.copyOf().also { lastJobAt[addr] = now } }
    }

    /**
     * A path push (`0x81`, [deleted] false) or a deletion (`0x8F`) for [key] at [now]. Unverifies it whatever it is, and
     * queues one job for a key Knit taught when its rate limit allows.
     */
    @Synchronized
    fun onPush(
        key: ByteArray,
        deleted: Boolean,
        now: Long,
    ): PushAction {
        val addr = addrOf(key) ?: return PushAction.IGNORED
        verified -= addr
        if (addr !in taught) return PushAction.IGNORED
        val last = lastJobAt[addr]
        if (last != null && now - last < repinGapMs) {
            // A deletion still outranks a queued pin: the contact is gone, and a pin of it can only fail.
            if (deleted && queued[addr] == Job.PIN) queued[addr] = Job.TEACH
            return PushAction.MARKED
        }
        lastJobAt[addr] = now
        queued[addr] = if (deleted) Job.TEACH else Job.PIN
        return PushAction.QUEUED
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
        /** At most one targeted re-pin per key a minute (binding rule "sends" 2). */
        const val REPIN_GAP_MS = 60_000L

        /**
         * Live teach writes a key may have a UTC day ([queueTeach], ADR 2026-11.gsqu's Stage 5c-2b amendment): what a flapping
         * board map can cost the board's flash per contact; past it the next Ready's reconcile writes the key.
         */
        const val MAX_LIVE_TEACHES_PER_DAY = 4

        /** A key's address: its first six bytes, or null for a key that is not 32. */
        fun addrOf(key: ByteArray): BoardAddr.MeshCore? =
            if (key.size == MeshcoreProto.KEY_BYTES) BoardAddr.MeshCore(key.copyOf(MeshcoreProto.PREFIX_BYTES)) else null
    }
}
