package app.getknit.knit.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.getknit.knit.call.BurstPcm
import app.getknit.knit.call.OpusPackets
import app.getknit.knit.call.SttPolicy
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.data.AudioFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * A walkie burst through the phone's own Opus encoder and decoder (`MediaCodec`, docs/VOICE_CALLS.md §3.11), which the
 * JVM tests never touch. Calls are on in release, and a phone whose Opus codec is missing or misbehaves is the same
 * class of gap as #353's HEVC encoder.
 *
 * The first case speaks a known tone through [TalkRecorder.nextFrom], the debug bridge's stand-in for a voice, so what
 * [TalkPlayer] decodes can be held against what went in: the same pitch, the same length, at the walkie's bit rate,
 * every packet one 20 ms Opus frame and, where the encoder offers constant bitrate, one size (§2.7: a chunk's size must
 * say nothing about what was said). The second records from the microphone itself.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.ui.call.WalkieOpusInstrumentedTest
 */
class WalkieOpusInstrumentedTest {
    @get:Rule
    val microphone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dir = File(context.cacheDir, "walkie-opus-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun aSpokenToneComesBackOutOfTheDecoderAtItsPitchAndLength() {
        val tone = AudioFixtures.tone(rate = WalkiePolicy.SAMPLE_RATE, toneMs = BURST_MS, totalMs = BURST_MS, level = LEVEL)
        val pcm = File(dir, "tone.pcm").apply { writeBytes(littleEndian(tone)) }
        val recorder =
            TalkRecorder(scope).apply {
                applyGain = false
                nextFrom = pcm
            }
        val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
        assertTrue("the Opus encoder opened", recorder.start(headset = false) { packets += it })
        // The file is read at the microphone's pace; once a packet per 20 ms of the tone has come out it is read through,
        // and stop() flushes the encoder and joins the capture, so the wait only has to cover the reading.
        awaitPackets(packets, BURST_MS / FRAME_MS)
        runBlocking { recorder.stop() }

        val burst = packets.toList()
        assertOneFrameEach(burst)
        val sizes = burst.map { it.size }
        val meanBytes = sizes.average()
        Log.i(TAG, "tone: ${burst.size} packets, sizes ${sizes.distinct().sorted()}, mean $meanBytes B")
        // 8 kbps of 20 ms frames is 20 bytes each; an encoder that ignored the rate would write its default, 4 to 8 times that.
        assertTrue("mean packet $meanBytes B at ${WalkiePolicy.BIT_RATE} bps", meanBytes in 10.0..40.0)
        if (opusOffersCbr()) {
            // The first and last packet are the encoder's ramp and flush; every one between is one size.
            assertEquals(
                "packet sizes under CBR",
                1,
                sizes
                    .drop(1)
                    .dropLast(1)
                    .distinct()
                    .size,
            )
        }

        val heard = decode(burst)
        val heardMs = heard.size * 1_000L / SttPolicy.SAMPLE_RATE
        assertTrue("decoded $heardMs ms of a $BURST_MS ms burst", heardMs in BURST_MS - 100L..BURST_MS + 200L)
        val rms = AudioFixtures.rms(heard, SttPolicy.SAMPLE_RATE, fromMs = 200, toMs = BURST_MS - 200)
        // A 0.3-level sine has an RMS of 0.21; narrowband Opus keeps a pure tone's level within a few dB.
        assertTrue("decoded RMS $rms", rms in 0.1..0.35)
        val pitch = AudioFixtures.crossingsPerSecond(heard, SttPolicy.SAMPLE_RATE, fromMs = 200, toMs = BURST_MS - 200) / 2
        assertTrue("decoded pitch $pitch Hz", pitch in AudioFixtures.TONE_HZ * 0.97..AudioFixtures.TONE_HZ * 1.03)
    }

    /**
     * The microphone's own path: an `AudioRecord` at 16 kHz mono from the fallback profile's sources (no call-path
     * verdict here), the noise suppressor where the phone has one, into the encoder, and back out of the decoder for
     * about as long as was spoken.
     */
    @Test
    fun aBurstFromTheMicrophoneComesOutAsOpusFramesThatDecode() {
        // A device without one fails here, never skips, and says which of the two below was missing.
        assertTrue("a microphone", context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        val recorder = TalkRecorder(scope)
        val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
        assertTrue("the microphone and the Opus encoder opened", recorder.start(headset = false) { packets += it })
        awaitPackets(packets, MIC_PACKETS)
        runBlocking { recorder.stop() }

        assertNotNull("a source opened", recorder.lastSource)
        Log.i(TAG, "microphone: source ${recorder.lastSource}, noise suppressor ${recorder.suppressor}, ${packets.size} packets")
        assertTrue("suppressor ${recorder.suppressor}", recorder.suppressor in SUPPRESSOR_STATES)
        val burst = packets.toList()
        assertOneFrameEach(burst)
        val heardMs = decode(burst).size * 1_000L / SttPolicy.SAMPLE_RATE
        val spokenMs = burst.size * FRAME_MS.toLong()
        assertTrue("decoded $heardMs ms of $spokenMs", heardMs in spokenMs - 100..spokenMs + 200)
    }

    /** Every packet is one 20 ms frame (what the player's timestamps count on) and fits a chunk's one-byte length. */
    private fun assertOneFrameEach(burst: List<ByteArray>) {
        assertTrue("packets", burst.isNotEmpty())
        burst.forEachIndexed { i, packet ->
            assertTrue("packet $i is ${packet.size} B", packet.size in 1..MAX_PACKET)
            assertEquals("packet $i plays", FRAME_MS * 1_000L, OpusPackets.durationUs(packet))
        }
    }

    /** [burst] through [TalkPlayer], what its tap hands the transcript gathered by the transcript's own [BurstPcm]. */
    private fun decode(burst: List<ByteArray>): FloatArray {
        val audio = BurstPcm(capMs = DECODE_CAP_MS)
        val player = TalkPlayer(tap = { pcm, rate, channels -> audio.add(pcm, rate, channels) })
        runBlocking {
            withContext(Dispatchers.IO) {
                // play() waits on the track to play the burst out; a track that never drains would wait for good.
                withTimeout(PLAY_BUDGET_MS) { player.play(burst, first = true, last = true) }
            }
        }
        return audio.drain()
    }

    private fun awaitPackets(
        packets: List<ByteArray>,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (packets.size < count) {
            assertTrue("${packets.size} of $count packets after $AWAIT_MS ms", System.currentTimeMillis() < deadline)
            Thread.sleep(POLL_MS)
        }
    }

    /** Whether the phone's Opus encoder offers constant bitrate, which [TalkRecorder] asks for wherever it does. */
    private fun opusOffersCbr(): Boolean {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        return try {
            codec.codecInfo
                .getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                .encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        } finally {
            codec.release()
        }
    }

    private fun littleEndian(samples: ShortArray): ByteArray =
        ByteBuffer
            .allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { asShortBuffer().put(samples) }
            .array()

    private companion object {
        const val TAG = "WalkieOpusTest"
        const val BURST_MS = 2_000
        const val FRAME_MS = 20
        const val LEVEL = 0.3
        const val MIC_PACKETS = 75
        const val MAX_PACKET = 255
        const val DECODE_CAP_MS = 10_000L
        const val PLAY_BUDGET_MS = 30_000L

        // Sized for a software-rendered emulator: a 2 s burst read at the microphone's pace.
        const val AWAIT_MS = 30_000L
        const val POLL_MS = 50L
        val SUPPRESSOR_STATES = setOf("on", "off", "call path", "unavailable", "refused")
    }
}
