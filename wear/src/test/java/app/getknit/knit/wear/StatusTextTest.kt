package app.getknit.knit.wear

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

/** The words every surface draws, from the English resources (pinned, so a translation never stands in for them). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class StatusTextTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

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
    fun `a reading is live for one missed refresh, then shown with its age`() {
        val now = 1_790_000_000_000L
        val edge = now - StatusText.STALE_MS
        assertEquals(Shown(linked), StatusText.shown(Snapshot(linked, edge), failedAtMs = 0L, nowMs = now))
        assertEquals(Shown(linked, agedSinceMs = edge - 1), StatusText.shown(Snapshot(linked, edge - 1), 0L, now))
        assertEquals(Shown(linked, agedSinceMs = now - HOURS_8), StatusText.shown(Snapshot(linked, now - HOURS_8), 0L, now))
        assertNull(StatusText.shown(null, failedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `a read that found no phone turns an aged reading into out of reach, never a live one`() {
        val now = 1_790_000_000_000L
        val old = Snapshot(linked, now - HOURS_8)
        assertNull(StatusText.shown(old, failedAtMs = now - 1_000L, nowMs = now))
        // A failure before the reading was taken says nothing about it.
        assertEquals(Shown(linked, now - HOURS_8), StatusText.shown(old, failedAtMs = now - HOURS_8 - 1, nowMs = now))
        // Inside the live window the reading stays live; the window's close is where it goes out of reach.
        val recent = Snapshot(linked, now - 60_000L)
        assertEquals(Shown(linked), StatusText.shown(recent, failedAtMs = now, nowMs = now))
        assertNull(StatusText.afterLive(recent, failedAtMs = now))
        assertEquals(Shown(linked, recent.fetchedAtMs), StatusText.afterLive(recent, failedAtMs = 0L))
    }

    @Test
    fun `nearby shows the count running and a dash off paused or unreachable`() {
        assertEquals(Face("5", "nearby", Glyph.Linked, Tone.Good), StatusText.nearby(res, linked))
        assertEquals(Face("–", "paused", Glyph.Paused), StatusText.nearby(res, linked.copy(state = MeshState.Paused)))
        assertEquals(Face("–", "off", Glyph.Off), StatusText.nearby(res, linked.copy(state = MeshState.Off)))
        assertEquals(Face("–", "nearby", Glyph.NoPhone), StatusText.nearby(res, null))
    }

    @Test
    fun `every short text fits seven characters`() = assertShortTextsFit(res)

    /**
     * The short complication's 7-character room, which every language's words must fit too. A language the watch
     * ships must also have loaded: falling back to English would pass for it unseen.
     */
    private fun assertShortTextsFit(res: Resources) {
        if (res.locale.language != "en") {
            val english = resourcesIn(Locale.US)
            val translated = SHORT_LABELS.count { res.getString(it) != english.getString(it) }
            assertTrue("${res.locale}: no short label is translated", translated > 0)
        }
        val words =
            listOf(
                res.getString(R.string.allow_short),
                Counts.durationShort(res, 59 * 60_000L),
            ) + SHORT_LABELS.map { res.getString(it) }
        for (word in words) assertTrue("${res.locale}: $word is too long", word.length <= 7)
        // An old reading's title, with its age as the watch-face library writes it in this language, at the widest
        // of each unit it counts in.
        val taken = 1_790_000_000_000L
        for (age in listOf(59 * 60_000L, 23 * 3_600_000L, 99 * 24 * 3_600_000L)) {
            val title = ago(taken, res.getString(R.string.aged_title_short)).getTextAt(res, Instant.ofEpochMilli(taken + age))
            assertTrue("${res.locale}: $title is too long", title.length <= 7)
        }
        val all =
            MeshState.entries.map {
                linked.copy(state = it, relayed = 9_999_999_999L, extra = WearExtra(carrying = 70_000, far = 0, links = emptyList()))
            } + null
        for (s in all) {
            val faces =
                listOf(
                    StatusText.nearby(res, s),
                    StatusText.health(res, s),
                    StatusText.carrying(res, s),
                    StatusText.relayedToday(res, Today(relayed = 9_999_999_999L, DayShare(), null, null, null)),
                    StatusText.relayedToday(res, Today(relayed = 9_999L, DayShare(), null, null, null)),
                    StatusText.day(res, Today(null, DayShare(linkedMs = 23 * 3_600_000L + 59 * 60_000L), null, null, null)),
                    StatusText.day(res, Today(null, DayShare(linkedMs = 9 * 3_600_000L + 59 * 60_000L), null, null, null)),
                )
            for (face in faces) {
                assertTrue("${res.locale}: ${face.text} is too long", face.text.length <= 7)
                assertTrue("${res.locale}: ${face.title} is too long", (face.title?.length ?: 0) <= 7)
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
        assertEquals("5 peers nearby", StatusLines.nearbyLine(res, linked).text)
        assertEquals("1 peer nearby", StatusLines.nearbyLine(res, linked.copy(nearby = 1)).text)
        assertEquals("No one nearby", StatusLines.nearbyLine(res, linked.copy(state = MeshState.Alone, nearby = 0)).text)
        assertEquals("Mesh paused", StatusLines.nearbyLine(res, linked.copy(state = MeshState.Paused)).text)
        assertEquals("Mesh off", StatusLines.nearbyLine(res, linked.copy(state = MeshState.Off)).text)
        assertEquals("Phone out of reach", StatusLines.nearbyLine(res, null).text)
        assertEquals("Linked · 5 nearby", StatusLines.healthLine(res, linked).text)
        assertEquals("Knit mesh", StatusLines.healthLine(res, linked).title)
        assertEquals("Weak · LoRa offline", StatusLines.healthLine(res, linked.copy(state = MeshState.Degraded)).text)
        val weakHealthy = linked.copy(state = MeshState.Degraded, nan = Plane.Live, lora = Plane.Absent)
        assertEquals("Weak · check radios", StatusLines.healthLine(res, weakHealthy).text)
        assertEquals("Phone out of reach", StatusLines.carryingLine(res, linked).text)
        val carrying = linked.copy(extra = WearExtra(carrying = 1_234, far = 0, links = emptyList()))
        assertEquals("1,234 held for others", StatusLines.carryingLine(res, carrying).text)
        val none = linked.copy(extra = WearExtra(carrying = 0, far = 0, links = emptyList()))
        assertEquals("Nothing held for others", StatusLines.carryingLine(res, none).text)
        val today =
            Today(relayed = 42, share = DayShare(linkedMs = 130 * 60_000L), busiest = null, linkedSinceMs = null, lastPeerAtMs = null)
        assertEquals("42 relayed today", StatusLines.relayedLine(res, linked, today).text)
        assertEquals("1,234 in all", StatusLines.relayedLine(res, linked, today).title)
        assertEquals("0 relayed today", StatusLines.relayedToday(res, 0))
        assertEquals("1 relayed today", StatusLines.relayedToday(res, 1))
        assertEquals("Relayed", StatusLines.relayedLine(res, null, null).title)
        assertEquals("No readings yet today", StatusLines.relayedLine(res, null, null).text)
        assertEquals("Linked 2\u00A0h 10\u00A0min today", StatusLines.dayLine(res, today).text)
        assertEquals(Face("+42", "today", Glyph.Relayed, Tone.Calm), StatusText.relayedToday(res, today))
        assertEquals("2h10", StatusText.day(res, today).text)
        assertEquals("Linked for 45\u00A0min", StatusLines.streak(res, today.copy(linkedSinceMs = 0L), 45 * 60_000L))
        assertEquals("Last peer 3\u00A0min ago", StatusLines.streak(res, today.copy(lastPeerAtMs = 0L), 200_000L))
        assertEquals(Face("Radios", "off", Glyph.NoRadio, Tone.Bad), StatusText.health(res, linked.copy(state = MeshState.NoRadio)))
        assertEquals(Face("Linked", "mesh", Glyph.Linked, Tone.Good), StatusText.health(res, linked))
    }

    @Test
    fun `radios are named only when one the phone has is weak or down, worst first`() {
        // BLE up, NAN degraded, LoRa down, relay never set up.
        assertEquals(listOf("LoRa board disconnected", "Wi-Fi Aware is limited"), StatusLines.problems(res, linked).map { it.text })
        assertEquals(listOf("LoRa", "Wi-Fi Aware"), StatusLines.problems(res, linked).map { it.title })
        assertEquals(listOf(Tone.Bad, Tone.Warn), StatusLines.problems(res, linked).map { it.tone })
        assertEquals("LoRa offline", StatusLines.problemShort(res, linked))
        val healthy = linked.copy(nan = Plane.Live, lora = Plane.Absent)
        assertEquals(emptyList<Line>(), StatusLines.problems(res, healthy))
        assertNull(StatusLines.problemShort(res, healthy))
        assertEquals(emptyList<Line>(), StatusLines.problems(res, linked.copy(state = MeshState.Paused)))
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
        assertEquals("5 nearby · 2 far", PeerLinks.summary(res, withFar))
        assertEquals("5 nearby", PeerLinks.summary(res, linked))
        assertEquals("2 far", PeerLinks.summary(res, withFar.copy(nearby = 0)))
        assertEquals("No one in range", PeerLinks.summary(res, linked.copy(state = MeshState.Alone, nearby = 0)))
        assertNull(PeerLinks.summary(res, linked.copy(state = MeshState.Off)))
        assertEquals("Relay", res.getString(LinkStyle.Relay.label))
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
        assertEquals("just now", Counts.ago(res, 3_000))
        assertEquals("42\u00A0s ago", Counts.ago(res, 42_000))
        assertEquals("3\u00A0min ago", Counts.ago(res, 200_000))
        assertEquals("2\u00A0h ago", Counts.ago(res, 7_300_000))
        assertEquals("just now", Counts.ago(res, -5_000))
        assertEquals("under a minute", Counts.duration(res, 30_000))
        assertEquals("45\u00A0min", Counts.duration(res, 45 * 60_000L))
        assertEquals("2\u00A0h", Counts.duration(res, 120 * 60_000L))
        assertEquals("2\u00A0h 10\u00A0min", Counts.duration(res, 130 * 60_000L))
        assertEquals("0m", Counts.durationShort(res, 0))
        assertEquals("2h05", Counts.durationShort(res, 125 * 60_000L))
        assertEquals("12h", Counts.durationShort(res, 12 * 60 * 60_000L + 30 * 60_000L))
    }

    @Test
    fun `relayed counts are compact`() {
        assertEquals("999", Counts.compact(999, Locale.US))
        assertEquals("1.2k", Counts.compact(1_234, Locale.US))
        assertEquals("12k", Counts.compact(12_345, Locale.US))
        assertEquals("1.2M", Counts.compact(1_234_567, Locale.US))
        assertEquals("12M", Counts.compact(12_345_678, Locale.US))
        assertEquals("1,2k", Counts.compact(1_234, Locale.GERMANY))
        assertEquals("3.312", Counts.grouped(3_312, Locale.GERMANY))
        assertEquals("3,312", Counts.grouped(3_312, res.locale))
    }

    @Test
    fun `no library carries a translation of the watch's own strings`() {
        // A library string of the same name brings its own translations, which win over ours wherever we ship
        // none: wear-compose's "state_off" once turned the mesh's "Off" into "Desactivado" on a Spanish watch.
        // Several languages Knit does not ship, since a library is not translated into all of them.
        for (tag in listOf("ja", "it", "nl", "ru", "pl", "tr", "ko", "zh-CN", "ar", "hi")) {
            val unshipped = resourcesIn(Locale.forLanguageTag(tag))
            for (field in R.string::class.java.fields) {
                val id = field.getInt(null)
                assertEquals("$tag: ${field.name}", res.getText(id), unshipped.getText(id))
            }
            for (field in R.plurals::class.java.fields) {
                val id = field.getInt(null)
                assertEquals("$tag: ${field.name}", res.getQuantityText(id, 2), unshipped.getQuantityText(id, 2))
            }
        }
    }

    private fun resourcesIn(locale: Locale): Resources =
        ApplicationProvider
            .getApplicationContext<Context>()
            .createConfigurationContext(Configuration(res.configuration).apply { setLocale(locale) })
            .resources

    @Test
    @Config(qualifiers = "de")
    fun `German short texts fit`() = assertShortTextsFit(res)

    @Test
    @Config(qualifiers = "es")
    fun `Spanish short texts fit`() = assertShortTextsFit(res)

    @Test
    @Config(qualifiers = "fr")
    fun `French short texts fit`() = assertShortTextsFit(res)

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `Brazilian Portuguese short texts fit`() = assertShortTextsFit(res)

    @Test
    @Config(qualifiers = "uk")
    fun `Ukrainian short texts fit`() = assertShortTextsFit(res)

    private companion object {
        const val HOURS_8 = 8 * 60 * 60_000L

        /** Every label a short complication or a tile card draws under a value. */
        val SHORT_LABELS =
            listOf(
                R.string.short_nearby,
                R.string.short_mesh,
                R.string.short_held,
                R.string.short_today,
                R.string.short_linked,
                R.string.short_paused,
                R.string.short_off,
                R.string.short_radios,
                R.string.short_radios_off,
                R.string.short_relayed,
            )
    }
}
