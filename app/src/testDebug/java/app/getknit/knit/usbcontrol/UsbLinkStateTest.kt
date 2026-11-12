@file:Suppress("MagicNumber") // counters and bytes are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.crypto.LinkSeal
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * One link frame by frame: nothing before a session, a paired computer's open switching it to sealed frames, and every
 * way a session ends (a frame that does not open, a clear frame, a bye, a forget, a lost place), each back on the clear
 * side with the old keys dead.
 */
class UsbLinkStateTest {
    private var seed = 0
    private val store = FakePairingStore()
    private val ended = mutableListOf<String>()
    private val api =
        mockk<UsbControlApi> {
            coEvery { identity() } returns buildJsonObject { put("node", "me") }
            coEvery { status() } returns buildJsonObject { put("health", "Healthy") }
        }
    private val door =
        UsbDoor(
            store = store,
            pending = PendingPairs({ 0L }),
            enabled = { true },
            deviceLocked = { false },
            now = { 0L },
            ephemeral = { LinkSeal.Ephemeral.fixed(scalar()) },
        )
    private val link = UsbLinkState(door, newSession = { UsbControlSession(api) }, onSessionEnd = { ended += it })
    private val pairing =
        Pairing(handle = ByteArray(16) { 3 }, psk = ByteArray(32) { 4 }, name = "desk", pairedAt = 0L, lastUsedAt = 0L)

    init {
        store.list = listOf(pairing)
    }

    private fun scalar() = ByteArray(32).also { java.util.Random(seed++.toLong()).nextBytes(it) }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    /** The computer's end of one session. */
    private inner class Host {
        lateinit var sealer: UsbSeal.Sealer
        lateinit var opener: UsbSeal.Opener

        suspend fun open(handle: ByteArray = pairing.handle): JsonObject {
            val e = LinkSeal.Ephemeral.fixed(scalar())
            val eH = e.pub
            val reply =
                clearReply(
                    link
                        .frame(
                            clear(
                                buildJsonObject {
                                    put("id", 1)
                                    put("op", "open")
                                    put("v", 2)
                                    put("nonce", "n")
                                    put("handles", buildJsonArray { add(JsonPrimitive(b64(handle))) })
                                    put("e", b64(eH))
                                },
                            ),
                        ).single(),
                )
            if (reply["ok"]?.jsonPrimitive?.content == "true") {
                val eP = Base64.getDecoder().decode(reply.getValue("e").jsonPrimitive.content)
                val keys = UsbSeal.sessionKeys(UsbSeal.Role.HOST, pairing.psk, UsbSeal.sharedSecret(e, eP), eH, eP, handle)
                sealer = UsbSeal.Sealer(keys.send)
                opener = UsbSeal.Opener(keys.recv)
            }
            return reply
        }

        fun sealed(json: String) = RawFrame(true, sealer.seal(json.encodeToByteArray()))

        fun read(frame: ByteArray): JsonObject = Json.parseToJsonElement(opener.open(frame).decodeToString()).jsonObject
    }

    private fun clear(json: JsonObject) = RawFrame(false, json.toString().encodeToByteArray())

    /** A frame the phone wrote in the clear, as JSON; fails on a sealed one. */
    private fun clearReply(frame: ByteArray): JsonObject {
        assertFalse(
            "written in the clear",
            UsbSeal.isSealed(
                java.nio.ByteBuffer
                    .wrap(frame)
                    .int,
            ),
        )
        return Json.parseToJsonElement(frame.copyOfRange(4, frame.size).decodeToString()).jsonObject
    }

    private fun JsonObject.code() = get("code")?.jsonPrimitive?.content

    private suspend fun Host.hello(): JsonObject = read(link.frame(sealed("""{"id":2,"op":"hello","v":1,"nonce":"h"}""")).single())

    @Test
    fun beforeASessionNothingIsServedClearOrSealed() =
        runTest {
            val reply =
                clearReply(
                    link
                        .frame(
                            clear(
                                buildJsonObject {
                                    put("id", 1)
                                    put("op", "status")
                                },
                            ),
                        ).single(),
                )
            assertEquals(Codes.SESSION_FIRST, reply.code())
            val stray = RawFrame(true, UsbSeal.Sealer(ByteArray(32)).seal("x".encodeToByteArray()))
            assertEquals(Codes.SESSION_FIRST, clearReply(link.frame(stray).single()).code())
            assertFalse(link.sessionUp)
        }

