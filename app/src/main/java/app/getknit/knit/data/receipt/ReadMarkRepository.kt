package app.getknit.knit.data.receipt

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase

/**
 * The reader's durable outbox of read receipts ([ReadMarkEntity]): what the person read here, and which of those reads
 * no frame has told the author yet. The mesh's `ReadSync` is its one writer of [markTold] / [reOweNotUnder]; the
 * mesh's `onMessagesRead` its one writer of [mark]. Every multi-chunk write runs in one transaction.
 */
class ReadMarkRepository(
    private val dao: ReadMarkDao,
    private val db: KnitDatabase,
) {
    /** Records [marks], keeping any already stored. */
    suspend fun mark(marks: List<ReadMarkEntity>) {
        if (marks.isEmpty()) return
        db.withWriteTransaction { marks.chunked(QUERY_IDS_MAX).forEach { dao.insertIfAbsent(it) } }
    }

    /** Which of [ids] the person has read here, told or not: the bits a receipt toward their author may carry. */
    suspend fun readAmong(ids: Collection<String>): Set<String> = query(ids) { dao.readAmong(it) }

    /** Which of [ids] are read and still owed: the ids a frame carrying their bits would tell. */
    suspend fun owedAmong(ids: Collection<String>): Set<String> = query(ids) { dao.owedAmong(it) }

    /** Up to [limit] reads owed to [authorId], oldest first. */
    suspend fun owedFor(
        authorId: String,
        limit: Int,
    ): List<String> = dao.owedFor(authorId, limit)

    /** Every author still owed a read — what a restart re-batches. */
    suspend fun owedAuthors(): Set<String> = dao.owedAuthors().toSet()

    /** Marks [ids] told at [at] by a frame sealed under [rootTag]. */
    suspend fun markTold(
        ids: Collection<String>,
        at: Long,
        rootTag: Long?,
    ) {
        if (ids.isEmpty()) return
        db.withWriteTransaction { ids.distinct().chunked(QUERY_IDS_MAX).forEach { dao.markTold(it, at, rootTag) } }
    }

    /** Owes [authorId] again the reads read since [since] and told under a root other than [liveRoot]. */
    suspend fun reOweNotUnder(
        authorId: String,
        liveRoot: Long,
        since: Long,
    ): Int = dao.reOweNotUnder(authorId, liveRoot, since)

    /** Forgets every read owed to [authorId]: blocked or removed, they are told nothing more. */
    suspend fun dropOwedFor(authorId: String) = dao.dropOwedFor(authorId)

    /** Forgets every owed read: the setting turned off. */
    suspend fun dropOwed() = dao.dropOwed()

    /** Drops owed marks read before [owedBefore], told ones read before [toldBefore], and orphans. */
    suspend fun prune(
        owedBefore: Long,
        toldBefore: Long,
    ) = dao.prune(owedBefore, toldBefore)

    private suspend fun query(
        ids: Collection<String>,
        read: suspend (List<String>) -> List<String>,
    ): Set<String> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            ids.distinct().chunked(QUERY_IDS_MAX).flatMapTo(HashSet()) { read(it) }
        }

    private companion object {
        // SQLite's bound-parameter limit is 999 on older builds: one IN list stays well under it.
        const val QUERY_IDS_MAX = 500
    }
}
