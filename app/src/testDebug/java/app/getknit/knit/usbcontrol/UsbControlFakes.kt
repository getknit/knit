@file:Suppress("MagicNumber") // a cursor's position is the fake's own

package app.getknit.knit.usbcontrol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The app as [UsbControlSession] sees it: what a test sets, and a record of every call that would change something. */
internal class FakeApi : UsbControlApi {
    var sendError: UsbControlError? = null
    val sent = mutableListOf<SendRequest>()
    val calls = mutableListOf<String>()
    val historyAsked = mutableListOf<Triple<String, HistoryCursor?, Int>>()
    var historyPage = HistoryPage(emptyList(), null, exhausted = true)
    var chatList: List<JsonObject> = emptyList()
    var statusThrows: Exception? = null

    /** Message id (or avatar hash) -> its plain bytes; [readError] refuses every read while set. */
    val files = mutableMapOf<String, ByteArray>()
    var readError: UsbControlError? = null
    var opens = 0

    /** Our status as the phone holds it, and every [setStatus] in order. */
    var ownStatus = ""
    val statuses = mutableListOf<String>()

    override suspend fun identity(): JsonObject =
        buildJsonObject {
            put("node", "n1")
            put("name", "Alice")
            put("status", ownStatus)
            put("app", "9.9.9")
        }

    override suspend fun setStatus(status: String) {
        statuses += status
        ownStatus = status
    }

    override suspend fun status(): JsonObject {
        statusThrows?.let { throw it }
        return buildJsonObject {
            put("node", "n1")
            put("health", "Healthy")
        }
    }

    override suspend fun contacts(): List<JsonObject> =
        listOf(
            buildJsonObject {
                put("id", "c1")
                put("body", "")
            },
        )

    override suspend fun chats(): List<JsonObject> = chatList

    override suspend fun chat(conversation: String): JsonObject? = chatList.firstOrNull { it["id"] == JsonPrimitive(conversation) }

    override suspend fun history(
        conversation: String,
        before: HistoryCursor?,
        limit: Int,
    ): HistoryPage {
        historyAsked += Triple(conversation, before, limit)
        return historyPage
    }

    override suspend fun message(id: String): JsonObject? = null

    override suspend fun send(request: SendRequest): JsonObject {
        sendError?.let { throw it }
        sent += request
        return buildJsonObject {
            put("status", "sent")
            put("messageId", "m-${sent.size}")
        }
    }

    override suspend fun react(
        id: String,
        emoji: String?,
    ): JsonObject = record("react $id $emoji")

    override suspend fun markRead(
        conversation: String,
        upTo: Long?,
    ): JsonObject = record("read $conversation $upTo")

    override suspend fun delete(id: String): JsonObject = record("delete $id")

    override suspend fun accept(conversation: String): JsonObject = record("accept $conversation")

    override suspend fun decline(conversation: String): JsonObject = record("decline $conversation")

    override suspend fun block(node: String): JsonObject = record("block $node")

    override suspend fun unblock(node: String): JsonObject = record("unblock $node")

    override suspend fun attachment(id: String): BlobSource {
        readError?.let { throw it }
        val bytes = files[id] ?: throw UsbControlError(Codes.NOT_FOUND, "no such message")
        return BlobSource("h-$id", "image/jpeg", "a.jpg") {
            opens++
            bytes
        }
    }

    override suspend fun avatar(hash: String): BlobSource {
        readError?.let { throw it }
        val bytes = files[hash] ?: throw UsbControlError(Codes.NOT_FOUND, "not a photo this phone shows")
        return BlobSource(hash, "image/jpeg", name = null) {
            opens++
            bytes
        }
    }

    override suspend fun fetch(id: String): JsonObject = record("fetch $id")

    /** Whether the person has typing indicators on, a refusal every cue meets while set, and each cue sent. */
    var typingOn = true
    var typingRefusal: UsbControlError? = null
    val cues = mutableListOf<String>()

    override suspend fun canType(conversation: String): Boolean {
        typingRefusal?.let { throw it }
        return typingOn
    }

    override suspend fun sendTyping(conversation: String) {
        cues += conversation
    }

    private fun record(call: String): JsonObject {
        calls += call
        return buildJsonObject { put("done", call) }
    }
}

/** The session's door to a feed: every subscribe's `since`, and how many unsubscribes. */
internal class FakeFeed : FeedControl {
    val since = mutableListOf<ChangeCursor?>()
    var unsubscribed = 0

    override fun subscribe(since: ChangeCursor?): SubscribeResult {
        this.since += since
        return SubscribeResult(since ?: ChangeCursor("e1", 7), resync = since?.epoch == "old")
    }

    override fun unsubscribe() {
        unsubscribed++
    }
}
