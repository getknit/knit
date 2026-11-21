package app.getknit.knit.data

import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.call.SttPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A voice note's length, waveform and speech samples on the phone's own AAC decoder (`MediaCodec`), which the JVM
 * tests replace with Robolectric's: the same class of gap as #353, where a codec a phone lacked failed only there.
 *
 * Each note is made here by the phone's AAC encoder from a known signal, a 440 Hz tone for the first half and silence
 * for the second, so what comes out can be checked against what went in: the waveform's bars are tall where the tone
 * was and flat where it was not, and the speech samples hold the tone at its pitch, resampled to the model's rate.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.data.VoiceAudioInstrumentedTest
 */
class VoiceAudioInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "voice-audio-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** The recorder's own format: AAC-LC behind ADTS headers at 16 kHz mono, the rate `VoiceRecorder` asks for first. */
    @Test
    fun aRecordedNoteDecodesToAWaveformTallWhereTheToneWasAndFlatWhereItWasNot() {
        val note = AudioFixtures.adts(AudioFixtures.tone(rate = 16_000, toneMs = TONE_MS, totalMs = TOTAL_MS), rate = 16_000)

        val durationMs = VoiceAudio.durationMs(note)
        assertNotNull("the encoder's output reads as ADTS", durationMs)
        // Whole 1024-sample frames, give or take the encoder's priming and its tail: the API 33 emulator's encoder
        // drops the last partial frame and wrote 46 for 46.9 (2,944 ms), others add a priming frame.
        assertTrue("duration $durationMs", durationMs!! in TOTAL_MS - SLACK_MS..TOTAL_MS + SLACK_MS)

        val description = runBlocking { VoiceAudio.describe(note) }
        assertEquals(durationMs, description.durationMs)
        val bars = checkNotNull(VoiceAudio.decodePeaks(description.peaks)) { "the platform decoder gave no waveform" }
        assertEquals(VoiceAudio.PEAK_COUNT, bars.size)
        assertToneThenSilence(bars, durationMs)

        val speech = checkNotNull(runBlocking { VoiceAudio.speechPcm(note) }) { "no speech samples" }
        assertSpeechHoldsTheTone(speech)
    }

    /**
     * A picked recording from another app: AAC in an MPEG-4 container at 44.1 kHz stereo, which states its length in
     * the container rather than in ADTS headers, and which the speech path mixes down and resamples.
     */
    @Test
    fun aPickedM4aTakesItsLengthFromItsContainerAndMixesDownToTheTone() {
        val pcm = AudioFixtures.tone(rate = 44_100, channels = 2, toneMs = TONE_MS, totalMs = TOTAL_MS)
        val file = AudioFixtures.m4a(pcm, rate = 44_100, channels = 2, out = File(dir, "picked.m4a"))
        assertNull("an MPEG-4 file is not ADTS", VoiceAudio.durationMs(file))

        val description = runBlocking { VoiceAudio.describe(file) }
        val durationMs = description.durationMs
        assertNotNull("the container states a length", durationMs)
        assertTrue("duration $durationMs", durationMs!! in TOTAL_MS - SLACK_MS..TOTAL_MS + SLACK_MS)
        val bars = checkNotNull(VoiceAudio.decodePeaks(description.peaks)) { "the platform decoder gave no waveform" }
        assertToneThenSilence(bars, durationMs)

        val speech = checkNotNull(runBlocking { VoiceAudio.speechPcm(file) }) { "no speech samples" }
        assertSpeechHoldsTheTone(speech)
    }

    /**
     * Bars over the tone are tall and bars over the silence flat. The bars near either edge of the tone are not judged:
     * an ADTS stream keeps the encoder's priming (up to two 1024-sample frames, 128 ms at 16 kHz), which moves the
     * tone later, and a transform codec smears its ends over a frame either way.
     */
    private fun assertToneThenSilence(
        bars: FloatArray,
        durationMs: Int,
    ) {
        val edge = (VoiceAudio.PEAK_COUNT.toLong() * TONE_MS / durationMs).toInt()
        val tone = bars.slice(EDGE_BARS until edge - EDGE_BARS)
        val silence = bars.slice(edge + EDGE_BARS + 1 until bars.size)
        val listing = bars.joinToString(" ") { "%.2f".format(java.util.Locale.ROOT, it) }
        assertTrue("tone bars tall: $listing", tone.all { it >= TALL })
        assertTrue("silent bars flat: $listing", silence.all { it <= FLAT })
    }

    /**
     * The model's samples: at [SttPolicy.SAMPLE_RATE], as long as the note, loud with the tone at its pitch for the
     * first half and silent after. A wrong rate in the resample shifts the pitch; a dropped buffer shortens the note.
     */
    private fun assertSpeechHoldsTheTone(speech: FloatArray) {
        val rate = SttPolicy.SAMPLE_RATE
        val ms = speech.size * 1_000L / rate
        assertTrue("speech is $ms ms", ms in TOTAL_MS - SLACK_MS..TOTAL_MS + SLACK_MS)
        val toneRms = AudioFixtures.rms(speech, rate, fromMs = 200, toMs = TONE_MS - 200)
        val silenceRms = AudioFixtures.rms(speech, rate, fromMs = TONE_MS + 300, toMs = TOTAL_MS - 200)
        // A 0.5-level sine has an RMS of 0.35.
        assertTrue("tone RMS $toneRms", toneRms in 0.2..0.5)
        assertTrue("silence RMS $silenceRms", silenceRms < 0.01)
        val crossings = AudioFixtures.crossingsPerSecond(speech, rate, fromMs = 200, toMs = TONE_MS - 200)
        val pitch = crossings / 2
        assertTrue("pitch $pitch Hz", pitch in AudioFixtures.TONE_HZ * 0.97..AudioFixtures.TONE_HZ * 1.03)
    }

    private companion object {
        const val TONE_MS = 1_500
        const val TOTAL_MS = 3_000
        const val SLACK_MS = 300
        const val TALL = 0.6f
        const val FLAT = 0.1f

        // 4 bars of a 3 s note are 190 ms: past the priming and a frame of smear on either side.
        const val EDGE_BARS = 4
    }
}
