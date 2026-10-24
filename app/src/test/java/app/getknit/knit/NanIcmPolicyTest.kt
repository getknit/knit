package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanIcmPolicy
import app.getknit.knit.mesh.wifiaware.NanIcmPolicy.Band
import app.getknit.knit.mesh.wifiaware.NanIcmPolicy.Reason
import app.getknit.knit.mesh.wifiaware.NanIcmPolicy.Sta
import app.getknit.knit.mesh.wifiaware.NanIcmPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanIcmPolicy] — what Wi-Fi Aware's configs may ask of Instant Communication Mode (GitHub #44). */
class NanIcmPolicyTest {
    private val base =
        NanIcmPolicy.Inputs(
            override = null,
            audioStreaming = false,
            lonely = false,
            postCureWindow = false,
            sta = Sta.Unknown,
            band5PrimaryUsable = null,
            fiveGhzRules = true,
        )

    private fun decide(inputs: NanIcmPolicy.Inputs) = NanIcmPolicy.decide(inputs)

    private fun on(
        band: Band,
        reason: Reason,
    ) = Verdict(allowed = true, band = band, reason = reason)

    private fun off(reason: Reason) = Verdict(allowed = false, band = null, reason = reason)

    @Test
    fun theOverrideWinsOverAudioAndLoneliness() {
        // A band trial needs a lonely, audio-playing phone to light ICM on the band it chose.
        val worst = base.copy(audioStreaming = true, lonely = true, sta = Sta.On(NanIcmPolicy.CH6_MHZ))
        assertEquals(on(Band.B5, Reason.OVERRIDE), decide(worst.copy(override = Band.B5)))
        assertEquals(on(Band.B24, Reason.OVERRIDE), decide(worst.copy(override = Band.B24)))
        // The override is the debug bridge's; it does not wait on the 5 GHz picks going live.
        assertEquals(on(Band.B5, Reason.OVERRIDE), decide(worst.copy(override = Band.B5, fiveGhzRules = false)))
    }

    @Test
    fun bluetoothAudioTurnsIcmOffEvenInThePostCureWindow() {
        assertEquals(off(Reason.AUDIO), decide(base.copy(audioStreaming = true)))
        assertEquals(off(Reason.AUDIO), decide(base.copy(audioStreaming = true, lonely = true, postCureWindow = true)))
        assertEquals(off(Reason.AUDIO), decide(base.copy(audioStreaming = true, sta = Sta.On(NanIcmPolicy.CH6_MHZ))))
    }

