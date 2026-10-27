package app.getknit.knit

import app.getknit.knit.mesh.link.FeedMeter
import app.getknit.knit.mesh.link.FeedWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 2026-10.nsr7: a feed's ETA, stall and deadline, from the receiver's progress reports or, before them, the pace. */
class FeedMeterTest {
    private var t = 0L

    // The arithmetic with no sample floor and no ceiling: these cases report a second or less apart, at rates past it.
    private val meter = FeedMeter({ t }, minSampleMs = 0, ceiling = Int.MAX_VALUE)

    // A stream whose writer has already handed [written] bytes to the stack (all of it unless a case says), so a report
    // is clamped by what was written only where the case means it to be.
    private fun streaming(
        key: String = KEY,
        offset: Long = 0,
        length: Long = MIB,
        written: Long = length,
    ) {
        meter.start(key)
        meter.sized(offset, length)
        meter.wrote(written.toInt())
    }

    @Test
    fun beforeAnyReportTheEtaIsThePacesAndTheDeadlineWaitsOutTheFirstReport() {
        streaming()
        val early = meter.feed(left = MIB / 2, wroteAt = 0, pace = 4_096, settled = true)
        assertEquals("half a MiB left at 4 KiB/s", 128_000L, early.etaMs)
        assertFalse(early.measured)
        assertTrue(early.live)

        // Settled, but a first report may still come: no deadline from the pace until the stall window has passed.
        t = FeedMeter.STALL_MS
        meter.feed(left = MIB / 2, wroteAt = t, pace = 4_096, settled = true) // deadline: 128 s × 5/4 + 30 s from now
        t = FeedMeter.STALL_MS + 190_000
        assertTrue("inside the deadline", meter.feed(left = MIB / 2, wroteAt = t, pace = 4_096, settled = true).live)
        t += 1
        assertFalse("past it", meter.feed(left = MIB / 2, wroteAt = t, pace = 4_096, settled = true).live)
    }

    @Test
    fun aStreamWithNoReportYetStallsWhenItsWritesStop() {
        streaming()
        t = FeedMeter.STALL_MS
        assertTrue(meter.feed(left = MIB, wroteAt = 0, pace = 4_096, settled = false).live)
        t = FeedMeter.STALL_MS + 1
        assertFalse("no write went through for the stall window", meter.feed(left = MIB, wroteAt = 0, pace = 4_096, settled = false).live)
    }

    @Test
    fun reportsMeasureTheRateByTheReceiversClockAndSetTheDeadlineOnce() {
        streaming(offset = 1_000, length = 1_000_000)
        meter.paced(200_000)
        assertFalse("another key's report is stale", meter.report("other", 500_000, 1_000))
        t = 9_999 // the report waited on the way back: the rate is still by the receiver's clock
        assertTrue(meter.report(KEY, 101_000, 1_000)) // 100 kB in the receiver's first second
        val f = meter.feed(left = 0, wroteAt = t, pace = 200_000, settled = true)
        assertTrue(f.measured)
        assertEquals("900 kB left at 100 kB/s", 9_000L, f.etaMs)
        t += 1_000
        assertEquals("counted from the report: it drained since", 8_000L, meter.feed(0, t, 200_000, true).etaMs)
        t -= 1_000

        // The deadline was set at the first measurement: 9 s × 5/4 + 30 s past t = 9 999.
        t = 9_999 + 11_250 + FeedMeter.DEADLINE_SLACK_MS
        meter.report(KEY, 900_000, 6_000)
        assertTrue(meter.feed(left = 0, wroteAt = t, pace = 200_000, settled = true).live)
        t += 1
        meter.report(KEY, 950_000, 7_000)
        assertFalse("past the deadline, moving or not", meter.feed(left = 0, wroteAt = t, pace = 200_000, settled = true).live)

        // Two reports after the pace was set, the third's window ran at one pace: 50 kB/s of 200 kB/s.
        meter.end() // it drained well under its pace: the link keeps the shortfall
        assertEquals(0.25, meter.drainRatio, 1e-9)
        meter.forgetDrain() // a PHY step
        assertEquals(1.0, meter.drainRatio, 0.0)
    }

