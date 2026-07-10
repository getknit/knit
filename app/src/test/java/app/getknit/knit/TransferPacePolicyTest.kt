package app.getknit.knit

import app.getknit.knit.mesh.link.PaceConfig
import app.getknit.knit.mesh.link.TransferPacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [TransferPacePolicy] — the pure average-feed-rate limiter for a file streamed over a link. */
class TransferPacePolicyTest {
    // 1000 B/s makes the arithmetic exact: 1 byte "costs" 1 ms of budget.
    private val kbps1 = PaceConfig(bytesPerSec = 1000)

    @Test
    fun unboundedWhenRateIsZeroOrNegative() {
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 10_000, elapsedMs = 0, config = PaceConfig(0)))
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 10_000, elapsedMs = 0, config = PaceConfig(-1)))
    }

    @Test
    fun noDelayWhileUnderBudget() {
        // 500 B in 1000 ms is well under 1000 B/s — the feed is behind the target, so don't hold it.
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 500, elapsedMs = 1000, config = kbps1))
        // Exactly on budget: 1000 B at 1000 ms → target 1000 ms, elapsed 1000 ms → 0.
        assertEquals(0, TransferPacePolicy.delayMs(bytesSent = 1000, elapsedMs = 1000, config = kbps1))
    }

    @Test
    fun delaysWhenAheadOfBudget() {
        // 1000 B "should" take 1000 ms at 1000 B/s; only 200 ms elapsed → wait the remaining 800 ms.
        assertEquals(800, TransferPacePolicy.delayMs(bytesSent = 1000, elapsedMs = 200, config = kbps1))
        // Nothing elapsed yet → the full budget for what's been sent.
        assertEquals(1000, TransferPacePolicy.delayMs(bytesSent = 1000, elapsedMs = 0, config = kbps1))
    }

    @Test
    fun delayGrowsWithBytesSentAtFixedElapsed() {
        val a = TransferPacePolicy.delayMs(bytesSent = 2000, elapsedMs = 100, config = kbps1)
        val b = TransferPacePolicy.delayMs(bytesSent = 4000, elapsedMs = 100, config = kbps1)
        assertTrue("more bytes fed at the same elapsed ⇒ a longer hold ($a < $b)", a < b)
        assertEquals(1900, a) // 2000 ms target − 100 ms elapsed
        assertEquals(3900, b) // 4000 ms target − 100 ms elapsed
    }

    @Test
    fun realisticBleCapPacesAWholeGifTail() {
        // A 200 KB WebP at 28 KB/s should take ~7.3 s; with nothing elapsed the feed is held to that budget.
        val cfg = PaceConfig(bytesPerSec = 28 * 1024)
        val wait = TransferPacePolicy.delayMs(bytesSent = 200L * 1024, elapsedMs = 0, config = cfg)
        assertTrue("≈7.15 s hold for a fully-buffered 200 KB feed, was $wait ms", wait in 7000..7500)
    }
}
