@file:Suppress("MagicNumber") // request ids and limits are the test's subject

package app.getknit.knit.usbcontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbControlSessionTest {
    private class FakeApi : UsbControlApi {
        var sendStatus = "ok"
        val sent = mutableListOf<Pair<String, String>>()
        val historyAsked = mutableListOf<Pair<String, Int>>()
        var statusThrows: Exception? = null

        override suspend fun identity(): JsonObject =
            buildJsonObject {
                put("node", "n1")
                put("name", "Alice")
                put("app", "9.9.9")
            }

        override suspend fun status(): JsonObject {
            statusThrows?.let { throw it }
            return buildJsonObject {
                put("node", "n1")
                put("health", "Healthy")
            }
        }

        override suspend fun contacts(): JsonArray = buildJsonArray { add(JsonPrimitive("c1")) }

        override suspend fun chats(): JsonArray = JsonArray(emptyList())

        override suspend fun history(
            conversation: String,
            limit: Int,
        ): JsonArray {
            historyAsked += conversation to limit
            return JsonArray(emptyList())
        }

        override suspend fun send(
            conversation: String,
            text: String,
        ): JsonObject {
            sent += conversation to text
            return buildJsonObject {
                put("status", sendStatus)
                put("message", "msg-$sendStatus")
            }
        }

        override fun messageEvents(): Flow<JsonObject> = emptyFlow()
    }

    private val api = FakeApi()
    private val session = UsbControlSession(api)

    private suspend fun hello(id: Int = 1) = session.handle("""{"id":$id,"op":"hello","v":0,"nonce":"abc","client":"knit-cli/0.1"}""")

    private val JsonObject.ok get() = getValue("ok").jsonPrimitive.boolean
    private val JsonObject.error get() = getValue("error").jsonPrimitive.content

    @Test
    fun nothingButHelloIsServedBeforeAHello() =
        runTest {
            val reply = session.handle("""{"id":7,"op":"status"}""")
            assertFalse(reply.ok)
            assertEquals("send hello first", reply.error)
            assertEquals(JsonPrimitive(7), reply["id"])
        }

    @Test
    fun helloEchoesTheNonceAndNamesThePhone() =
        runTest {
            val reply = hello()
            assertTrue(reply.ok)
            assertEquals("abc", reply.getValue("nonce").jsonPrimitive.content)
            assertEquals(0, reply.getValue("v").jsonPrimitive.int)
            assertEquals("n1", reply.getValue("node").jsonPrimitive.content)
            assertTrue(reply.getValue("ops").jsonArray.contains(JsonPrimitive("send")))
            assertEquals("knit-cli/0.1", session.client)
        }

    @Test
    fun helloRefusesAnotherVersionAndAMissingNonce() =
        runTest {
            assertEquals(
                "unsupported version 1; this phone speaks 0",
                session.handle("""{"id":1,"op":"hello","v":1,"nonce":"n"}""").error,
            )
            assertEquals("missing nonce", session.handle("""{"id":2,"op":"hello","v":0}""").error)
            assertFalse(session.handle("""{"id":3,"op":"status"}""").ok)
        }

    @Test
    fun aStringIdIsEchoedAsSent() =
        runTest {
            hello()
            assertEquals(JsonPrimitive("req-9"), session.handle("""{"id":"req-9","op":"status"}""")["id"])
        }

    @Test
    fun sendNeedsATargetAndText() =
        runTest {
            hello()
            assertEquals("missing conv or to", session.handle("""{"id":2,"op":"send","text":"hi"}""").error)
            assertEquals("missing text", session.handle("""{"id":3,"op":"send","to":"n2","text":"  "}""").error)
            assertTrue(api.sent.isEmpty())
        }

    @Test
    fun sendToADmGoesToThatThread() =
        runTest {
            hello()
            val reply = session.handle("""{"id":2,"op":"send","to":"n2","text":"hi there"}""")
            assertTrue(reply.ok)
            assertEquals("n2", reply.getValue("conv").jsonPrimitive.content)
            assertEquals(listOf("n2" to "hi there"), api.sent)
        }

    @Test
    fun aRefusedOrFailedSendFailsTheRequestWithTheReason() =
        runTest {
            hello()
            for (status in listOf("refused", "error")) {
                api.sendStatus = status
                val reply = session.handle("""{"id":2,"op":"send","conv":"g-x","text":"hi"}""")
                assertFalse(status, reply.ok)
                assertEquals("msg-$status", reply.error)
            }
        }

    @Test
    fun historyDefaultsTo50AndIsCappedAt200() =
        runTest {
            hello()
            session.handle("""{"id":2,"op":"history","conv":"nearby"}""")
            session.handle("""{"id":3,"op":"history","conv":"nearby","limit":5000}""")
            session.handle("""{"id":4,"op":"history","conv":"nearby","limit":0}""")
            assertEquals(listOf("nearby" to 50, "nearby" to 200, "nearby" to 1), api.historyAsked)
        }

    @Test
    fun aHelloOrByeDropsTheSubscription() =
        runTest {
            hello()
            session.handle("""{"id":2,"op":"subscribe"}""")
            assertTrue(session.subscribed)
            hello(3)
            assertFalse(session.subscribed)
            session.handle("""{"id":4,"op":"subscribe"}""")
            assertTrue(session.handle("""{"id":5,"op":"bye"}""").ok)
            assertFalse(session.subscribed)
            assertNull(session.client)
            assertEquals("send hello first", session.handle("""{"id":6,"op":"status"}""").error)
        }

    @Test
    fun aMalformedRequestGetsAnErrorWithNoId() =
        runTest {
            val notJson = session.handle("not json")
            assertEquals("not a JSON object", notJson.error)
            assertEquals(JsonNull, notJson["id"])
            assertEquals("missing op", session.handle("""{"id":1}""").error)
            hello()
            assertEquals("unknown op: launch", session.handle("""{"id":2,"op":"launch"}""").error)
        }

    @Test
    fun aCancelledRequestCancelsRatherThanAnswering() =
        runTest {
            hello()
            api.statusThrows = CancellationException("gone")
            val thrown = runCatching { session.handle("""{"id":2,"op":"status"}""") }.exceptionOrNull()
            assertTrue("$thrown", thrown is CancellationException)
        }

    @Test
    fun anAppFaultAnswersThatRequestAndTheSessionGoesOn() =
        runTest {
            hello()
            api.statusThrows = IllegalStateException("boom")
            assertEquals("internal: IllegalStateException", session.handle("""{"id":2,"op":"status"}""").error)
            api.statusThrows = null
            assertTrue(session.handle("""{"id":3,"op":"status"}""").ok)
        }
}
