package app.getknit.knit.data.receipt

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** One message's delivered-to tally, as returned by [MessageReceiptDao.observeDeliveredCounts]. */
data class MessageDeliveryCount(
    val messageId: String,
    val delivered: Int,
)

@Dao
interface MessageReceiptDao {
    /**
     * Records one acker's receipt, keeping the row already stored. First-evidence-wins by construction:
     * a receipt is re-served routinely (a custody replay, a retried tick, a batch covering an id we have
     * already seen), and only the first crossing describes when the message actually got there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(receipt: MessageReceiptEntity)

    /**
     * Everyone whose receipt for [messageId] has reached us, oldest first — the per-recipient rows the
     * message-details screen splits into "delivered to" and "waiting on". Narrowed by the `messageId`
     * index.
     */
    @Query("SELECT * FROM message_receipts WHERE messageId = :messageId ORDER BY notedAt ASC")
    fun observeForMessage(messageId: String): Flow<List<MessageReceiptEntity>>

    /**
     * How many of [roster] have acked each of [conversationId]'s messages, one row per message that has at
     * least one — the chat bubble's "delivered to N of M" without loading every receipt row in the thread
     * (a busy group thread can hold thousands: every message times the roster, and an accepted thread has no
     * retention cap).
     *
     * The roster filter is not an optimization: it is what keeps this count identical to the one the
     * message-details screen derives, which lists only *current* members. Without it a departed member's
     * surviving row would push the bubble to "3 of 2".
     */
    @Query(
        "SELECT messageId, COUNT(*) AS delivered FROM message_receipts " +
            "WHERE ackerNodeId IN (:roster) " +
            "AND messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId) " +
            "GROUP BY messageId",
    )
    fun observeDeliveredCounts(
        conversationId: String,
        roster: List<String>,
    ): Flow<List<MessageDeliveryCount>>

    /** Drops every receipt for a message (there is no FK cascade) when the message is deleted. */
    @Query("DELETE FROM message_receipts WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    /**
     * Reclaims receipt rows whose message is no longer stored (the table has no FK cascade) — left behind
     * by a deleted conversation, a group leave, or either retention trim. Unlike
     * [app.getknit.knit.data.reaction.ReactionDao.deleteOrphansOlderThan] this needs no age floor: a
     * receipt is only ever written for a message we already hold, so an orphan can only mean the message
     * has since gone.
     */
    @Query("DELETE FROM message_receipts WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphans()

    /** Every receipt recorded for [messageIds]: who has acked each of them. */
    @Query("SELECT * FROM message_receipts WHERE messageId IN (:messageIds)")
    suspend fun forMessages(messageIds: List<String>): List<MessageReceiptEntity>
}
