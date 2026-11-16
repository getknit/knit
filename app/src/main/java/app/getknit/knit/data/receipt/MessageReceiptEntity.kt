package app.getknit.knit.data.receipt

import androidx.room3.Entity
import androidx.room3.Index
import app.getknit.knit.data.message.DeliveryPlane

/**
 * One person's delivery receipt for one message, as recorded on this device. The composite primary key
 * ([messageId], [ackerNodeId]) enforces "at most one receipt per person per message", so a re-served or
 * duplicated tick is absorbed by a plain insert-if-absent.
 *
 * This exists purely so the message-details screen can say *which* members of a group have received a
 * message, not merely that someone has. **Nothing about it touches the wire**: the acker is the
 * authenticated `RelayEnvelope.senderId` both receipt forms already carry, the "≥1 member received it"
 * semantic of the tick on `messages.received` is unchanged, and no digest folds over this table. Rows are
 * written only for an acker we can attribute — the DM's addressed recipient, a group's *effective roster
 * member*, or any signed peer in the public broadcast room (see `InboundPipeline.ackerFor`).
 *
 * [notedAt] is **our own clock** when the receipt was applied — "when their receipt reached us" —
 * deliberately not the acking device's `sentAt`. Mesh devices have no time sync, and a custody-escalated
 * batch's `sentAt` is its 45 s flush time rather than the moment of delivery, so a peer-clock value could
 * render a delivery *earlier* than the send it acknowledges. [via] is the [DeliveryPlane.code] that
 * receipt arrived on, first-evidence-wins like [app.getknit.knit.data.message.MessageDao.markReceived]'s.
 *
 * [readAt] is our own clock when a read receipt from [ackerNodeId] for this message was applied (ADR 2026-11.z6jw),
 * null until one is: a read rides a delivery receipt as `MessageContent.rm`, so a read row is always a
 * delivered row too, and [readAt] is never earlier than [notedAt]. First-evidence-wins like [notedAt]. Counting rows
 * still counts deliveries; [readAt] only narrows them.
 *
 * There is deliberately no foreign key to `messages` (the [app.getknit.knit.data.reaction.ReactionEntity]
 * posture): rows are reclaimed explicitly on a message delete and by the orphan reaper, so the schema
 * keeps its "no FK anywhere" shape over SQLCipher.
 */
@Entity(
    tableName = "message_receipts",
    primaryKeys = ["messageId", "ackerNodeId"],
    indices = [Index("messageId")],
)
data class MessageReceiptEntity(
    val messageId: String,
    val ackerNodeId: String,
    val notedAt: Long,
    val via: Int = DeliveryPlane.Unknown.code,
    val readAt: Long? = null,
)
