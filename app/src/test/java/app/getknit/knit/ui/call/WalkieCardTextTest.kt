package app.getknit.knit.ui.call

import app.getknit.knit.call.WalkieStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** The walkie diagnostics card's values (docs/VOICE_CALLS.md C-3.11-10, "The card"), as [WalkieDiagnostics] draws them. */
class WalkieCardTextTest {
    @Test
    fun heardLossIsAFloorWhileABurstWasCutShort() {
        assertEquals("1.2 %", heardLossOf(WalkieStats(lossPercent = 1.2)))
        assertEquals("≥ 2.4 %", heardLossOf(WalkieStats(lossPercent = 2.4, burstsCutShort = 1)))
        assertEquals("—", heardLossOf(WalkieStats()))
    }

    @Test
    fun gapsAreNoneUntilABurstHasPlayedThenCountedWithTheirLength() {
        assertEquals("—", gapsOf(WalkieStats()))
        assertEquals("0", gapsOf(WalkieStats(burstsIn = 3)))
        assertEquals("1 · 420 ms", gapsOf(WalkieStats(burstsIn = 3, gaps = 1, gapMs = 420)))
        assertEquals("2 · 1.6 s", gapsOf(WalkieStats(burstsIn = 3, gaps = 2, gapMs = 1_640)))
    }

    @Test
    fun theChunksAndBurstsLinesNameWhatNeverCameEachWay() {
        val s =
            WalkieStats(
                chunksOut = 42,
                chunksFailed = 1,
                chunksUnheard = 3,
                chunksIn = 40,
                chunksLost = 2,
                burstsOut = 12,
                burstsUnreported = 1,
                burstsIn = 10,
                burstsCutShort = 2,
            )
        assertEquals("480 ms · 42 out, 1 failed, 3 unheard · 40 in, 2 lost", chunksOf(s))
        assertEquals("12 sent, 1 unreported · 10 heard, 2 cut short", burstsOf(s))
        assertEquals("480 ms · 0 out · 0 in", chunksOf(WalkieStats()))
        assertEquals("0 sent · 0 heard", burstsOf(WalkieStats()))
    }
}
