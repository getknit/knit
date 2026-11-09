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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.SequenceInputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Callable

/**
 * The sealed form of the link handshake (#286, [LinkHandshake.openSeal], [LinkHandshake.receiveOpening], [SealedLinkSocket])
 * and §7's refusal of a plain link between two sealing builds. Every failed seal here is bytes that arrived and were wrong;
 * an EOF or a timeout, at any stage, is the no-reply or unreadable outcome it is on a plain link (contract revision 1).
 * The canned-stream tests drive one side against a peer's exact sealed bytes, built from fixed ephemeral scalars;
 * [bothEndsSealAndProveOverAPipe] runs both sides at once. The plain form's own tests are [LinkHandshakeTest]'s.
 */
class SealedLinkHandshakeTest {
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

    // The plain HELLOs at this build's caps (CAP_LINK_SEAL among them), as LinkHandshakeTest writes them.
    private val helloD = Protocol.hello(alice.nodeId, nonceA).encodeToByteArray()
    private val helloR = Protocol.hello(bob.nodeId, nonceB).encodeToByteArray()

    private val tag = ByteArray(Protocol.HELLO_FORM_BYTES) { (0xCE + it).toByte() }
    private val aliceAirs = ByteArray(Protocol.HELLO_FORM_BYTES) { (0x30 + it).toByte() }
    private val tagHelloD = Protocol.hello(alice.nodeId, nonceA, told = aliceAirs, addressed = tag).encodeToByteArray()

    private fun record(
        type: LinkFraming.Type,
        payload: ByteArray,
    ) = LinkFraming.encode(type, payload)

    private fun legacyHello(nodeId: String) = "$nodeId|${Protocol.VERSION}|9".encodeToByteArray()

