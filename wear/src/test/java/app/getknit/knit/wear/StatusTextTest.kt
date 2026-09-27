package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearStatus
import app.getknit.knit.wearstatus.WearStatusCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {
    private val linked =
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
    fun `the watch decodes the phone's golden vector`() {
        // Byte for byte the vector in :app's WearStatusCodecTest — the proof both ends read one layout.
        val golden = "010305001bd2040000803bb16a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(linked, WearStatusCodec.decode(golden))
    }

    @Test
    fun `a snapshot older than one missed refresh is no data`() {
        val now = 10_000_000L
        assertEquals(linked, StatusText.fresh(Snapshot(linked, now - StatusText.STALE_MS), now))
        assertNull(StatusText.fresh(Snapshot(linked, now - StatusText.STALE_MS - 1), now))
        assertNull(StatusText.fresh(null, now))
    }

    @Test
    fun `nearby shows the count running and a dash off or paused`() {
        assertEquals(Face("5", "near", Glyph.Mesh), StatusText.nearby(linked))
        assertEquals(Face("–", "paused", Glyph.MeshOff), StatusText.nearby(linked.copy(state = MeshState.Paused)))
        assertEquals("–", StatusText.nearby(null).text)
    }

    @Test
    fun `transports letter the planes that are up`() {
        assertEquals("B·n", StatusText.transports(linked).text)
        val all = linked.copy(nan = Plane.Live, lora = Plane.Live, spool = Plane.Live)
        assertEquals("B·N·L·S", StatusText.transports(all).text)
        val none = linked.copy(ble = Plane.Down, nan = Plane.Absent)
        assertEquals("none", StatusText.transports(none).text)
        val off = none.copy(state = MeshState.Off)
        assertEquals("Off", StatusText.transports(off).text)
    }

    @Test
    fun `every short text fits seven characters`() {
        for (state in MeshState.entries) {
            val s = linked.copy(state = state, relayed = 9_999_999_999L)
            for (face in listOf(StatusText.nearby(s), StatusText.health(s), StatusText.transports(s), StatusText.relayed(s))) {
                assertTrue("${face.text} is too long", face.text.length <= 7)
            }
        }
    }

    @Test
    fun `relayed counts are compact`() {
        assertEquals("999", StatusText.compact(999))
        assertEquals("1.2k", StatusText.compact(1_234))
        assertEquals("12k", StatusText.compact(12_345))
        assertEquals("1.2M", StatusText.compact(1_234_567))
        assertEquals("12M", StatusText.compact(12_345_678))
    }
}
