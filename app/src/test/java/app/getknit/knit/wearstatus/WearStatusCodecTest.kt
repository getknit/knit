package app.getknit.knit.wearstatus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone → watch snapshot layout. [GOLDEN] and [GOLDEN_EXTENDED] are repeated byte for byte in `:wear`'s
 * decode test — the two modules compile one codec, and the vectors are what prove a watch build reads what a
 * phone build wrote, with and without the appended block.
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
        assertEquals(20, WearStatusCodec.encode(extended).size)
    }

    @Test
    fun `the extended vector appends carrying, far peers and the link nibbles`() {
        assertEquals(GOLDEN_EXTENDED, WearStatusCodec.encode(extended).toHex())
        assertEquals(extended, WearStatusCodec.decode(GOLDEN_EXTENDED.fromHex()))
        // An older watch reads the first 13 bytes and ignores the rest; an older phone's 13 decode with no extra.
        assertEquals(sample, WearStatusCodec.decode(GOLDEN_EXTENDED.fromHex()).let { it!!.copy(extra = null) })
        assertNull(WearStatusCodec.decode(GOLDEN.fromHex())!!.extra)
    }

    @Test
    fun `links stop at the first empty slot, drop zero masks and cap at eight`() {
        val many = extended.copy(extra = WearExtra(carrying = 0, far = 0, links = listOf(1, 0, 2) + List(9) { 3 }))
        val back = WearStatusCodec.decode(WearStatusCodec.encode(many))!!.extra!!
        assertEquals(listOf(1, 2) + List(6) { 3 }, back.links)
        val none = extended.copy(extra = WearExtra(carrying = 70_000, far = 300, links = emptyList()))
        val saturated = WearStatusCodec.decode(WearStatusCodec.encode(none))!!.extra!!
        assertEquals(WearExtra(carrying = 0xFFFF, far = 0xFF, links = emptyList()), saturated)
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

    private val extended =
        sample.copy(
            extra =
                WearExtra(
                    carrying = 12,
                    far = 2,
                    links =
                        listOf(
                            WearLink.BLE or WearLink.NAN,
                            WearLink.BLE,
                            WearLink.NAN,
                            WearLink.LORA,
                            WearLink.LORA or WearLink.SPOOL,
                        ),
                ),
        )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val GOLDEN = "010305001bd2040000803bb16a"
        const val GOLDEN_EXTENDED = "010305001bd2040000803bb16a0c000213420c00"
    }
}