    @Test
    fun nobodyToCueTurnsIcmOffUnlessACureJustCycledTheSession() {
        assertEquals(off(Reason.LONELY), decide(base.copy(lonely = true)))
        assertEquals(off(Reason.LONELY), decide(base.copy(lonely = true, sta = Sta.On(NanIcmPolicy.CH6_MHZ))))
        // The post-cure window reads lonely as not lonely, and the band rules then apply as usual.
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(base.copy(lonely = true, postCureWindow = true)))
        assertEquals(
            on(Band.B5, Reason.NO_STA),
            decide(base.copy(lonely = true, postCureWindow = true, sta = Sta.None)),
        )
        // The window means nothing to a node with someone to cue: it was never off for loneliness.
        assertEquals(decide(base), decide(base.copy(postCureWindow = true)))
    }

    @Test
    fun aStaOnChannelSixAsksFor24OnEveryBuild() {
        val sta = base.copy(sta = Sta.On(NanIcmPolicy.CH6_MHZ))
        assertEquals(on(Band.B24, Reason.STA_CHANNEL), decide(sta))
        assertEquals(on(Band.B24, Reason.STA_CHANNEL), decide(sta.copy(fiveGhzRules = false)))
    }

    @Test
    fun aStaOnChannel149AsksFor5OnlyWhileThe5GhzPicksAreLive() {
        val sta = base.copy(sta = Sta.On(NanIcmPolicy.CH149_MHZ))
        assertEquals(on(Band.B5, Reason.STA_CHANNEL), decide(sta))
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(sta.copy(fiveGhzRules = false)))
    }

    @Test
    fun aStaOnChannel44AsksFor5OnlyWhen149IsKnownNotUsable() {
        // The framework grants 5 GHz ICM on 5745 when the chip lists it, else 5220: only then is 5 GHz the STA's channel.
        val sta = base.copy(sta = Sta.On(NanIcmPolicy.CH44_MHZ))
        assertEquals(on(Band.B5, Reason.STA_CHANNEL), decide(sta.copy(band5PrimaryUsable = false)))
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(sta.copy(band5PrimaryUsable = true)))
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(sta.copy(band5PrimaryUsable = null)))
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(sta.copy(band5PrimaryUsable = false, fiveGhzRules = false)))
    }

    @Test
    fun noStaAsksFor5ButAnUnknownStaNeverDoes() {
        assertEquals(on(Band.B5, Reason.NO_STA), decide(base.copy(sta = Sta.None)))
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(base.copy(sta = Sta.Unknown)))
        assertEquals(on(Band.B24, Reason.DEFAULT), decide(base.copy(sta = Sta.None, fiveGhzRules = false)))
    }

    @Test
    fun aStaOnAnyOtherChannelTakesTheDefault() {
        for (freq in listOf(2412, 2462, 5180, 5500, 5955)) {
            assertEquals("$freq MHz", on(Band.B24, Reason.DEFAULT), decide(base.copy(sta = Sta.On(freq))))
        }
    }

    @Test
    fun releaseNeverAsksFor5GhzWhateverTheInputs() {
        // fiveGhzRules is false in release and the override is the debug bridge's: across every other input, the band
        // asked is 2.4 GHz or nothing — today's behaviour, minus the audio and lonely rules.
        val stas = listOf(Sta.Unknown, Sta.None) + listOf(2437, 5745, 5220, 5180, 0).map { Sta.On(it) }
        val flags = listOf(false, true)
        val inputs =
            flags.flatMap { audio ->
                flags.flatMap { lonely ->
                    flags.flatMap { cure ->
                        stas.flatMap { sta ->
                            listOf(null, false, true).map { usable ->
                                NanIcmPolicy.Inputs(null, audio, lonely, cure, sta, usable, fiveGhzRules = false)
                            }
                        }
                    }
                }
            }
        assertEquals(2 * 2 * 2 * stas.size * 3, inputs.size)
        for (input in inputs) {
            val v = decide(input)
            assertNotEquals("$input", Band.B5, v.band)
            assertEquals("$input", v.allowed, v.band != null)
        }
    }

    @Test
    fun theStaIsUnknownUntilTheWatchHasSpoken() {
        assertEquals(Sta.Unknown, NanIcmPolicy.staOf(registered = false, settled = true, frequencies = emptyList()))
        assertEquals(Sta.Unknown, NanIcmPolicy.staOf(registered = false, settled = true, frequencies = listOf(2437)))
        assertEquals(Sta.Unknown, NanIcmPolicy.staOf(registered = true, settled = false, frequencies = emptyList()))
        assertEquals(Sta.None, NanIcmPolicy.staOf(registered = true, settled = true, frequencies = emptyList()))
        // A network up whose capabilities have not said its frequency yet is not "no STA".
        assertEquals(Sta.Unknown, NanIcmPolicy.staOf(registered = true, settled = true, frequencies = listOf(0)))
    }

    @Test
    fun withSeveralNetworksChannelSixDecidesFirstThen149Then44() {
        fun pick(vararg f: Int) = NanIcmPolicy.staOf(registered = true, settled = true, frequencies = f.toList())
        assertEquals(Sta.On(2437), pick(5745, 2437))
        assertEquals(Sta.On(5745), pick(5220, 5745))
        assertEquals(Sta.On(5220), pick(5180, 5220))
        assertEquals(Sta.On(5180), pick(0, 5180))
        assertEquals(Sta.On(2437), pick(0, 5180, 2437))
    }

    @Test
    fun theLitMeterMergesOverlappingWindows() {
        val m = NanIcmPolicy.LitMeter(30_000L)
        m.reset(1_000L)
        assertEquals(0L, m.litMs(50_000L))
        assertFalse(m.litAt(1_000L))
        m.onConfig(10_000L)
        assertTrue(m.litAt(39_999L))
        assertFalse(m.litAt(40_000L))
        assertEquals(15_000L, m.litMs(25_000L)) // mid-window: lit up to now
        m.onConfig(30_000L) // inside the window: extends it to 60 s, no double count
        assertEquals(50_000L, m.litMs(100_000L))
        m.onConfig(200_000L) // after a gap: a fresh window
        assertEquals(80_000L, m.litMs(500_000L))
        assertEquals(3, m.configs())
        assertEquals(499_000L, m.upMs(500_000L))
        m.reset(600_000L)
        assertEquals(0L, m.litMs(700_000L))
        assertEquals(0, m.configs())
    }

    @Test
    fun aKeepaliveEvery25sReadsAsLitTheWholeTime() {
        // The relight's own cadence (ICM_REARM_COOLDOWN_MS, 25 s) under the framework's 30 s: ten minutes of it is ten
        // minutes and one window lit, and then nothing while nothing is sent.
        val m = NanIcmPolicy.LitMeter(30_000L)
        m.reset(0L)
        for (t in 0L..600_000L step 25_000L) m.onConfig(t)
        assertEquals(630_000L, m.litMs(900_000L))
        assertEquals(25, m.configs())
    }
}