    @Test
    fun aDeadlineMovesBackWhenTheSendersOwnSplitCutsThePace() {
        streaming(length = 1_000_000)
        meter.report(KEY, 100_000, 1_000) // 100 kB/s, at a pace of 100 kB/s
        meter.feed(left = 0, wroteAt = t, pace = 100_000, settled = true) // deadline: 9 s × 5/4 + 30 s = 41.25 s
        t = 30_000
        meter.report(KEY, 150_000, 31_000)
        // A second feed started on another link: the pace halved, and the rest now takes 850 kB / 50 kB/s = 17 s at best.
        meter.feed(left = 0, wroteAt = t, pace = 50_000, settled = true)
        t = 30_000 + 17_000 * 5 / 4 + FeedMeter.DEADLINE_SLACK_MS
        meter.report(KEY, 500_000, 70_000) // still moving
        assertTrue("the deadline moved back with the pace", meter.feed(left = 0, wroteAt = t, pace = 50_000, settled = true).live)
    }

    @Test
    fun timeTheWriterSleptThroughItsOwnPaceWaitIsNeitherAStallNorLate() {
        streaming(length = 100_000)
        meter.report(KEY, 10_000, 1_000)
        meter.feed(left = 0, wroteAt = 0, pace = 10_000, settled = true) // deadline: 9 s × 5/4 + 30 s = 41.25 s
        meter.pausing(2_000)
        t = 60_000 // the CPU slept through the writer's 2 s pace wait
        assertTrue("still asleep in its own wait", meter.feed(0, 0, 10_000, true).live)
        meter.resumed()
        assertTrue("woken, the sleep counts for neither", meter.feed(0, 0, 10_000, true).live)
        t += FeedMeter.STALL_MS - 2_000
        assertTrue(meter.feed(0, 0, 10_000, true).live)
        t += 1
        assertFalse("15 s of the writer awake with no report moving", meter.feed(0, 0, 10_000, true).live)
    }

    @Test
    fun aReceiverThatStopsReadingStallsWhileTheWriterPacesNormally() {
        streaming()
        meter.report(KEY, 100_000, 1_000)
        // 2 s chunk cycles: almost all of each in the writer's own wait, none of it slept.
        repeat(8) {
            meter.pausing(1_990)
            t += 1_990
            meter.resumed()
            t += 10
        }
        assertFalse("no report moved for 16 s", meter.feed(0, t, 4_096, true).live)
    }

    @Test
    fun aShortfallIsReadOnlyFromReportsAtOnePaceWithTheWriterAwake() {
        streaming(length = 10_000_000)
        meter.paced(10_000)
        var held = 0L
        var ms = 0L

        fun report(rate: Int) {
            held += rate * 2L
            ms += 2_000
            meter.report(KEY, held, ms)
        }
        repeat(4) { report(10_000) } // kept up with 10 kB/s
        meter.paced(20_000) // another feed ended: the pace doubled, the next reports' windows still carry the old one
        report(12_000)
        report(15_000)
        meter.pausing(2_000)
        t += 30_000
        meter.resumed() // a sleep: the next two reports' windows hold it
        report(3_000)
        report(19_000)
        report(20_000)
        meter.end()
        assertEquals("no window read at one pace fell short", 1.0, meter.drainRatio, 0.0)
    }

    @Test
    fun aStreamIsJudgedByItsWritesUntilItsFirstReportThenByItsReports() {
        streaming()
        meter.report(KEY, 100_000, 1_000)
        t = FeedMeter.STALL_MS
        meter.report(KEY, 100_000, 3_000) // a report, but nothing more held
        t = FeedMeter.STALL_MS + 1
        assertFalse("its writes may go on into the stack: only the report counts", meter.feed(0, t, 4_096, true).live)

        meter.end()
        streaming(key = "next") // queued behind the last one's tail, it cannot be reported yet: its writes count
        t += FeedMeter.STALL_MS + 1
        assertTrue("still writing", meter.feed(MIB, t, 4_096, false).live)
        assertFalse("not writing either", meter.feed(MIB, t - FeedMeter.STALL_MS - 1, 4_096, false).live)
    }