    @Test
    fun aPairedComputersOpenSwitchesTheLinkToSealedFrames() =
        runTest {
            val host = Host()
            assertEquals("true", host.open()["ok"]?.jsonPrimitive?.content)
            assertTrue(link.sessionUp)
            val hello = host.hello()
            assertEquals(
                "me",
                hello
                    .getValue("self")
                    .jsonObject
                    .getValue("node")
                    .jsonPrimitive.content,
            )
            val status = host.read(link.frame(host.sealed("""{"id":3,"op":"status"}""")).single())
            assertEquals("Healthy", status.getValue("health").jsonPrimitive.content)
        }

    @Test
    fun aFrameThatDoesNotOpenEndsTheSessionAndSaysSoInTheClear() =
        runTest {
            val host = Host()
            host.open()
            host.hello()
            val forged = host.sealed("""{"id":3,"op":"status"}""").bytes.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertEquals(Codes.SESSION_ENDED, clearReply(link.frame(RawFrame(true, forged)).single()).code())
            assertFalse(link.sessionUp)
            assertEquals(1, ended.size)
            assertEquals(
                "the old keys open nothing now",
                Codes.SESSION_FIRST,
                clearReply(link.frame(host.sealed("""{"id":4,"op":"status"}""")).single()).code(),
            )
        }

    @Test
    fun aClearFrameInASessionEndsItAndIsServedAsTheNextRun() =
        runTest {
            val first = Host()
            first.open()
            first.hello()
            val second = Host()
            assertEquals("the next run's open is served", "true", second.open()["ok"]?.jsonPrimitive?.content)
            assertEquals(listOf("a new run opened"), ended)
            assertTrue(second.hello().containsKey("self"))
            val stale = first.sealed("""{"id":9,"op":"status"}""")
            assertEquals("the last run's keys end the new session", Codes.SESSION_ENDED, clearReply(link.frame(stale).single()).code())
        }

    @Test
    fun aByeEndsTheSessionAfterItsSealedReply() =
        runTest {
            val host = Host()
            host.open()
            host.hello()
            val reply = host.read(link.frame(host.sealed("""{"id":5,"op":"bye"}""")).single())
            assertEquals("true", reply["ok"]?.jsonPrimitive?.content)
            assertFalse(link.sessionUp)
            assertEquals(listOf("bye"), ended)
        }

    @Test
    fun forgettingThePairingEndsItsSessionAndOnlyIts() =
        runTest {
            val host = Host()
            host.open()
            assertTrue("another pairing's forget leaves it", link.forgotten(listOf(ByteArray(16) { 9 })).isEmpty())
            assertTrue(link.sessionUp)
            assertEquals(Codes.SESSION_ENDED, clearReply(link.forgotten(listOf(pairing.handle)).single()).code())
            assertFalse(link.sessionUp)
        }

    @Test
    fun aLostPlaceEndsTheSessionAndAnswersTheFramingError() =
        runTest {
            val host = Host()
            host.open()
            assertEquals(Codes.FRAMING, clearReply(link.framing("frame length 0").single()).code())
            assertFalse(link.sessionUp)
        }

    @Test
    fun eventsAreSealedInTheSessionsOwnCountAndNeverSentWithoutOne() =
        runTest {
            val event = buildJsonObject { put("event", "status") }
            assertEquals(null, link.event(event))
            val host = Host()
            host.open()
            host.hello()
            assertEquals(
                "status",
                host
                    .read(checkNotNull(link.event(event)))
                    .getValue("event")
                    .jsonPrimitive.content,
            )
            val after = host.read(link.frame(host.sealed("""{"id":6,"op":"status"}""")).single())
            assertEquals("a reply after an event opens at the next counter", "Healthy", after.getValue("health").jsonPrimitive.content)
        }

    @Test
    fun theLinkClosingEndsTheSessionSilently() =
        runTest {
            Host().open()
            link.close()
            assertFalse(link.sessionUp)
            assertEquals(listOf("link closed"), ended)
        }
}
