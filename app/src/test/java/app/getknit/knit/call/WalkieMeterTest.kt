package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A walkie session's numbers (docs/VOICE_CALLS.md §3.11): what the diagnostics card and the bridge read. */
class WalkieMeterTest {
    private val meter = WalkieMeter()

    @Test
    fun nothingMeasuredIsNull() {
        val s = meter.snapshot()
        assertNull(s.transitMs)
        assertNull(s.jitterMs)
        assertNull(s.lossPercent)
        assertNull(s.sentLossPercent)
        assertNull(s.sendKbps)
        assertNull(s.receiveKbps)
        assertNull(s.bufferMs)
        assertNull(s.handMs)
        assertNull(s.frameOut)
        assertNull(s.frameIn)
    }

    @Test
    fun theSendRateIsTheLatestBurstsFramesOverItsSpeech() {
        meter.burstOut()
        meter.chunkOut(audio(24), frameBytes = 900, handMs = 50)
        meter.burstOut()
        meter.chunkOut(audio(24), frameBytes = 712, handMs = 10)
        meter.chunkOut(audio(24), frameBytes = 712, handMs = 14)
        // A chunk the radio refused counts as failed, and neither in the rate nor in the frame size.
        meter.chunkOut(audio(24), frameBytes = null, handMs = 2_500)
        val s = meter.snapshot()
        assertEquals(1_424 * 8.0 / 960, s.sendKbps!!, EPSILON)
        assertEquals((900 + 712 + 712) / 3, s.frameOut)
        assertEquals(3, s.chunksOut)
        assertEquals(1, s.chunksFailed)
        assertEquals(14.0, s.handMs!!, EPSILON)
    }

    @Test
    fun theReceiveRateStartsOverWithEachBurst() {
        meter.chunkIn(chunk(b = 0, q = 0), at = 1_000, sentAt = 900, frameBytes = 600)
        meter.chunkIn(chunk(b = 1, q = 0), at = 2_000, sentAt = 1_900, frameBytes = 708)
        meter.chunkIn(chunk(b = 1, q = 1), at = 2_480, sentAt = 2_380, frameBytes = 708)
        val s = meter.snapshot()
        assertEquals(1_416 * 8.0 / 960, s.receiveKbps!!, EPSILON)
        assertEquals((600 + 708 + 708) / 3, s.frameIn)
        assertEquals(3, s.chunksIn)
    }

    @Test
    fun jitterFollowsRfc3550AndIsBlindToClockSkew() {
        // A sender's clock 5 s behind: every arrival 100 ms after the stamp plus the skew, evenly spaced.
        val skew = 5_000L
        for (q in 0 until 4) meter.chunkIn(chunk(q = q), at = skew + 100 + q * 480L, sentAt = q * 480L, frameBytes = 700)
        assertEquals(0.0, meter.snapshot().jitterMs!!, EPSILON)
        // One chunk 160 ms later than its spacing: J += (160 - 0) / 16.
        meter.chunkIn(chunk(q = 4), at = skew + 100 + 4 * 480L + 160, sentAt = 4 * 480L, frameBytes = 700)
        assertEquals(10.0, meter.snapshot().jitterMs!!, EPSILON)
        // Transit carries the skew: it is read off two clocks.
        assertEquals(5_100.0, meter.snapshot().transitMs!!, EPSILON)
    }

    @Test
    fun anUnstampedChunkCountsButIsNeverTimed() {
        meter.chunkIn(chunk(q = 0), at = 1_000, sentAt = 0, frameBytes = 700)
        val s = meter.snapshot()
        assertEquals(1, s.chunksIn)
        assertNull(s.transitMs)
        assertNull(s.jitterMs)
    }

    @Test
    fun transitIsTheMedianOfTheLatestChunks() {
        val transits = listOf(120L, 900L, 130L, 140L)
        transits.forEachIndexed { q, t -> meter.chunkIn(chunk(q = q), at = 10_000 + t, sentAt = 10_000, frameBytes = 700) }
        assertEquals(135.0, meter.snapshot().transitMs!!, EPSILON)
    }

    @Test
    fun lossIsTheSkippedOfTheExpected() {
        for (q in 0 until 3) meter.chunkIn(chunk(q = q), at = 1_000, sentAt = 900, frameBytes = 700)
        meter.skipped(1)
        val s = meter.snapshot()
        assertEquals(25.0, s.lossPercent!!, EPSILON)
        assertEquals(1, s.chunksLost)
    }

    @Test
    fun aBurstCutShortCountsItsLastChunkLostAtLeast() {
        for (q in 0 until 3) meter.chunkIn(chunk(q = q), at = 1_000, sentAt = 900, frameBytes = 700)
        meter.skipped(1)
        meter.cutShort(1)
        val s = meter.snapshot()
        assertEquals(2 * 100.0 / 5, s.lossPercent!!, EPSILON)
        assertEquals(1, s.chunksLost)
        assertEquals(1, s.burstsCutShort)
    }

