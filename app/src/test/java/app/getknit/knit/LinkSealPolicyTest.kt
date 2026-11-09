package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.LinkSealPolicy
import app.getknit.knit.mesh.bluetooth.LinkSealPolicy.Count
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkHandshake.AnswerOutcome
import app.getknit.knit.mesh.link.LinkHandshake.DialOutcome
import app.getknit.knit.mesh.link.LinkHandshake.Opening
import app.getknit.knit.mesh.link.LinkHandshake.SealOpened
import app.getknit.knit.mesh.link.LinkHandshake.SealedHello
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LinkSealPolicy] (#286, ADR 2026-11.8mhs): which form a Bluetooth dial opens with (the advert heard at the address it
 * dials, never the other PHY's), which seal counter each handshake end moves, and when a plain dial's silence forgets a
 * cached GATT payload (contract §7, revisions 1 and 2).
 */
class LinkSealPolicyTest {
    /** What the transport keeps per address ([BluetoothMeshTransport]'s HeardDevice): the advert's own flag. */
    private data class Heard(
        val address: String,
        val linkSeal: Boolean,
    )

    private val peer = NodeId.derive("peer-seal")
    private val reply = Protocol.PeerWire(nodeId = peer, protoVersion = Protocol.VERSION, capabilities = 0L)

    private fun sealedTo(
        viaCoded: Boolean,
        oneM: Heard?,
        coded: Heard?,
    ): Boolean? = DialPlan.target(viaCoded, oneM, coded)?.let { LinkSealPolicy.dialsSealed(it, Heard::linkSeal) }

    // --- The dial's form ---

    @Test
    fun aDialOpensSealedFromTheServiceDataItWasMadeFrom() {
        val sealed =
            BleAdvertPayload.parse(
                BleAdvertPayload.advert(peer, 0x7L, 1L, 0x81, sideLive = true, gattPeers = false, aware = false),
            )!!
        val older = BleAdvertPayload.parse(BleAdvertPayload.encode(peer, 0x7L, 1L, 0x81, flags = BleAdvertPayload.FLAG_SIDE_CHANNEL))!!
        assertEquals(true, sealedTo(false, Heard("a", sealed.linkSeal), null))
        assertEquals("a 2.11.0 advert dials plain", false, sealedTo(false, Heard("a", older.linkSeal), null))
        val v1 = BleAdvertPayload.parse(BleAdvertPayload.encode(peer, 0x7L, 1L, 0x81).copyOf(BleAdvertPayload.SIZE_V1))!!
        assertEquals("a 23-byte advert dials plain", false, sealedTo(false, Heard("a", v1.linkSeal), null))
    }

    @Test
    fun aDialOpensSealedFromTheGattPayloadItWasMadeFrom() {
        // knit-ios's GATT value (caps 0x09, cue 0, PSM 0x80) with FLAG_DIALS_GATT_PEERS, then with FLAG_LINK_SEAL beside it.
        val unsealed = hex("09294213fa67091e03c2f2ae8d071b9d6700000000008002")
        val sealed = hex("09294213fa67091e03c2f2ae8d071b9d6700000000008012")
        assertEquals(false, sealedTo(false, Heard("iphone", BleAdvertPayload.parse(unsealed)!!.linkSeal), null))
        assertEquals(true, sealedTo(false, Heard("iphone", BleAdvertPayload.parse(sealed)!!.linkSeal), null))
    }

    @Test
    fun aCodedAndA1MSightingThatDisagreeOpenWithTheFormOfTheAddressDialed() {
        val oneM = Heard("1m", linkSeal = false)
        val coded = Heard("coded", linkSeal = true)
        assertEquals("dialed at the Coded address: its flag", true, sealedTo(viaCoded = true, oneM, coded))
        assertEquals("dialed at the 1M address: its flag", false, sealedTo(viaCoded = false, oneM, coded))
        // The other way round, and the #148 fallback to the Coded address when no 1M address is known.
        assertEquals(false, sealedTo(viaCoded = true, Heard("1m", true), Heard("coded", false)))
        assertEquals(true, sealedTo(viaCoded = false, Heard("1m", true), Heard("coded", false)))
        assertEquals(true, sealedTo(viaCoded = false, null, coded))
        assertNull(sealedTo(viaCoded = true, oneM, null))
    }

    @Test
    fun aBuildThatDoesNotSealNeverOpensSealed() {
        val target = Heard("a", linkSeal = true) to false
        assertFalse(LinkSealPolicy.dialsSealed(target, Heard::linkSeal, seals = false))
        assertTrue(LinkSealPolicy.dialsSealed(target, Heard::linkSeal, seals = true))
    }

    // --- Outcomes and counters ---

    @Test
    fun aKeyShareStageFoldsIntoTheDialsOutcome() {
        assertEquals(
            "silence where the key share belongs is no reply",
            DialOutcome.NoReply,
            LinkSealPolicy.unopened(SealOpened.NoReply, true),
        )
        assertEquals(DialOutcome.NoReply, LinkSealPolicy.unopened(null, inTime = true))
        assertEquals(
            DialOutcome.SealFailed("hello where a key share belongs"),
            LinkSealPolicy.unopened(SealOpened.Failed("hello where a key share belongs"), true),
        )
        // The stage's watchdog closed the socket: no reply, whatever the read came back with.
        assertEquals(DialOutcome.NoReply, LinkSealPolicy.unopened(SealOpened.Failed("x"), inTime = false))
    }

    @Test
    fun eachDialOutcomeCountsAtMostOneSealCounter() {
        assertEquals(Count.FAILED, LinkSealPolicy.dialCount(DialOutcome.SealFailed("frame 1 did not open")))
        assertEquals(Count.REFUSED, LinkSealPolicy.dialCount(DialOutcome.SealRefused(reply)))
        assertEquals("an EOF or timeout is never a failed seal", Count.NONE, LinkSealPolicy.dialCount(DialOutcome.NoReply))
        assertEquals(Count.NONE, LinkSealPolicy.dialCount(DialOutcome.WrongPeer(reply)))
        assertEquals(
            "a proof failure counts linkProofFailed only",
            Count.NONE,
            LinkSealPolicy.dialCount(DialOutcome.ProofFailed(reply, "bad signature")),
        )
    }

    @Test
    fun theRespondersStagesCountOnlyBytesThatWereWrong() {
        assertEquals(Count.FAILED, LinkSealPolicy.openingCount(Opening.SealFailed("key share length 31")))
        assertEquals(Count.NONE, LinkSealPolicy.openingCount(Opening.None))
        assertEquals(Count.FAILED, LinkSealPolicy.sealedHelloCount(SealedHello.SealFailed(LinkHandshake.WHY_UNSEALABLE)))
        assertEquals(Count.NONE, LinkSealPolicy.sealedHelloCount(SealedHello.None))
        assertEquals(Count.FAILED, LinkSealPolicy.answerCount(AnswerOutcome.SealFailed("inner key_share record")))
        assertEquals(Count.REFUSED, LinkSealPolicy.answerCount(AnswerOutcome.SealRefused))
        assertEquals(Count.NONE, LinkSealPolicy.answerCount(AnswerOutcome.ProofFailed("timeout")))
        assertEquals(Count.NONE, LinkSealPolicy.answerCount(AnswerOutcome.WriteFailed))
    }

    // --- The stale GATT payload (contract §7, revisions 1 and 2) ---

    private val unflagged =
        BleAdvertPayload.Parsed(
            peer,
            capabilities = 0x09,
            digestCue = 0,
            psm = 0x80,
            flags = BleAdvertPayload.FLAG_DIALS_GATT_PEERS,
        )
    private val flagged = unflagged.copy(flags = BleAdvertPayload.FLAG_DIALS_GATT_PEERS or BleAdvertPayload.FLAG_LINK_SEAL)

    @Test
    fun aPlainDialFromAnUnflaggedPayloadThatDrewNoReplyForgetsIt() {
        assertTrue(LinkSealPolicy.forgetsUnsealedPayload(sealedDial = false, DialOutcome.NoReply, gattSighted = true, payload = unflagged))
    }

    @Test
    fun nothingElseForgetsThePayload() {
        assertFalse(
            "a sealed dial's silence forgets nothing (revision 2)",
            LinkSealPolicy.forgetsUnsealedPayload(sealedDial = true, DialOutcome.NoReply, gattSighted = true, payload = flagged),
        )
        assertFalse(
            "a flagged payload is not stale",
            LinkSealPolicy.forgetsUnsealedPayload(false, DialOutcome.NoReply, gattSighted = true, payload = flagged),
        )
        assertFalse(
            "a service-data peer refreshes its flag every advert",
            LinkSealPolicy.forgetsUnsealedPayload(false, DialOutcome.NoReply, gattSighted = false, payload = unflagged),
        )
        assertFalse(
            "no payload held",
            LinkSealPolicy.forgetsUnsealedPayload(false, DialOutcome.NoReply, gattSighted = true, payload = null),
        )
        assertFalse(
            "a HELLO without the bit is never refused under §7",
            LinkSealPolicy.forgetsUnsealedPayload(false, DialOutcome.NoReply, true, unflagged, helloSeals = false),
        )
        listOf(DialOutcome.WrongPeer(reply), DialOutcome.ProofFailed(reply, "eof"), DialOutcome.SealRefused(reply)).forEach {
            assertFalse("$it is a reply", LinkSealPolicy.forgetsUnsealedPayload(false, it, gattSighted = true, payload = unflagged))
        }
    }

    @Test
    fun aForgottenPayloadTakesTheDeviceHeardFromItWithIt() {
        val cache = java.util.concurrent.ConcurrentHashMap(mapOf(peer to Heard("iphone", linkSeal = false)))
        assertTrue(LinkSealPolicy.forgetHeardAt(cache, peer, "iphone", Heard::address))
        assertNull("no stale flag left to dial plain from", cache[peer])
        // A newer sighting at another address (the iPhone rotated) stays, and a missing key is a no-op.
        cache[peer] = Heard("rotated", linkSeal = true)
        assertFalse(LinkSealPolicy.forgetHeardAt(cache, peer, "iphone", Heard::address))
        assertEquals(Heard("rotated", linkSeal = true), cache[peer])
        assertFalse(LinkSealPolicy.forgetHeardAt(cache, "nobody", "iphone", Heard::address))
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
