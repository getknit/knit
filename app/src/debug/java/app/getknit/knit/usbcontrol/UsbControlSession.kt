package app.getknit.knit.usbcontrol

import app.getknit.knit.TextLimits
import app.getknit.knit.isValidReactionEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One host's session over the accessory stream: protocol v1 (`docs/USB_CONTROL.md`). A request is `{"id":…,"op":…}`
 * and gets exactly one reply, `{"id":…,"ok":true,…}` or `{"id":…,"ok":false,"code":…,"error":…}`, with the id echoed
 * as sent. Nothing but `hello` is served before a hello; a hello resets the session (a new run on the same cable sends
 * one), so [subscribed] drops until the host asks again. The caller owns the event feed and follows [subscribed].
 *
 * A list reply carries at most its `limit` and stops early at [budget] bytes, saying `more` and where to go on from,
 * so no reply runs past the frame cap however long a thread or a body is.
 */
internal class UsbControlSession(
    private val api: UsbControlApi,
    private val feed: FeedControl = FeedControl.None,
    private val ledger: SendLedger = SendLedger(System::currentTimeMillis),
    private val budget: Int = FrameBudget.BYTES,
) {
    var subscribed: Boolean = false
        private set

    /** The client name the last hello gave, null before one. */
    var client: String? = null
        private set

    private var hello = false

    @Suppress("TooGenericExceptionCaught") // one request's fault answers that request; the session goes on
    suspend fun handle(frame: String): JsonObject {
        val request =
            runCatching { JSON.parseToJsonElement(frame) }.getOrNull() as? JsonObject
                ?: return failure(JsonNull, UsbControlError(Codes.BAD_REQUEST, "not a JSON object"))
        val id = request["id"] ?: JsonNull
        val op = request.string("op") ?: return failure(id, UsbControlError(Codes.BAD_REQUEST, "missing op"))
        if (op != OP_HELLO && !hello) return failure(id, UsbControlError(Codes.HELLO_FIRST, "send hello first"))
        return try {
            success(id, serve(op, request))
        } catch (e: UsbControlError) {
            failure(id, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(id, UsbControlError(Codes.INTERNAL, "internal: ${e.javaClass.simpleName}"))
        }
    }

    private suspend fun serve(
        op: String,
        request: JsonObject,
    ): Map<String, JsonElement> =
        when (op) {
            OP_HELLO -> serveHello(request)
            "status" -> api.status()
            "contacts" -> listReply("contacts", api.contacts(), request, DEFAULT_LIST, MAX_LIST)
            "chats" -> listReply("chats", api.chats(), request, DEFAULT_LIST, MAX_LIST)
            "chat" -> mapOf("chat" to (api.chat(request.need("conv")) ?: notFound("no such chat")))
            "history" -> serveHistory(request)
            "message" -> mapOf("message" to (api.message(request.need("messageId")) ?: notFound("no such message")))
            "send" -> serveSend(request)
            "react" -> serveReact(request)
            "read" -> api.markRead(request.need("conv"), request.long("upTo"))
            "delete" -> api.delete(request.need("messageId"))
            "accept" -> api.accept(request.need("conv"))
            "decline" -> api.decline(request.need("conv"))
            "block" -> api.block(request.need("node"))
            "unblock" -> api.unblock(request.need("node"))
            "subscribe" -> serveSubscribe(request)
            "unsubscribe" -> serveUnsubscribe()
            "bye" -> serveBye()
            else -> throw UsbControlError(Codes.UNKNOWN_OP, "unknown op: $op")
        }

    private suspend fun serveHello(request: JsonObject): Map<String, JsonElement> {
        val version = request.int("v") ?: refuse("missing v")
        if (version != VERSION) throw UsbControlError(Codes.BAD_VERSION, "unsupported version $version; this phone speaks $VERSION")
        val nonce = request.string("nonce")?.takeIf { it.length in 1..MAX_NONCE } ?: refuse("missing nonce")
        hello = true
        dropSubscription()
        client = request.string("client")?.take(MAX_CLIENT)
        return mapOf(
            "v" to JsonPrimitive(VERSION),
            "nonce" to JsonPrimitive(nonce),
            "self" to api.identity(),
            "ops" to buildJsonArray { OPS.forEach { add(JsonPrimitive(it)) } },
            "limits" to LIMITS,
        )
    }

    private suspend fun serveHistory(request: JsonObject): Map<String, JsonElement> {
        val conversation = request.need("conv")
        val limit = (request.int("limit") ?: DEFAULT_HISTORY).coerceIn(1, MAX_HISTORY)
        val before =
            (request["before"] as? JsonObject)?.let { cursor ->
                val sentAt = cursor.long("sentAt") ?: refuse("before needs sentAt")
                HistoryCursor(sentAt, cursor.string("id") ?: refuse("before needs id"))
            }
        val page = api.history(conversation, before, limit)
        val kept = FrameBudget.fit(page.items.map { it.first }, limit, budget)
        val cut = kept.size < page.items.size
        val more = cut || !page.exhausted
        val next = if (cut) page.items[kept.size - 1].second else page.tail
        return mapOf(
            "conv" to JsonPrimitive(conversation),
            // Oldest first, as a thread reads.
            "messages" to JsonArray(kept.asReversed()),
            "more" to JsonPrimitive(more),
            "next" to if (more && next != null) cursorJson(next) else JsonNull,
        )
    }

    /**
     * A send, once: a `clientMsgId` this phone has already sent answers the reply it got then, with `duplicate`, and
     * sends nothing, so a host that lost the link mid-send can simply ask again. Rate limited with `react`.
     */
    private suspend fun serveSend(request: JsonObject): Map<String, JsonElement> {
        val conversation = request.string("conv") ?: request.string("to") ?: refuse("missing conv or to")
        val text = request.string("text")?.takeIf { it.isNotBlank() } ?: refuse("missing text")
        if (text.length > TextLimits.MESSAGE) refuse("text longer than ${TextLimits.MESSAGE} characters")
        val clientMsgId = request.string("clientMsgId")
        if (clientMsgId != null && clientMsgId.length !in 1..MAX_CLIENT_ID) refuse("clientMsgId is 1..$MAX_CLIENT_ID characters")
        clientMsgId?.let { ledger.replay(it) }?.let { return it + ("duplicate" to JsonPrimitive(true)) }
        val send =
            SendRequest(
                conversation = conversation,
                text = text,
                replyTo = request.string("replyTo"),
                mentions = request.strings("mentions", MAX_MENTIONS),
                styles = request.triples("styles"),
            )
        spend()
        val reply = api.send(send) + mapOf("conv" to JsonPrimitive(conversation))
        clientMsgId?.let { ledger.record(it, JsonObject(reply)) }
        return reply
    }

    private suspend fun serveReact(request: JsonObject): Map<String, JsonElement> {
        val id = request.need("messageId")
        val emoji =
            when (request["emoji"]) {
                null, JsonNull -> {
                    null
                }

                else -> {
                    request.string("emoji")?.takeIf(::isValidReactionEmoji)
                        ?: refuse("emoji is 1..${TextLimits.REACTION} chars, or null")
                }
            }
        spend()
        return api.react(id, emoji)
    }

    /** One token from the send budget, or a refusal saying when the next one comes. */
    private fun spend() {
        ledger.take()?.let { wait ->
            throw UsbControlError(Codes.RATE_LIMITED, "too many sends; try again shortly", mapOf("retryAfterMs" to JsonPrimitive(wait)))
        }
    }

    /**
     * Starts the event feed: from `since` (a cursor an earlier subscribe or event gave) when the phone still holds
     * everything after it, else from now with `resync: true`, which tells the client to read everything again first.
     */
    private fun serveSubscribe(request: JsonObject): Map<String, JsonElement> {
        val since =
            (request["since"] as? JsonObject)?.let { cursor ->
                ChangeCursor(cursor.string("epoch") ?: refuse("since needs epoch"), cursor.long("seq") ?: refuse("since needs seq"))
            }
        val result = feed.subscribe(since)
        subscribed = true
        return mapOf(
            "subscribed" to JsonPrimitive(true),
            "cursor" to cursorJson(result.cursor),
            "resync" to JsonPrimitive(result.resync),
        )
    }

    private fun serveUnsubscribe(): Map<String, JsonElement> {
        dropSubscription()
        return mapOf("subscribed" to JsonPrimitive(false))
    }

    private fun serveBye(): Map<String, JsonElement> {
        dropSubscription()
        hello = false
        client = null
        return emptyMap()
    }

    private fun dropSubscription() {
        if (subscribed) feed.unsubscribe()
        subscribed = false
    }

    /** [items] from the request's `offset`, at most its `limit`, cut to the budget; `next` is the offset to go on from. */
    private fun listReply(
        key: String,
        items: List<JsonObject>,
        request: JsonObject,
        defaultLimit: Int,
        maxLimit: Int,
    ): Map<String, JsonElement> {
        val offset = (request.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (request.int("limit") ?: defaultLimit).coerceIn(1, maxLimit)
        val rest = if (offset < items.size) items.subList(offset, items.size) else emptyList()
        val kept = FrameBudget.fit(rest, limit, budget)
        val more = kept.size < rest.size
        return mapOf(
            key to JsonArray(kept),
            "more" to JsonPrimitive(more),
            "next" to if (more) JsonPrimitive(offset + kept.size) else JsonNull,
        )
    }

    /** An optional array of at most [max] strings. */
    private fun JsonObject.strings(
        key: String,
        max: Int,
    ): List<String> {
        val array = this[key] ?: return emptyList()
        if (array == JsonNull) return emptyList()
        val items = (array as? JsonArray)?.takeIf { it.size <= max } ?: refuse("$key is an array of at most $max")
        return items.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse("$key holds strings") }
    }

    /** An optional array of `[start, length, code]` integer triples. */
    private fun JsonObject.triples(key: String): List<List<Int>> {
        val array = this[key] ?: return emptyList()
        if (array == JsonNull) return emptyList()
        val items = (array as? JsonArray)?.takeIf { it.size <= MAX_STYLES } ?: refuse("$key is an array of at most $MAX_STYLES")
        return items.map { item ->
            val triple = (item as? JsonArray)?.takeIf { it.size == 3 } ?: refuse("$key holds [start, length, code] triples")
            triple.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: refuse("$key holds integers") }
        }
    }

    private fun refuse(message: String): Nothing = throw UsbControlError(Codes.BAD_REQUEST, message)

    private fun notFound(message: String): Nothing = throw UsbControlError(Codes.NOT_FOUND, message)

    private fun JsonObject.need(key: String): String = string(key)?.takeIf { it.isNotEmpty() } ?: refuse("missing $key")

    private fun success(
        id: JsonElement,
        body: Map<String, JsonElement>,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("ok", true)
            body.forEach { (k, v) -> if (k != "id" && k != "ok") put(k, v) }
        }

    private fun failure(
        id: JsonElement,
        error: UsbControlError,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("ok", false)
            put("code", error.code)
            put("error", error.message.orEmpty())
            error.extra.forEach { (k, v) -> if (k !in RESERVED) put(k, v) }
        }

    companion object {
        const val VERSION = 1
        const val OP_HELLO = "hello"
        val OPS =
            listOf(
                OP_HELLO,
                "status",
                "contacts",
                "chats",
                "chat",
                "history",
                "message",
                "send",
                "react",
                "read",
                "delete",
                "accept",
                "decline",
                "block",
                "unblock",
                "subscribe",
                "unsubscribe",
                "bye",
            )
        const val DEFAULT_LIST = 100
        const val MAX_LIST = 500
        const val DEFAULT_HISTORY = 50
        const val MAX_HISTORY = 200
        const val MAX_NONCE = 64
        const val MAX_CLIENT = 64
        const val MAX_CLIENT_ID = 64
        const val MAX_MENTIONS = 16
        const val MAX_STYLES = 64
        private val RESERVED = setOf("id", "ok", "code", "error")
        private val JSON = Json

        val LIMITS: JsonObject =
            buildJsonObject {
                put("maxFrame", UsbControlFrames.MAX_FRAME)
                put("maxText", TextLimits.MESSAGE)
                put("maxReaction", TextLimits.REACTION)
                put("maxHistory", MAX_HISTORY)
                put("maxList", MAX_LIST)
            }

        fun cursorJson(cursor: HistoryCursor): JsonObject =
            buildJsonObject {
                put("sentAt", cursor.sentAt)
                put("id", cursor.id)
            }

        fun cursorJson(cursor: ChangeCursor): JsonObject =
            buildJsonObject {
                put("epoch", cursor.epoch)
                put("seq", cursor.seq)
            }

        /** The event the server sends when a frame can't be read; it drops what it held and reads on. */
        fun framingError(reason: String): JsonObject =
            buildJsonObject {
                put("event", "error")
                put("code", Codes.FRAMING)
                put("error", "framing: $reason")
            }

        internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
