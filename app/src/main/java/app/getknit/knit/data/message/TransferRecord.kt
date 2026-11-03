package app.getknit.knit.data.message

import app.getknit.knit.mesh.protocol.TransferPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a direct Wi-Fi file transfer stands. One vocabulary for the live state (`transfer/TransferManager`),
 * the persisted [TransferRecord] and the chat card. [Offered] is the same phase seen from both sides — the
 * offer is out and unanswered — and `outgoing` says which side this is. Names are stored in the row's JSON,
 * so this enum is append-only and never renamed.
 */
enum class TransferPhase {
    Offered,
    Connecting,
    Transferring,
    Done,
    Declined,
    Expired,
    Cancelled,
    Failed,
    ;

    /** True once nothing more can happen to the transfer. */
    val terminal: Boolean
        get() = this == Done || this == Declined || this == Expired || this == Cancelled || this == Failed
}

/**
 * What a [MessageEntity.KIND_FILE_TRANSFER] row's [MessageEntity.body] records: the file that was offered
 * ([name], [size], [mime]), which side offered it, how far it got, and — once received — where it was saved.
 * Stored as a small JSON object, the [PeerRename] convention, so the row needs no new column. Deliberately
 * never carries the Wi-Fi credentials or the stream key: those live in memory for one transfer and die with it.
 *
 * The sender's record also keeps [sourceUri], the picked document the file was read from, so the card can offer
 * Retry without a second trip through the picker ([retryable]). It is this phone's own handle on the file and
 * means nothing anywhere else. [retried] marks a record whose Retry already started a newer transfer, so the old
 * card stops offering it.
 *
 * A bundle of translation models (ADR 2026-11.ejn8) keeps its direction pins in [models], which the card reads
 * through this phone's own table; [name] is only the label that went with it, and Retry re-offers the pins.
 *
 * Progress is not here either — the row is rewritten only on a phase change, and the card reads live bytes
 * from the manager's state. A non-terminal record with no live state (the process died mid-transfer) is
 * what the card draws as interrupted.
 */
@Serializable
data class TransferRecord(
    val id: String,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String? = null,
    val phase: TransferPhase,
    val savedUri: String? = null,
    val reason: Int? = null,
    val sourceUri: String? = null,
    val retried: Boolean = false,
    val models: List<Long>? = null,
) {
    fun encode(): String = json.encodeToString(this)

    /**
     * Whether the sender's card offers Retry. [live] says the manager still holds this transfer, which means it
     * is still running. Retry covers every way an outgoing transfer can end short: a failure for any reason, an
     * offer that expired or was cancelled, a busy peer, and a record a process death left mid-phase. It is not
     * offered for a finished file or an offer the other person turned down themselves.
     */
    fun retryable(live: Boolean): Boolean =
        outgoing &&
            (sourceUri != null || models != null) &&
            !retried &&
            !live &&
            phase != TransferPhase.Done &&
            !(phase == TransferPhase.Declined && reason == TransferPayload.REASON_USER)

    /**
     * The chat row for this record in the DM with [peerId]: a status-notice-shaped row (`received = true`, no
     * tick, out of unread counts) whose sender is whoever offered the file. The id is deterministic per
     * transfer, so every phase change is an upsert of the same row — and [sentAt] stays the offer's time
     * through all of them, so a thread does not jump up the chat list each time a phase turns over.
     *
     * It is the one notice kind the chat list *does* preview: unlike a rename, its sender is a real author
     * and its subject is the conversation itself (`ui/chat/transferPreview`).
     */
    fun toEntity(
        peerId: String,
        selfId: String,
        sentAt: Long,
    ): MessageEntity =
        MessageEntity(
            id = rowId(id),
            senderId = if (outgoing) selfId else peerId,
            recipientId = if (outgoing) peerId else selfId,
            conversationId = peerId,
            body = encode(),
            sentAt = sentAt,
            received = true,
            kind = MessageEntity.KIND_FILE_TRANSFER,
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The `messages.id` of the record for transfer [transferId]. */
        fun rowId(transferId: String): String = "xfer:$transferId"

        /** Reads [body]; null for anything that is not an encoded record (a row this build cannot read). */
        fun decode(body: String): TransferRecord? = runCatching { json.decodeFromString<TransferRecord>(body) }.getOrNull()
    }
}
