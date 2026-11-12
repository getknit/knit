package app.getknit.knit.usbcontrol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the USB control session reads and does in the app, behind an interface so the session tests without one. */
internal interface UsbControlApi {
    /** `node`, `name`, `alias`, `avatarHash` and `app` (the version name), for the hello reply and the `self` event. */
    suspend fun identity(): JsonObject

    suspend fun status(): JsonObject

    /** Every direct contact as a contact object, by label. */
    suspend fun contacts(): List<JsonObject>

    /** Every thread the chat list shows and every message request, as chat objects, most recent first. */
    suspend fun chats(): List<JsonObject>

    /** One thread's chat object, or null when the chat list shows no such thread and it is no request. */
    suspend fun chat(conversation: String): JsonObject?

    /** One page of a thread back from [before] (the newest when null), newest first: see [HistoryPage]. */
    suspend fun history(
        conversation: String,
        before: HistoryCursor?,
        limit: Int,
    ): HistoryPage

    /** One message object, or null when it is gone or its sender is blocked. */
    suspend fun message(id: String): JsonObject?

    /**
     * Sends [request] and answers `status` (`sent`, or `queued` on the Meshtastic room's board) and the new message's
     * `id` when there is one; a send that did not happen throws [UsbControlError] with its code.
     */
    suspend fun send(request: SendRequest): JsonObject

    /** Sets our reaction on message [id] to [emoji], or clears it when null; `changed` says whether anything went. */
    suspend fun react(
        id: String,
        emoji: String?,
    ): JsonObject

    /** Moves the thread's read watermark to [upTo] (its newest message when null), never back, and clears its notifications. */
    suspend fun markRead(
        conversation: String,
        upTo: Long?,
    ): JsonObject

    /** Deletes message [id] from this phone only, as the chat's own delete does. */
    suspend fun delete(id: String): JsonObject

    /** Accepts a message request; refuses with `not_pending` a thread that is none. */
    suspend fun accept(conversation: String): JsonObject

    /** Deletes a message request, as the requests inbox does; refuses with `not_pending` a thread that is none. */
    suspend fun decline(conversation: String): JsonObject

    suspend fun block(node: String): JsonObject

    suspend fun unblock(node: String): JsonObject
}

/** A `send`, its arguments checked for shape: [styles] are `[start, length, code]` triples, [mentions] node ids. */
internal data class SendRequest(
    val conversation: String,
    val text: String,
    val replyTo: String? = null,
    val mentions: List<String> = emptyList(),
    val styles: List<List<Int>> = emptyList(),
)

/** The session's door to its change feed, which the server's worker owns: `subscribe` and `unsubscribe` go through it. */
internal interface FeedControl {
    fun subscribe(since: ChangeCursor?): SubscribeResult

    fun unsubscribe()

    /** For a session with no feed (a test of the request side alone): subscribing starts from nothing. */
    object None : FeedControl {
        override fun subscribe(since: ChangeCursor?): SubscribeResult = SubscribeResult(ChangeCursor("", 0), resync = since != null)

        override fun unsubscribe() = Unit
    }
}

/** A position in a thread: the row at ([sentAt], [id]) in the thread's `(sentAt, id)` order. */
internal data class HistoryCursor(
    val sentAt: Long,
    val id: String,
)

/**
 * A page of a thread, newest first. [items] are the readable rows with each one's cursor (a blocked sender's row is
 * left out but still moves [tail]); [tail] is the oldest row the page read, readable or not, and [exhausted] says the
 * thread has nothing older than it.
 */
internal class HistoryPage(
    val items: List<Pair<JsonObject, HistoryCursor>>,
    val tail: HistoryCursor?,
    val exhausted: Boolean,
)

/** A request the session refuses: a [code] a client can act on, the reason in words, and any [extra] fields. */
internal class UsbControlError(
    val code: String,
    message: String,
    val extra: Map<String, JsonElement> = emptyMap(),
) : Exception(message)

/** The `code` of every refusal. */
internal object Codes {
    const val HELLO_FIRST = "hello_first"
    const val BAD_VERSION = "bad_version"
    const val BAD_REQUEST = "bad_request"
    const val UNKNOWN_OP = "unknown_op"
    const val NOT_FOUND = "not_found"
    const val BLOCKED = "blocked"
    const val REFUSED = "refused"
    const val UNKNOWN_GROUP = "unknown_group"
    const val NOT_PENDING = "not_pending"
    const val RATE_LIMITED = "rate_limited"
    const val INTERNAL = "internal"
    const val FRAMING = "framing"
}
