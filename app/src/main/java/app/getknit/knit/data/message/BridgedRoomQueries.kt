package app.getknit.knit.data.message

import androidx.room3.Query

/**
 * The bridged board rooms' own writes to `messages` (ADR 2026-11.gsqu's Stage 6 amendment), kept apart from [MessageDao]
 * for detekt's LargeClass, as `transfer/TransferEnds` is from `TransferManager`. [MessageDao] inherits them and Room
 * generates them as its own: this is no DAO of its own, and nothing but [MessageDao] implements it.
 */
interface BridgedRoomQueries {
    /**
     * Marks our own post [id] in the MeshCore room ([conversationId]) as ended without (confirmed) airing (ADR
     * 2026-11.gsqu's Stage 6 amendment): [neverSent] — the plane held it past its freshness and never handed it to the radio
     * — sets [MessageEntity.pendingKey], the column's own words (written locally, never sent) on a row with no recipient,
     * which no resend path reads; otherwise it was given up at the write and may have aired, and its plane is no longer
     * known: [MessageEntity.receivedVia] goes back to `DeliveryPlane.Unknown` from the LoRa it was queued on. Only our own
     * ordinary words in that room ([me], no `originNode`, no recipient), never a row already marked never sent; how many
     * rows it marked.
     */
    @Query(
        "UPDATE messages SET pendingKey = CASE WHEN :neverSent THEN 1 ELSE 0 END, " +
            "receivedVia = CASE WHEN :neverSent THEN receivedVia ELSE 0 END WHERE id = :id AND conversationId = :conversationId " +
            "AND senderId = :me AND originNode IS NULL AND kind = 0 AND recipientId IS NULL AND pendingKey = 0",
    )
    suspend fun markPublicPostEnded(
        id: String,
        conversationId: String,
        me: String,
        neverSent: Boolean,
    ): Int
}
