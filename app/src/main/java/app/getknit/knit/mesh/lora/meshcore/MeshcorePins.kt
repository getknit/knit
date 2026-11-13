package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardAddr

/**
 * What the session knows about the zero-hop pins on its board's contacts (ADR 2026-11.gsqu §3; binding rules "sends" 1
 * and 2). Pure bookkeeping; the session does the reads and writes.
 *
 * - **The key map.** A DM is addressed by a 6-byte prefix, a pin by the 32-byte key: the map is fed only by the session's
 *   contact plumbing (a teach, a read, the taught list Stage 5 hands it), never by an advert, so a flood of adverts keeps
 *   nothing here. A send to a prefix with no key is refused.
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
    private val lastJobAt = HashMap<BoardAddr.MeshCore, Long>()
    private val queued = LinkedHashMap<BoardAddr.MeshCore, Job>()

    /** Learns [key]'s prefix → key, from the contact plumbing; returns its address, or null for a key not 32 bytes. */
    @Synchronized
    fun learn(key: ByteArray): BoardAddr.MeshCore? {
        val addr = addrOf(key) ?: return null
        keys[addr] = key.copyOf()
        return addr
    }

    /**
     * The keys Knit taught this board, ranked most-wanted first (Stage 5's `MeshcoreContacts` decides): the targets of a
     * targeted re-pin and the order of the Ready re-assert. Each is learned; a key dropped from the list keeps its map
     * entry (a DM to it still pins it first) but no longer draws a re-pin.
     */
    @Synchronized
    fun setTaught(ranked: List<ByteArray>) {
        taught.clear()
        ranked.forEach { key -> learn(key)?.let(taught::add) }
        queued.keys.retainAll(taught)
    }

    /** Marks [key] taught by Knit (a teach this session wrote), at the end of the ranking. */
    @Synchronized
    fun noteTaught(key: ByteArray) {
        learn(key)?.let(taught::add)
    }

    /** The key a DM to [addr] is written for, or null when none was learned: the send is refused. */
    @Synchronized
    fun keyFor(addr: BoardAddr.MeshCore): ByteArray? = keys[addr]?.copyOf()

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
    fun onReady(now: Long): List<ByteArray> {
        verified.clear()
        queued.clear()
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

    /** Whether any job is queued. */
    @Synchronized
    fun hasJobs(): Boolean = queued.isNotEmpty()

    companion object {
        /** At most one targeted re-pin per key a minute (binding rule "sends" 2). */
        const val REPIN_GAP_MS = 60_000L

        /** A key's address: its first six bytes, or null for a key that is not 32. */
        fun addrOf(key: ByteArray): BoardAddr.MeshCore? =
            if (key.size == MeshcoreProto.KEY_BYTES) BoardAddr.MeshCore(key.copyOf(MeshcoreProto.PREFIX_BYTES)) else null
    }
}
