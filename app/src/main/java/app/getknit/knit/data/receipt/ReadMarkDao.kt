package app.getknit.knit.data.receipt

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ReadMarkDao {
    /** Records reads, keeping a mark already stored: the first read is the one that happened. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(marks: List<ReadMarkEntity>)

    /** Which of [messageIds] the person has read here, told or not. */
    @Query("SELECT messageId FROM read_marks WHERE messageId IN (:messageIds)")
    suspend fun readAmong(messageIds: List<String>): List<String>

    /** Which of [messageIds] are read and still owed to their author. */
    @Query("SELECT messageId FROM read_marks WHERE toldAt IS NULL AND messageId IN (:messageIds)")
    suspend fun owedAmong(messageIds: List<String>): List<String>

    /** Up to [limit] reads still owed to [authorId], oldest read first. */
    @Query("SELECT messageId FROM read_marks WHERE authorId = :authorId AND toldAt IS NULL ORDER BY readAt ASC LIMIT :limit")
    suspend fun owedFor(
        authorId: String,
        limit: Int,
    ): List<String>

    /** Every author still owed a read. */
    @Query("SELECT DISTINCT authorId FROM read_marks WHERE toldAt IS NULL")
    suspend fun owedAuthors(): List<String>

    /** Marks [messageIds] told at [at] by a frame sealed under [rootTag]; a re-tell moves the mark to the newer root. */
    @Query("UPDATE read_marks SET toldAt = :at, toldRoot = :rootTag WHERE messageId IN (:messageIds)")
    suspend fun markTold(
        messageIds: List<String>,
        at: Long,
        rootTag: Long?,
    )

    /**
     * Owes [authorId] again every read told under a root other than [liveRoot] and read since [since]: the author can no
     * longer open the frame that told it (a reset, a restore). Returns how many came back.
     */
    @Query(
        "UPDATE read_marks SET toldAt = NULL, toldRoot = NULL WHERE authorId = :authorId AND toldAt IS NOT NULL " +
            "AND (toldRoot IS NULL OR toldRoot != :liveRoot) AND readAt >= :since",
    )
    suspend fun reOweNotUnder(
        authorId: String,
        liveRoot: Long,
        since: Long,
    ): Int

    /** Forgets every read still owed to [authorId]: the person blocked or removed them, and owes them nothing more. */
    @Query("DELETE FROM read_marks WHERE authorId = :authorId AND toldAt IS NULL")
    suspend fun dropOwedFor(authorId: String)

    /** Forgets every read still owed (the setting turned off); told marks stay, gated by the setting where read. */
    @Query("DELETE FROM read_marks WHERE toldAt IS NULL")
    suspend fun dropOwed()

    /**
     * Drops owed marks read before [owedBefore], told marks read before [toldBefore], and marks whose message is gone.
     */
    @Query(
        "DELETE FROM read_marks WHERE (toldAt IS NULL AND readAt < :owedBefore) " +
            "OR (toldAt IS NOT NULL AND readAt < :toldBefore) OR messageId NOT IN (SELECT id FROM messages)",
    )
    suspend fun prune(
        owedBefore: Long,
        toldBefore: Long,
    )
}
