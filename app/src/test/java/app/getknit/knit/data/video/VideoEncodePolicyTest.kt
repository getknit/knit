package app.getknit.knit.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size-first encode of a short video (ADR 2026-10.hmfe): 480p, 30 fps, about 400 kbps, 61 s at most. */
class VideoEncodePolicyTest {
    private fun source(
        width: Int = 1080,
        height: Int = 1920,
        durationMs: Long = 45_000,
    ) = VideoSource(width, height, rotationDegrees = 0, durationMs = durationMs, frameRate = 60f, hasAudio = true)

    @Test
    fun aPhoneClipGoesOutAt480pHevcAtTheSetRate() {
        val plan = VideoEncodePolicy.plan(source(), hevcEncoder = true)
        assertEquals(VideoEncodePolicy.MIME_HEVC, plan.videoMime)
        assertEquals(480, plan.shortSide)
        assertEquals(30f, plan.maxFrameRate)
        assertEquals(VideoEncodePolicy.HEVC_VIDEO_BITRATE, plan.videoBitrate)
        assertEquals(VideoEncodePolicy.AUDIO_BITRATE, plan.audioBitrate)
        assertFalse(plan.trimmed)
    }

    @Test
    fun theRateFitsUnderTheFiveMegabyteTierAtTheLongestDuration() {
        val plan = VideoEncodePolicy.plan(source(durationMs = 61_000), hevcEncoder = true)
        val bytes = (plan.videoBitrate + plan.audioBitrate).toLong() * 61 / 8
        assertTrue("61 s at the planned rate is $bytes B", bytes < VideoEncodePolicy.CEILING_BYTES)
    }

    @Test
    fun landscapeScalesByItsShortSideToo() {
        val plan = VideoEncodePolicy.plan(source(width = 3840, height = 2160), hevcEncoder = true)
        assertEquals(480, plan.shortSide)
        assertEquals(VideoEncodePolicy.HEVC_VIDEO_BITRATE, plan.videoBitrate)
    }

    @Test
    fun aSmallSourceIsNeverUpscaledAndSpendsLess() {
        val plan = VideoEncodePolicy.plan(source(width = 360, height = 640), hevcEncoder = true)
        assertNull(plan.shortSide)
        assertTrue(plan.videoBitrate < VideoEncodePolicy.HEVC_VIDEO_BITRATE)
        val tiny = VideoEncodePolicy.plan(source(width = 120, height = 160), hevcEncoder = true)
        assertEquals(VideoEncodePolicy.MIN_VIDEO_BITRATE, tiny.videoBitrate)
    }

    @Test
    fun aPhoneWithoutAnHevcEncoderSendsH264AtAHigherRate() {
        val plan = VideoEncodePolicy.plan(source(), hevcEncoder = false)
        assertEquals(VideoEncodePolicy.MIME_H264, plan.videoMime)
        assertEquals(490_000, plan.videoBitrate)
    }

    @Test
    fun aLongerClipIsCutAtSixtyOneSeconds() {
        val plan = VideoEncodePolicy.plan(source(durationMs = 180_000), hevcEncoder = true)
        assertEquals(61_000L, plan.clipEndMs)
        assertTrue(plan.trimmed)
        assertNull(VideoEncodePolicy.plan(source(durationMs = 61_000), hevcEncoder = true).clipEndMs)
    }

    @Test
    fun theComposerAsksAboutExactlyTheClipsTheEncodeCuts() {
        // The composer's "Send the first 61 seconds?" reads `cuts` before any encode; the plan reads it to cut.
        for (durationMs in listOf(0L, 45_000L, 61_000L, 61_001L, 180_000L)) {
            val plan = VideoEncodePolicy.plan(source(durationMs = durationMs), hevcEncoder = true)
            assertEquals("at $durationMs ms", plan.trimmed, VideoEncodePolicy.cuts(durationMs))
        }
        assertFalse(VideoEncodePolicy.cuts(61_000L))
        assertTrue(VideoEncodePolicy.cuts(61_001L))
    }

    @Test
    fun anOvershootIsEncodedOnceMoreLowerThenRefused() {
        val plan = VideoEncodePolicy.plan(source(), hevcEncoder = true)
        assertEquals(VideoEncodeVerdict.Accept, VideoEncodePolicy.next(plan, VideoEncodePolicy.CEILING_BYTES))
        val retry = VideoEncodePolicy.next(plan, VideoEncodePolicy.CEILING_BYTES + 1) as VideoEncodeVerdict.Retry
        assertEquals(1, retry.plan.attempt)
        assertEquals(245_000, retry.plan.videoBitrate)
        assertEquals(plan.shortSide, retry.plan.shortSide)
        assertEquals(VideoEncodeVerdict.Refuse, VideoEncodePolicy.next(retry.plan, VideoEncodePolicy.CEILING_BYTES + 1))
    }
}
