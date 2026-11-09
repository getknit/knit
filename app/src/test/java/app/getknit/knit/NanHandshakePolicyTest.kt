package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkHandshake.AnswerOutcome
import app.getknit.knit.mesh.link.LinkHandshake.DialOutcome
import app.getknit.knit.mesh.link.LinkHandshake.Opening
import app.getknit.knit.mesh.link.LinkHandshake.SealOpened
import app.getknit.knit.mesh.link.LinkHandshake.SealedHello
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.wifiaware.NanCueCodec
import app.getknit.knit.mesh.wifiaware.NanHandshakePolicy
import app.getknit.knit.mesh.wifiaware.NanHandshakePolicy.DialVerdict
import app.getknit.knit.mesh.wifiaware.NanHandshakePolicy.HoldKind
import app.getknit.knit.mesh.wifiaware.NanHandshakePolicy.Refusal
import app.getknit.knit.mesh.wifiaware.NanHandshakePolicy.Tally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** #286 on the Wi-Fi Aware plane: the form a dial opens with, what each dial outcome costs, and the `dialerAlive` table. */
class NanHandshakePolicyTest {
    private val peer = NodeId.derive("nan-handshake-peer")
    private val reply = Protocol.PeerWire(peer, Protocol.VERSION, Protocol.LOCAL_CAPABILITIES)
    private val socket =
        object : LinkSocket {
            override val input = ByteArrayInputStream(ByteArray(0))
            override val output = ByteArrayOutputStream()

            override fun close() = Unit
        }

    private fun neverAsked(): Boolean = throw AssertionError("a verdict other than a legacy reply's never asks LinkClaims")

    // --- the form ---

    @Test
    fun theSsiCapsPickTheSealedFormOnlyWhenTheyCarryTheSealBit() {
        assertTrue(NanHandshakePolicy.opensSealed(Protocol.LOCAL_CAPABILITIES))
        assertTrue("the bit alone decides", NanHandshakePolicy.opensSealed(Protocol.CAP_LINK_SEAL))
        // v2.11.0 (proof, no seal) and v2.10.x (neither) SSIs dial plain.
        assertFalse(NanHandshakePolicy.opensSealed(0xC2FFFL))
        assertFalse(NanHandshakePolicy.opensSealed(0x2FFFL))
        assertFalse(NanHandshakePolicy.opensSealed(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL.inv()))
    }

    @Test
    fun aBuildThatDoesNotSealNeverOpensSealedWhateverTheSsiSays() {
        val local = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL.inv()
        assertFalse(NanHandshakePolicy.opensSealed(Protocol.LOCAL_CAPABILITIES, localCaps = local))
    }

    @Test
    fun thePublishedSsiCarriesTheSealBitAndStaysUnderTheMessageCap() {
        // WifiAwareTransport's publish SSI, as built there: the advert, then the `|d<digest>` segment, at its longest.
        val ssi = Protocol.advertise(peer) + NanCueCodec.encodeSsiDigest(Long.MAX_VALUE)
        val parsed = checkNotNull(Protocol.parse(ssi))
        assertTrue("a peer dialing from our SSI opens sealed", NanHandshakePolicy.opensSealed(parsed.capabilities))
        assertTrue("1c2fff", ssi.contains("|1c2fff|"))
        assertTrue("${ssi.length} bytes", ssi.encodeToByteArray().size < 255)
    }

    // --- the dialer's outcomes ---

    @Test
    fun noKeyShareIsTheFlatRetryAndNeverAFailedSeal() {
        assertEquals(DialVerdict.Retry(), NanHandshakePolicy.opened(SealOpened.NoReply))
    }

    @Test
    fun aWrongKeyShareHoldsThePeerAsAFailedSeal() {
        assertEquals(
            DialVerdict.Hold(HoldKind.SEAL_FAILED, "hello where a key share belongs"),
            NanHandshakePolicy.opened(SealOpened.Failed("hello where a key share belongs")),
        )
    }

    @Test
    fun bothKeySharesCrossedRunsTheSealedDialNext() {
        // Sealed carries a socket only the handshake builds; any value of it is "go on".
        val sealed = sealedOpening()
        assertNull(NanHandshakePolicy.opened(sealed))
    }

