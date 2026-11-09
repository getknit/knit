package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.Protocol
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable

/**
 * The plain form of the link handshake ([LinkHandshake.dialPlain], [LinkHandshake.dialTagPlain],
 * [LinkHandshake.receiveOpening], [LinkHandshake.answerPlain], ADR 2026-11.264w): the two-way HELLO, and on a link whose
 * two HELLOs carry [Protocol.CAP_LINK_PROOF], a proof each way over the v1 transcript of the node id each HELLO claims. A
 * legacy HELLO on either end links unproven, a PROOF is never written toward a HELLO without the bit, and a proof that is
 * missing, malformed, another identity's or for the other role is a failure, never a guess.
 *
 * This build seals (#286), so §7 refuses a plain link toward a HELLO that carries [Protocol.CAP_LINK_SEAL]: every plain
 * peer here is a build that proves and does not seal (v2.11.0's shape, [unsealedCaps]) or a legacy one. The sealed form
 * and §7's own cases are [SealedLinkHandshakeTest]'s; [twoSealingBuildsNeverLinkPlainOverAPipe] and
 * [theRespondersToldFormRidesItsSealedReplyAndItsProofCoversIt] run both ends at once.
 */
class LinkHandshakeTest {
    @Before
    fun tink() = TinkInit.ensure()

    private class Id(
        seed: Int,
    ) {
        private val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(ByteArray(32) { (seed + it).toByte() })
        val bundle = PublicKeyBundle.fromRaw(signing.publicKey, X25519.publicFromPrivate(ByteArray(32) { (seed * 3 + it).toByte() }))!!
        val prover = LinkProver(bundle, Ed25519Sign(signing.privateKey)::sign)
        val nodeId = prover.nodeId
    }

    private val alice = Id(1)
    private val bob = Id(40)
    private val nonceA = ByteArray(Protocol.HELLO_NONCE_BYTES) { it.toByte() }
    private val nonceB = ByteArray(Protocol.HELLO_NONCE_BYTES) { (0x10 + it).toByte() }

    /** A build that proves and does not seal: this build's caps without [Protocol.CAP_LINK_SEAL]. */
    private val unsealedCaps = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL.inv()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** A HELLO from a build at [unsealedCaps], as [Protocol.hello] would write it there. */
    private fun unsealedHello(
        nodeId: String,
        nonce: ByteArray,
        told: ByteArray? = null,
    ): ByteArray {
        val t = told?.let { "|t${hex(it)}" }.orEmpty()
        return "$nodeId|${Protocol.VERSION}|${unsealedCaps.toString(16)}|n${hex(nonce)}$t".encodeToByteArray()
    }

    /** An advert-shaped HELLO (fields 0-2) at [unsealedCaps], with [suffix] where the nonce belongs. */
    private fun unsealedAdvert(
        nodeId: String,
        suffix: String,
    ) = "$nodeId|${Protocol.VERSION}|${unsealedCaps.toString(16)}$suffix".encodeToByteArray()

    // Alice (this build) dials Bob (a build that doesn't seal): the two HELLO payloads exactly as they cross the link.
    private val helloD = Protocol.hello(alice.nodeId, nonceA).encodeToByteArray()
    private val helloR = unsealedHello(bob.nodeId, nonceB)

    // A dialer that doesn't seal (Alice) and this build answering (Bob).
    private val oldD = unsealedHello(alice.nodeId, nonceA)
    private val ourR = Protocol.hello(bob.nodeId, nonceB).encodeToByteArray()

    private val replyWire = Protocol.parse(helloR.decodeToString())!!

    private fun record(
        type: LinkFraming.Type,
        payload: ByteArray,
    ) = LinkFraming.encode(type, payload)

    private fun legacyHello(nodeId: String) = "$nodeId|${Protocol.VERSION}|9".encodeToByteArray()

