package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The link handshake both radios open a link with ([LinkHandshake], ADR 2026-11.264w): the two-way HELLO, and on a link
 * whose two HELLOs carry [Protocol.CAP_LINK_PROOF], a proof each way of the node id each HELLO claims. A legacy HELLO on
 * either end links unproven, a PROOF is never written toward a HELLO without the bit, and a proof that is missing,
 * malformed, another identity's or for the other role is a failure, never a guess. The canned-stream tests drive one
 * side against exact bytes; [bothEndsProveOverAPipe] runs both sides at once.
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

    // Alice dials Bob: the two HELLO payloads exactly as they cross the link.
    private val helloD = Protocol.hello(alice.nodeId, nonceA).encodeToByteArray()
    private val helloR = Protocol.hello(bob.nodeId, nonceB).encodeToByteArray()

    private fun record(
        type: LinkFraming.Type,
        payload: ByteArray,
    ) = LinkFraming.encode(type, payload)

    private fun legacyHello(nodeId: String) = "$nodeId|${Protocol.VERSION}|9".encodeToByteArray()

    private fun records(bytes: ByteArray): List<LinkFraming.Message> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { LinkFraming.read(input) }.toList()
    }

    // --- the dialer, against a canned responder ---

    @Test
    fun aDialerVerifiesTheRespondersProofAndThenProvesItself() {
        val bobsProof = bob.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        val out = ByteArrayOutputStream()

        val dialed =
            LinkHandshake.dial(
                ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof)),
                out,
                alice.prover,
                bob.nodeId,
                nonceA,
            )

        assertEquals(LinkHandshake.Dialed.Linked(Protocol.parse(helloR.decodeToString())!!, proven = true), dialed)
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO, LinkFraming.Type.PROOF), written.map { it.type })
        assertArrayEquals(helloD, written[0].payload)
        assertNull(LinkProof.verify(written[1].payload, alice.nodeId, LinkProof.Role.DIALER, helloD, helloR))
    }

    @Test
    fun aLegacyReplyLinksUnprovenAndIsSentNoProof() {
        val out = ByteArrayOutputStream()

        val dialed =
            LinkHandshake.dial(ByteArrayInputStream(record(LinkFraming.Type.HELLO, legacyHello(bob.nodeId))), out, alice.prover, bob.nodeId)

        assertTrue(dialed is LinkHandshake.Dialed.Linked && !dialed.proven)
        assertEquals(listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
    }

    @Test
    fun theDialersHelloCarriesTheBitAndAFreshNonce() {
        val out = ByteArrayOutputStream()
        LinkHandshake.dial(ByteArrayInputStream(ByteArray(0)), out, alice.prover, bob.nodeId)
        LinkHandshake.dial(ByteArrayInputStream(ByteArray(0)), out, alice.prover, bob.nodeId)

        val hellos = records(out.toByteArray()).map { Protocol.parse(it.payload.decodeToString())!! }
        assertTrue(hellos.all { it.offersProof && it.nonceHex != null })
        assertTrue("a nonce per attempt", hellos[0].nonceHex != hellos[1].nonceHex)
    }

    @Test
    fun noReplyAndAnotherPeersReplyAreRejected() {
        assertEquals(
            LinkHandshake.Dialed.NoReply,
            LinkHandshake.dial(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), alice.prover, bob.nodeId),
        )
        val other =
            LinkHandshake.dial(
                ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR)),
                ByteArrayOutputStream(),
                alice.prover,
                NodeId.derive("x"),
            )
        assertTrue(other is LinkHandshake.Dialed.WrongPeer)
    }

    @Test
    fun aReplyWithTheBitAndNoValidNonceIsAFailedProofAndIsSentNoProof() {
        for (bad in listOf("", "|n00", "|N${"0".repeat(32)}", "|n${"0".repeat(31)}g", "|n${"0".repeat(33)}")) {
            val reply = "${Protocol.advertise(bob.nodeId)}$bad".encodeToByteArray()
            val out = ByteArrayOutputStream()

            val dialed = LinkHandshake.dial(ByteArrayInputStream(record(LinkFraming.Type.HELLO, reply)), out, alice.prover, bob.nodeId)

            assertTrue(bad, dialed is LinkHandshake.Dialed.ProofFailed)
            assertEquals(bad, listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
        }
    }

    @Test
    fun aMissingOrMisplacedProofFailsAndOursIsNeverWritten() {
        val cases =
            mapOf(
                "eof" to ByteArray(0),
                "frame first" to record(LinkFraming.Type.FRAME, byteArrayOf(1)),
                "keepalive first" to record(LinkFraming.Type.KEEPALIVE, ByteArray(0)),
                "truncated" to record(LinkFraming.Type.PROOF, ByteArray(LinkProof.PAYLOAD_BYTES)).copyOf(20),
            )
        for ((name, after) in cases) {
            val out = ByteArrayOutputStream()
            val dialed =
                LinkHandshake.dial(
                    ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR) + after),
                    out,
                    alice.prover,
                    bob.nodeId,
                    nonceA,
                )

            assertTrue(name, dialed is LinkHandshake.Dialed.ProofFailed)
            assertEquals(name, listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
        }
    }

    @Test
    fun everyProofTheVectorsRejectFailsTheDialer() {
        // The keyed vectors' `proofRejects`, as the dialer meets them on Bob's side of the link: each must fail.
        val ffNonce = Protocol.hello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { -1 }).encodeToByteArray()
        val rejects =
            mapOf(
                "dialer role" to bob.prover.proof(LinkProof.Role.DIALER, helloD, helloR),
                "another responder nonce" to bob.prover.proof(LinkProof.Role.RESPONDER, helloD, ffNonce),
                "another identity" to alice.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR),
                "127 bytes" to bob.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR).copyOf(LinkProof.PAYLOAD_BYTES - 1),
            )
        for ((name, proof) in rejects) {
            val dialed =
                LinkHandshake.dial(
                    ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, proof)),
                    ByteArrayOutputStream(),
                    alice.prover,
                    bob.nodeId,
                    nonceA,
                )
            assertTrue(name, dialed is LinkHandshake.Dialed.ProofFailed)
        }
    }

    // --- the responder, against a canned dialer ---

    @Test
    fun aResponderRepliesWithItsProofInOneWriteAndVerifiesTheDialers() {
        val alicesProof = alice.prover.proof(LinkProof.Role.DIALER, helloD, helloR)
        val frame = record(LinkFraming.Type.FRAME, byteArrayOf(4, 5, 6))
        val input = ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloD) + record(LinkFraming.Type.PROOF, alicesProof) + frame)
        val hello = checkNotNull(LinkHandshake.receiveHello(input))
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

        val answered = LinkHandshake.answer(input, out, bob.prover, hello, nonceB)

        assertEquals(LinkHandshake.Answered.Linked(proven = true), answered)
        assertEquals("reply and proof in one write", 1, writes)
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO, LinkFraming.Type.PROOF), written.map { it.type })
        assertArrayEquals(helloR, written[0].payload)
        assertNull(LinkProof.verify(written[1].payload, bob.nodeId, LinkProof.Role.RESPONDER, helloD, helloR))
        // A record behind the proof is the link's: the handshake took exactly the PROOF.
        assertEquals(LinkFraming.Message(LinkFraming.Type.FRAME, byteArrayOf(4, 5, 6)), LinkFraming.read(input))
    }

    @Test
    fun aLegacyDialerGetsAReplyWithTheBitAndNoProofAndLinksUnproven() {
        val input = ByteArrayInputStream(record(LinkFraming.Type.HELLO, legacyHello(alice.nodeId)))
        val hello = checkNotNull(LinkHandshake.receiveHello(input))
        val out = ByteArrayOutputStream()

        assertFalse(hello.offersProof)
        assertEquals(LinkHandshake.Answered.Linked(proven = false), LinkHandshake.answer(input, out, bob.prover, hello))
        val written = records(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.HELLO), written.map { it.type })
        val reply = Protocol.parse(written[0].payload.decodeToString())!!
        assertTrue(reply.offersProof && reply.nonceHex != null)
    }

    @Test
    fun aDialerHelloWithTheBitAndNoValidNonceIsRefusedWithNoReply() {
        val input = ByteArrayInputStream(record(LinkFraming.Type.HELLO, Protocol.advertise(alice.nodeId).encodeToByteArray()))
        val hello = checkNotNull(LinkHandshake.receiveHello(input))
        val out = ByteArrayOutputStream()

        assertTrue(hello.malformedOffer)
        assertTrue(LinkHandshake.answer(input, out, bob.prover, hello) is LinkHandshake.Answered.ProofFailed)
        assertEquals(0, out.size())
    }

    @Test
    fun aDialersMissingWrongOrMisplacedProofFailsTheResponder() {
        val ffNonce = Protocol.hello(bob.nodeId, ByteArray(Protocol.HELLO_NONCE_BYTES) { -1 }).encodeToByteArray()
        val after =
            mapOf(
                "eof" to ByteArray(0),
                "frame first" to record(LinkFraming.Type.FRAME, byteArrayOf(1)),
                "responder role" to record(LinkFraming.Type.PROOF, alice.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)),
                "another responder nonce" to record(LinkFraming.Type.PROOF, alice.prover.proof(LinkProof.Role.DIALER, helloD, ffNonce)),
                "another identity" to record(LinkFraming.Type.PROOF, bob.prover.proof(LinkProof.Role.DIALER, helloD, helloR)),
                "127 bytes" to record(LinkFraming.Type.PROOF, alice.prover.proof(LinkProof.Role.DIALER, helloD, helloR).copyOf(127)),
            )
        for ((name, bytes) in after) {
            val input = ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloD) + bytes)
            val hello = checkNotNull(LinkHandshake.receiveHello(input))

            val answered = LinkHandshake.answer(input, ByteArrayOutputStream(), bob.prover, hello, nonceB)

            assertTrue(name, answered is LinkHandshake.Answered.ProofFailed)
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

        val dialed =
            LinkHandshake.dialTag(
                ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof)),
                out,
                alice.prover,
                tag,
                told = aliceAirs,
                nonce = nonceA,
            )

        // No id was expected: Bob is whoever the proof says answered.
        assertEquals(LinkHandshake.Dialed.Linked(Protocol.parse(helloR.decodeToString())!!, proven = true), dialed)
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
        val dialed = LinkHandshake.dialTag(ByteArrayInputStream(record(LinkFraming.Type.HELLO, legacy)), out, alice.prover, tag)

        val expected = LinkHandshake.Dialed.ProofFailed(Protocol.parse(legacy.decodeToString())!!, LinkHandshake.WHY_UNPROVEN)
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
                LinkHandshake.dialTag(
                    ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR) + after),
                    out,
                    alice.prover,
                    tag,
                    told = aliceAirs,
                    nonce = nonceA,
                )
            assertTrue(dialed is LinkHandshake.Dialed.ProofFailed)
            val why = (dialed as LinkHandshake.Dialed.ProofFailed).why
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
            LinkHandshake.dial(
                ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof)),
                out,
                alice.prover,
                bob.nodeId,
                nonceA,
                told = aliceAirs,
                addressed = addressed,
            )

        assertTrue(dialed is LinkHandshake.Dialed.Linked && dialed.proven)
        val written = records(out.toByteArray())
        assertArrayEquals(helloTold, written[0].payload)
        val sent = Protocol.parse(written[0].payload.decodeToString())!!
        assertEquals(NodeId.fromBytes(aliceAirs), sent.told)
        assertEquals(bob.nodeId, sent.addressed)
        assertNull(LinkProof.verify(written[1].payload, alice.nodeId, LinkProof.Role.DIALER, helloTold, helloR))
    }

    @Test
    fun aTagDialThatReachesItselfIsTheWrongPeer() {
        val ownReply = Protocol.hello(alice.nodeId, nonceB).encodeToByteArray()
        val reply = ByteArrayInputStream(record(LinkFraming.Type.HELLO, ownReply))
        val dialed = LinkHandshake.dialTag(reply, ByteArrayOutputStream(), alice.prover, tag)
        assertTrue(dialed is LinkHandshake.Dialed.WrongPeer)
    }

    @Test
    fun theRespondersToldFormRidesItsReplyAndItsProofCoversIt() {
        val bobAirs = ByteArray(Protocol.HELLO_FORM_BYTES) { (0x50 + it).toByte() }
        val toBob = PipedOutputStream()
        val bobIn = BufferedInputStream(PipedInputStream(toBob, PIPE_BYTES))
        val toAlice = PipedOutputStream()
        val aliceIn = BufferedInputStream(PipedInputStream(toAlice, PIPE_BYTES))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val dialer = pool.submit(Callable { LinkHandshake.dialTag(aliceIn, toBob, alice.prover, bobAirs, told = aliceAirs) })
            val responder =
                pool.submit(
                    Callable {
                        val hello = checkNotNull(LinkHandshake.receiveHello(bobIn))
                        hello.wire to LinkHandshake.answer(bobIn, toAlice, bob.prover, hello, told = bobAirs)
                    },
                )

            val dialed = dialer.get(JOIN_S, TimeUnit.SECONDS)
            val (dialerHello, answered) = responder.get(JOIN_S, TimeUnit.SECONDS)

            assertTrue(dialed is LinkHandshake.Dialed.Linked && dialed.proven)
            val reply = (dialed as LinkHandshake.Dialed.Linked).reply
            assertEquals(bob.nodeId, reply.nodeId)
            assertEquals("the reply tells what Bob airs", NodeId.fromBytes(bobAirs), reply.told)
            assertEquals("the dialer's HELLO names what it dialed", NodeId.fromBytes(bobAirs), dialerHello.addressed)
            assertEquals(NodeId.fromBytes(aliceAirs), dialerHello.told)
            assertEquals(LinkHandshake.Answered.Linked(proven = true), answered)
        } finally {
            pool.shutdownNow()
        }
    }

    // --- both ends at once ---

    @Test
    fun bothEndsProveOverAPipe() {
        val toBob = PipedOutputStream()
        val bobIn = BufferedInputStream(PipedInputStream(toBob, PIPE_BYTES))
        val toAlice = PipedOutputStream()
        val aliceIn = BufferedInputStream(PipedInputStream(toAlice, PIPE_BYTES))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val dialer = pool.submit(Callable { LinkHandshake.dial(aliceIn, toBob, alice.prover, bob.nodeId) })
            val responder =
                pool.submit(
                    Callable {
                        val hello = checkNotNull(LinkHandshake.receiveHello(bobIn))
                        hello.wire.nodeId to LinkHandshake.answer(bobIn, toAlice, bob.prover, hello)
                    },
                )

            val dialed = dialer.get(JOIN_S, TimeUnit.SECONDS)
            val (claimed, answered) = responder.get(JOIN_S, TimeUnit.SECONDS)

            assertTrue(dialed is LinkHandshake.Dialed.Linked && dialed.proven && dialed.reply.nodeId == bob.nodeId)
            assertEquals(alice.nodeId, claimed)
            assertEquals(LinkHandshake.Answered.Linked(proven = true), answered)
        } finally {
            pool.shutdownNow()
        }
    }

    // --- the HELLO read ---

    @Test
    fun theHelloReadKeepsTheBytesAsReceived() {
        val hello = checkNotNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloD))))

        assertArrayEquals(helloD, hello.payload)
        assertEquals(alice.nodeId, hello.wire.nodeId)
        assertTrue(hello.offersProof && !hello.malformedOffer)
    }

    @Test
    fun aFirstRecordThatIsNotAHelloIsRejected() {
        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.FRAME, helloD))))
        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.PROOF, ByteArray(LinkProof.PAYLOAD_BYTES)))))
    }

    @Test
    fun aHelloNamingANodeIdThatIsNotCanonicalIsRejected() {
        // #199: SQLite drops a leading U+FEFF from a UTF-16 bind, so a BOM-led twin would pass the transports'
        // duplicate-link checks and then read and write the real id's peers row.
        val hello = record(LinkFraming.Type.HELLO, Protocol.advertise("\uFEFF${alice.nodeId}").encodeToByteArray())

        assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(hello)))
    }

    @Test
    fun anEmptyTruncatedOrUnknownFirstRecordIsRejectedRatherThanThrown() {
        val hello = record(LinkFraming.Type.HELLO, helloD)
        for (bytes in listOf(ByteArray(0), hello.copyOf(hello.size - 1), byteArrayOf(0x7f, 0, 0, 0, 1, 0x41))) {
            assertNull(LinkHandshake.receiveHello(ByteArrayInputStream(bytes)))
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
