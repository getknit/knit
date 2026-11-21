package app.getknit.knit.data.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a contact's ask for this phone's Knit stands, as its card in the thread says it (ADR 2026-11.29m7). */
enum class UpdateRequestPhase {
    /** Waiting for the person here: the card offers Send and Not now while the ask is still live in memory. */
    Waiting,

    /**
     * The person tapped Send and the update was offered as a direct transfer, which this card then follows
     * (`ui/chat/foldAskedTransfers`). Says only that the person said yes: whether the bytes arrived is the transfer's.
     */
    Sent,

    /** The person tapped Not now. */
    Declined,

    /** Send was tapped but the transfer could not start (busy, or this phone no longer runs that version). */
    Unsent,

    /** Nobody answered in time. */
    Expired,
}

/**
 * What a [MessageEntity.KIND_APP_UPDATE_REQUEST] row's [MessageEntity.body] records: a contact's ask for this phone's
 * build (ADR 2026-11.29m7's amendment), the [TransferRecord] convention, so the row needs no new column. Written by
 * the newer phone only, as the ask moves (`update/PeerUpdates`), never on the wire, and keyed on the ask's
 * [requestId] so each move upserts the one row.
 */
@Serializable
data class UpdateRequestRecord(
    val requestId: String,
    val versionCode: Long,
    val phase: UpdateRequestPhase,
) {
    fun encode(): String = json.encodeToString(this)

    /**
     * The chat row in the DM with [peerId], who asked: status-notice-shaped like a transfer's (`received = true`, no
     * tick, out of unread counts and search), its sender the asker, at [askedAt] (this phone's clock when the ask came
     * in) through every phase, so the card stays where the ask arrived.
     */
    fun toEntity(
        peerId: String,
        selfId: String,
        askedAt: Long,
    ): MessageEntity =
        MessageEntity(
            id = rowId(peerId, requestId),
            senderId = peerId,
            recipientId = selfId,
            conversationId = peerId,
            body = encode(),
            sentAt = askedAt,
            received = true,
            kind = MessageEntity.KIND_APP_UPDATE_REQUEST,
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The `messages.id` of [peerId]'s ask [requestId]. The asker picks the id, so it is scoped to the asker: one
         * contact's id can never name, or move, another's card.
         */
        fun rowId(
            peerId: String,
            requestId: String,
        ): String = "upd:$peerId:$requestId"

        /** Reads [body]; null for anything that is not an encoded record (a row this build cannot read). */
        fun decode(body: String): UpdateRequestRecord? = runCatching { json.decodeFromString<UpdateRequestRecord>(body) }.getOrNull()
    }
}