    @Test
    fun noReplyAndAWrongPeerAreTheFlatRetry() {
        assertEquals(DialVerdict.Retry(), NanHandshakePolicy.dialed(DialOutcome.NoReply, ::neverAsked))
        assertEquals(DialVerdict.Retry(), NanHandshakePolicy.dialed(DialOutcome.WrongPeer(reply), ::neverAsked))
    }

    @Test
    fun aTransientProofFailureIsTheFlatRetryButStillToldToLinkClaims() {
        for (why in listOf(LinkHandshake.WHY_UNREADABLE, LinkHandshake.WHY_EOF)) {
            assertEquals(
                why,
                DialVerdict.Retry(proofFailed = why),
                NanHandshakePolicy.dialed(DialOutcome.ProofFailed(reply, why), ::neverAsked),
            )
        }
    }

    @Test
    fun aProofThatCameAndFailedHoldsThePeer() {
        assertEquals(
            DialVerdict.Hold(HoldKind.PROOF_FAILED, "bad_signature"),
            NanHandshakePolicy.dialed(DialOutcome.ProofFailed(reply, "bad_signature"), ::neverAsked),
        )
    }

    @Test
    fun aSealFailureHoldsThePeerAndNeverTellsLinkClaims() {
        val verdict = NanHandshakePolicy.dialed(DialOutcome.SealFailed("sealed frame"), ::neverAsked)
        assertEquals(DialVerdict.Hold(HoldKind.SEAL_FAILED, "sealed frame"), verdict)
    }

    @Test
    fun aPlainReplyWithTheSealBitHoldsThePeerAndNamesTheClaimedId() {
        val verdict = NanHandshakePolicy.dialed(DialOutcome.SealRefused(reply), ::neverAsked) as DialVerdict.Hold
        assertEquals(HoldKind.SEAL_REFUSED, verdict.hold)
        assertTrue(verdict.why, verdict.why.contains(peer))
    }

    @Test
    fun aProvenLinkRegistersTheSocketTheHandshakeNamed() {
        val linked = DialOutcome.Linked(reply, proven = true, link = socket)
        val verdict = NanHandshakePolicy.dialed(linked, ::neverAsked) as DialVerdict.Register
        assertSame(socket, verdict.linked.link)
    }

    @Test
    fun aLegacyReplyRegistersUnlessTheIdMustProveItself() {
        val linked = DialOutcome.Linked(reply, proven = false, link = socket)
        assertEquals(DialVerdict.Register(linked), NanHandshakePolicy.dialed(linked) { false })
        assertEquals(DialVerdict.Hold(HoldKind.UNPROVEN_REPLY, "unproven reply"), NanHandshakePolicy.dialed(linked) { true })
    }

    // --- the dialerAlive table ---

    @Test
    fun aMissingFirstRecordIsNotAlive() {
        assertEquals(Refusal(dialerAlive = false), NanHandshakePolicy.refusal(Opening.None))
    }

    @Test
    fun aRefusedKeyShareIsAliveAndAFailedSeal() {
        val refusal = checkNotNull(NanHandshakePolicy.refusal(Opening.SealFailed("key share length")))
        assertTrue("the dialer blocks on our key share", refusal.dialerAlive)
        assertEquals(Tally.SEAL_FAILED, refusal.tally)
        assertNull(refusal.proofFailed)
    }

    @Test
    fun aPlainOrSealedOpeningGoesOn() {
        val hello = LinkHandshake.ReceivedHello(ByteArray(0), reply)
        assertNull(NanHandshakePolicy.refusal(Opening.Plain(hello)))
        assertNull(NanHandshakePolicy.refusal(Opening.Sealed(sealedResponderSocket())))
    }

    @Test
    fun aPlainHelloRefusedUnderSection7IsAliveAndCountedRefused() {
        assertTrue(NanHandshakePolicy.PLAIN_REFUSED.dialerAlive)
        assertEquals(Tally.SEAL_REFUSED, NanHandshakePolicy.PLAIN_REFUSED.tally)
        assertEquals(
            "the answer's own §7 refusal is the same row",
            NanHandshakePolicy.PLAIN_REFUSED,
            NanHandshakePolicy.refusal(AnswerOutcome.SealRefused),
        )
    }

