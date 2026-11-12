@file:Suppress("MagicNumber") // times, counts and bytes are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.crypto.LinkSeal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The clear side of a link, as a hostile or a careless computer meets it: pairing with the code the phone shows, the
 * three tries and the two minutes, one exchange at a time, a store the Keystore refused, the lock gate per link, and
 * nothing served before a session.
 */
class UsbDoorTest {
    private var clock = 1_000_000L
    private var locked = false
    private var enabled = true
    private var seed = 0
    private var ephemerals = 0
    private val store = FakePairingStore()
    private val notices = mutableListOf<PendingPair?>()
    private val pending = PendingPairs({ clock }) { notices += it }

    private fun door() =
        UsbDoor(
            store = store,
            pending = pending,
            enabled = { enabled },
            deviceLocked = { locked },
            now = { clock },
            drawCode = { CODE },
            ephemeral = {
                ephemerals++
                LinkSeal.Ephemeral.fixed(scalar())
            },
        )

    /** A distinct scalar for every ephemeral the test draws, on either end. */
    private fun scalar() = ByteArray(32).also { java.util.Random(seed++.toLong()).nextBytes(it) }

    /**
     * The computer's end of one exchange: its own ephemeral, spent once on the phone's answer (as knit-cli keeps the
     * secret across a retyped code), and what each typed code derives from it.
     */
    private inner class Host {
        val e = LinkSeal.Ephemeral.fixed(scalar())
        val eH: ByteArray = e.pub
        private var ss: ByteArray? = null

        fun secret(eP: ByteArray): ByteArray = ss ?: UsbSeal.sharedSecret(e, eP).also { ss = it }

        fun outputs(
            eP: ByteArray,
            code: String = CODE,
        ) = UsbPairingCrypto.schedule(secret(eP), code, eH, eP, CLIENT.encodeToByteArray())
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun bytes(
        reply: JsonObject,
        key: String,
    ) = Base64.getDecoder().decode(reply.getValue(key).jsonPrimitive.content)

    private fun request(
        op: String,
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("id", 1)
        put("op", op)
        put("v", UsbDoor.VERSION)
        put("nonce", "n1")
        build()
    }.toString()

    private fun UsbDoor.pair(host: Host): JsonObject =
        handle(
            request("pair") {
                put("client", CLIENT)
                put("e", b64(host.eH))
            },
        ).reply

    private fun UsbDoor.confirm(value: ByteArray): JsonObject = handle(request("pair.confirm") { put("confirm", b64(value)) }).reply

    private fun UsbDoor.open(
        host: Host,
        handle: ByteArray,
    ): UsbDoor.Outcome =
        handle(
            request("open") {
                put("handles", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(b64(handle))) })
                put("e", b64(host.eH))
            },
        )

    private fun JsonObject.ok() = getValue("ok").jsonPrimitive.boolean

    private fun JsonObject.code() = get("code")?.jsonPrimitive?.content

    /** Pairs one computer through [door] and returns what it derived. */
    private fun paired(door: UsbDoor): UsbPairingCrypto.Outputs {
        val host = Host()
        val answer = door.pair(host)
        assertTrue(answer.toString(), answer.ok())
        val outputs = host.outputs(bytes(answer, "e"))
        assertTrue(door.confirm(outputs.confirmHost).ok())
        return outputs
    }

    @Test
    fun aComputerThatTypedTheCodePairsAndBothEndsHoldTheSameKey() {
        val door = door()
        val host = Host()
        val answer = door.pair(host)
        assertTrue(answer.ok())
        assertEquals("n1", answer.getValue("nonce").jsonPrimitive.content)
        assertNotNull("the phone shows the code now", pending.live())
        val outputs = host.outputs(bytes(answer, "e"))
        val done = door.confirm(outputs.confirmHost)
        assertTrue(done.toString(), done.ok())
        assertArrayEquals("the phone proves it holds the same key", outputs.confirmPhone, bytes(done, "confirm"))
        val kept = store.list.single()
        assertArrayEquals(outputs.psk, kept.psk)
        assertArrayEquals(outputs.handle, kept.handle)
        assertEquals("knit-cli on desk", kept.name)
        assertNull("the exchange is over", pending.live())
        assertEquals("the notice went up and came down", 2, notices.size)
    }

    @Test
    fun aWrongCodeCostsOneOfThreeTriesAndATypoIsNotFatal() {
        val door = door()
        val host = Host()
        val eP = bytes(door.pair(host), "e")
        val wrong = door.confirm(host.outputs(eP, code = "7KQFM2XD9S").confirmHost)
        assertEquals(Codes.BAD_CODE, wrong.code())
        assertEquals(2, wrong.getValue("attemptsLeft").jsonPrimitive.int)
        assertTrue("the right code on the same exchange still pairs", door.confirm(host.outputs(eP).confirmHost).ok())
        assertEquals(1, store.list.size)
    }

    @Test
    fun threeWrongCodesEndTheExchangeAndNothingIsKept() {
        val door = door()
        val host = Host()
        val eP = bytes(door.pair(host), "e")
        val wrong = host.outputs(eP, code = "7KQFM2XD9S").confirmHost
        assertEquals(
            listOf(2, 1, 0),
            (1..3).map {
                door
                    .confirm(wrong)
                    .getValue("attemptsLeft")
                    .jsonPrimitive.int
            },
        )
        assertNull(pending.live())
        assertEquals("the right code is too late", Codes.EXPIRED, door.confirm(host.outputs(eP).confirmHost).code())
        assertTrue(store.list.isEmpty())
    }

    @Test
    fun theExchangeLastsTwoMinutesOnThePhonesClock() {
        val first = door()
        val early = Host()
        val pinned = early.outputs(bytes(first.pair(early), "e"))
        clock += UsbDoor.PAIR_WINDOW_MS - 1
        assertTrue("a millisecond before the end it still pairs", first.confirm(pinned.confirmHost).ok())

        val door = door()
        val host = Host()
        val outputs = host.outputs(bytes(door.pair(host), "e"))
        clock += UsbDoor.PAIR_WINDOW_MS
        assertEquals(Codes.EXPIRED, door.confirm(outputs.confirmHost).code())
        assertEquals("only the first exchange was kept", 1, store.list.size)
        assertNull("the notice came down with it", notices.last())
    }

    @Test
    fun onePairingWaitsAtATimeAndAPlugInGetsThree() {
        val door = door()
        assertTrue(door.pair(Host()).ok())
        assertEquals(Codes.PAIR_BUSY, door.pair(Host()).code())
        assertEquals("another link waits too", Codes.PAIR_BUSY, door().pair(Host()).code())
        pending.cancel()
        assertTrue(door.pair(Host()).ok())
        pending.cancel()
        assertTrue(door.pair(Host()).ok())
        pending.cancel()
        assertEquals(Codes.PAIR_LIMIT, door.pair(Host()).code())
        assertTrue("a new link (a replug) starts again", door().pair(Host()).ok())
    }

    @Test
    fun aStoreTheKeystoreRefusedIsNeverWrittenOver() {
        val door = door()
        val outputs = paired(door)
        store.unavailable = true
        assertEquals(Codes.STORE_UNAVAILABLE, door.pair(Host()).code())
        assertEquals(
            "not 'not paired': the pairing may well be there",
            Codes.STORE_UNAVAILABLE,
            door.open(Host(), outputs.handle).reply.code(),
        )
        assertNull(pending.live())
    }

    @Test
    fun aRefusedWriteAtConfirmKeepsNothingAndSaysSo() {
        val door = door()
        val host = Host()
        val outputs = host.outputs(bytes(door.pair(host), "e"))
        store.refuseWrites = true
        assertEquals(Codes.STORE_UNAVAILABLE, door.confirm(outputs.confirmHost).code())
        assertTrue(store.list.isEmpty())
    }

    @Test
    fun aFullStoreTakesNoNinthComputer() {
        repeat(UsbDoor.MAX_PAIRINGS) {
            paired(door())
            clock += PendingPairs.START_WINDOW_MS // past the phone-wide rate, which this test is not about
        }
        assertEquals(Codes.PAIRINGS_FULL, door().pair(Host()).code())
    }

    @Test
    fun nothingButPairConfirmAndOpenIsServedBeforeASession() {
        val door = door()
        for (op in listOf("hello", "status", "chats", "send", "subscribe")) {
            val reply = door.handle(request(op)).reply
            assertEquals(op, Codes.SESSION_FIRST, reply.code())
            assertEquals("a refusal echoes the nonce, so a stale one is told apart", "n1", reply.getValue("nonce").jsonPrimitive.content)
        }
        assertEquals(Codes.BAD_REQUEST, door.handle("not json").reply.code())
        assertEquals(Codes.SESSION_FIRST, door.sealedWithoutSession().code())
    }

    @Test
    fun aPairedComputerOpensASessionWhoseKeysMatchItsOwn() {
        val outputs = paired(door())
        val host = Host()
        val opened = door().open(host, outputs.handle)
        assertTrue(opened.reply.toString(), opened.reply.ok())
        val session = checkNotNull(opened.opened)
        assertArrayEquals(outputs.handle, session.handle)
        val eP = bytes(opened.reply, "e")
        val hostKeys = UsbSeal.sessionKeys(UsbSeal.Role.HOST, outputs.psk, host.secret(eP), host.eH, eP, outputs.handle)
        val frame = UsbSeal.Sealer(hostKeys.send).seal("""{"op":"hello"}""".encodeToByteArray())
        assertEquals("""{"op":"hello"}""", UsbSeal.Opener(session.keys.recv).open(frame).decodeToString())
    }

    @Test
    fun aComputerForgottenWhileItOpensStaysForgotten() {
        val outputs = paired(door())
        clock += UsbDoor.TOUCH_EVERY_MS // so the open rewrites the record's last-used time
        // The person taps Forget on the phone just after the open read the list, before it writes the new time back.
        store.afterNextRead = { store.update { list -> list.filterNot { it.handle contentEquals outputs.handle } } }
        assertTrue("the open that read it first still opens", door().open(Host(), outputs.handle).reply.ok())
        assertTrue("the touch did not put it back", store.list.none { it.handle contentEquals outputs.handle })
        assertEquals(Codes.NOT_PAIRED, door().open(Host(), outputs.handle).reply.code())
    }

    @Test
    fun anOpenMovesOnlyItsOwnRecordsLastUse() {
        val first = paired(door())
        val second = paired(door())
        clock += UsbDoor.TOUCH_EVERY_MS
        assertTrue(door().open(Host(), first.handle).reply.ok())
        val byHandle = store.list.associateBy { it.handleText }
        assertEquals(clock, byHandle.getValue(B64.encodeToString(first.handle)).lastUsedAt)
        assertTrue(byHandle.getValue(B64.encodeToString(second.handle)).lastUsedAt < clock)
    }

    @Test
    fun aReplugCantRunTheExchangeInALoop() {
        repeat(PendingPairs.MAX_STARTS) {
            assertTrue("start $it", door().pair(Host()).ok())
            pending.cancel()
        }
        assertEquals("a fresh link, but the phone-wide rate", Codes.PAIR_LIMIT, door().pair(Host()).code())
        clock += PendingPairs.START_WINDOW_MS
        assertTrue(door().pair(Host()).ok())
    }

    @Test
    fun anUnknownHandleIsNotPaired() {
        paired(door())
        assertEquals(Codes.NOT_PAIRED, door().open(Host(), ByteArray(16) { 1 }).reply.code())
    }

    @Test
    fun aLinkServesALockedPhoneOnlyOnceItWasUnlockedOnThatLink() {
        val outputs = paired(door())
        val first = door()
        assertTrue("unlocked: the link opens", first.open(Host(), outputs.handle).reply.ok())
        locked = true
        assertTrue("locked since: the same link still serves", first.open(Host(), outputs.handle).reply.ok())

        // The link ended without a detach (a bridge close, a frame that did not open), then a locked replug.
        first.close()
        val second = door()
        assertEquals(Codes.LOCKED, second.open(Host(), outputs.handle).reply.code())
        assertEquals(false, second.unlocked)
        second.userPresent()
        assertTrue("unlocked while it was up", second.open(Host(), outputs.handle).reply.ok())
    }

    @Test
    fun aLockedOrUnpairedOpenSpendsNoKeyWork() {
        val outputs = paired(door())
        val before = ephemerals
        locked = true
        assertEquals(Codes.LOCKED, door().open(Host(), outputs.handle).reply.code())
        locked = false
        assertEquals(Codes.NOT_PAIRED, door().open(Host(), ByteArray(16)).reply.code())
        enabled = false
        assertEquals(Codes.OFF, door().open(Host(), outputs.handle).reply.code())
        assertEquals(Codes.OFF, door().pair(Host()).code())
        assertEquals(before, ephemerals)
    }

    @Test
    fun theLinkEndingEndsItsExchange() {
        val door = door()
        assertTrue(door.pair(Host()).ok())
        door().close()
        assertNotNull("another link's close leaves it", pending.live())
        door.close()
        assertNull(pending.live())
    }

    @Test
    fun aClaimedNameIsCleanedAndBoundedBeforeThePhoneShowsIt() {
        val door = door()
        val host = Host()
        val answer =
            door
                .handle(
                    request("pair") {
                        put("client", "evil‮name\nwith a break")
                        put("e", b64(host.eH))
                    },
                ).reply
        assertTrue(answer.ok())
        val shown = checkNotNull(pending.live()).name
        assertTrue(shown, '‮' !in shown && '\n' !in shown)
        assertEquals(Codes.BAD_REQUEST, door().handle(request("pair") { put("client", "x".repeat(65)) }).reply.code())
    }

    private companion object {
        const val CODE = "7KQFM2XD9R"
        const val CLIENT = "knit-cli on desk"
    }
}
