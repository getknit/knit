@file:Suppress("MagicNumber") // request ids are the test's subject

package app.getknit.knit.usbcontrol

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `typing` op: a cue per thread per interval, never where the thread takes none, on a budget of its own. */
class UsbControlTypingTest {
    private var now = 0L
    private val api = FakeApi()
    private val ledger = SendLedger(clock = { now }, burst = 3, perMinute = 60)
    private val session = UsbControlSession(api, FakeFeed(), ledger)

    private suspend fun hello() = session.handle("""{"id":1,"op":"hello","v":1,"nonce":"abc"}""")

    private val JsonObject.ok get() = getValue("ok").jsonPrimitive.boolean
    private val JsonObject.code get() = getValue("code").jsonPrimitive.content

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean

    private suspend fun typing(conversation: String) = session.handle("""{"id":30,"op":"typing","conv":"$conversation"}""")

    @Test
    fun helloOffersTypingAndItsInterval() =
        runTest {
            val reply = hello()
            assertTrue(reply.getValue("ops").jsonArray.contains(JsonPrimitive("typing")))
            assertEquals(
                SendLedger.TYPING_INTERVAL_MS,
                reply
                    .getValue("limits")
                    .jsonObject
                    .getValue("typingIntervalMs")
                    .jsonPrimitive.long,
            )
        }

    @Test
    fun aTypingCueGoesOncePerThreadPerIntervalAndAKeystrokeSoonerIsNoError() =
        runTest {
            hello()
            val first = typing("c")
            assertTrue("$first", first.flag("sent"))
            assertEquals("c", first.text("conv"))
            val sooner = typing("c")
            assertTrue("$sooner", sooner.ok)
            assertFalse(sooner.flag("sent"))
            assertTrue("another thread keeps its own interval", typing("d").flag("sent"))
            now += SendLedger.TYPING_INTERVAL_MS
            assertTrue(typing("c").flag("sent"))
            assertEquals(listOf("c", "d", "c"), api.cues)
        }

    @Test
    fun noCueGoesWhileTypingIndicatorsAreOffAndTheOffAnswerSpendsNothing() =
        runTest {
            hello()
            api.typingOn = false
            val off = typing("c")
            assertTrue("$off", off.ok)
            assertFalse(off.flag("sent"))
            assertTrue(api.cues.isEmpty())
            api.typingOn = true
            assertTrue("the off answer started no interval", typing("c").flag("sent"))
        }

    @Test
    fun aThreadThatTakesNoCueIsRefusedAndSendsNothing() =
        runTest {
            hello()
            api.typingRefusal = UsbControlError(Codes.REFUSED, "this thread carries no typing cue")
            val refused = typing("m-public")
            assertFalse(refused.ok)
            assertEquals(Codes.REFUSED, refused.code)
            assertEquals(Codes.BAD_REQUEST, session.handle("""{"id":31,"op":"typing"}""").code)
            api.typingRefusal = null
            assertTrue("the refusal started no interval", typing("m-public").flag("sent"))
            assertEquals(listOf("m-public"), api.cues)
        }

    @Test
    fun typingCuesHaveABudgetOfTheirOwnAndLeaveTheSendBudgetWhole() =
        runTest {
            hello()
            // The default cue burst is 4: a fifth thread at once gets none.
            repeat(SendLedger.DEFAULT_TYPING_BURST) { assertTrue(typing("t$it").flag("sent")) }
            assertFalse("past the cue budget", typing("t9").flag("sent"))
            repeat(3) { assertTrue("sends keep their own budget", session.handle("""{"id":32,"op":"send","to":"n2","text":"hi"}""").ok) }
            assertEquals(SendLedger.DEFAULT_TYPING_BURST, api.cues.size)
        }
}