    @Test
    fun aHelloAdmissionRefusedIsAliveAndCountsNoSeal() {
        assertEquals(Refusal(dialerAlive = true), NanHandshakePolicy.NOT_ADMITTED)
    }

    @Test
    fun noSealedHelloIsNotAliveAndNeverAFailedSeal() {
        assertEquals(Refusal(dialerAlive = false), NanHandshakePolicy.refusal(SealedHello.None))
    }

    @Test
    fun aSealedHelloThatBrokeTheSealIsAliveAndAFailedSeal() {
        val refusal = checkNotNull(NanHandshakePolicy.refusal(SealedHello.SealFailed(LinkHandshake.WHY_UNSEALABLE)))
        assertTrue(refusal.dialerAlive)
        assertEquals(Tally.SEAL_FAILED, refusal.tally)
    }

    @Test
    fun aSealedHelloThatCameGoesOn() {
        assertNull(NanHandshakePolicy.refusal(SealedHello.Hello(LinkHandshake.ReceivedHello(ByteArray(0), reply))))
    }

    @Test
    fun anUnwritableAnswerIsAlive() {
        assertEquals(Refusal(dialerAlive = true), NanHandshakePolicy.refusal(AnswerOutcome.WriteFailed))
    }

    @Test
    fun aProofThatCameAndFailedIsAliveAndAnUnreadableOneIsNot() {
        for (why in listOf(LinkHandshake.WHY_EOF, "bad_signature", "digest record before the proof")) {
            val refusal = checkNotNull(NanHandshakePolicy.refusal(AnswerOutcome.ProofFailed(why)))
            assertTrue(why, refusal.dialerAlive)
            assertEquals(why, refusal.proofFailed)
            assertEquals("a proof failure is never a seal count", Tally.NONE, refusal.tally)
        }
        val unreadable = checkNotNull(NanHandshakePolicy.refusal(AnswerOutcome.ProofFailed(LinkHandshake.WHY_UNREADABLE)))
        assertFalse("our soTimeout cut it, or the NDP went: maybe gone", unreadable.dialerAlive)
        assertEquals(LinkHandshake.WHY_UNREADABLE, unreadable.proofFailed)
    }

    @Test
    fun aProofFrameThatBrokeTheSealIsAliveAndAFailedSealNotAProofFailure() {
        val refusal = checkNotNull(NanHandshakePolicy.refusal(AnswerOutcome.SealFailed("sealed frame")))
        assertTrue(refusal.dialerAlive)
        assertEquals(Tally.SEAL_FAILED, refusal.tally)
        assertNull(refusal.proofFailed)
    }

    @Test
    fun aLinkedAnswerIsNoRefusal() {
        assertNull(NanHandshakePolicy.refusal(AnswerOutcome.Linked(proven = true, link = socket)))
    }

    /** A real [Opening.Sealed]'s socket: two ends exchanged key shares over pipes. */
    private fun sealedResponderSocket() = (exchange().second as Opening.Sealed).link

    private fun sealedOpening(): SealOpened = exchange().first

    private fun exchange(): Pair<SealOpened, Opening> {
        val dialerToResponder = java.io.PipedOutputStream()
        val responderIn = java.io.PipedInputStream(dialerToResponder, PIPE)
        val responderToDialer = java.io.PipedOutputStream()
        val dialerIn = java.io.PipedInputStream(responderToDialer, PIPE)
        val dialer = pipeSocket(dialerIn, dialerToResponder)
        val responder = pipeSocket(responderIn, responderToDialer)
        var opening: Opening? = null
        val thread = Thread { opening = LinkHandshake.receiveOpening(responder) }.apply { start() }
        val opened = LinkHandshake.openSeal(dialer)
        thread.join(JOIN_MS)
        return opened to checkNotNull(opening)
    }

    private fun pipeSocket(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) = object : LinkSocket {
        override val input = java.io.BufferedInputStream(input)
        override val output = output

        override fun close() = Unit
    }

    private companion object {
        const val PIPE = 4096
        const val JOIN_MS = 5_000L
    }
}