    @Test
    fun sentLossIsWhatThePeersReportsDidNotCountAndABurstWithNoneCountsWhole() {
        meter.judged(chunks = 10, heard = 7)
        assertEquals(30.0, meter.snapshot().sentLossPercent!!, EPSILON)
        meter.judged(chunks = 5, heard = null)
        // A report counts a chunk that came both ways twice: never more heard than sent.
        meter.judged(chunks = 4, heard = 6)
        val s = meter.snapshot()
        assertEquals(8 * 100.0 / 19, s.sentLossPercent!!, EPSILON)
        assertEquals(8, s.chunksUnheard)
        assertEquals(1, s.burstsUnreported)
    }

    @Test
    fun audioThatComesAfterThePlayerRanOutEndsAGap() {
        meter.played(audio(24), first = true, at = 0)
        // On time: the first chunk's 480 ms has just played.
        meter.played(audio(24), first = false, at = 480)
        assertEquals(0, meter.snapshot().gaps)
        // The third comes a second after the second ran out.
        meter.played(audio(24), first = false, at = 1_960)
        val s = meter.snapshot()
        assertEquals(1, s.gaps)
        assertEquals(1_000L, s.gapMs)
    }

    @Test
    fun aSilenceUnderTheFloorIsTheTracksOwnLatency() {
        meter.played(audio(24), first = true, at = 0)
        meter.played(audio(24), first = false, at = 529)
        assertEquals(0, meter.snapshot().gaps)
        // At the floor it counts: the second began at 529 and ran out at 1 009, and the third came 50 ms later.
        meter.played(audio(24), first = false, at = 1_059)
        assertEquals(1, meter.snapshot().gaps)
        assertEquals(50L, meter.snapshot().gapMs)
    }

    @Test
    fun whileThisPhoneTalksTheHeldPlayerRunsNothingOut() {
        meter.played(audio(24), first = true, at = 0)
        meter.held(true, at = 100)
        meter.held(false, at = 5_000)
        // 100 ms played before the hold and 300 after: 80 ms still to go.
        meter.played(audio(24), first = false, at = 5_300)
        assertEquals(0, meter.snapshot().gaps)
        // Those 80 ms and the 480 just handed run out at 5 860; the next comes 500 ms after.
        meter.played(audio(24), first = false, at = 6_360)
        assertEquals(1, meter.snapshot().gaps)
        assertEquals(500L, meter.snapshot().gapMs)
    }

    @Test
    fun anEmptyEndAndTheNextBurstEndNoGap() {
        meter.played(audio(24), first = true, at = 0)
        // A burst given up on closes with no audio: nothing played after the silence.
        meter.played(ByteArray(0), first = false, at = 4_480)
        meter.played(audio(24), first = true, at = 10_000)
        assertEquals(0, meter.snapshot().gaps)
    }

    @Test
    fun theBufferIsTheLatestBurstsWaitToPlay() {
        meter.started(firstAt = 1_000, at = 1_412)
        meter.started(firstAt = 5_000, at = 5_120)
        assertEquals(120.0, meter.snapshot().bufferMs!!, EPSILON)
    }

    @Test
    fun burstsAreCountedForTheSessionOnly() {
        meter.burstSent()
        meter.burstHeard()
        meter.burstHeard()
        assertEquals(1, meter.snapshot().burstsOut)
        assertEquals(2, meter.snapshot().burstsIn)
        // A new session's card starts at none either way, whatever the last one sent.
        meter.reset()
        meter.burstHeard()
        assertEquals(0, meter.snapshot().burstsOut)
        assertEquals(1, meter.snapshot().burstsIn)
    }

    @Test
    fun aResetStartsTheSessionOver() {
        meter.burstOut()
        meter.chunkOut(audio(24), frameBytes = 712, handMs = 10)
        meter.chunkIn(chunk(q = 0), at = 1_000, sentAt = 900, frameBytes = 700)
        meter.skipped(2)
        meter.cutShort(1)
        meter.judged(chunks = 4, heard = null)
        meter.started(firstAt = 1_000, at = 1_400)
        meter.played(audio(24), first = true, at = 0)
        meter.played(audio(24), first = false, at = 2_000)
        meter.burstSent()
        meter.burstHeard()
        meter.reset()
        assertEquals(WalkieStats(), meter.snapshot())
    }

    // [packets] 20 ms SILK wideband packets (TOC config 9, one frame) of 20 bytes, joined as a chunk carries them.
    private fun audio(packets: Int): ByteArray = OpusPackets.join(List(packets) { ByteArray(20).also { it[0] = SILK_WB_20MS } })

    private fun chunk(
        b: Int = 0,
        q: Int,
    ) = TalkChunk(b = b, q = q, a = audio(24))

    private companion object {
        const val SILK_WB_20MS: Byte = (9 shl 3).toByte()
        const val EPSILON = 1e-9
    }
}
