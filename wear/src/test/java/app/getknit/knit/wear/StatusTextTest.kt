package app.getknit.knit.wear

import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearExtra
import app.getknit.knit.wearstatus.WearLink
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
    fun `nearby shows the count running and a dash off paused or unreachable`() {
        assertEquals(Face("5", "nearby", Glyph.Linked, Tone.Good), StatusText.nearby(linked))
        assertEquals(Face("–", "paused", Glyph.Paused), StatusText.nearby(linked.copy(state = MeshState.Paused)))
        assertEquals(Face("–", "nearby", Glyph.NoPhone), StatusText.nearby(null))
    }

    @Test
    fun `every short text fits seven characters`() {
        val all =
            MeshState.entries.map {
                linked.copy(state = it, relayed = 9_999_999_999L, extra = WearExtra(carrying = 70_000, far = 0, links = emptyList()))
            } + null
        for (s in all) {
            val faces =
                listOf(
                    StatusText.nearby(s),
                    StatusText.health(s),
                    StatusText.carrying(s),
                    StatusText.relayedToday(Today(relayed = 9_999_999_999L, DayShare(), null, null, null)),
                    StatusText.day(Today(null, DayShare(linkedMs = 23 * 3_600_000L + 59 * 60_000L), null, null, null)),
                )
            for (face in faces) {
                assertTrue("${face.text} is too long", face.text.length <= 7)
                assertTrue("${face.title} is too long", (face.title?.length ?: 0) <= 7)
            }
        }
    }

    @Test
    fun `each state has its own glyph and tone`() {
        assertEquals(MeshState.entries.size + 1, (MeshState.entries + null).map { StatusText.glyph(it) }.toSet().size)
        assertEquals(Tone.Good, StatusText.tone(MeshState.Linked))
        assertEquals(Tone.Calm, StatusText.tone(MeshState.Alone))
        assertEquals(Tone.Warn, StatusText.tone(MeshState.Degraded))
        assertEquals(Tone.Bad, StatusText.tone(MeshState.NoRadio))
        assertEquals(Tone.Muted, StatusText.tone(null))
    }

    @Test
    fun `long lines say what the numbers mean`() {
        assertEquals("5 peers nearby", StatusLines.nearbyLine(linked).text)
        assertEquals("1 peer nearby", StatusLines.nearbyLine(linked.copy(nearby = 1)).text)
        assertEquals("No one nearby", StatusLines.nearbyLine(linked.copy(state = MeshState.Alone, nearby = 0)).text)
        assertEquals("Mesh paused", StatusLines.nearbyLine(linked.copy(state = MeshState.Paused)).text)
        assertEquals("Phone out of reach", StatusLines.nearbyLine(null).text)
        assertEquals("Linked · 5 nearby", StatusLines.healthLine(linked).text)
        assertEquals("Weak · LoRa offline", StatusLines.healthLine(linked.copy(state = MeshState.Degraded)).text)
        assertEquals("Phone out of reach", StatusLines.carryingLine(linked).text)
        val carrying = linked.copy(extra = WearExtra(carrying = 1_234, far = 0, links = emptyList()))
        assertEquals("1,234 held for others", StatusLines.carryingLine(carrying).text)
        val today =
            Today(relayed = 42, share = DayShare(linkedMs = 130 * 60_000L), busiest = null, linkedSinceMs = null, lastPeerAtMs = null)
        assertEquals("42 relayed today", StatusLines.relayedLine(linked, today).text)
        assertEquals("1,234 in all", StatusLines.relayedLine(linked, today).title)
        assertEquals("Linked 2 h 10 min today", StatusLines.dayLine(today).text)
        assertEquals(Face("+42", "today", Glyph.Relayed, Tone.Calm), StatusText.relayedToday(today))
        assertEquals("2h10", StatusText.day(today).text)
    }

    @Test
    fun `radios are named only when one the phone has is weak or down, worst first`() {
        // BLE up, NAN degraded, LoRa down, relay never set up.
        assertEquals(listOf("LoRa board disconnected", "Wi-Fi Aware is limited"), StatusLines.problems(linked).map { it.text })
        assertEquals(listOf(Tone.Bad, Tone.Warn), StatusLines.problems(linked).map { it.tone })
        assertEquals("LoRa offline", StatusLines.problemShort(linked))
        val healthy = linked.copy(nan = Plane.Live, lora = Plane.Absent)
        assertEquals(emptyList<Line>(), StatusLines.problems(healthy))
        assertNull(StatusLines.problemShort(healthy))
        assertEquals(emptyList<Line>(), StatusLines.problems(linked.copy(state = MeshState.Paused)))
    }

    @Test
    fun `a peer's line takes the best plane that reaches it, and the legend lists only what is drawn`() {
        assertEquals(LinkStyle.Aware, PeerLinks.style(WearLink.BLE or WearLink.NAN))
        assertEquals(LinkStyle.Bluetooth, PeerLinks.style(WearLink.BLE))
        assertEquals(LinkStyle.LoRa, PeerLinks.style(WearLink.LORA or WearLink.SPOOL))
        assertEquals(LinkStyle.Relay, PeerLinks.style(WearLink.SPOOL))
        assertNull(PeerLinks.style(0))
        assertTrue(PeerLinks.near(WearLink.NAN))
        assertTrue(!PeerLinks.near(WearLink.LORA))
        assertEquals(listOf(LinkStyle.Bluetooth), PeerLinks.legend(listOf(WearLink.BLE, WearLink.BLE)))
        assertEquals(
            listOf(LinkStyle.Bluetooth, LinkStyle.Aware, LinkStyle.Relay),
            PeerLinks.legend(listOf(WearLink.SPOOL, WearLink.NAN, WearLink.BLE)),
        )
        val withFar = linked.copy(extra = WearExtra(carrying = 0, far = 2, links = emptyList()))
        assertEquals("5 nearby · 2 far", PeerLinks.summary(withFar))
        assertEquals("5 nearby", PeerLinks.summary(linked))
        assertEquals("No one in range", PeerLinks.summary(linked.copy(state = MeshState.Alone, nearby = 0)))
        assertNull(PeerLinks.summary(linked.copy(state = MeshState.Off)))
    }

    @Test
    fun `the watch decodes the phone's extended vector`() {
        // Byte for byte GOLDEN_EXTENDED in :app's WearStatusCodecTest.
        val golden = "010305001bd2040000803bb16a0c000213420c00".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val extra = WearStatusCodec.decode(golden)!!.extra
        assertEquals(
            WearExtra(
                carrying = 12,
                far = 2,
                links = listOf(WearLink.BLE or WearLink.NAN, WearLink.BLE, WearLink.NAN, WearLink.LORA, WearLink.LORA or WearLink.SPOOL),
            ),
            extra,
        )
    }

    @Test
    fun `ages read as the watch would say them`() {
        assertEquals("just now", Counts.ago(3_000))
        assertEquals("42 s ago", Counts.ago(42_000))
        assertEquals("3 min ago", Counts.ago(200_000))
        assertEquals("2 h ago", Counts.ago(7_300_000))
        assertEquals("just now", Counts.ago(-5_000))
        assertEquals("under a minute", Counts.duration(30_000))
        assertEquals("45 min", Counts.duration(45 * 60_000L))
        assertEquals("2 h", Counts.duration(120 * 60_000L))
        assertEquals("2 h 10 min", Counts.duration(130 * 60_000L))
        assertEquals("0m", Counts.durationShort(0))
        assertEquals("2h05", Counts.durationShort(125 * 60_000L))
        assertEquals("12h", Counts.durationShort(12 * 60 * 60_000L + 30 * 60_000L))
    }

    @Test
    fun `relayed counts are compact`() {
        assertEquals("999", Counts.compact(999))
        assertEquals("1.2k", Counts.compact(1_234))
        assertEquals("12k", Counts.compact(12_345))
        assertEquals("1.2M", Counts.compact(1_234_567))
        assertEquals("12M", Counts.compact(12_345_678))
    }
}
