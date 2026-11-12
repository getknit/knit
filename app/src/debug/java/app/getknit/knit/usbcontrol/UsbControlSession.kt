package app.getknit.knit.usbcontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.put

/** What the USB control session reads and does in the app, behind an interface so the session tests without one. */
internal interface UsbControlApi {
    /** `node`, `name` and `app` (the version name), for the hello reply. */
    suspend fun identity(): JsonObject

    suspend fun status(): JsonObject

    suspend fun contacts(): JsonArray

    suspend fun chats(): JsonArray

    suspend fun history(
        conversation: String,
        limit: Int,
    ): JsonArray

    /** `status` (`ok`, `queued`, `blocked`, `refused`, `error`) and a `message`; `error` and `refused` fail the request. */
    suspend fun send(
        conversation: String,
        text: String,
    ): JsonObject

    /** One `{"event":"message",…}` object per message new since collection began. */
    fun messageEvents(): Flow<JsonObject>
}

/** A request the session refuses, with the reason it answers. */
internal class UsbControlError(
    message: String,
) : Exception(message)

/**
 * One host's session over the accessory stream: protocol v0 of the spike. A request is `{"id":…,"op":…}` and gets
 * exactly one reply, `{"id":…,"ok":true,…}` or `{"id":…,"ok":false,"error":"…"}`, with the id echoed as sent. Nothing
 * but `hello` is served before a hello; a hello resets the session (a new CLI run on the same cable sends one), so
 * [subscribed] drops until the host asks again. The caller owns the event feed and follows [subscribed].
 */
internal class UsbControlSession(
    private val api: UsbControlApi,
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
                ?: return failure(JsonNull, "not a JSON object")
        val id = request["id"] ?: JsonNull
        val op = request.string("op") ?: return failure(id, "missing op")
        if (op != OP_HELLO && !hello) return failure(id, "send hello first")
        return try {
            success(id, serve(op, request))
        } catch (e: UsbControlError) {
            failure(id, e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(id, "internal: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun serve(
        op: String,
        request: JsonObject,
    ): Map<String, JsonElement> =
        when (op) {
            OP_HELLO -> {
                serveHello(request)
            }

            "status" -> {
                api.status()
            }

            "contacts" -> {
                mapOf("contacts" to api.contacts())
            }

            "chats" -> {
                mapOf("chats" to api.chats())
            }

            "history" -> {
                val conversation = request.string("conv") ?: refuse("missing conv")
                val limit = (request.int("limit") ?: DEFAULT_HISTORY).coerceIn(1, MAX_HISTORY)
                mapOf("conv" to JsonPrimitive(conversation), "messages" to api.history(conversation, limit))
            }

            "send" -> {
                serveSend(request)
            }

            "subscribe" -> {
                subscribed = true
                mapOf("subscribed" to JsonPrimitive(true))
            }

            "unsubscribe" -> {
                subscribed = false
                mapOf("subscribed" to JsonPrimitive(false))
            }

            "bye" -> {
                subscribed = false
                hello = false
                client = null
                emptyMap()
            }

            else -> {
                refuse("unknown op: $op")
            }
        }

    private suspend fun serveHello(request: JsonObject): Map<String, JsonElement> {
        val version = request.int("v") ?: refuse("missing v")
        if (version != VERSION) refuse("unsupported version $version; this phone speaks $VERSION")
        val nonce = request.string("nonce")?.takeIf { it.length in 1..MAX_NONCE } ?: refuse("missing nonce")
        hello = true
        subscribed = false
        client = request.string("client")?.take(MAX_CLIENT)
        return api.identity() +
            mapOf(
                "v" to JsonPrimitive(VERSION),
                "nonce" to JsonPrimitive(nonce),
                "ops" to buildJsonArray { OPS.forEach { add(JsonPrimitive(it)) } },
            )
    }

    private suspend fun serveSend(request: JsonObject): Map<String, JsonElement> {
        val conversation = request.string("conv") ?: request.string("to") ?: refuse("missing conv or to")
        val text = request.string("text")?.takeIf { it.isNotBlank() } ?: refuse("missing text")
        if (text.length > MAX_TEXT) refuse("text longer than $MAX_TEXT characters")
        val outcome = api.send(conversation, text)
        val status = outcome.string("status")
        if (status == "error" || status == "refused") refuse(outcome.string("message") ?: "send $status")
        return outcome + mapOf("conv" to JsonPrimitive(conversation))
    }

    private fun refuse(message: String): Nothing = throw UsbControlError(message)

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
        error: String,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("ok", false)
            put("error", error)
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    companion object {
        const val VERSION = 0
        const val OP_HELLO = "hello"
        val OPS = listOf(OP_HELLO, "status", "contacts", "chats", "history", "send", "subscribe", "unsubscribe", "bye")
        const val DEFAULT_HISTORY = 50
        const val MAX_HISTORY = 200
        const val MAX_TEXT = 4_000
        const val MAX_NONCE = 64
        const val MAX_CLIENT = 64
        private val JSON = Json

        /** The event the server sends when a frame can't be read; it drops what it held and reads on. */
        fun framingError(reason: String): JsonObject =
            buildJsonObject {
                put("event", "error")
                put("error", "framing: $reason")
            }
    }
}
