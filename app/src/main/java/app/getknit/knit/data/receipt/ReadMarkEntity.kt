package app.getknit.knit.data.receipt

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One message someone else wrote that the person read on this phone, kept so its author can be told (ADR 2026-11.z6jw)
 * — the reader's half of a read receipt, the author's half being [MessageReceiptEntity.readAt].
 *
 * Written by `MeshController.onMessagesRead` only for a DM or a private-group message whose author's pinned profile
 * carries `Protocol.CAP_READ_RECEIPTS`, and only while `SettingsStore.readReceiptsEnabled` is on. [toldAt] is null while
 * the read is **owed**: no frame carrying it has been originated `relay = true` (or pushed into the author's spool scope)
 * yet. Durable on purpose: a read has no heal path — the exists-gate re-acks a re-delivered message, never a read — so a
 * process death before the read leaves would lose it for good.
 *
 * [toldRoot] is the session root the telling frame was sealed under (`SealedTick.rootTag`), so a root change toward
 * [authorId] can owe again the reads the author can no longer open. A told mark is kept beyond that so a later receipt
 * toward the author (a re-ack under a new era, a LoRa re-ack) can carry the read bit again for free.
 *
 * Local only: never framed, never in custody, never in a backup (`BackupTables.TRANSIENT`) — a restored phone re-roots
 * every session, so a read owed under the old ones would buy only duplicates. No foreign key to `messages`, the
 * [MessageReceiptEntity] posture; the prune reclaims orphans.
 */
@Entity(
    tableName = "read_marks",
    indices = [Index("authorId")],
)
data class ReadMarkEntity(
    @PrimaryKey val messageId: String,
    val authorId: String,
    val conversationId: String,
    // Our clock when the person read it.
    val readAt: Long,
    val toldAt: Long? = null,
    val toldRoot: Long? = null,
)
