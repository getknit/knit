package app.getknit.knit.wearstatus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone → watch snapshot layout. [GOLDEN] is repeated byte for byte in `:wear`'s decode test — the two
 * modules compile one codec, and the vector is what proves a watch build reads what a phone build wrote.
 */
class WearStatusCodecTest {
    private val sample =
        WearStatus(
            state = MeshState.Linked,
            nearby = 5,
            ble = Plane.Live,
            nan = Plane.Degraded,
            lora = Plane.Down,
            spool = Plane.Absent,
            relayed = 1234,
            stampSec = 1_790_000_000,
        )

    @Test
    fun `encodes the golden vector`() {
        assertEquals(GOLDEN, WearStatusCodec.encode(sample).toHex())
    }

    @Test
    fun `decodes the golden vector`() {
        assertEquals(sample, WearStatusCodec.decode(GOLDEN.fromHex()))
    }

    @Test
    fun `fits one default-MTU read`() {
        assert(WearStatusCodec.encode(sample).size <= 20)
    }

    @Test
    fun `every state and plane round-trips`() {
        for (state in MeshState.entries) {
            for (plane in Plane.entries) {
                val s = sample.copy(state = state, ble = plane, nan = plane, lora = plane, spool = plane)
                assertEquals(s, WearStatusCodec.decode(WearStatusCodec.encode(s)))
            }
        }
    }

    @Test
    fun `counts saturate rather than wrap`() {
        val big = sample.copy(nearby = 70_000, relayed = 5_000_000_000L)
        val back = WearStatusCodec.decode(WearStatusCodec.encode(big))!!
        assertEquals(0xFFFF, back.nearby)
        assertEquals(0xFFFF_FFFFL, back.relayed)
        assertEquals(0, WearStatusCodec.decode(WearStatusCodec.encode(sample.copy(nearby = -3)))!!.nearby)
    }

    @Test
    fun `trailing bytes are ignored, so fields can be appended`() {
        val longer = GOLDEN.fromHex() + byteArrayOf(9, 9, 9)
        assertEquals(sample, WearStatusCodec.decode(longer))
    }

    @Test
    fun `an unknown version, an unknown state or a short read decodes to nothing`() {
        val bytes = GOLDEN.fromHex()
        assertNull(WearStatusCodec.decode(bytes.copyOf().also { it[0] = 2 }))
        assertNull(WearStatusCodec.decode(bytes.copyOf().also { it[1] = 99 }))
        assertNull(WearStatusCodec.decode(bytes.copyOf(WearStatusCodec.SIZE - 1)))
        assertArrayEquals(bytes, WearStatusCodec.encode(WearStatusCodec.decode(bytes)!!))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val GOLDEN = "010305001bd2040000803bb16a"
    }
}
