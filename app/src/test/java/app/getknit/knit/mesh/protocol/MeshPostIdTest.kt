package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived id every gateway mints for the same bridged Meshtastic post. Determinism is the whole feature:
 * every board in range hears the same packet, and one id is what makes the duplicate copies collapse on the
 * dedup, storage and digest machinery that already exists instead of multiplying through it.
 */
class MeshPostIdTest {
    @Test
    fun `two gateways hearing one packet mint the same id`() {
        assertEquals(FrameId.forMeshPost(0x1234abcd, 9911), FrameId.forMeshPost(0x1234abcd, 9911))
    }

    @Test
    fun `both halves of the pair are load-bearing`() {
        val base = FrameId.forMeshPost(0x1234abcd, 9911)
        assertNotEquals(base, FrameId.forMeshPost(0x1234abce, 9911))
        assertNotEquals(base, FrameId.forMeshPost(0x1234abcd, 9912))
    }

    @Test
    fun `the id is canonical, so it round-trips and the transcoder can compact it`() {
        val id = FrameId.forMeshPost(0x1234abcd, 9911)
        assertEquals(FrameId.LENGTH, id.length)
        // toBytesOrNull is a round-trip check, not a pattern match, so passing it is what proves the id is in
        // exactly the form FrameId.new() mints — which is what lets the 0x05 transcoder carry it as raw bytes
        // rather than falling back to text.
        val bytes = FrameId.toBytesOrNull(id)
        assertNotNull(id, bytes)
        assertEquals(FrameId.ID_BYTES, bytes!!.size)
        assertEquals(id, FrameId.fromBytes(bytes))
    }

    @Test
    fun `a node number's whole 32-bit range mints distinct ids`() {
        val ids = listOf(0L, 1L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL).map { FrameId.forMeshPost(it, 1) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.length == FrameId.LENGTH })
    }
}
