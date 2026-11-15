package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.TextLimits
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.PublicPostKey
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [MeshcorePublicJudge] (ADR 2026-11.gsqu's Stage 6 amendment; the design passes' lora and security N1, N10): a slot-0 text
 * splits at its first `": "` only behind a name a companion can hold (1–31 B), is decoded leniently and cleaned, never read
 * as a Knit frame, and keyed on the sender's time and the raw bytes before any cleaning. Plus slot 0 as the handshake reads
 * it ([MeshcorePublicSlot]) and the Public key's constant.
 */
class MeshcorePublicJudgeTest {
    private fun judge(
        text: ByteArray,
        stamp: Long? = STAMP,
        live: Boolean = true,
    ) = MeshcorePublicJudge.judge(text, stamp, live)

    private fun post(text: ByteArray): MeshcorePublicPost {
        val verdict = judge(text)
        assertTrue("$verdict", verdict is MeshcorePublicJudge.Verdict.Post)
        return (verdict as MeshcorePublicJudge.Verdict.Post).post
    }

    private fun post(text: String): MeshcorePublicPost = post(text.encodeToByteArray())

    @Test
    fun theCompanionsFormSplitsAtTheFirstSeparatorBehindAName() {
        val p = post("Ana: the north gate at noon")
        assertEquals("Ana", p.name)
        assertEquals("the north gate at noon", p.body)
        assertEquals("NodePrefs.h:17: char node_name[32]", 31, MeshcorePublicJudge.NAME_MAX_BYTES)
        val longest = "N".repeat(31)
        assertEquals("a 31-byte name is a name", longest, post("$longest: hi").name)
    }

    @Test
    fun aSeparatorPastByte31OrNoneMeansNoNameAndTheWholeTextIsTheBody() {
        val tooLong = "N".repeat(32)
        val past = post("$tooLong: hi")
        assertNull(past.name)
        assertEquals("$tooLong: hi", past.body)
        // A multi-byte name is measured in bytes: eleven three-byte characters are 33 B.
        val wide = "€".repeat(11)
        assertNull(post("$wide: hi").name)
        val none = post("no separator here")
        assertNull(none.name)
        assertEquals("no separator here", none.body)
        // Nothing before the separator is no name either.
        val empty = post(": hi")
        assertNull(empty.name)
        assertEquals(": hi", empty.body)
        // A colon without the space is not the companion's form.
        assertNull(post("Ana:hi").name)
    }

    @Test
    fun aNameHoldingTheSeparatorSplitsEarly() {
        // A stock user named `Dr: Who` reads as `Dr`, the rest of the name in the body: all a reader can know.
        val p = post("Dr: Who: hello")
        assertEquals("Dr", p.name)
        assertEquals("Who: hello", p.body)
    }

    @Test
    fun theNameIsCleanedAndAnInvisibleOneIsNone() {
        val p = post("‮Bob\u0007‏: hi")
        assertEquals("bidi and control characters go", "Bob", p.name)
        assertNull("only invisible characters: no name", post("​: hi").name)
        val long = post("Ana Maria Example Longname: hi")
        assertTrue(long.name!!.length <= TextLimits.DISPLAY_NAME)
        // A name is a claim, never an identity: "You" and our own board's tag are names like any other (6b draws them).
        assertEquals("You", post("You: hi").name)
        assertEquals("Knit a0a1", post("Knit a0a1: hi").name)
    }

    @Test
    fun anEmptyBodyIsRefusedAndAMissingTextOrStampToo() {
        assertEquals(MeshcorePublicJudge.Verdict.Refused(MeshcorePublicJudge.Refusal.EMPTY_BODY), judge("Ana:    ".encodeToByteArray()))
        assertEquals(MeshcorePublicJudge.Verdict.Refused(MeshcorePublicJudge.Refusal.EMPTY_BODY), judge(ByteArray(0)))
        assertEquals(
            MeshcorePublicJudge.Verdict.Refused(MeshcorePublicJudge.Refusal.NO_TEXT),
            judge("Ana: hi".encodeToByteArray(), stamp = null),
        )
        assertEquals(
            MeshcorePublicJudge.Verdict.Refused(MeshcorePublicJudge.Refusal.NO_TEXT),
            MeshcorePublicJudge.judge(null, STAMP, live = true),
        )
    }

