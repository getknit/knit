package app.getknit.knit.mesh

/**
 * What custody's global row cap may read beyond the frame and the held set: how widely a row is believed carried, its
 * `spread` (the custody-carry-policy contract, §3 and §5). Only the cap's choice of row reads it
 * ([app.getknit.knit.data.forward.ForwardRepository]); admission, every refusal, the TTL, the sender, group and room
 * trims, the digest fold and the carry gate never do, so below the cap two stores holding the same frames still keep the
 * same ones (ADR 006). At the cap they may differ for good, which is the decided aim: let go first of what others already
 * carry. Whatever eviction reads, a peer can read back from our digest, so nothing but who holds a frame belongs here.
 *
 * The event side keeps the implementer's held set in step with custody's rows. `ForwardRepository` calls it under its
 * own mutex, after the write it reports, so an event never races the table it describes: [onKept] after a write that
 * holds its row, [retainOnly] with every live id whenever the digest is rebuilt (after any eviction and every sweep), and
 * [onLeft] when a row is removed. Each defaults to doing nothing, and none may suspend or block: they run inside the
 * repository's critical section.
 */
interface CarryReader {
    /**
     * The spreads one write's cap reads, taken once and only when the cap binds. [source], the neighbour [newcomer]
     * arrived from, already counts among the newcomer's holders (capped as every spread is): that peer holds the frame
     * whether or not this write keeps it, which is what lets two linked full stores settle instead of swapping a frame
     * back and forth.
     */
    fun snapshot(
        newcomer: String,
        source: String?,
    ): CarrySnapshot

    /** Custody holds [id] after a write, newly stored or already held; [source] is the neighbour it came from, if known. */
    fun onKept(
        id: String,
        source: String?,
    ) = Unit

    /** Custody's live rows are exactly [liveIds]: every other id has left (evicted, trimmed or expired). */
    fun retainOnly(liveIds: Set<String>) = Unit

    /** Custody removed [id] (a receipt's purge, or any other single delete). */
    fun onLeft(id: String) = Unit

    companion object {
        /** No holders anywhere: every spread is 0, and the cap's order is the frame-global one of ADR 2026-10.7mqm. */
        val EMPTY: CarryReader =
            object : CarryReader {
                override fun snapshot(
                    newcomer: String,
                    source: String?,
                ): CarrySnapshot = CarrySnapshot.EMPTY
            }
    }
}

/** One write's view of the carry ledger (custody-carry-policy §3). */
fun interface CarrySnapshot {
    /** How many distinct peers are believed to hold [id], at most the ledger's cap. */
    fun spread(id: String): Int

    companion object {
        /** [CarryReader.EMPTY]'s view: no holders anywhere. */
        val EMPTY: CarrySnapshot = CarrySnapshot { 0 }
    }
}