    private fun records(bytes: ByteArray): List<LinkFraming.Message> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { LinkFraming.read(input) }.toList()
    }

    private val scalarD = ByteArray(32) { (0x40 + it).toByte() }
    private val scalarR = ByteArray(32) { (0x60 + it).toByte() }

    private fun ephemeralD() = LinkSeal.Ephemeral.fixed(scalarD)

    private fun ephemeralR() = LinkSeal.Ephemeral.fixed(scalarR)

    private val eD = ephemeralD().pub
    private val eR = ephemeralR().pub
    private val binding = LinkSeal.Binding(eD, eR)

    private fun keysOf(role: LinkProof.Role): LinkSeal.Keys =
        when (role) {
            LinkProof.Role.DIALER -> LinkSeal.derive(role, ephemeralD(), eR)
            LinkProof.Role.RESPONDER -> LinkSeal.derive(role, ephemeralR(), eD)
        }

    /** What [role] writes on a sealed link: its key share, then each chunk sealed as one frame, in counter order. */
    private fun sealedStream(
        role: LinkProof.Role,
        vararg chunks: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(LinkFraming.encodeKeyShare(if (role == LinkProof.Role.DIALER) eD else eR))
        val socket = SealedLinkSocket(StreamLink(ByteArray(0), out), keysOf(role), role)
        for (chunk in chunks) {
            socket.output.write(chunk)
            socket.output.flush()
        }
        return out.toByteArray()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun helloWithCaps(
        nodeId: String,
        caps: Long,
        nonce: ByteArray?,
    ): ByteArray {
        val n = nonce?.let { "|n${hex(it)}" }.orEmpty()
        return "$nodeId|${Protocol.VERSION}|${caps.toString(16)}$n".encodeToByteArray()
    }

    // The two sealed HELLOs as the dialer and responder write them, at this build's caps.
    private val sealedHelloD = Protocol.hello(alice.nodeId, nonceA).encodeToByteArray()
    private val sealedHelloR = Protocol.hello(bob.nodeId, nonceB).encodeToByteArray()

    private fun bobsSealedReply(proof: ByteArray = bob.prover.sealedProof(LinkProof.Role.RESPONDER, binding, sealedHelloD, sealedHelloR)) =
        record(LinkFraming.Type.HELLO, sealedHelloR) + record(LinkFraming.Type.PROOF, proof)

    /** Alice's sealed dial against a canned responder whose bytes are [reply]. */
    private fun aliceDialsSealed(
        reply: ByteArray,
        out: OutputStream = ByteArrayOutputStream(),
    ): LinkHandshake.DialOutcome {
        val link = StreamLink(reply, out)
        val opened = LinkHandshake.openSeal(link, ephemeralD())
        val sealed = (opened as LinkHandshake.SealOpened.Sealed).link
        return LinkHandshake.dialSealed(sealed, alice.prover, bob.nodeId, nonceA)
    }

    /** Bob's sealed answer to a canned dialer whose bytes are [dialer]: the opening, the HELLO read and, when it holds, the answer. */
    private fun bobAnswersSealed(
        dialer: ByteArray,
        out: OutputStream = ByteArrayOutputStream(),
    ): Any {
        val opening = LinkHandshake.receiveOpening(StreamLink(dialer, out), ephemeralR())
        val sealed = (opening as LinkHandshake.Opening.Sealed).link
        val hello =
            when (val read = LinkHandshake.receiveSealedHello(sealed)) {
                is LinkHandshake.SealedHello.Hello -> read.hello
                else -> return read
            }
        return LinkHandshake.answerSealed(sealed, bob.prover, hello, nonceB)
    }

    private class Wire(
        private val sink: OutputStream,
    ) : OutputStream() {
        val seen = ByteArrayOutputStream()

        override fun write(b: Int) {
            seen.write(b)
            sink.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            seen.write(b, off, len)
            sink.write(b, off, len)
        }

        override fun flush() = sink.flush()
    }

    @Test
    fun bothEndsSealAndProveOverAPipe() {
        val toBob = PipedOutputStream()
        val bobIn = BufferedInputStream(PipedInputStream(toBob, PIPE_BYTES))
        val toAlice = PipedOutputStream()
        val aliceIn = BufferedInputStream(PipedInputStream(toAlice, PIPE_BYTES))
        val aliceWire = Wire(toBob)
        val bobWire = Wire(toAlice)
        val (alicesEnd, bobsEnd) =
            bothEnds(
                Callable {
                    val opened = LinkHandshake.openSeal(StreamLink(aliceIn, aliceWire)) as LinkHandshake.SealOpened.Sealed
                    val dialed = LinkHandshake.dialSealed(opened.link, alice.prover, bob.nodeId)
                    // The link carries records both ways once it is up.
                    LinkFraming.write(opened.link.output, LinkFraming.Type.FRAME, byteArrayOf(1, 2, 3))
                    opened.link.output.flush()
                    // The link to register is the sealed socket, never the one below it.
                    check((dialed as LinkHandshake.DialOutcome.Linked).link === opened.link)
                    dialed to LinkFraming.read(opened.link.input)
                },
                Callable {
                    val opening = LinkHandshake.receiveOpening(StreamLink(bobIn, bobWire)) as LinkHandshake.Opening.Sealed
                    val hello = (LinkHandshake.receiveSealedHello(opening.link) as LinkHandshake.SealedHello.Hello).hello
                    val answered = LinkHandshake.answerSealed(opening.link, bob.prover, hello)
                    check((answered as LinkHandshake.AnswerOutcome.Linked).link === opening.link)
                    LinkFraming.write(opening.link.output, LinkFraming.Type.FRAME, byteArrayOf(4, 5))
                    opening.link.output.flush()
                    Triple(hello.wire.nodeId, answered, LinkFraming.read(opening.link.input))
                },
                JOIN_S,
            ) { closePipes(toBob, toAlice, aliceIn, bobIn) }
        val (dialed, aliceGot) = alicesEnd
        val (claimed, answered, bobGot) = bobsEnd

        // Both outcomes are Linked, each on its own sealed socket (checked inside the tasks).
        assertTrue(dialed.proven && dialed.sealed)
        assertEquals(bob.nodeId, dialed.reply.nodeId)
        assertEquals(alice.nodeId, claimed)
        assertTrue(answered.proven && answered.sealed)
        assertEquals(LinkFraming.Message(LinkFraming.Type.FRAME, byteArrayOf(4, 5)), aliceGot)
        assertEquals(LinkFraming.Message(LinkFraming.Type.FRAME, byteArrayOf(1, 2, 3)), bobGot)
        // A listener sees a 37-byte key share each way, then only sealed frames, and neither node id anywhere.
        for (side in listOf(aliceWire.seen.toByteArray(), bobWire.seen.toByteArray())) {
            val records = records(side)
            assertEquals(LinkFraming.Type.KEY_SHARE, records.first().type)
            assertTrue(records.drop(1).all { it.type == LinkFraming.Type.SEALED })
            val text = String(side, Charsets.ISO_8859_1)
            assertFalse(text.contains(alice.nodeId) || text.contains(bob.nodeId))
        }
    }

    // The peer's stream ends cleanly after a whole frame that holds only part of an inner record: the inner read meets an
    // EOF, the socket ended below it, so it is the peer gone (contract revision 1), never a failed seal.

    @Test
    fun anEofAtAFrameBoundaryMidReplyIsNoReplyNeverASealFailure() {
        val halfReply = sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, sealedHelloR).copyOf(10))
        assertEquals(LinkHandshake.DialOutcome.NoReply, aliceDialsSealed(halfReply))
    }

    @Test
    fun anEofAtAFrameBoundaryMidDialerHelloIsNoneNeverASealFailure() {
        val halfHello = sealedStream(LinkProof.Role.DIALER, record(LinkFraming.Type.HELLO, sealedHelloD).copyOf(10))
        assertEquals(LinkHandshake.SealedHello.None, bobAnswersSealed(halfHello))
    }

    @Test
    fun anEofAtAFrameBoundaryMidProofIsUnreadableNeverASealFailure() {
        val proof = record(LinkFraming.Type.PROOF, bob.prover.sealedProof(LinkProof.Role.RESPONDER, binding, sealedHelloD, sealedHelloR))
        val halfProof = sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, sealedHelloR), proof.copyOf(20))
        val dialed = aliceDialsSealed(halfProof)
        assertEquals(LinkHandshake.WHY_UNREADABLE, (dialed as LinkHandshake.DialOutcome.ProofFailed).why)
    }

    @Test
    fun aTagDialSealsAndProves() {
        val bobsProof = bob.prover.sealedProof(LinkProof.Role.RESPONDER, binding, tagHelloD, sealedHelloR)
        val reply = sealedStream(LinkProof.Role.RESPONDER, bobsSealedReply(bobsProof))
        val out = ByteArrayOutputStream()
        val opened = LinkHandshake.openSeal(StreamLink(reply, out), ephemeralD()) as LinkHandshake.SealOpened.Sealed

        val dialed = LinkHandshake.dialTagSealed(opened.link, alice.prover, tag, told = aliceAirs, nonce = nonceA)

        assertEquals(
            LinkHandshake.DialOutcome.Linked(Protocol.parse(sealedHelloR.decodeToString())!!, proven = true, link = opened.link),
            dialed,
        )
        // What Alice wrote opens, as Bob reads it, to her tagged HELLO and her v2 proof.
        val inner = bobReads(out.toByteArray())
        assertArrayEquals(tagHelloD, inner[0].payload)
        assertNull(LinkProof.verifySealed(inner[1].payload, alice.nodeId, LinkProof.Role.DIALER, binding, tagHelloD, sealedHelloR))
    }

    /** The inner records Bob opens from what Alice wrote, behind her key share. */
    private fun bobReads(aliceWrote: ByteArray): List<LinkFraming.Message> {
        val input = ByteArrayInputStream(aliceWrote)
        assertEquals(LinkFraming.Type.KEY_SHARE, LinkFraming.read(input)?.type)
        val socket =
            SealedLinkSocket(StreamLink(input, ByteArrayOutputStream()), keysOf(LinkProof.Role.RESPONDER), LinkProof.Role.RESPONDER)
        return generateSequence { LinkFraming.read(socket.input) }.toList()
    }

    @Test
    fun theResponderReadsEitherFirstRecord() {
        val plain = LinkHandshake.receiveOpening(StreamLink(record(LinkFraming.Type.HELLO, helloD), ByteArrayOutputStream()))
        assertArrayEquals(helloD, (plain as LinkHandshake.Opening.Plain).hello.payload)

        val out = ByteArrayOutputStream()
        val sealed = LinkHandshake.receiveOpening(StreamLink(LinkFraming.encodeKeyShare(eD), out), ephemeralR())
        assertTrue(sealed is LinkHandshake.Opening.Sealed)
        // Our key share went out at once, before any admission, and nothing else.
        assertArrayEquals(LinkFraming.encodeKeyShare(eR), out.toByteArray())
        assertArrayEquals(eD, (sealed as LinkHandshake.Opening.Sealed).link.binding.eD)
        assertArrayEquals(eR, sealed.link.binding.eR)
    }

    @Test
    fun aResponderThatClosesOnOurKeyShareIsNoReplyAndNoSealFailure() {
        // Contract revision 1: an EOF is never a failed seal, so the dialer sees today's no-reply refusal.
        val out = ByteArrayOutputStream()
        assertEquals(LinkHandshake.SealOpened.NoReply, LinkHandshake.openSeal(StreamLink(ByteArray(0), out)))
        assertEquals("our key share, and nothing else", LinkFraming.Type.KEY_SHARE, records(out.toByteArray()).single().type)
        // So is a key share cut short.
        val cut = LinkFraming.encodeKeyShare(eR).copyOf(20)
        assertEquals(LinkHandshake.SealOpened.NoReply, LinkHandshake.openSeal(StreamLink(cut, ByteArrayOutputStream())))
    }

    @Test
    fun aRawTimeoutWhereTheKeyShareBelongsIsNoReply() {
        val link = StreamLink(timesOut(ByteArray(0)), ByteArrayOutputStream())
        assertEquals(LinkHandshake.SealOpened.NoReply, LinkHandshake.openSeal(link))
    }

    @Test
    fun aResponderThatAnswersOurKeyShareWithAnythingElseFailsTheSeal() {
        val cases =
            mapOf(
                "a plain hello" to record(LinkFraming.Type.HELLO, helloR),
                "a sealed frame" to sealedFrameOf(4),
                "an unknown tag" to byteArrayOf(12, 0, 0, 0, 0),
                "a 31-byte key share" to byteArrayOf(10, 0, 0, 0, 31) + ByteArray(31),
                "a 33-byte key share header" to byteArrayOf(10, 0, 0, 0, 33),
                "a low-order key share" to LinkFraming.encodeKeyShare(ByteArray(32)),
            )
        for ((name, reply) in cases) {
            val out = ByteArrayOutputStream()
            val opened = LinkHandshake.openSeal(StreamLink(reply, out))
            assertTrue(name, opened is LinkHandshake.SealOpened.Failed)
            assertEquals(name, 1, records(out.toByteArray()).size)
        }
    }

    @Test
    fun aBadKeyShareFailsTheResponderAndNothingIsWritten() {
        val cases =
            mapOf(
                "31 bytes" to byteArrayOf(10, 0, 0, 0, 31) + ByteArray(31),
                "a 33-byte header" to byteArrayOf(10, 0, 0, 0, 33),
                "low order" to LinkFraming.encodeKeyShare(ByteArray(32)),
            )
        for ((name, first) in cases) {
            val out = ByteArrayOutputStream()
            assertTrue(name, LinkHandshake.receiveOpening(StreamLink(first, out)) is LinkHandshake.Opening.SealFailed)
            assertEquals(name, 0, out.size())
        }
    }

    @Test
    fun aSealedReplyWithoutEitherBitOrTheNonceFailsTheSeal() {
        val seal = Protocol.CAP_LINK_SEAL
        val proof = Protocol.CAP_LINK_PROOF
        val replies =
            mapOf(
                "no seal bit" to helloWithCaps(bob.nodeId, Protocol.LOCAL_CAPABILITIES and seal.inv(), nonceB),
                "no proof bit" to helloWithCaps(bob.nodeId, Protocol.LOCAL_CAPABILITIES and proof.inv(), nonceB),
                "no nonce" to helloWithCaps(bob.nodeId, Protocol.LOCAL_CAPABILITIES, null),
                "a legacy hello" to legacyHello(bob.nodeId),
            )
        for ((name, hello) in replies) {
            val dialed = aliceDialsSealed(sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, hello)))
            assertTrue(name, dialed is LinkHandshake.DialOutcome.SealFailed)
        }
    }

    @Test
    fun aSealedDialerHelloWithoutEitherBitOrTheNonceFailsTheResponderWithNoReply() {
        val seal = Protocol.CAP_LINK_SEAL
        val proof = Protocol.CAP_LINK_PROOF
        val hellos =
            mapOf(
                "no seal bit" to helloWithCaps(alice.nodeId, Protocol.LOCAL_CAPABILITIES and seal.inv(), nonceA),
                "no proof bit" to helloWithCaps(alice.nodeId, Protocol.LOCAL_CAPABILITIES and proof.inv(), nonceA),
                "no nonce" to helloWithCaps(alice.nodeId, Protocol.LOCAL_CAPABILITIES, null),
            )
        for ((name, hello) in hellos) {
            val out = ByteArrayOutputStream()
            val result = bobAnswersSealed(sealedStream(LinkProof.Role.DIALER, record(LinkFraming.Type.HELLO, hello)), out)
            assertTrue(name, result is LinkHandshake.SealedHello.SealFailed)
            assertEquals("only our key share went out", LinkFraming.encodeKeyShare(eR).toList(), out.toByteArray().toList())
        }
    }

    @Test
    fun aFirstInnerRecordThatIsNotAHelloFailsTheSeal() {
        val firsts =
            mapOf(
                "a frame" to record(LinkFraming.Type.FRAME, byteArrayOf(1)),
                "a proof" to record(LinkFraming.Type.PROOF, ByteArray(128)),
                "an inner key share" to LinkFraming.encodeKeyShare(eD),
                "an inner sealed frame" to sealedFrameOf(2),
                "an inner unknown tag" to byteArrayOf(12, 0, 0, 0, 0),
            )
        for ((name, first) in firsts) {
            assertTrue(name, bobAnswersSealed(sealedStream(LinkProof.Role.DIALER, first)) is LinkHandshake.SealedHello.SealFailed)
            assertTrue(name, aliceDialsSealed(sealedStream(LinkProof.Role.RESPONDER, first)) is LinkHandshake.DialOutcome.SealFailed)
        }
    }

    @Test
    fun nothingWhereTheSealedHelloBelongsIsNoReplyNeverASealFailure() {
        // R refused after its key share (contract §4): an EOF, an EOF mid-frame, or a timeout.
        val keyShare = sealedStream(LinkProof.Role.RESPONDER)
        val halfFrame = sealedStream(LinkProof.Role.RESPONDER, bobsSealedReply()).copyOf(keyShare.size + 9)
        for (reply in listOf(keyShare, halfFrame)) {
            assertEquals(LinkHandshake.DialOutcome.NoReply, aliceDialsSealed(reply))
        }
        val link = StreamLink(timesOut(keyShare), ByteArrayOutputStream())
        val opened = LinkHandshake.openSeal(link, ephemeralD()) as LinkHandshake.SealOpened.Sealed
        assertEquals(LinkHandshake.DialOutcome.NoReply, LinkHandshake.dialSealed(opened.link, alice.prover, bob.nodeId, nonceA))
        // The responder's side of it: nothing where the dialer's sealed HELLO belongs.
        val sealed = LinkHandshake.receiveOpening(StreamLink(LinkFraming.encodeKeyShare(eD), ByteArrayOutputStream()), ephemeralR())
        assertEquals(LinkHandshake.SealedHello.None, LinkHandshake.receiveSealedHello((sealed as LinkHandshake.Opening.Sealed).link))
    }

    private fun closePipes(vararg streams: Closeable) {
        streams.forEach { runCatching { it.close() } }
    }

    /** [bytes], then a read timeout, as a Wi-Fi Aware `soTimeout` cuts a read. */
    private fun timesOut(bytes: ByteArray): InputStream =
        SequenceInputStream(
            ByteArrayInputStream(bytes),
            object : InputStream() {
                override fun read(): Int = throw SocketTimeoutException("read timed out")
            },
        )

    @Test
    fun aRawFaultOnTheProofReadIsUnreadableNotASealFailure() {
        val reply = sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, sealedHelloR), ByteArray(0))
        val withProof =
            sealedStream(
                LinkProof.Role.RESPONDER,
                record(LinkFraming.Type.HELLO, sealedHelloR),
                record(LinkFraming.Type.PROOF, bob.prover.sealedProof(LinkProof.Role.RESPONDER, binding, sealedHelloD, sealedHelloR)),
            )
        val helloEnds = reply.size
        // A timeout mid-frame on the PROOF read is the dialer's `dialerAlive` case on Wi-Fi Aware: unreadable, as today.
        val midFrame = StreamLink(timesOut(withProof.copyOf(helloEnds + 7)), ByteArrayOutputStream())
        val opened = LinkHandshake.openSeal(midFrame, ephemeralD()) as LinkHandshake.SealOpened.Sealed
        val dialed = LinkHandshake.dialSealed(opened.link, alice.prover, bob.nodeId, nonceA)
        assertEquals(
            LinkHandshake.DialOutcome.ProofFailed(Protocol.parse(sealedHelloR.decodeToString())!!, LinkHandshake.WHY_UNREADABLE),
            dialed,
        )
        // An EOF mid-frame is unreadable too; a clean EOF where the PROOF belongs is `eof`.
        val cut = aliceDialsSealed(withProof.copyOf(helloEnds + 7))
        assertEquals(LinkHandshake.WHY_UNREADABLE, (cut as LinkHandshake.DialOutcome.ProofFailed).why)
        val eof = aliceDialsSealed(reply)
        assertEquals(LinkHandshake.WHY_EOF, (eof as LinkHandshake.DialOutcome.ProofFailed).why)
    }

    @Test
    fun aTamperedSealedReplyOrProofFrameFailsTheSealNotTheProof() {
        val proof = bob.prover.sealedProof(LinkProof.Role.RESPONDER, binding, sealedHelloD, sealedHelloR)
        val reply =
            sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, sealedHelloR), record(LinkFraming.Type.PROOF, proof))
        val helloFrameAt = 37 + 5
        val proofFrameAt = sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, sealedHelloR)).size + 5
        for (at in listOf(helloFrameAt, proofFrameAt + 3)) {
            val tampered = reply.copyOf().also { it[at] = (it[at].toInt() xor 0x20).toByte() }
            assertTrue("byte $at", aliceDialsSealed(tampered) is LinkHandshake.DialOutcome.SealFailed)
        }
        // The responder's side: the dialer's PROOF frame tampered.
        val dialerProof = alice.prover.sealedProof(LinkProof.Role.DIALER, binding, sealedHelloD, sealedHelloR)
        val dialer =
            sealedStream(LinkProof.Role.DIALER, record(LinkFraming.Type.HELLO, sealedHelloD), record(LinkFraming.Type.PROOF, dialerProof))
        val at = sealedStream(LinkProof.Role.DIALER, record(LinkFraming.Type.HELLO, sealedHelloD)).size + 9
        val tampered = dialer.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
        assertTrue(bobAnswersSealed(tampered) is LinkHandshake.AnswerOutcome.SealFailed)
        val answered = bobAnswersSealed(dialer)
        assertTrue(answered is LinkHandshake.AnswerOutcome.Linked && answered.proven && answered.link is SealedLinkSocket)
    }

    @Test
    fun aV1ProofOrAProofOverASwappedKeyShareFailsAsAProofOnASealedLink() {
        val v1 = bob.prover.proof(LinkProof.Role.RESPONDER, sealedHelloD, sealedHelloR)
        val swapped =
            bob.prover.sealedProof(
                LinkProof.Role.RESPONDER,
                LinkSeal.Binding(eD, ByteArray(32) { -1 }),
                sealedHelloD,
                sealedHelloR,
            )
        for ((name, proof) in mapOf("v1" to v1, "swapped key share" to swapped)) {
            val dialed = aliceDialsSealed(sealedStream(LinkProof.Role.RESPONDER, bobsSealedReply(proof)))
            assertEquals(name, "signature", (dialed as LinkHandshake.DialOutcome.ProofFailed).why)
        }
        // And on the responder: the dialer's v1 proof.
        val dialerV1 = alice.prover.proof(LinkProof.Role.DIALER, sealedHelloD, sealedHelloR)
        val answered =
            bobAnswersSealed(
                sealedStream(LinkProof.Role.DIALER, record(LinkFraming.Type.HELLO, sealedHelloD), record(LinkFraming.Type.PROOF, dialerV1)),
            )
        assertEquals(LinkHandshake.AnswerOutcome.ProofFailed("signature"), answered)
    }

    @Test
    fun aSealedDialToAReusedAddressIsTheWrongPeer() {
        val carol = Id(70)
        val helloC = Protocol.hello(carol.nodeId, nonceB).encodeToByteArray()
        val dialed = aliceDialsSealed(sealedStream(LinkProof.Role.RESPONDER, record(LinkFraming.Type.HELLO, helloC)))
        assertTrue(dialed is LinkHandshake.DialOutcome.WrongPeer)
    }

    // --- §7: no plain link between two sealing builds ---

    @Test
    fun aPlainDialWhoseReplyCarriesTheSealBitIsRefusedAndWritesNoProof() {
        val bobsProof = bob.prover.proof(LinkProof.Role.RESPONDER, helloD, helloR)
        val out = ByteArrayOutputStream()

        val dialed =
            LinkHandshake.dialPlain(
                StreamLink(record(LinkFraming.Type.HELLO, helloR) + record(LinkFraming.Type.PROOF, bobsProof), out),
                alice.prover,
                bob.nodeId,
                nonceA,
            )

        assertEquals(LinkHandshake.DialOutcome.SealRefused(Protocol.parse(helloR.decodeToString())!!), dialed)
        assertEquals(listOf(LinkFraming.Type.HELLO), records(out.toByteArray()).map { it.type })
        // A reused address still reads as the wrong peer first.
        val other =
            LinkHandshake.dialPlain(
                StreamLink(record(LinkFraming.Type.HELLO, helloR), ByteArrayOutputStream()),
                alice.prover,
                NodeId.derive("x"),
            )
        assertTrue(other is LinkHandshake.DialOutcome.WrongPeer)
        // A tag dial likewise.
        val tagged =
            LinkHandshake.dialTagPlain(
                StreamLink(record(LinkFraming.Type.HELLO, helloR), ByteArrayOutputStream()),
                alice.prover,
                tag,
            )
        assertTrue(tagged is LinkHandshake.DialOutcome.SealRefused)
    }

    @Test
    fun aPlainDialerHelloWithTheSealBitIsRefusedByTheResponderWithNoReply() {
        val hello = checkNotNull(LinkHandshake.receiveHello(ByteArrayInputStream(record(LinkFraming.Type.HELLO, helloD))))
        assertTrue(LinkHandshake.refusesPlain(hello))
        val out = ByteArrayOutputStream()
        assertEquals(LinkHandshake.AnswerOutcome.SealRefused, LinkHandshake.answerPlain(StreamLink(ByteArray(0), out), bob.prover, hello))
        assertEquals(0, out.size())
    }

    private val v211Caps = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL.inv() and Protocol.CAP_BLIND_ADVERT.inv()

    @Test
    fun aV211PeerLinksPlainAndProvenBothWays() {
        // v2.11.0 proves and doesn't seal: its HELLO carries CAP_LINK_PROOF without CAP_LINK_SEAL.
        val oldR = helloWithCaps(bob.nodeId, v211Caps, nonceB)
        val bobsProof = bob.prover.proof(LinkProof.Role.RESPONDER, helloD, oldR)
        val out = ByteArrayOutputStream()
        val dialedLink = StreamLink(record(LinkFraming.Type.HELLO, oldR) + record(LinkFraming.Type.PROOF, bobsProof), out)
        val dialed = LinkHandshake.dialPlain(dialedLink, alice.prover, bob.nodeId, nonceA)
        // A plain link runs on the socket dialed.
        assertEquals(LinkHandshake.DialOutcome.Linked(Protocol.parse(oldR.decodeToString())!!, proven = true, link = dialedLink), dialed)
        assertFalse((dialed as LinkHandshake.DialOutcome.Linked).sealed)
        val written = records(out.toByteArray())
        assertNull(LinkProof.verify(written[1].payload, alice.nodeId, LinkProof.Role.DIALER, helloD, oldR))

        val oldD = helloWithCaps(alice.nodeId, v211Caps, nonceA)
        val alicesProof = alice.prover.proof(LinkProof.Role.DIALER, oldD, helloR)
        val link = StreamLink(record(LinkFraming.Type.HELLO, oldD) + record(LinkFraming.Type.PROOF, alicesProof), ByteArrayOutputStream())
        val opening = LinkHandshake.receiveOpening(link) as LinkHandshake.Opening.Plain
        assertFalse(LinkHandshake.refusesPlain(opening.hello))
        assertEquals(
            LinkHandshake.AnswerOutcome.Linked(proven = true, link = link),
            LinkHandshake.answerPlain(link, bob.prover, opening.hello, nonceB),
        )
    }

    @Test
    fun aV210PeerLinksPlainAndUnprovenBothWays() {
        val dialed =
            LinkHandshake.dialPlain(
                StreamLink(record(LinkFraming.Type.HELLO, legacyHello(bob.nodeId)), ByteArrayOutputStream()),
                alice.prover,
                bob.nodeId,
            )
        assertTrue(dialed is LinkHandshake.DialOutcome.Linked && !dialed.proven && !dialed.sealed)

        val link = StreamLink(record(LinkFraming.Type.HELLO, legacyHello(alice.nodeId)), ByteArrayOutputStream())
        val opening = LinkHandshake.receiveOpening(link) as LinkHandshake.Opening.Plain
        assertFalse(LinkHandshake.refusesPlain(opening.hello))
        assertEquals(
            LinkHandshake.AnswerOutcome.Linked(proven = false, link = link),
            LinkHandshake.answerPlain(link, bob.prover, opening.hello),
        )
    }

    @Test
    fun thePlainFunctionsRefuseASealedSocket() {
        val sealed =
            (
                LinkHandshake.openSeal(
                    StreamLink(LinkFraming.encodeKeyShare(eR), ByteArrayOutputStream()),
                    ephemeralD(),
                ) as LinkHandshake.SealOpened.Sealed
            ).link
        assertThrows(IllegalArgumentException::class.java) { LinkHandshake.dialPlain(sealed, alice.prover, bob.nodeId) }
        assertThrows(IllegalArgumentException::class.java) { LinkHandshake.dialTagPlain(sealed, alice.prover, tag) }
        // And a dialer's seal can't answer, nor a responder's dial.
        assertThrows(IllegalArgumentException::class.java) { LinkHandshake.receiveSealedHello(sealed) }
    }

    @Test
    fun eachAttemptDrawsAFreshKeyShare() {
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        LinkHandshake.openSeal(StreamLink(ByteArray(0), first))
        LinkHandshake.openSeal(StreamLink(ByteArray(0), second))
        assertFalse(first.toByteArray().contentEquals(second.toByteArray()))
    }

    private companion object {
        const val PIPE_BYTES = 64 * 1024
        const val JOIN_S = 20L
    }
}
