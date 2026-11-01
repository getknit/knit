package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [IdElision] and the `0x06` frame around it. The contract is the transcoder's — the receiver recovers the exact
 * canonical bytes — with one difference: a prefixed id stands for whatever the receiver's directory matches, so the
 * answer is a list of candidates the signature chooses between, and a prefix nobody matches is a lost frame.
 */
class IdElisionTest {
    private val random = Random(SEED)

    private fun bytes(n: Int) = random.nextBytes(n)

    private val bundle = checkNotNull(PublicKeyBundle.fromRaw(bytes(32), bytes(32))).encoded
    private val alice = NodeId.fromPublicKeyBundle(bundle)
    private val bob = NodeId.fromBytes(bytes(NodeId.BYTES))

    private fun profile(id: String = FrameId.profileId(alice, SENT_AT)): ByteArray =
        WireCodec.encodeEnvelope(
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = id,
                senderId = alice,
                sentAt = SENT_AT,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(
                            name = "Alice",
                            status = "",
                            pubKey = bundle,
                            protoVersion = Protocol.VERSION,
                            capabilities = Protocol.LOCAL_CAPABILITIES,
                            version = SENT_AT,
                        ),
                    ),
            ),
        )

    private fun dm(
        from: String = alice,
        to: String = bob,
    ): ByteArray =
        WireCodec.encodeEnvelope(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.fromBytes(bytes(16)),
                senderId = from,
                sentAt = SENT_AT,
                recipientId = to,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = EncEnvelope.VERSION_DM_V3,
                                    nonce = ByteArray(0),
                                    ct = bytes(CT_BYTES),
                                    keys = emptyList(),
                                    r = RatchetHeader(se = 1, ek = bytes(32), pe = 0, n = 0),
                                ),
                        ),
                    ),
            ),
        )

    private fun knows(vararg ids: String): (ByteArray) -> List<ByteArray> {
        val all = ids.map { NodeId.toBytes(it) }
        return { prefix -> all.filter { it.copyOf(prefix.size).contentEquals(prefix) } }
    }

    private val nobody: (ByteArray) -> List<ByteArray> = { emptyList() }

    @Test
    fun theLabelsItAddressesAreSchemaOnes() {
        val schema = FrameTranscoder.schema()
        assertEquals(1, schema["RelayEnvelope.type"])
        assertEquals(2, schema["RelayEnvelope.id"])
        assertEquals(3, schema["RelayEnvelope.senderId"])
        assertEquals(4, schema["RelayEnvelope.sentAt"])
        assertEquals(5, schema["RelayEnvelope.recipientId"])
        assertEquals(7, schema["RelayEnvelope.payload"])
        assertEquals(4, schema["ProfileContent.pubKey"])
    }

    @Test
    fun aProfileDropsItsIdAndSenderAndRebuildsWithNoDirectory() {
        val signed = profile()
        val transcoded = checkNotNull(FrameTranscoder.transcode(signed))
        val elided = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = true))
        val saved = transcoded.size - elided.size
        assertTrue("the 50-byte id and the 16-byte sender each shrink to a null, saved $saved", saved >= DERIVED_SAVING)
        val back = IdElision.rebuild(elided, nobody)
        assertEquals(1, back.size)
        assertArrayEquals(signed, back.single())
    }

    @Test
    fun aProfileWhoseIdIsNotTheDerivedOneKeepsItsId() {
        val signed = profile(id = FrameId.new())
        val elided = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = true))
        assertArrayEquals(signed, IdElision.rebuild(elided, nobody).single())
        val transcoded = checkNotNull(FrameTranscoder.transcode(signed))
        assertEquals("only the sender is derived: 17 bytes down to a 1-byte null", transcoded.size - NodeId.BYTES, elided.size)
    }

    @Test
    fun aDmSendsBothIdsAsPrefixesThatTheRecipientExpands() {
        val signed = dm()
        val transcoded = checkNotNull(FrameTranscoder.transcode(signed))
        val elided = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = true))
        assertEquals(2 * (NodeId.BYTES - IdElision.PREFIX_BYTES), transcoded.size - elided.size)
        val back = IdElision.rebuild(elided, knows(alice, bob, NodeId.fromBytes(bytes(NodeId.BYTES))))
        assertEquals(1, back.size)
        assertArrayEquals(signed, back.single())
    }

    @Test
    fun aDmWithoutTheRecipientPrefixStillNeedsOnlyTheSender() {
        val signed = dm()
        val elided = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = false))
        assertArrayEquals(signed, IdElision.rebuild(elided, knows(alice)).single())
    }

    @Test
    fun aPrefixNobodyMatchesIsALostFrame() {
        val elided = checkNotNull(IdElision.elide(dm(), prefixSender = true, prefixRecipient = true))
        assertTrue(IdElision.rebuild(elided, knows(bob)).isEmpty())
        assertTrue(IdElision.rebuild(elided, nobody).isEmpty())
    }

    @Test
    fun aCollidingPrefixYieldsOneCandidatePerMatchAndTheRightOneAmongThem() {
        val twin = NodeId.toBytes(alice).copyOf().also { it[NodeId.BYTES - 1] = (it[NodeId.BYTES - 1] + 1).toByte() }
        val signed = dm()
        val elided = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = true))
        val back = IdElision.rebuild(elided, knows(alice, bob, NodeId.fromBytes(twin)))
        assertEquals(2, back.size)
        assertEquals(1, back.count { it.contentEquals(signed) })
    }

    @Test
    fun aRecipientPrefixNobodyMatchesIsALostFrameToo() {
        val elided = checkNotNull(IdElision.elide(dm(), prefixSender = true, prefixRecipient = true))
        assertTrue(IdElision.rebuild(elided, knows(alice)).isEmpty())
    }

    /** [size] ids sharing Alice's prefix, Alice among them — what a crowded directory answers for her prefix. */
    private fun crowd(size: Int): List<ByteArray> {
        val head = NodeId.toBytes(alice).copyOf(IdElision.PREFIX_BYTES)
        return List(size - 1) { head + bytes(NodeId.BYTES - IdElision.PREFIX_BYTES) } + NodeId.toBytes(alice)
    }

    @Test
    fun aCrowdAtTheCapYieldsEveryCandidateAndTheRightOneAmongThem() {
        val signed = dm()
        val elided = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = false))
        val back = IdElision.rebuild(elided) { crowd(IdElision.MAX_CANDIDATES) }
        assertEquals(IdElision.MAX_CANDIDATES, back.size)
        assertEquals(1, back.count { it.contentEquals(signed) })
    }

    @Test
    fun aCrowdPastTheCapIsARefusal() {
        val elided = checkNotNull(IdElision.elide(dm(), prefixSender = true, prefixRecipient = false))
        assertTrue(IdElision.rebuild(elided) { crowd(IdElision.MAX_CANDIDATES + 1) }.isEmpty())
    }

    @Test
    fun aProfileElidesItsDerivedIdsWhateverThePrefixFlagsSay() {
        val signed = profile()
        val flagged = checkNotNull(IdElision.elide(signed, prefixSender = true, prefixRecipient = true))
        val unflagged = checkNotNull(IdElision.elide(signed, prefixSender = false, prefixRecipient = false))
        assertArrayEquals(flagged, unflagged)
        assertArrayEquals(signed, IdElision.rebuild(unflagged, nobody).single())
    }

    @Test
    fun aFrameTheTranscoderRefusesIsRefused() {
        assertNull(IdElision.elide(byteArrayOf(0x01), prefixSender = true, prefixRecipient = true))
        assertTrue(IdElision.rebuild(byteArrayOf(0x01), nobody).isEmpty())
    }

    @Test
    fun theElidedFrameCarriesSigAndRoutingAndDecodesToTheEnvelope() {
        val signed = dm()
        val wire = WireEnvelope(ttl = 3, hops = 1, relay = false, sig = bytes(FastFrameCodec.SIG_BYTES), signed = signed)
        val frame = checkNotNull(FastFrameCodec.encodeElided(wire, prefixSender = true, prefixRecipient = true))
        assertEquals(FastFrameCodec.TAG_ELIDED, frame[0])
        assertNull("not a decodeCompact tag", FastFrameCodec.decodeCompact(frame))
        val back = FastFrameCodec.decodeElided(frame, knows(alice, bob)).single()
        assertArrayEquals(signed, back.signed)
        assertArrayEquals(wire.sig, back.sig)
        assertEquals(3, back.ttl)
        assertEquals(1, back.hops)
        assertEquals(false, back.relay)
        val transcoded = checkNotNull(FastFrameCodec.encodeTranscoded(wire))
        assertEquals(2 * (NodeId.BYTES - IdElision.PREFIX_BYTES), transcoded.size - frame.size)
    }

    /** A body whose map count reads negative is refused before `toInt()` sizes a list from it (see FrameTranscoderTest). */
    @Test
    fun aBodyWithAMapCountPastLongMaxIsRefusedWithoutAllocatingForIt() {
        IdElision.rebuild(MAP_COUNT_PAST_LONG_MAX, nobody) // loads the classes, so the measure is the call's alone
        val allocated = allocatedBy { assertTrue(IdElision.rebuild(MAP_COUNT_PAST_LONG_MAX, nobody).isEmpty()) }
        assertTrue("allocated $allocated bytes refusing an 11-byte body", allocated < ONE_MIB)
    }

    @Test
    fun theUnsignedFormElidesToo() {
        val wire = WireEnvelope(relay = false, sig = ByteArray(0), signed = dm())
        val frame = checkNotNull(FastFrameCodec.encodeElided(wire, prefixSender = true, prefixRecipient = true))
        val back = FastFrameCodec.decodeElided(frame, knows(alice, bob)).single()
        assertEquals(0, back.sig.size)
        assertArrayEquals(wire.signed, back.signed)
    }

    private companion object {
        const val ONE_MIB = 1L shl 20
        const val SEED = 0x6E1D
        const val SENT_AT = 1_760_000_000_123L
        const val CT_BYTES = 40

        /** The profile id's text (~50 B) and the sender's 17 B, each down to a 1-byte null. */
        const val DERIVED_SAVING = 60
    }
}
