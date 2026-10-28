package app.getknit.knit.call

import android.media.MediaRecorder.AudioSource.MIC
import android.media.MediaRecorder.AudioSource.UNPROCESSED
import android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
import android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a walkie burst records from (docs/VOICE_CALLS.md C-3.11-5, C-3.13-7). */
class TalkCaptureTest {
    @Test
    fun thePhonesMicrophoneTakesTheCallPathOnTheStandardProfile() {
        assertEquals(
            listOf(VOICE_COMMUNICATION, VOICE_RECOGNITION, MIC),
            TalkCapture.sources(headset = false, profile = CaptureProfile.STANDARD),
        )
    }

    @Test
    fun theFallbackProfileKeepsTheCallPathOut() {
        // A generic image's vendor hands VOICE_COMMUNICATION only zeros (ADR 2026-10.kw29).
        assertEquals(listOf(VOICE_RECOGNITION, MIC), TalkCapture.sources(headset = false, profile = CaptureProfile.FALLBACK))
    }

    @Test
    fun aHeadsetRecordsFromTheCallPathOnEitherProfile() {
        CaptureProfile.entries.forEach { profile ->
            assertEquals(listOf(VOICE_COMMUNICATION, MIC), TalkCapture.sources(headset = true, profile = profile))
        }
    }

    @Test
    fun aChosenSourceIsTriedFirstOnThePhoneOnly() {
        assertEquals(
            listOf(UNPROCESSED, VOICE_RECOGNITION, MIC),
            TalkCapture.sources(headset = false, profile = CaptureProfile.FALLBACK, first = UNPROCESSED),
        )
        assertEquals(
            listOf(VOICE_RECOGNITION, VOICE_COMMUNICATION, MIC),
            TalkCapture.sources(headset = false, profile = CaptureProfile.STANDARD, first = VOICE_RECOGNITION),
        )
        assertEquals(listOf(VOICE_COMMUNICATION, MIC), TalkCapture.sources(headset = true, profile = CaptureProfile.STANDARD, first = MIC))
    }

    @Test
    fun knitAddsASuppressorOnlyWhereTheSourceHasNone() {
        assertFalse(TalkCapture.suppress(VOICE_COMMUNICATION))
        assertTrue(TalkCapture.suppress(VOICE_RECOGNITION))
        assertTrue(TalkCapture.suppress(MIC))
        assertTrue(TalkCapture.suppress(UNPROCESSED))
    }

    @Test
    fun aSecondOfZerosOnTheCallPathIsADeadRoute() {
        assertTrue(TalkCapture.deadMicrophone(VOICE_COMMUNICATION, headset = false, samples = SECOND, peak = 0))
    }

    @Test
    fun anythingElseIsNot() {
        // Too short, a single sample above zero, the recognition path, a headset, or no microphone at all.
        assertFalse(TalkCapture.deadMicrophone(VOICE_COMMUNICATION, headset = false, samples = SECOND - 1, peak = 0))
        assertFalse(TalkCapture.deadMicrophone(VOICE_COMMUNICATION, headset = false, samples = SECOND, peak = 1))
        assertFalse(TalkCapture.deadMicrophone(VOICE_RECOGNITION, headset = false, samples = SECOND, peak = 0))
        assertFalse(TalkCapture.deadMicrophone(VOICE_COMMUNICATION, headset = true, samples = SECOND, peak = 0))
        assertFalse(TalkCapture.deadMicrophone(null, headset = false, samples = SECOND, peak = 0))
    }

    private companion object {
        const val SECOND = WalkiePolicy.SAMPLE_RATE.toLong()
    }
}
