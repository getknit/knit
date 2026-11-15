package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.MessageDigest

/**
 * The row id of a post on a MeshCore board's Public channel ([FrameId.forMeshcorePost], ADR 2026-11.gsqu's Stage 6
 * amendment): derived from the sender's stamp and the raw text as drained, so an offline-queue replay and our own post
 * re-aired after the board's seen ring forgot it land on one row.
 */
class MeshcorePostIdTest {
    private val raw = "Knit 1a2b: hello mesh".encodeToByteArray()

    @Test
    fun `one post drained twice mints one id`() {
        assertEquals(FrameId.forMeshcorePost(1_763_200_000, raw), FrameId.forMeshcorePost(1_763_200_000, raw.copyOf()))
    }

    @Test
    fun `the stamp and every byte of the text are load-bearing`() {
        val base = FrameId.forMeshcorePost(1_763_200_000, raw)
        assertNotEquals(base, FrameId.forMeshcorePost(1_763_200_001, raw))
        assertNotEquals(base, FrameId.forMeshcorePost(1_763_200_000, "Knit 1a2b: hello mesh!".encodeToByteArray()))
        // The name is part of what receivers read, so the same words under another name are another post.
        assertNotEquals(base, FrameId.forMeshcorePost(1_763_200_000, "Knit 1a2c: hello mesh".encodeToByteArray()))
        // Raw bytes, not decoded text: an invalid sequence keys on itself, never on its U+FFFD replacement.
        val bad = byteArrayOf(0x41, 0xC3.toByte(), 0x41)
        val replaced = bad.decodeToString().encodeToByteArray()
        assertNotEquals(FrameId.forMeshcorePost(1, bad), FrameId.forMeshcorePost(1, replaced))
    }

    @Test
    fun `the stamp and the text cannot trade digits across the separator`() {
        // "12" + ":3…" and "1" + ":23…" would hash the same without the separator after the decimal stamp.
        assertNotEquals(FrameId.forMeshcorePost(12, "3".encodeToByteArray()), FrameId.forMeshcorePost(1, "23".encodeToByteArray()))
    }

    @Test
    fun `it is domain-separated from the Meshtastic room's id`() {
        // forMeshPost hashes "knit-meshpost-id-v1:<node>:<packetId>"; nothing here can mint the same input.
        assertNotEquals(FrameId.forMeshPost(1, 2), FrameId.forMeshcorePost(1, "2".encodeToByteArray()))
    }

    @Test
    fun `the domain string is frozen`() {
        // Pinned on purpose (ADR 2026-11.gsqu's Stage 6 amendment): a change gives every replayed post a second row.
        val expected =
            FrameId.fromBytes(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("knit-meshcore-public-id-v1:1763200000:".encodeToByteArray() + raw)
                    .copyOf(FrameId.ID_BYTES),
            )
        assertEquals(expected, FrameId.forMeshcorePost(1_763_200_000, raw))
    }

    @Test
    fun `the id is canonical, so it looks like every other row's`() {
        val id = FrameId.forMeshcorePost(1_763_200_000, raw)
        assertEquals(FrameId.LENGTH, id.length)
        val bytes = FrameId.toBytesOrNull(id)
        assertNotNull(bytes)
        assertEquals(id, FrameId.fromBytes(bytes!!))
    }
}