    @Test
    fun invalidUtf8DecodesLenientlyAndTheBodyIsClamped() {
        val p = post("Ana: ".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28, 0x41))
        assertEquals("�(A", p.body)
        val long = post("Ana: " + "x".repeat(TextLimits.MESSAGE + 50))
        assertEquals(TextLimits.MESSAGE, long.body.length)
    }

    /**
     * Security's correction to the brief: the frame always says plain (`MyMesh.cpp:574`) and the board passes flags under 4
     * (`BaseChatMesh.cpp:418`), so a signed-plain sender's 4-byte prefix arrives as text — part of the name here, its control
     * bytes cleaned away.
     */
    @Test
    fun aSignedPlainPrefixArrivesAsTextAndIsCleaned() {
        val p = post(byteArrayOf(1, 2, 3, 4) + "Bob: hi".encodeToByteArray())
        assertEquals("Bob", p.name)
        assertEquals("hi", p.body)
    }

    /** Security N10: a text whose bytes are a valid `0x06` Knit frame is words, never decoded as a frame. */
    @Test
    fun aValidElidedFrameAsTextIsAPlainPost() {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "f-1",
                senderId = NodeId.derive("bob"),
                sentAt = 1_790_000_000_000L,
                payload = WireCodec.encodePayload(ChatContent(body = "hi")),
            )
        val frame =
            requireNotNull(
                FastFrameCodec.encodeElided(
                    WireEnvelope(relay = true, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env)),
                    prefixSender = true,
                    prefixRecipient = true,
                ),
            )
        assertEquals(FastFrameCodec.TAG_ELIDED, frame[0])
        val p = post(frame)
        assertEquals("keyed on the bytes as they came", PublicPostKey(STAMP, frame), p.key)
        assertEquals(frame.decodeToString().trim().take(TextLimits.MESSAGE), p.body)
    }

    /** The row's id inputs: the sender's time and the raw bytes, before any cleaning — so a replay lands on the same row. */
    @Test
    fun theKeyIsTheStampAndTheRawBytesStableAcrossAReplay() {
        val raw = "‮Bob: hi  ".encodeToByteArray()
        val live = (judge(raw, live = true) as MeshcorePublicJudge.Verdict.Post).post
        val replayed = (judge(raw, live = false) as MeshcorePublicJudge.Verdict.Post).post
        assertTrue(live.live)
        assertFalse(replayed.live)
        assertEquals(live.key, replayed.key)
        assertEquals(live.key.hashCode(), replayed.key.hashCode())
        assertArrayEquals("not the cleaned text", raw, live.key.raw)
        assertEquals(STAMP, live.key.timestampSecs)
        assertNotEquals("a byte apart", live.key, post("‮Bob: hj  ").key)
        assertNotEquals("a second apart", live.key, (judge(raw, STAMP + 1) as MeshcorePublicJudge.Verdict.Post).post.key)
        // Nothing a stranger wrote reaches a log through a toString.
        assertFalse(live.toString().contains("Bob"))
        assertFalse(live.key.toString().contains("$STAMP"))
    }

    // --- slot 0 as the handshake reads it ---

    @Test
    fun thePublicKeyIsTheFirmwaresPublishedOne() {
        // MyMesh.cpp:114, `#define PUBLIC_GROUP_PSK "izOH6cXN6mrJ5e26oRXNcg=="`.
        assertArrayEquals(Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg=="), MeshcoreProto.PUBLIC_GROUP_PSK)
        assertArrayEquals("the fake boots it into slot 0 too", FakeMeshcoreAir.PUBLIC_PSK, MeshcoreProto.PUBLIC_GROUP_PSK)
        MeshcoreProto.PUBLIC_GROUP_PSK[0] = 0
        assertEquals("a copy each read", 0x8B.toByte(), MeshcoreProto.PUBLIC_GROUP_PSK[0])
    }

    @Test
    fun slotZeroIsWhateverItHolds() {
        val public = MeshcorePublicSlot.of("Public".encodeToByteArray(), MeshcoreProto.PUBLIC_GROUP_PSK)
        assertEquals(MeshcorePublicSlot("Public", keyIsPublic = true, empty = false, knit = false), public)
        val rekeyed = MeshcorePublicSlot.of("Public".encodeToByteArray(), ByteArray(16) { 7 })
        assertFalse("named Public, keyed privately: a private group", rekeyed.keyIsPublic)
        assertEquals("Public", rekeyed.name)
        assertTrue("cleared", MeshcorePublicSlot.of(ByteArray(0), ByteArray(16)).empty)
        assertTrue("unnamed: receivers skip it", MeshcorePublicSlot.of(ByteArray(0), ByteArray(16) { 7 }).empty)
        assertTrue("the null key: reaches nobody", MeshcorePublicSlot.of("x".encodeToByteArray(), ByteArray(16)).empty)
        assertTrue(MeshcorePublicSlot.of(KnitChannel.NAME.encodeToByteArray(), KnitChannel.PSK).knit)
        assertEquals("cleaned for one line", "Our Group", MeshcorePublicSlot.of("Our‮ Group".encodeToByteArray(), ByteArray(16) { 7 }).name)
    }

    private companion object {
        const val STAMP = 1_790_000_000L
    }
}
