package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.receipt.MessageReceiptDao
import app.getknit.knit.data.receipt.MessageReceiptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for *who* has received a message — the per-acker rows behind the message-details
 * screen's "delivered to / waiting on" split.
 *
 * It owns the delivery write outright: [record] flips the shared tick on `messages` **and** stores the
 * acker in one transaction, so the aggregate ✓✓ and the per-recipient list can never disagree (the
 * "multi-step data-layer mutations are transactional" rule — the send and receive paths write these
 * tables from different coroutines). It wraps [MessageRepository] rather than extending it, the
 * [GroupRepository] idiom, so the widely-constructed message repository keeps its signature.
 *
 * Nothing here is wire state: see [MessageReceiptEntity] for why storing the acker changes no frame,
 * no digest, and no delivery semantic.
 */
class MessageReceiptRepository(
    private val dao: MessageReceiptDao,
    private val messages: MessageRepository,
    private val db: KnitDatabase,
) {
    /** Everyone whose receipt for [messageId] has reached us, oldest first. */
    fun observeForMessage(messageId: String): Flow<List<MessageReceiptEntity>> = dao.observeForMessage(messageId)

    /**
     * Applies a delivery receipt for [messageId] that arrived on [via] at [at] (our clock). [acker] is the
     * node the receipt is attributable to, or null when it isn't — an unattributable receipt still flips
     * the tick, exactly as it always has, and simply stores no row. That asymmetry is the point: the tick's
     * "≥1 recipient received it" rule is a wire semantic and stays untouched, while the per-recipient list
     * is local bookkeeping that must not be plantable by a non-member (`InboundPipeline.ackerFor`).
     *
     * Both halves are idempotent, so a duplicate or custody-re-served receipt is absorbed.
     */
    suspend fun record(
        messageId: String,
        acker: String?,
        via: DeliveryPlane,
        at: Long,
    ) = db.withWriteTransaction {
        messages.markReceived(messageId, via)
        if (acker != null) dao.insertIfAbsent(MessageReceiptEntity(messageId, acker, at, via.code))
    }

    /**
     * How many of [roster] have acked each message in [conversationId], keyed by message id — the chat
     * bubble's group tick count. Messages nobody has acked are simply absent (treat as 0).
     */
    fun observeDeliveredCounts(
        conversationId: String,
        roster: List<String>,
    ): Flow<Map<String, Int>> =
        dao
            .observeDeliveredCounts(conversationId, roster)
            .map { rows -> rows.associate { it.messageId to it.delivered } }

    /** Removes all receipts for a deleted message, since the table has no FK cascade. */
    suspend fun deleteForMessage(messageId: String) = dao.deleteForMessage(messageId)

    /**
     * Reclaims receipt rows orphaned by a deleted conversation, a group leave, or a retention trim, since
     * the table has no FK cascade. Mirrors [ReactionRepository.deleteOrphans]; run on the prune heartbeat.
     */
    suspend fun deleteOrphans() = dao.deleteOrphans()

    /** Who has acked each of [messageIds], by message id; a message nobody acked is absent. */
    suspend fun ackersOf(messageIds: List<String>): Map<String, Set<String>> =
        if (messageIds.isEmpty()) {
            emptyMap()
        } else {
            messageIds
                .chunked(QUERY_IDS_MAX)
                .flatMap { dao.forMessages(it) }
                .groupBy({ it.messageId }, { it.ackerNodeId })
                .mapValues { it.value.toSet() }
        }

    private companion object {
        // SQLite's bound-parameter limit is 999 on older builds: one IN list stays well under it.
        const val QUERY_IDS_MAX = 500
    }
}
