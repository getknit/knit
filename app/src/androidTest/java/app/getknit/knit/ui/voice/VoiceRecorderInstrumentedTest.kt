package app.getknit.knit.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.getknit.knit.data.VoiceAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A voice note recorded on the phone's own microphone and AAC encoder through `MediaRecorder` into a pipe, which no JVM
 * test reaches: the encoder's sample-rate query, the `VOICE_RECOGNITION` → `MIC` fallback, and the ADTS stream the
 * pipe drains, judged by the same [VoiceAudio] the bubble draws from.
 *
 * The emulator's microphone hands silence or the host's input; either way the note is a valid ADTS stream of about the
 * length recorded, and it decodes. The recording's length is the input here, not a wait for something, which is why
 * the test sleeps through it.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.ui.voice.VoiceRecorderInstrumentedTest
 */
class VoiceRecorderInstrumentedTest {
    @get:Rule
    val microphone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        // Every emulator and phone the suites run on has one; a device that reports none fails here, never skips.
        assertTrue("a microphone", context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aNoteFromTheMicrophoneIsAnAdtsStreamOfAboutItsLengthThatDecodes() {
        val recorder = VoiceRecorder(context, scope)
        assertTrue("the microphone opened", recorder.start())
        Thread.sleep(RECORD_MS)
        val bytes = runBlocking { recorder.stop() }
        assertNotNull("the recording came back", bytes)

        val durationMs = VoiceAudio.durationMs(bytes!!)
        assertNotNull("the recording is ADTS", durationMs)
        // Bounded loosely on both sides: an emulator's audio clock runs behind the wall's, and the encoder's start
        // and the pipe's tail add a frame or two.
        assertTrue("$durationMs ms recorded in $RECORD_MS", durationMs!!.toLong() in RECORD_MS / 2..RECORD_MS + 2_000)

        val description = runBlocking { VoiceAudio.describe(bytes) }
        assertEquals(durationMs, description.durationMs)
        assertNotNull("the platform decoder reads the recording", description.peaks)
    }

    /**
     * Every exit releases the microphone (the recorder's own contract): after a cancel and after a stop the app holds
     * no recording at all, as the audio service counts them, and the same recorder and a second one open it again.
     * Opening again alone would not show it: since Android 10 a second capture starts beside a leaked one, silenced.
     */
    @Test
    fun theMicrophoneIsReleasedAfterACancelAndAfterAStop() {
        val idle = recordings()
        val recorder = VoiceRecorder(context, scope)
        assertTrue("first start", recorder.start())
        awaitRecordings("while recording") { it > idle }
        Thread.sleep(SHORT_MS)
        recorder.cancel()
        awaitRecordings("after a cancel") { it == idle }

        assertTrue("start after a cancel", recorder.start())
        Thread.sleep(SHORT_MS)
        assertNotNull("a stop after a cancel still records", runBlocking { recorder.stop() })
        awaitRecordings("after a stop") { it == idle }

        val second = VoiceRecorder(context, scope)
        assertTrue("another recorder opens it after both", second.start())
        Thread.sleep(SHORT_MS)
        assertNotNull(runBlocking { second.stop() })
        awaitRecordings("after the second stop") { it == idle }
    }

    /**
     * How many recordings the audio service reports active. The waits compare it with the count before the first start,
     * so a recording of another app's that it may list cancels out.
     */
    private fun recordings(): Int = context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.size

    /** Polls [recordings] until [settled] holds: a release reaches the audio service a moment after the call returns. */
    private fun awaitRecordings(
        stage: String,
        settled: (Int) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (!settled(recordings())) {
            assertTrue("$stage: ${recordings()} recording(s) active after $AWAIT_MS ms", System.currentTimeMillis() < deadline)
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val RECORD_MS = 2_000L
        const val SHORT_MS = 700L
        const val AWAIT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