    @Test
    fun aRateMeasuredBeforeTheBoostIsEarnedYieldsToTheSettlingPaceAndToANewPace() {
        streaming(length = 2_000_000)
        meter.paced(2_000)
        meter.report(KEY, 4_000, 2_000) // 2 kB/s: the base, before the link earned
        assertEquals("unsettled: the settling guess stands", 99_800L, meter.feed(0, t, 20_000, settled = false).etaMs)
        assertEquals("settled: the measurement does", 998_000L, meter.feed(0, t, 2_000, settled = true).etaMs)
        meter.paced(20_000) // earned: the old rate says nothing of the new pace
        assertEquals("the new pace until a report measures it", 99_800L, meter.feed(0, t, 20_000, settled = true).etaMs)
        meter.report(KEY, 34_000, 4_000) // its window spans the earn: it only rebases
        assertFalse(meter.feed(0, t, 20_000, settled = true).measured)
        meter.report(KEY, 74_000, 6_000) // 20 kB/s, all at the new pace
        assertEquals(96_300L, meter.feed(0, t, 20_000, settled = true).etaMs)
    }

    @Test
    fun aMeterSamplesAboutEveryTwoSecondsHoweverOftenTheReceiverReports() {
        // ADR 2026-10.5buf: a windowed sender's receiver reports on every chunk, and a reader catching up a burst reports
        // bytes ms apart. The meter's settle, smoothing and first-rate deadline count samples tuned to two-second reports,
        // so a report sooner than that after the last sample takes none.
        val meter = FeedMeter({ t }, ceiling = Int.MAX_VALUE)
        meter.start(KEY)
        meter.sized(0, MIB)
        meter.wrote(MIB.toInt())
        meter.paced(8_192)
        meter.report(KEY, 4_096, 250)
        meter.report(KEY, 1_000_000, 253) // a burst: 2.7 GB/s for 3 ms
        assertFalse("no sample yet", meter.measured)
        meter.report(KEY, 1_016_384, FeedMeter.MIN_SAMPLE_MS)
        assertTrue(meter.measured)
        assertEquals("one sample over two seconds", (MIB - 1_016_384) * 1_000 / 508_192, meter.feed(0, t, 8_192, settled = true).etaMs)
    }

    @Test
    fun aReportOfMoreThanEverWasWrittenReadsAsWhatWasAndStillCounts() {
        // #158: a `held` past the stream once read the feed as about to end. Clamped to the writer's own bytes, and never
        // refused: the owner feeds the window only from a report the meter took.
        streaming(length = 1_000_000, written = 10_000)
        assertTrue("clamped, not refused", meter.report(KEY, 50_000, 1_000))
        assertEquals("990 kB left at the 10 kB/s written, not 950 kB at 50", 99_000L, meter.feed(0, t, 4_096, true).etaMs)
    }

    @Test
    fun aHeldNearLongMaxLeavesTheRateAtTheWritersAndTheEtaAboveZero() {
        // #158: `(held - fromHeld) * 1000` overflowed, and any `held` past the length left nothing to deliver: ETA 0.
        streaming(length = 1_000_000, written = 20_000)
        meter.report(KEY, 20_000, 1_000) // honest: 20 kB/s
        assertEquals(49_000L, meter.feed(0, t, 4_096, true).etaMs)
        meter.wrote(20_000) // the writer keeps its rate; the receiver claims everything
        meter.report(KEY, Long.MAX_VALUE, 2_000)
        val f = meter.feed(0, t, 4_096, true)
        assertTrue(f.live)
        assertEquals("960 kB left, still at 20 kB/s", 48_000L, f.etaMs)
    }

    @Test
    fun aHeldBelowTheOffsetReadsAsTheOffset() {
        streaming(offset = 1_000, length = 100_000)
        assertTrue(meter.report(KEY, 0, 1_000))
        assertEquals("nothing delivered: 100 kB at the pace, not 101", 25_000L, meter.feed(0, t, 4_000, true).etaMs)
    }

    @Test
    fun aSilentReceiverClaimingTheWholeStreamInOneReportSetsNoRatePastTheCeiling() {
        // #158's design pass: `ms` is the peer's clock, so a clamp to what was written alone does not bound the rate. A
        // receiver silent through the feed, then "all of it, two seconds in", read a MiB at 512 KiB/s into [lastRate].
        val meter = FeedMeter({ t })
        meter.start(KEY)
        meter.sized(0, MIB)
        meter.wrote(MIB.toInt())
        t = 60_000
        assertTrue(meter.report(KEY, MIB, FeedMeter.MIN_SAMPLE_MS))
        meter.end()
        assertTrue("lastRate ${meter.lastRate}", meter.lastRate in 1..FeedWindow.CEILING_BYTES_PER_SEC)
    }

    private companion object {
        const val KEY = "k"
        const val MIB = 1L shl 20
    }
}
