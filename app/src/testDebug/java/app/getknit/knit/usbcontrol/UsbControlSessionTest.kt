@file:Suppress("MagicNumber") // request ids and limits are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.TextLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbControlSessionTest {
    private class FakeApi : UsbControlApi {
        var sendError: UsbControlError? = null
        val sent = mutableListOf<SendRequest>()
        val calls = mutableListOf<String>()
        val historyAsked = mutableListOf<Triple<String, HistoryCursor?, Int>>()
        var historyPage = HistoryPage(emptyList(), null, exhausted = true)
        var chatList: List<JsonObject> = emptyList()
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

        override suspend fun contacts(): List<JsonObject> = listOf(item("c1"))

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

        private fun record(call: String): JsonObject {
            calls += call
            return buildJsonObject { put("done", call) }
        }
    }

    private class FakeFeed : FeedControl {
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

    private var now = 0L
    private val api = FakeApi()
    private val feed = FakeFeed()
    private val ledger = SendLedger(clock = { now }, burst = 3, perMinute = 60)
    private val session = UsbControlSession(api, feed, ledger, budget = BUDGET)

    private suspend fun hello(id: Int = 1) = session.handle("""{"id":$id,"op":"hello","v":1,"nonce":"abc","client":"knit-cli/0.2"}""")

    private val JsonObject.ok get() = getValue("ok").jsonPrimitive.boolean
    private val JsonObject.code get() = getValue("code").jsonPrimitive.content
    private val JsonObject.error get() = getValue("error").jsonPrimitive.content

    @Test
    fun nothingButHelloIsServedBeforeAHello() =
        runTest {
            val reply = session.handle("""{"id":7,"op":"status"}""")
            assertFalse(reply.ok)
            assertEquals(Codes.HELLO_FIRST, reply.code)
            assertEquals(JsonPrimitive(7), reply["id"])
        }

    @Test
    fun helloEchoesTheNonceNamesThePhoneAndStatesItsLimits() =
        runTest {
            val reply = hello()
            assertTrue(reply.ok)
            assertEquals("abc", reply.getValue("nonce").jsonPrimitive.content)
            assertEquals(1, reply.getValue("v").jsonPrimitive.int)
            assertEquals(
                "n1",
                reply
                    .getValue("self")
                    .jsonObject
                    .getValue("node")
                    .jsonPrimitive.content,
            )
            assertTrue(reply.getValue("ops").jsonArray.contains(JsonPrimitive("history")))
            val limits = reply.getValue("limits").jsonObject
            assertEquals(TextLimits.MESSAGE, limits.getValue("maxText").jsonPrimitive.int)
            assertEquals(UsbControlFrames.MAX_FRAME, limits.getValue("maxFrame").jsonPrimitive.int)
            assertEquals("knit-cli/0.2", session.client)
        }

    @Test
    fun helloRefusesAnotherVersionAndAMissingNonce() =
        runTest {
            val old = session.handle("""{"id":1,"op":"hello","v":0,"nonce":"n"}""")
            assertEquals(Codes.BAD_VERSION, old.code)
            assertEquals("unsupported version 0; this phone speaks 1", old.error)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":2,"op":"hello","v":1}""").code)
            assertEquals(Codes.HELLO_FIRST, session.handle("""{"id":3,"op":"status"}""").code)
        }

    @Test
    fun aStringIdIsEchoedAsSent() =
        runTest {
            hello()
            assertEquals(JsonPrimitive("req-9"), session.handle("""{"id":"req-9","op":"status"}""")["id"])
        }

    @Test
    fun sendNeedsATargetAndTextWithinTheAppsOwnLimit() =
        runTest {
            hello()
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":2,"op":"send","text":"hi"}""").code)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":3,"op":"send","to":"n2","text":"  "}""").code)
            val long = "a".repeat(TextLimits.MESSAGE + 1)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":4,"op":"send","to":"n2","text":"$long"}""").code)
            assertTrue(api.sent.isEmpty())
        }

    @Test
    fun sendToADmGoesToThatThreadAndAnswersTheNewId() =
        runTest {
            hello()
            val reply = session.handle("""{"id":2,"op":"send","to":"n2","text":"hi there"}""")
            assertTrue(reply.ok)
            assertEquals("n2", reply.getValue("conv").jsonPrimitive.content)
            assertEquals("m-1", reply.getValue("messageId").jsonPrimitive.content)
            assertEquals(listOf(SendRequest("n2", "hi there")), api.sent)
        }

    @Test
    fun aSendCarriesItsReplyMentionsAndStyles() =
        runTest {
            hello()
            session.handle("""{"id":2,"op":"send","conv":"g-x","text":"@Bob hi","replyTo":"m0","mentions":["bob"],"styles":[[0,4,0]]}""")
            assertEquals(listOf(SendRequest("g-x", "@Bob hi", "m0", listOf("bob"), listOf(listOf(0, 4, 0)))), api.sent)
        }

    @Test
    fun aSendWithMisshapenExtrasIsRefusedBeforeItGoes() =
        runTest {
            hello()
            val misshapen =
                listOf(
                    """"mentions":"bob"""",
                    """"mentions":[1]""",
                    """"styles":[[0,4]]""",
                    """"styles":[["a",1,0]]""",
                    """"clientMsgId":""""",
                )
            for (extra in misshapen) {
                val reply = session.handle("""{"id":2,"op":"send","to":"n2","text":"hi",$extra}""")
                assertEquals(extra, Codes.BAD_REQUEST, reply.code)
            }
            assertTrue(api.sent.isEmpty())
        }

    @Test
    fun aRefusedSendFailsTheRequestWithItsCode() =
        runTest {
            hello()
            api.sendError = UsbControlError(Codes.BLOCKED, "the on-device content filter refused it")
            val reply = session.handle("""{"id":2,"op":"send","conv":"nearby","text":"hi"}""")
            assertEquals(Codes.BLOCKED, reply.code)
            assertEquals("the on-device content filter refused it", reply.error)
        }

    @Test
    fun aClientMsgIdSentAgainIsAnsweredFromTheLedgerAndNotSentTwice() =
        runTest {
            hello()
            val first = session.handle("""{"id":2,"op":"send","to":"n2","text":"hi","clientMsgId":"c-1"}""")
            hello(3) // the link dropped; a new run on the same phone
            val again = session.handle("""{"id":4,"op":"send","to":"n2","text":"hi","clientMsgId":"c-1"}""")
            assertEquals(1, api.sent.size)
            assertEquals(first.getValue("messageId"), again.getValue("messageId"))
            assertTrue(again.getValue("duplicate").jsonPrimitive.boolean)
            assertEquals("the envelope still answers this request", JsonPrimitive(4), again["id"])
        }

    @Test
    fun sendsAndReactionsShareABudgetThatRefillsWithTime() =
        runTest {
            hello()
            repeat(2) { session.handle("""{"id":2,"op":"send","to":"n2","text":"hi"}""") }
            assertTrue(session.handle("""{"id":3,"op":"react","messageId":"m1","emoji":"👍"}""").ok)
            val limited = session.handle("""{"id":4,"op":"send","to":"n2","text":"hi"}""")
            assertEquals(Codes.RATE_LIMITED, limited.code)
            assertEquals(1_000L, limited.getValue("retryAfterMs").jsonPrimitive.long)
            now += 1_000
            assertTrue(session.handle("""{"id":5,"op":"send","to":"n2","text":"hi"}""").ok)
            assertEquals(3, api.sent.size)
        }

    @Test
    fun aReactionSetsOrClearsAndRefusesWhatIsNoEmoji() =
        runTest {
            hello()
            session.handle("""{"id":2,"op":"react","messageId":"m1","emoji":"👍"}""")
            session.handle("""{"id":3,"op":"react","messageId":"m1","emoji":null}""")
            session.handle("""{"id":4,"op":"react","messageId":"m1"}""")
            val long = "x".repeat(TextLimits.REACTION + 1)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":5,"op":"react","messageId":"m1","emoji":"$long"}""").code)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":6,"op":"react","messageId":"m1","emoji":" "}""").code)
            assertEquals(listOf("react m1 👍", "react m1 null", "react m1 null"), api.calls)
        }

    @Test
    fun theOtherWritesGoToTheirOwnCall() =
        runTest {
            hello()
            session.handle("""{"id":2,"op":"read","conv":"c","upTo":42}""")
            session.handle("""{"id":3,"op":"read","conv":"c"}""")
            session.handle("""{"id":4,"op":"delete","messageId":"m1"}""")
            session.handle("""{"id":5,"op":"accept","conv":"c"}""")
            session.handle("""{"id":6,"op":"decline","conv":"c"}""")
            session.handle("""{"id":7,"op":"block","node":"n2"}""")
            session.handle("""{"id":8,"op":"unblock","node":"n2"}""")
            assertEquals(
                listOf("read c 42", "read c null", "delete m1", "accept c", "decline c", "block n2", "unblock n2"),
                api.calls,
            )
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":9,"op":"block"}""").code)
        }

    @Test
    fun subscribeHandsTheFeedItsCursorAndAnswersWhereItStarts() =
        runTest {
            hello()
            val fresh = session.handle("""{"id":2,"op":"subscribe"}""")
            assertEquals(
                "e1",
                fresh
                    .getValue("cursor")
                    .jsonObject
                    .getValue("epoch")
                    .jsonPrimitive.content,
            )
            assertFalse(fresh.getValue("resync").jsonPrimitive.boolean)
            val stale = session.handle("""{"id":3,"op":"subscribe","since":{"epoch":"old","seq":3}}""")
            assertTrue(stale.getValue("resync").jsonPrimitive.boolean)
            assertEquals(listOf(null, ChangeCursor("old", 3)), feed.since)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":4,"op":"subscribe","since":{"seq":3}}""").code)
        }

    @Test
    fun historyDefaultsTo50IsCappedAt200AndPassesItsCursor() =
        runTest {
            hello()
            session.handle("""{"id":2,"op":"history","conv":"nearby"}""")
            session.handle("""{"id":3,"op":"history","conv":"nearby","limit":5000}""")
            session.handle("""{"id":4,"op":"history","conv":"nearby","limit":0,"before":{"sentAt":7,"id":"m7"}}""")
            assertEquals(
                listOf(
                    Triple("nearby", null, 50),
                    Triple("nearby", null, 200),
                    Triple("nearby", HistoryCursor(7, "m7"), 1),
                ),
                api.historyAsked,
            )
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":5,"op":"history","conv":"nearby","before":{"sentAt":7}}""").code)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":6,"op":"history"}""").code)
        }

    @Test
    fun aHistoryPageReadsOldestFirstAndGoesOnFromItsTail() =
        runTest {
            hello()
            // Newest first from the app; the tail is a blocked row the page read but left out.
            val newestFirst = listOf(item("m3") to HistoryCursor(3, "m3"), item("m2") to HistoryCursor(2, "m2"))
            api.historyPage = HistoryPage(newestFirst, HistoryCursor(1, "m1"), exhausted = false)
            val reply = session.handle("""{"id":2,"op":"history","conv":"nearby","limit":3}""")
            val ids =
                reply.getValue("messages").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            assertEquals(listOf("m2", "m3"), ids)
            assertTrue(reply.getValue("more").jsonPrimitive.boolean)
            assertEquals(
                1L,
                reply
                    .getValue("next")
                    .jsonObject
                    .getValue("sentAt")
                    .jsonPrimitive.long,
            )

            api.historyPage = HistoryPage(listOf(item("m1") to HistoryCursor(1, "m1")), HistoryCursor(1, "m1"), exhausted = true)
            val last = session.handle("""{"id":3,"op":"history","conv":"nearby"}""")
            assertFalse(last.getValue("more").jsonPrimitive.boolean)
            assertEquals(JsonNull, last["next"])
        }

    @Test
    fun aHistoryPageOverTheBudgetStopsEarlyAndGoesOnFromTheLastItKept() =
        runTest {
            hello()
            val big = "x".repeat(BUDGET / 2)
            api.historyPage =
                HistoryPage(
                    (5 downTo 1).map { item("m$it", big) to HistoryCursor(it.toLong(), "m$it") },
                    HistoryCursor(1, "m1"),
                    exhausted = true,
                )
            val reply = session.handle("""{"id":2,"op":"history","conv":"nearby"}""")
            // Each item is just over half the budget, so one fits: the newest, the one nearest the cursor.
            val ids =
                reply.getValue("messages").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            assertEquals(listOf("m5"), ids)
            assertTrue("the app had more, though it said exhausted", reply.getValue("more").jsonPrimitive.boolean)
            assertEquals(
                "m5",
                reply
                    .getValue("next")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun aListReplyPagesByOffsetAndStopsAtTheBudget() =
        runTest {
            hello()
            api.chatList = (1..5).map { item("c$it") }
            val first = session.handle("""{"id":2,"op":"chats","limit":2}""")
            assertEquals(
                listOf("c1", "c2"),
                first.getValue("chats").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                },
            )
            assertEquals(2, first.getValue("next").jsonPrimitive.int)
            val rest = session.handle("""{"id":3,"op":"chats","offset":2}""")
            assertEquals(3, rest.getValue("chats").jsonArray.size)
            assertFalse(rest.getValue("more").jsonPrimitive.boolean)

            api.chatList = (1..5).map { item("c$it", "x".repeat(BUDGET / 2)) }
            val cut = session.handle("""{"id":4,"op":"chats"}""")
            assertTrue(cut.getValue("chats").jsonArray.size < 5)
            assertTrue(cut.getValue("more").jsonPrimitive.boolean)
        }

    @Test
    fun aMissingChatOrMessageIsNotFound() =
        runTest {
            hello()
            assertEquals(Codes.NOT_FOUND, session.handle("""{"id":2,"op":"chat","conv":"nobody"}""").code)
            assertEquals(Codes.NOT_FOUND, session.handle("""{"id":3,"op":"message","messageId":"m9"}""").code)
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
            assertEquals("the feed was stopped each time", 2, feed.unsubscribed)
            assertNull(session.client)
            assertEquals(Codes.HELLO_FIRST, session.handle("""{"id":6,"op":"status"}""").code)
        }

    @Test
    fun aMalformedRequestGetsAnErrorWithNoId() =
        runTest {
            val notJson = session.handle("not json")
            assertEquals(Codes.BAD_REQUEST, notJson.code)
            assertEquals(JsonNull, notJson["id"])
            assertEquals("missing op", session.handle("""{"id":1}""").error)
            hello()
            assertEquals(Codes.UNKNOWN_OP, session.handle("""{"id":2,"op":"launch"}""").code)
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
            val reply = session.handle("""{"id":2,"op":"status"}""")
            assertEquals(Codes.INTERNAL, reply.code)
            assertEquals("internal: IllegalStateException", reply.error)
            api.statusThrows = null
            assertTrue(session.handle("""{"id":3,"op":"status"}""").ok)
        }

    private companion object {
        const val BUDGET = 4_096

        fun item(
            id: String,
            body: String = "",
        ): JsonObject =
            buildJsonObject {
                put("id", id)
                put("body", body)
            }
    }
}
