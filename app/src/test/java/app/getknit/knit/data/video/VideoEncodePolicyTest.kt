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
        val plan = VideoEncodePolicy.plan(source(), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        assertEquals(VideoEncodePolicy.MIME_HEVC, plan.videoMime)
        assertEquals(480, plan.shortSide)
        assertEquals(30f, plan.maxFrameRate)
        assertEquals(VideoEncodePolicy.HEVC_VIDEO_BITRATE, plan.videoBitrate)
        assertEquals(VideoEncodePolicy.AUDIO_BITRATE, plan.audioBitrate)
        assertFalse(plan.trimmed)
    }

    @Test
    fun theRateFitsUnderTheFiveMegabyteTierAtTheLongestDuration() {
        val plan = VideoEncodePolicy.plan(source(durationMs = 61_000), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        val bytes = (plan.videoBitrate + plan.audioBitrate).toLong() * 61 / 8
        assertTrue("61 s at the planned rate is $bytes B", bytes < VideoEncodePolicy.CEILING_BYTES)
    }

    @Test
    fun landscapeScalesByItsShortSideToo() {
        val plan = VideoEncodePolicy.plan(source(width = 3840, height = 2160), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        assertEquals(480, plan.shortSide)
        assertEquals(VideoEncodePolicy.HEVC_VIDEO_BITRATE, plan.videoBitrate)
    }

    @Test
    fun aSmallSourceIsNeverUpscaledAndSpendsLess() {
        val plan = VideoEncodePolicy.plan(source(width = 360, height = 640), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        assertNull(plan.shortSide)
        assertTrue(plan.videoBitrate < VideoEncodePolicy.HEVC_VIDEO_BITRATE)
        val tiny = VideoEncodePolicy.plan(source(width = 120, height = 160), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        assertEquals(VideoEncodePolicy.MIN_VIDEO_BITRATE, tiny.videoBitrate)
    }

    @Test
    fun aPhoneWithoutAnHevcEncoderSendsH264AtAHigherRate() {
        val plan = VideoEncodePolicy.plan(source(), hevcEncoder = false, bitrateMode = BitrateMode.CBR)
        assertEquals(VideoEncodePolicy.MIME_H264, plan.videoMime)
        assertEquals(490_000, plan.videoBitrate)
    }

    @Test
    fun aLongerClipIsCutAtSixtyOneSeconds() {
        val plan = VideoEncodePolicy.plan(source(durationMs = 180_000), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        assertEquals(61_000L, plan.clipEndMs)
        assertTrue(plan.trimmed)
        assertNull(VideoEncodePolicy.plan(source(durationMs = 61_000), hevcEncoder = true, bitrateMode = BitrateMode.CBR).clipEndMs)
    }

    @Test
    fun theComposerAsksAboutExactlyTheClipsTheEncodeCuts() {
        // The composer's "Send the first 61 seconds?" reads `cuts` before any encode; the plan reads it to cut.
        for (durationMs in listOf(0L, 45_000L, 61_000L, 61_001L, 180_000L)) {
            val plan = VideoEncodePolicy.plan(source(durationMs = durationMs), hevcEncoder = true, bitrateMode = BitrateMode.CBR)
            assertEquals("at $durationMs ms", plan.trimmed, VideoEncodePolicy.cuts(durationMs))
        }
        assertFalse(VideoEncodePolicy.cuts(61_000L))
        assertTrue(VideoEncodePolicy.cuts(61_001L))
    }

    @Test
    fun aCbrOvershootIsEncodedOnceMoreAsASmallerPictureAtTheSameRateThenRefused() {
        // The Redmi Note 13 Pro+ on Device Farm, 2026-11-20 (#353): its HEVC encoder in CBR put a busy clip at 5,076,452 B
        // for 350 kbps asked, and a 0.7x rate came out 1 % smaller. A smaller picture is the retry in CBR too.
        val src = source(durationMs = 61_000)
        val plan = VideoEncodePolicy.plan(src, hevcEncoder = true, bitrateMode = BitrateMode.CBR)
        assertEquals(VideoEncodeVerdict.Accept, VideoEncodePolicy.next(plan, src, VideoEncodePolicy.CEILING_BYTES))
        val retry = VideoEncodePolicy.next(plan, src, 5_076_452) as VideoEncodeVerdict.Retry
        assertEquals(1, retry.plan.attempt)
        assertEquals(plan.videoBitrate, retry.plan.videoBitrate)
        assertEquals(416, retry.plan.shortSide)
        assertEquals(VideoEncodeVerdict.Refuse, VideoEncodePolicy.next(retry.plan, src, VideoEncodePolicy.CEILING_BYTES + 1))
    }

    @Test
    fun aVbrOvershootIsEncodedOnceMoreAsASmallerPictureAtTheSameRateThenRefused() {
        // The emulator, 2026-11-20 (#353): AOSP's H.264 encoder offers only VBR, and VQApply lifted 490 kbps to its
        // 1.23 Mbps floor at 854 x 480, so a busy 61 s clip comes out near 9.7 MB however low the rate asked.
        val src = source(durationMs = 61_000)
        val plan = VideoEncodePolicy.plan(src, hevcEncoder = false, bitrateMode = BitrateMode.VBR)
        val floorBytes = (1_229_760L + plan.audioBitrate) * 61 / 8
        val retry = VideoEncodePolicy.next(plan, src, floorBytes) as VideoEncodeVerdict.Retry
        assertEquals(1, retry.plan.attempt)
        assertEquals(plan.videoBitrate, retry.plan.videoBitrate)
        val short = checkNotNull(retry.plan.shortSide)
        assertEquals("even", 0, short % 2)
        // At the floor's 3.0 bits per pixel, the smaller picture's floor and the plan's rate both fit the ceiling.
        val long = short * 16 / 9
        val retryFloor = maxOf((long.toLong() * short * 3), plan.videoBitrate.toLong())
        val retryBytes = (retryFloor + plan.audioBitrate) * 61 / 8
        assertTrue("$short p would come out at $retryBytes B", retryBytes <= VideoEncodePolicy.CEILING_BYTES)
        assertTrue("$short p is still worth sending", short >= 240)
        assertEquals(VideoEncodeVerdict.Refuse, VideoEncodePolicy.next(retry.plan, src, VideoEncodePolicy.CEILING_BYTES + 1))
    }

    @Test
    fun anEncodeThatRanPastTheCapIsSizedForTheWholeClipAndRetriedSmaller() {
        // The emulator, 2026-11-20 (#353): the busy clip passed the 8 MiB in-memory cap 51.5 s into its 61 s.
        val cap = 8L * 1024 * 1024
        val estimate = VideoEncodePolicy.overflowEstimate(cap, writtenUs = 51_500_000, plannedUs = 61_000_000)
        assertEquals(cap * 61_000 / 51_500, estimate)
        val src = source(durationMs = 61_000)
        val plan = VideoEncodePolicy.plan(src, hevcEncoder = false, bitrateMode = BitrateMode.VBR)
        val retry = VideoEncodePolicy.next(plan, src, estimate) as VideoEncodeVerdict.Retry
        assertTrue("${retry.plan.shortSide}", checkNotNull(retry.plan.shortSide) in 240..320)
        // Nothing written, or a muxer past the clip's end, still reads as over the cap, never as a fit.
        assertEquals(cap + 1, VideoEncodePolicy.overflowEstimate(cap, writtenUs = 0, plannedUs = 61_000_000))
        assertEquals(cap + 1, VideoEncodePolicy.overflowEstimate(cap, writtenUs = 62_000_000, plannedUs = 61_000_000))
    }

    @Test
    fun aVbrRetryShrinksASmallSourceFromItsOwnSizeAndNeverBelowTheFloor() {
        val small = source(width = 360, height = 640, durationMs = 61_000)
        val plan = VideoEncodePolicy.plan(small, hevcEncoder = false, bitrateMode = BitrateMode.VBR)
        val retry = VideoEncodePolicy.next(plan, small, VideoEncodePolicy.CEILING_BYTES * 2) as VideoEncodeVerdict.Retry
        assertTrue("${retry.plan.shortSide}", checkNotNull(retry.plan.shortSide) < 360)
        // Just over the ceiling it sheds a little from its own 360, where scaling from 480 would give 442, an upscale.
        val near = VideoEncodePolicy.next(plan, small, VideoEncodePolicy.CEILING_BYTES + 1) as VideoEncodeVerdict.Retry
        assertEquals(330, near.plan.shortSide)
        val huge = VideoEncodePolicy.next(plan, small, VideoEncodePolicy.CEILING_BYTES * 1_000) as VideoEncodeVerdict.Retry
        assertEquals(VideoEncodePolicy.MIN_SHORT_SIDE, huge.plan.shortSide)
    }
}