    private fun records(bytes: ByteArray): List<LinkFraming.Message> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { LinkFraming.read(input) }.toList()
    }

    /** Bob's half against a canned dialer: the opening read, which must be a plain HELLO, and the link it came on. */
    private fun opened(
        bytes: ByteArray,
        out: OutputStream = ByteArrayOutputStream(),
    ): Pair<StreamLink, LinkHandshake.ReceivedHello> {
        val link = StreamLink(bytes, out)
        val opening = LinkHandshake.receiveOpening(link)
        return link to (opening as LinkHandshake.Opening.Plain).hello
    }

    // --- the dialer, against a canned responder ---

    @Test
    fun aDialerVerifiesTheRespondersProofAndThenProvesItself() {
        val bobsProof = bob.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        val out = ByteArrayOutputStream()
        val link = StreamLink(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof), out)

        val dialed = LinkHandshake.dialPlain(link, alice.prover, bob.nodeId, nonceA)

        assertEquals(LinkHandshake.DialOutcome.Linked(replyWire, proven = true, link = link), dialed)
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO, LinkFraming.Type.PROOF), written.map { it.type })
        // The v1 exchange, byte for byte: our HELLO as Protocol.hello writes it, our proof over the v1 transcript.
        assertArrayEquals(helloD, written[0].payload)
        assertArrayEquals(alice.prover.proof(LinkProof.Role.DIALER, helloD, helloR), written[1].payload)
        assertNull(LinkProof.verify(written[1].payload, alice.nodeId, LinkProof.Role.DIALER, helloD, helloR))
    }

    @Test
    fun aLegacyReplyLinksUnprovenAndIsSentNoProof() {
        val out = ByteArrayOutputStream()

        val dialed =
            LinkHandshake.dialPlain(
                StreamLink(record(LinkFraming.Type.HELLO, legacyHello(bob.nodeId)), out),
                alice.prover,
                bob.nodeId,
            )

        assertTrue(dialed is LinkHandshake.DialOutcome.Linked && !dialed.proven && !dialed.sealed)
        assertEquals(listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
    }

    @Test
    fun theDialersHelloCarriesTheBitsAndAFreshNonce() {
        val out = ByteArrayOutputStream()
        LinkHandshake.dialPlain(StreamLink(ByteArray(0), out), alice.prover, bob.nodeId)
        LinkHandshake.dialPlain(StreamLink(ByteArray(0), out), alice.prover, bob.nodeId)

        val hellos = records(out.toByteArray()).map { Protocol.parse(it.payload.decodeToString())!! }
        assertTrue(hellos.all { it.offersProof && it.nonceHex != null && it.capabilities and Protocol.CAP_LINK_SEAL != 0L })
        assertTrue("a nonce per attempt", hellos[0].nonceHex != hellos[1].nonceHex)
    }

    @Test
    fun noReplyAndAnotherPeersReplyAreRejected() {
        assertEquals(
            LinkHandshake.DialOutcome.NoReply,
            LinkHandshake.dialPlain(StreamLink(ByteArray(0), ByteArrayOutputStream()), alice.prover, bob.nodeId),
        )
        val other =
            LinkHandshake.dialPlain(
                StreamLink(record(LinkFraming.Type.HELLO, helloR), ByteArrayOutputStream()),
                alice.prover,
                NodeId.derive("x"),
            )
        assertEquals(LinkHandshake.DialOutcome.WrongPeer(replyWire), other)
    }

    @Test
    fun aReplyWithTheBitAndNoValidNonceIsAFailedProofAndIsSentNoProof() {
        for (bad in listOf("", "|n00", "|N${"0".repeat(32)}", "|n${"0".repeat(31)}g", "|n${"0".repeat(33)}")) {
            val reply = unsealedAdvert(bob.nodeId, bad)
            val out = ByteArrayOutputStream()

            val dialed = LinkHandshake.dialPlain(StreamLink(record(LinkFraming.Type.HELLO, reply), out), alice.prover, bob.nodeId)

            assertEquals(bad, "no nonce", (dialed as LinkHandshake.DialOutcome.ProofFailed).why)
            assertEquals(bad, listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
        }
    }

    @Test
    fun aMissingOrMisplacedProofFailsAndOursIsNeverWritten() {
        val cases =
            mapOf(
                "eof" to (ByteArray(0) to LinkHandshake.WHY_EOF),
                "frame first" to (record(LinkFraming.Type.FRAME, byteArrayOf(1)) to "frame record before the proof"),
                "keepalive first" to (record(LinkFraming.Type.KEEPALIVE, ByteArray(0)) to "keepalive record before the proof"),
                "truncated" to
                    (record(LinkFraming.Type.PROOF, ByteArray(LinkProof.PAYLOAD_BYTES)).copyOf(20) to LinkHandshake.WHY_UNREADABLE),
            )
        for ((name, case) in cases) {
            val (after, why) = case
            val out = ByteArrayOutputStream()
            val dialed =
                LinkHandshake.dialPlain(StreamLink(record(LinkFraming.Type.HELLO, helloR) + after, out), alice.prover, bob.nodeId, nonceA)

            assertEquals(name, LinkHandshake.DialOutcome.ProofFailed(replyWire, why), dialed)
            assertEquals(name, listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
        }
    }

    @Test
    fun everyProofTheVectorsRejectFailsTheDialer() {
        // The keyed vectors' `proofRejects`, as the dialer meets them on Bob's side of the link: each must fail.
        val ffNonce = unsealedHello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { -1 })
        val rejects =
            mapOf(
                "dialer role" to (bob.prover.proof(LinkProof.Role.DIALER, helloD, helloR) to "signature"),
                "another responder nonce" to (bob.prover.proof(LinkProof.Role.RESPONDER, helloD, ffNonce) to "signature"),
                "another identity" to (alice.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR) to "id_mismatch"),
                "127 bytes" to
                    (bob.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR).copyOf(LinkProof.PAYLOAD_BYTES - 1) to "length"),
                "a v2 proof" to (sealedProofOverV1Hellos() to "signature"),
            )
        for ((name, case) in rejects) {
            val (proof, why) = case
            val dialed =
                LinkHandshake.dialPlain(
                    StreamLink(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, proof), ByteArrayOutputStream()),
                    alice.prover,
                    bob.nodeId,
                    nonceA,
                )
            assertEquals(name, LinkHandshake.DialOutcome.ProofFailed(replyWire, why), dialed)
        }
    }

    // A v2 signature over the same HELLOs: the label keeps it from verifying on a plain link.
    private fun sealedProofOverV1Hellos(): ByteArray {
        val binding = LinkSeal.Binding(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        return bob.prover.sealedProof(LinkProof.Role.RESPONDER, binding, helloD, helloR)
    }

    // --- the responder, against a canned dialer ---

    @Test
    fun aResponderRepliesWithItsProofInOneWriteAndVerifiesTheDialers() {
        val alicesProof = alice.prover.proof(LinkProof.Role.DIALER, oldD, ourR)
        val frame = record(LinkFraming.Type.FRAME, byteArrayOf(4, 5, 6))
        var writes = 0
        val out =
            object : ByteArrayOutputStream() {
                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    writes++
                    super.write(b, off, len)
                }
            }
        val (link, hello) = opened(record(LinkFraming.Type.HELLO, oldD) + record(LinkFraming.Type.PROOF, alicesProof) + frame, out)

        val answered = LinkHandshake.answerPlain(link, bob.prover, hello, nonceB)

        assertEquals(LinkHandshake.AnswerOutcome.Linked(proven = true, link = link), answered)
        assertEquals("reply and proof in one write", 1, writes)
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO, LinkFraming.Type.PROOF), written.map { it.type })
        // The v1 exchange, byte for byte.
        assertArrayEquals(ourR, written[0].payload)
        assertArrayEquals(bob.prover.proof(LinkProof.Role.RESPONDER, oldD, ourR), written[1].payload)
        assertNull(LinkProof.verify(written[1].payload, bob.nodeId, LinkProof.Role.RESPONDER, oldD, ourR))
        // A record behind the proof is the link's: the handshake took exactly the PROOF.
        assertEquals(LinkFraming.Message(LinkFraming.Type.FRAME, byteArrayOf(4, 5, 6)), LinkFraming.read(link.input))
    }

    @Test
    fun aLegacyDialerGetsAReplyWithTheBitAndNoProofAndLinksUnproven() {
        val out = ByteArrayOutputStream()
        val (link, hello) = opened(record(LinkFraming.Type.HELLO, legacyHello(alice.nodeId)), out)

        assertFalse(hello.offersProof)
        assertEquals(LinkHandshake.AnswerOutcome.Linked(proven = false, link = link), LinkHandshake.answerPlain(link, bob.prover, hello))
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO), written.map { it.type })
        val reply = Protocol.parse(written[0].payload.decodeToString())!!
        assertTrue(reply.offersProof && reply.nonceHex != null)
    }

    @Test
    fun aDialerHelloWithTheBitAndNoValidNonceIsRefusedWithNoReply() {
        val out = ByteArrayOutputStream()
        val (link, hello) = opened(record(LinkFraming.Type.HELLO, unsealedAdvert(alice.nodeId, "")), out)

        assertTrue(hello.malformedOffer)
        assertEquals(LinkHandshake.AnswerOutcome.ProofFailed("no nonce"), LinkHandshake.answerPlain(link, bob.prover, hello))
        assertEquals(0, out.size())
    }

    @Test
    fun aDialersMissingWrongOrMisplacedProofFailsTheResponder() {
        val ffNonce = Protocol.hello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { -1 }).encodeToByteArray()
        val after =
            mapOf(
                "eof" to (ByteArray(0) to LinkHandshake.WHY_EOF),
                "frame first" to (record(LinkFraming.Type.FRAME, byteArrayOf(1)) to "frame record before the proof"),
                "responder role" to
                    (record(LinkFraming.Type.PROOF, alice.prover.proof(LinkProof.Role.RESPONDER, oldD, ourR)) to "signature"),
                "another responder nonce" to
                    (record(LinkFraming.Type.PROOF, alice.prover.proof(LinkProof.Role.DIALER, oldD, ffNonce)) to "signature"),
                "another identity" to
                    (record(LinkFraming.Type.PROOF, bob.prover.proof(LinkProof.Role.DIALER, oldD, ourR)) to "id_mismatch"),
                "127 bytes" to
                    (record(LinkFraming.Type.PROOF, alice.prover.proof(LinkProof.Role.DIALER, oldD, ourR).copyOf(127)) to "length"),
            )
        for ((name, case) in after) {
            val (bytes, why) = case
            val (link, hello) = opened(record(LinkFraming.Type.HELLO, oldD) + bytes)

            val answered = LinkHandshake.answerPlain(link, bob.prover, hello, nonceB)

            assertEquals(name, LinkHandshake.AnswerOutcome.ProofFailed(why), answered)
        }
    }

    // --- a tag dial (ADR 2026-11.xp6d) ---

    private val tag = ByteArray(Protocol.HELLO_FORM_BYTES) { (0xCE + it).toByte() }
    private val aliceAirs = ByteArray(Protocol.HELLO_FORM_BYTES) { (0x30 + it).toByte() }
    private val tagHelloD = Protocol.hello(alice.nodeId, nonceA, told = aliceAirs, addressed = tag).encodeToByteArray()

    @Test
    fun aTagDialTakesTheProvenIdItReachedAndProvesItself() {
        val bobsProof = bob.prover.proof(LinkProof.Role.RESPONDER, tagHelloD, helloR)
        val out = ByteArrayOutputStream()
        val link = StreamLink(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof), out)

        val dialed = LinkHandshake.dialTagPlain(link, alice.prover, tag, told = aliceAirs, nonce = nonceA)

        // No id was expected: Bob is whoever the proof says answered.
        assertEquals(LinkHandshake.DialOutcome.Linked(replyWire, proven = true, link = link), dialed)
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO, LinkFraming.Type.PROOF), written.map { it.type })
        assertArrayEquals(tagHelloD, written[0].payload)
        val sent = Protocol.parse(written[0].payload.decodeToString())!!
        assertEquals("our HELLO names the tag we dialed", NodeId.fromBytes(tag), sent.addressed)
        assertEquals(NodeId.fromBytes(aliceAirs), sent.told)
        // Our proof signs the HELLO with both fields in it, as it crossed the link.
        assertNull(LinkProof.verify(written[1].payload, alice.nodeId, LinkProof.Role.DIALER, tagHelloD, helloR))
    }

    @Test
    fun aTagDialRefusesAReplyThatCannotProveItselfAndSendsNoProof() {
        val out = ByteArrayOutputStream()

        val legacy = legacyHello(bob.nodeId)
        val dialed = LinkHandshake.dialTagPlain(StreamLink(record(LinkFraming.Type.HELLO, legacy), out), alice.prover, tag)

        val expected = LinkHandshake.DialOutcome.ProofFailed(Protocol.parse(legacy.decodeToString())!!, LinkHandshake.WHY_UNPROVEN)
        assertEquals(expected, dialed)
        assertFalse(LinkHandshake.isTransient(LinkHandshake.WHY_UNPROVEN))
        assertEquals(listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
    }

    @Test
    fun aTagDialWhoseProofFailsOrIsMissingSendsNoProof() {
        val forged = alice.prover.proof(LinkProof.Role.RESPONDER, tagHelloD, helloR) // signed by the wrong identity
        // A forged proof is evidence (not transient); a missing one is the peer gone (transient): the caller's retry or
        // penalty rests on that split.
        val cases = listOf(record(LinkFraming.Type.PROOF, forged) to false, ByteArray(0) to true)
        for ((after, transient) in cases) {
            val out = ByteArrayOutputStream()
            val dialed =
                LinkHandshake.dialTagPlain(
                    StreamLink(record(LinkFraming.Type.HELLO, helloR) + after, out),
                    alice.prover,
                    tag,
                    told = aliceAirs,
                    nonce = nonceA,
                )
            assertTrue(dialed is LinkHandshake.DialOutcome.ProofFailed)
            val why = (dialed as LinkHandshake.DialOutcome.ProofFailed).why
            assertEquals(why, transient, LinkHandshake.isTransient(why))
            if (transient) assertEquals(LinkHandshake.WHY_EOF, why)
            assertEquals(listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
        }
    }

    @Test
    fun aClearDialCarriesTheFormsItIsGivenAndProvesOverThem() {
        // A blinding phone dialing a clear advert says what it airs (`t`) and what it dialed (`a`) in the HELLO its proof
        // then signs.
        val addressed = NodeId.toBytes(bob.nodeId)
        val helloTold = Protocol.hello(alice.nodeId, nonceA, told = aliceAirs, addressed = addressed).encodeToByteArray()
        val bobsProof = bob.prover.proof(LinkProof.Role.RESPONDER, helloTold, helloR)
        val out = ByteArrayOutputStream()

        val dialed =
            LinkHandshake.dialPlain(
                StreamLink(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof), out),
                alice.prover,
                bob.nodeId,
                nonceA,
                told = aliceAirs,
                addressed = addressed,
            )

        assertTrue(dialed is LinkHandshake.DialOutcome.Linked && dialed.proven)
        val written = records(out.toByteArray())
        assertArrayEquals(helloTold, written[0].payload)
        val sent = Protocol.parse(written[0].payload.decodeToString())!!
        assertEquals(NodeId.fromBytes(aliceAirs), sent.told)
        assertEquals(bob.nodeId, sent.addressed)
        assertNull(LinkProof.verify(written[1].payload, alice.nodeId, LinkProof.Role.DIALER, helloTold, helloR))
    }

    @Test
    fun aTagDialThatReachesItselfIsTheWrongPeer() {
        val ownReply = unsealedHello(alice.nodeId, nonceB)
        val dialed =
            LinkHandshake.dialTagPlain(
                StreamLink(record(LinkFraming.Type.HELLO, ownReply), ByteArrayOutputStream()),
                alice.prover,
                tag,
            )
        assertTrue(dialed is LinkHandshake.DialOutcome.WrongPeer)
    }

    @Test
    fun theRespondersToldFormRidesItsReplyAndItsProofCoversIt() {
        val bobAirs = ByteArray(Protocol.HELLO_FORM_BYTES) { (0x50 + it).toByte() }
        val out = ByteArrayOutputStream()
        val (link, hello) =
            opened(record(LinkFraming.Type.HELLO, oldD) + record(LinkFraming.Type.PROOF, ByteArray(0)), out)

        LinkHandshake.answerPlain(link, bob.prover, hello, nonceB, told = bobAirs)

        val written = records(out.toByteArray())
        val reply = Protocol.parse(written[0].payload.decodeToString())!!
        assertEquals("the reply tells what Bob airs", NodeId.fromBytes(bobAirs), reply.told)
        assertNull(LinkProof.verify(written[1].payload, bob.nodeId, LinkProof.Role.RESPONDER, oldD, written[0].payload))
    }

    // --- both ends at once ---

    @Test
    fun theRespondersToldFormRidesItsSealedReplyAndItsProofCoversIt() {
        // Two of this build's phones link sealed, so the `t` / `a` fields cross inside the seal.
        val bobAirs = ByteArray(Protocol.HELLO_FORM_BYTES) { (0x50 + it).toByte() }
        val toBob = PipedOutputStream()
        val bobIn = BufferedInputStream(PipedInputStream(toBob, PIPE_BYTES))
        val toAlice = PipedOutputStream()
        val aliceIn = BufferedInputStream(PipedInputStream(toAlice, PIPE_BYTES))
        val (dialed, bobsEnd) =
            bothEnds(
                Callable {
                    val opened = LinkHandshake.openSeal(StreamLink(aliceIn, toBob)) as LinkHandshake.SealOpened.Sealed
                    LinkHandshake.dialTagSealed(opened.link, alice.prover, bobAirs, told = aliceAirs)
                },
                Callable {
                    val opening = LinkHandshake.receiveOpening(StreamLink(bobIn, toAlice)) as LinkHandshake.Opening.Sealed
                    val hello = (LinkHandshake.receiveSealedHello(opening.link) as LinkHandshake.SealedHello.Hello).hello
                    hello.wire to LinkHandshake.answerSealed(opening.link, bob.prover, hello, told = bobAirs)
                },
                JOIN_S,
            ) { closeAll(toBob, toAlice, aliceIn, bobIn) }
        val (dialerHello, answered) = bobsEnd

        assertTrue(dialed is LinkHandshake.DialOutcome.Linked && dialed.proven && dialed.sealed)
        val reply = (dialed as LinkHandshake.DialOutcome.Linked).reply
        assertEquals(bob.nodeId, reply.nodeId)
        assertEquals("the reply tells what Bob airs", NodeId.fromBytes(bobAirs), reply.told)
        assertEquals("the dialer's HELLO names what it dialed", NodeId.fromBytes(bobAirs), dialerHello.addressed)
        assertEquals(NodeId.fromBytes(aliceAirs), dialerHello.told)
        assertTrue(answered is LinkHandshake.AnswerOutcome.Linked && answered.proven && answered.sealed)
    }

    @Test
    fun twoSealingBuildsNeverLinkPlainOverAPipe() {
        // §7 across a real exchange: Alice dials plain (a stripped advert flag), Bob refuses before replying and closes,
        // and Alice reads no reply. Neither writes a PROOF.
        val toBob = PipedOutputStream()
        val bobIn = BufferedInputStream(PipedInputStream(toBob, PIPE_BYTES))
        val toAlice = PipedOutputStream()
        val aliceIn = BufferedInputStream(PipedInputStream(toAlice, PIPE_BYTES))
        val bobLink = StreamLink(bobIn, toAlice)
        val (dialed, bobsEnd) =
            bothEnds(
                Callable { LinkHandshake.dialPlain(StreamLink(aliceIn, toBob), alice.prover, bob.nodeId) },
                Callable {
                    val hello = (LinkHandshake.receiveOpening(bobLink) as LinkHandshake.Opening.Plain).hello
                    val refused = LinkHandshake.refusesPlain(hello)
                    val answered = LinkHandshake.answerPlain(bobLink, bob.prover, hello)
                    bobLink.close()
                    refused to answered
                },
                JOIN_S,
            ) { closeAll(toBob, toAlice, aliceIn, bobIn) }
        val (refused, answered) = bobsEnd

        assertTrue(refused)
        assertEquals(LinkHandshake.AnswerOutcome.SealRefused, answered)
        assertEquals(LinkHandshake.DialOutcome.NoReply, dialed)
    }

    private fun closeAll(vararg streams: Closeable) {
        streams.forEach { runCatching { it.close() } }
    }

    // --- the HELLO read ---

    @Test
    fun theHelloReadKeepsTheBytesAsReceived() {
        for (read in listOf(
            checkNotNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloD)))),
            opened(record(LinkFraming.Type.HELLO, helloD)).second,
        )) {
            assertArrayEquals(helloD, read.payload)
            assertEquals(alice.nodeId, read.wire.nodeId)
            assertTrue(read.offersProof && !read.malformedOffer && read.offersSeal && read.sealable)
        }
    }

    @Test
    fun aFirstRecordThatIsNotAHelloIsRejected() {
        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.FRAME, helloD))))
        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.PROOF, ByteArray(LinkProof.PAYLOAD_BYTES)))))
        // The direct-link plane's read takes a key share for what it is there: not a HELLO.
        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(LinkFraming.encodeKeyShare(ByteArray(32)))))
        // The opening read tells the two forms apart and refuses everything else, nothing written.
        for (first in listOf(record(LinkFraming.Type.FRAME, helloD), record(LinkFraming.Type.PROOF, ByteArray(128)), sealedFrameOf(1))) {
            val out = ByteArrayOutputStream()
            assertEquals(LinkHandshake.Opening.None, LinkHandshake.receiveOpening(StreamLink(first, out)))
            assertEquals(0, out.size())
        }
    }

    @Test
    fun aHelloNamingANodeIdThatIsNotCanonicalIsRejected() {
        // #199: SQLite drops a leading U+FEFF from a UTF-16 bind, so a BOM-led twin would pass the transports'
        // duplicate-link checks and then read and write the real id's peers row.
        val hello = record(LinkFraming.Type.HELLO, Protocol.advertise("\uFEFF${alice.nodeId}").encodeToByteArray())

        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(hello)))
        assertEquals(LinkHandshake.Opening.None, LinkHandshake.receiveOpening(StreamLink(hello, ByteArrayOutputStream())))
        // And a reply naming one is no reply at all.
        val dialed = LinkHandshake.dialPlain(StreamLink(hello, ByteArrayOutputStream()), alice.prover, bob.nodeId)
        assertEquals(LinkHandshake.DialOutcome.NoReply, dialed)
    }

    @Test
    fun anEmptyTruncatedOrUnknownFirstRecordIsRejectedRatherThanThrown() {
        val hello = record(LinkFraming.Type.HELLO, helloD)
        val keyShare = LinkFraming.encodeKeyShare(ByteArray(32) { 9 })
        val unknown = listOf(byteArrayOf(0x7f, 0, 0, 0, 1, 0x41), byteArrayOf(12, 0, 0, 0, 0))
        for (bytes in listOf(ByteArray(0), hello.copyOf(hello.size - 1)) + unknown) {
            assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(bytes)))
            assertEquals(LinkHandshake.Opening.None, LinkHandshake.receiveOpening(StreamLink(bytes, ByteArrayOutputStream())))
        }
        // A key share cut short is the peer gone, not a failed seal.
        for (cut in listOf(1, 3, 5, keyShare.size - 1)) {
            assertEquals(
                LinkHandshake.Opening.None,
                LinkHandshake.receiveOpening(StreamLink(keyShare.copyOf(cut), ByteArrayOutputStream())),
            )
        }
    }

    @Test
    fun theLaneHelloCarriesTheBitAndANonceAndIsFlushed() {
        var flushed = false
        val out =
            object : ByteArrayOutputStream() {
                override fun flush() {
                    flushed = true
                }
            }

        LinkHandshake.writeLaneHello(out, alice.nodeId)

        val written = records(out.toByteArray())
        assertTrue(flushed)
        assertEquals(listOf(LinkFraming.Type.HELLO), written.map { it.type })
        val hello = assertNotNullAndParse(written[0].payload)
        assertTrue(hello.offersProof && hello.nonceHex != null)
    }

    private fun assertNotNullAndParse(payload: ByteArray): Protocol.PeerWire =
        Protocol.parse(payload.decodeToString()).also {
            assertNotNull(it)
        }!!

    private companion object {
        const val PIPE_BYTES = 64 * 1024
        const val JOIN_S = 20L
    }
}
