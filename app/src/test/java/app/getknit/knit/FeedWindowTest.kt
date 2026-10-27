package app.getknit.knit

import app.getknit.knit.mesh.link.FeedWindow
import app.getknit.knit.mesh.link.PaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 2026-10.nsr7's phase 2: a feed clocked by its receiver's reports keeps about [FeedWindow.QUEUE_MS] of the drain
 * queued, on any link speed, and finds a faster link by itself. The simulated link drains at a set rate from one queue
 * (the stack's and the air's), and its receiver reports as `RxReports` does for a windowed sender.
 */
@Suppress("LargeClass") // one window's whole contract over one shared Sim harness: it grows by a test a finding, by design
class FeedWindowTest {
    /**
     * One simulated stream: the sender follows the window, the air drains [drain] B/s, and the receiver reports as
     * `RxReports` does for a windowed sender — on the chunk that brings 2 KiB since its last report, or [reportEveryMs]
     * after it once anything more landed — each report reaching the sender [lagMs] later (the reverse air, which a slow
     * connection interval stretches).
     *
     * With [records], the receiver holds a chunk only once all of it has crossed, as its reader takes whole records — on
     * Coded's slow air a chunk takes a second to cross, so its reports come by the record. A [darkReceiver]'s CPU sleeps
     * between records: its trailing report never fires, and it reports only on the record its reader is awake for.
     * With a [fallback], a lit writer the window takes off its window ([FeedWindow.pacing]) writes the pace's chunks at
     * the pace's spacing, as `FramedLink` does.
     */
    private class Sim(
        var drain: Int,
        var lagMs: Long = 100,
        private val reportEveryMs: Long = 500,
        // A dark sender: its CPU sleeps, so its waits never fire and it writes only when a report wakes it.
        private val asleep: Boolean = false,
        minChunk: Int = FeedWindow.MIN_CHUNK_BYTES,
        private val records: Boolean = false,
        private val darkReceiver: Boolean = false,
        var fallback: PaceConfig? = null,
    ) {
        val window =
            FeedWindow().apply {
                this.minChunk = minChunk
                start(offset = 0, now = 0)
            }
        var t = 0L
        var queue = 0L // written and not yet across the air
        var held = 0L // what the receiver holds
        private var crossed = 0L
        private val chunkEnds = ArrayDeque<Long>()
        private var written = 0L
        private var pacedAt = 0L
        var maxQueueMs = 0L // the deepest queue, in ms of the drain, since [measureFrom]
        var everPaced = false // whether the window fell back to the pace since [measureFrom]: a report owed and not sent
        private var measureFrom = 0L
        private val inFlightReports = ArrayDeque<Triple<Long, Long, Long>>() // (arrives, held, ms)
        private var reportedAt = 0L
        private var reportedHeld = 0L
        private var waitUntil = 0L

        // The writer's turn: its wait has run out, and a dark one only at the instant a report woke it (or at the start).
        private fun writerAwake(): Boolean = t >= waitUntil && (!asleep || t == waitUntil || t == 0L)

        fun measureFrom(at: Long) {
            measureFrom = at
            maxQueueMs = 0
            everPaced = false
        }

        fun run(untilMs: Long) {
            while (t < untilMs) {
                deliverReports()
                writerTurn()
                airAndReceiver()
                if (t >= measureFrom) maxQueueMs = maxOf(maxQueueMs, queue * 1000 / drain)
                everPaced = everPaced || (t >= measureFrom && window.pacing(t))
                t += STEP_MS
            }
        }

        private fun deliverReports() {
            while (inFlightReports.firstOrNull()?.first?.let { it <= t } == true) {
                val (_, h, ms) = inFlightReports.removeFirst()
                window.report(h, ms, t)
                waitUntil = t // a report wakes the writer
            }
        }

        private fun writerTurn() {
            val pace = fallback
            if (pace != null && window.pacing(t)) {
                if (!asleep && t >= pacedAt) {
                    val chunk = window.chunkFor(false, pace)
                    write(chunk)
                    pacedAt = t + chunk * 1000L / pace.bytesPerSec
                }
                return
            }
            while (writerAwake()) {
                val wait = window.waitMs()
                if (wait > 0) {
                    waitUntil = if (asleep) Long.MAX_VALUE else t + wait
                    return
                }
                write(window.chunkBytes())
            }
        }

        private fun write(chunk: Int) {
            window.wrote(chunk, t)
            queue += chunk
            written += chunk
            chunkEnds.addLast(written)
        }

        // One step of the air, and the receiver's report on what it now holds.
        private fun airAndReceiver() {
            val drained = minOf(queue, drain.toLong() * STEP_MS / 1000)
            queue -= drained
            crossed += drained
            val heldBefore = held
            if (records) {
                while (chunkEnds.firstOrNull()?.let { it <= crossed } == true) held = chunkEnds.removeFirst()
            } else {
                held = crossed
            }
            // A dark receiver is awake only for a record its reader takes; a lit one's trailing report fires anyway.
            val awake = !darkReceiver || held > heldBefore
            val grown = held - reportedHeld
            val due = awake && t - reportedAt >= reportEveryMs
            if (grown >= CHUNK_REPORT_BYTES || (grown > 0 && due)) {
                inFlightReports.addLast(Triple(t + lagMs, held, t))
                reportedHeld = held
                reportedAt = t
            }
        }

        /** What the receiver got per second over (from, now]. */
        fun throughputSince(
            from: Long,
            heldThen: Long,
        ): Long = (held - heldThen) * 1000 / (t - from)
    }

    @Test
    fun aSteadyDrainIsFoundAndTheQueueStaysNearHalfASecondOfIt() {
        val sim = Sim(drain = 20_000)
        sim.run(5_000)
        sim.measureFrom(5_000)
        val heldAt5 = sim.held
        sim.run(15_000)
        assertEquals("the rate is the drain", 20_000.0, sim.window.rate.toDouble(), 2_000.0)
        val throughput = (sim.held - heldAt5) / 10
        assertTrue("the air is kept busy: $throughput B/s of 20000", throughput >= 19_000)
        assertTrue("a frame behind the file waits under a second: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_000)
    }

    @Test
    fun aDarkSenderThatWritesOnlyWhenAReportWakesItStillKeepsTheAirBusy() {
        // Device rep 2026-10-07 P9→P8, both dark: a window of half a second of the rate, refilled once a report, capped
        // the feed at window ÷ gap and shrank it to its floor. Covering the gap keeps the link's drain.
        val sim = Sim(drain = 12_000, asleep = true)
        sim.run(8_000)
        val heldAt8 = sim.held
        sim.measureFrom(8_000)
        sim.run(20_000)
        val throughput = (sim.held - heldAt8) / 12
        assertTrue("the air is kept busy: $throughput B/s of 12000", throughput >= 10_800)
        assertTrue("and a frame waits under about a second and a half: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_500)
    }

    @Test
    fun aLongRoundTripIsCoveredByGrowingTheWindowNotByTheRateAlone() {
        // Device sweep 2026-10-07 (W3 rep 2): a dark pair at a 255 ms interval, reports a second in coming back; a window
        // sized from a rate measured through it sat at its floor, 3–4.5 KB/s on a link that drained 6.
        val sim = Sim(drain = 6_000, lagMs = 900, asleep = true)
        sim.run(10_000)
        val heldAt10 = sim.held
        sim.run(30_000)
        val throughput = (sim.held - heldAt10) / 20
        // Less the round-trip probe's dip every ten seconds: a round at one chunk, about 12% at this round trip.
        assertTrue("the air is kept busy: $throughput B/s of 6000", throughput >= 5_000)
    }

    @Test
    fun aRoundTripThatRisesMidFileIsFollowedUp() {
        // W3 rep 1 on the device: the link's interval stepped from 15 ms to 255 ms three seconds into the file, and the
        // reports' way back with it. The round trip's estimate rises at its next probe, and the window with it.
        val sim = Sim(drain = 10_000, lagMs = 50, asleep = true)
        sim.run(6_000)
        sim.lagMs = 900
        sim.run(12_000)
        val heldAt12 = sim.held
        sim.measureFrom(12_000)
        sim.run(30_000)
        val throughput = sim.throughputSince(12_000, heldAt12)
        assertTrue("the air is kept busy, less the probe's dip: $throughput B/s of 10000", throughput >= 8_000)
        assertTrue("and the queue stays short: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_500)
    }

    @Test
    fun aLitSenderToAReceiverWhoseReportsComeBackSlowlyGetsTheLink() {
        // Device sweep 4 (2026-10-07): the P9 lit, the P8 dark. A window covering only the gap between reports got half
        // the link (2.9 and 4.0 KB/s on a link that drained 5.3 and 8.8); covering the round trip, it gets the drain.
        for ((drain, lag) in listOf(10_000 to 900L, 20_000 to 900L, 6_000 to 900L)) {
            val sim = Sim(drain = drain, lagMs = lag)
            sim.run(10_000)
            val heldAt10 = sim.held
            sim.measureFrom(10_000)
            sim.run(40_000)
            val throughput = sim.throughputSince(10_000, heldAt10)
            assertTrue("$drain B/s, $lag ms back: $throughput B/s", throughput >= drain * 85 / 100)
            // The round trip's own bytes sit in the stack too, at the slow end of each report's way back.
            assertTrue("$drain B/s, $lag ms back: the queue stays short (${sim.maxQueueMs} ms)", sim.maxQueueMs <= 2_000)
        }
    }

    @Test
    fun aDipMidFileCostsOnlyTheDip() {
        // A second feed starting, A2DP, a missed connection event: the drain halves for two seconds and comes back.
        val sim = Sim(drain = 6_000, lagMs = 900, asleep = true)
        sim.run(10_000)
        sim.drain = 3_000
        sim.run(12_000)
        sim.drain = 6_000
        sim.run(20_000)
        val heldAt20 = sim.held
        sim.run(40_000)
        val throughput = sim.throughputSince(20_000, heldAt20)
        assertTrue("back to the drain after the dip: $throughput B/s of 6000", throughput >= 5_400)
    }

    @Test
    fun aSlowLinkKeepsItsQueueShortWhereAFixedPaceWouldFillIt() {
        // 8jwn's slowest measured 1M drain: 28 KiB/s held a text minutes behind a photo on it.
        val sim = Sim(drain = 4_800)
        sim.run(5_000)
        sim.measureFrom(5_000)
        sim.run(20_000)
        // The starting 8 KiB stays the stream's floor: about 1.7 s of this drain, plus a chunk.
        assertTrue("the queue stays within a couple of seconds: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_500)
        assertTrue("and the air stays busy", sim.window.rate >= 4_300)
    }

    @Test
    fun aFastLinkIsFoundFromTheSmallStartingWindow() {
        // The dark pair of the A/B's T8 cell drained a 341 KB photo in about 12 s: a fixed 4 KiB/s base took 111 s.
        val sim = Sim(drain = 40_000)
        sim.run(4_000)
        assertTrue("found within four seconds: ${sim.window.rate}", sim.window.rate >= 36_000)
        sim.measureFrom(4_000)
        sim.run(12_000)
        assertTrue("341 KB crosses in about 9 s", sim.held >= 341_000)
        assertTrue("still within a second: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 1_000)
    }

    @Test
    fun aDrainThatFallsIsFollowedWithinAFewReports() {
        val sim = Sim(drain = 25_000)
        sim.run(8_000)
        sim.drain = 6_000 // a second link starts feeding, or the boost lets go
        // What was queued for the faster drain, three-quarters of a second of it, is about three seconds of the slower
        // one: it drains, and the window follows the drain down meanwhile.
        sim.run(13_000)
        sim.measureFrom(13_000)
        sim.run(22_000)
        assertTrue("followed down: ${sim.window.rate}", sim.window.rate <= 7_000)
        assertTrue("its queue back within about the floor of the slower drain: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_000)
    }

    @Test
    fun aLateReturnLeansSlowNeverFast() {
        // A report queued behind the receiver's own file: its lag overstates the queue, so the feed holds back.
        val sim = Sim(drain = 20_000, lagMs = 900)
        sim.run(5_000)
        sim.measureFrom(5_000)
        sim.run(15_000)
        // The report's way back is part of the round trip the window covers: at 900 ms the queue runs a little longer.
        assertTrue("about a second and a half: ${sim.maxQueueMs} ms", sim.maxQueueMs <= 2_000)
        assertTrue("and still most of the drain", sim.window.rate >= 15_000)
    }

    @Test
    fun beforeTheFirstReportOnlyTheStartingWindowGoes() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        assertEquals(0L, w.waitMs())
        w.wrote(4_096, 0)
        assertEquals("the starting window is full: wait for a report", FeedWindow.REPORT_WAIT_MS, w.waitMs())
        assertFalse(w.pacing(FeedWindow.NO_REPORT_MS - 1))
        assertTrue("a receiver silent this long gets the old pace", w.pacing(FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun theWritersOwnSleepCountsTowardTheSilenceOnlyWhenNothingWasOwed() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192, 0)
        w.slept(ms = 10_000, now = 10_100)
        assertTrue("a window out: the receiver owes a report, so its silence counts", w.pacing(10_100))
        w.report(held = 8_192, ms = 500, now = 13_200)
        w.slept(ms = 10_000, now = 23_300)
        assertFalse("nothing out: nothing was owed, so the sleep does not count", w.pacing(23_300))
        assertTrue(w.pacing(23_200 + FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun aReceiverThatReportsEveryTwoSecondsIsPacedForTheRestOfTheStream() {
        // A build or a switch that reports at nsr7's paced cadence never reports the starting window: windowed against
        // it, the feed ran in three-second fits below the paced base (the bluetooth-reviewer's finding). Its reports
        // each cover several chunks; two such, two seconds apart, and the stream is paced.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        assertFalse(w.pacing(4_100))
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        assertTrue("two two-second gaps of several chunks: paced", w.pacing(6_100))
        w.report(held = 12_288, ms = 6_100, now = 6_200)
        assertTrue("for the rest of the stream", w.pacing(6_200))
        w.start(offset = 0, now = 7_000)
        assertFalse("a new stream asks again", w.pacing(7_000))
    }

    @Test
    fun aDozingReceiverWokenMidFileGetsItsWindowBack() {
        // The D2 run of 2026-10-08: a dozing P9's reports came back late and several chunks at a time, so the feed was
        // taken for a two-second reporter and paced at 4 KiB/s to its end. Woken, it reports each small paced chunk
        // inside the gap, and three in a row give the window back.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        assertTrue(w.pacing(6_100))
        assertEquals("paced in the smallest chunks", FeedWindow.MIN_CHUNK_BYTES, w.chunkFor(false, BASE_PACE))
        repeat(4) { w.wrote(2_048, 6_200 + it * 500L) }
        w.report(held = 14_336, ms = 6_500, now = 6_600)
        w.report(held = 16_384, ms = 7_000, now = 7_100)
        assertTrue("two on time are not yet three", w.pacing(7_100))
        w.report(held = 18_432, ms = 7_500, now = 7_600)
        assertFalse("three chunks answered on time: windowed again", w.pacing(7_600))
        assertEquals("and its chunks are the pace's own again", 8_192, w.pacedChunkBytes(8_192))
    }

    @Test
    fun aDozingReceiverWorkingThroughItsBacklogDoesNotWinTheWindowBack() {
        // Still asleep, it reads a backlog of paced chunks at once and reports each milliseconds apart: one chunk a
        // report, inside the gap, but sooner than the pace could have delivered them (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        w.chunkFor(false, BASE_PACE) // paced: 2 KiB every half second, so a report under a quarter second is a burst
        repeat(4) { w.wrote(2_048, 6_200) }
        w.report(held = 14_336, ms = 8_000, now = 8_100)
        w.report(held = 16_384, ms = 8_005, now = 8_100)
        w.report(held = 18_432, ms = 8_010, now = 8_100)
        w.report(held = 20_480, ms = 8_015, now = 8_100)
        assertTrue("a backlog read at once: still paced", w.pacing(8_100))
    }

    @Test
    fun theWindowsChunksStillOutWhenTheFeedIsPacedAreNoAnswer() {
        // The latch trips with a window of chunks out, before any paced chunk sets the burst floor. A dozing receiver's
        // reports for those, read at once, are a backlog, not a receiver awake (the bluetooth-reviewer's second pass).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(10) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        assertTrue(w.pacing(6_100))
        w.report(held = 14_336, ms = 6_001, now = 6_100)
        w.report(held = 16_384, ms = 6_002, now = 6_100)
        w.report(held = 18_432, ms = 6_003, now = 6_100)
        w.report(held = 20_480, ms = 6_004, now = 6_100)
        assertTrue("the window's own chunks, read at once: still paced", w.pacing(6_100))
    }

    @Test
    fun aReportThatProvesNoNewChunkIsNotOnTime() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        w.report(held = 12_288, ms = 6_500, now = 6_600)
        w.report(held = 12_288, ms = 7_000, now = 7_100)
        w.report(held = 12_288, ms = 7_500, now = 7_600)
        assertTrue("three reports holding nothing new: still paced", w.pacing(7_600))
    }

    @Test
    fun aReceiverRepeatingOneHeldIsPacedOnceTheSilenceRunsFromItsLastChunk() {
        // #149: a report repeating its `held` every two seconds kept the silence fresh, the window shut and the feed
        // waiting for the link's life. Only a report that lands a chunk is heard from the receiver.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(4) { w.wrote(2_048, 0) }
        w.report(held = 2_048, ms = 500, now = 600)
        assertFalse("not before the silence from its last chunk", w.pacing(600 + FeedWindow.NO_REPORT_MS - 1))
        for (i in 1..6) {
            val now = 600 + i * 2_000L
            w.report(held = 2_048, ms = 500 + i * 2_000L, now = now)
            if (now - 600 >= FeedWindow.NO_REPORT_MS) assertTrue("a repeat at $now is not heard", w.pacing(now))
        }
    }

    @Test
    fun aSubChunkTrickleIsNotHeardEither() {
        // Option 1 of #149 made stricter: a byte more each report, under the silence apart, is not a chunk landed.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(4) { w.wrote(2_048, 0) }
        w.report(held = 2_048, ms = 500, now = 600)
        for (i in 1..4) {
            val now = 600 + i * 2_900L
            w.report(held = 2_048L + i, ms = 500 + i * 2_900L, now = now)
            if (now - 600 >= FeedWindow.NO_REPORT_MS) assertTrue("a trickle at $now is not heard", w.pacing(now))
        }
    }

    @Test
    fun aRepeatWhileTheQueueStandsKeepsTheDrain() {
        // A standing queue resets the drain's filter to the report's sample: a repeat's is about nothing, and it took
        // the window to its start. Only a report that lands a chunk is the air's measure.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(20) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 500, now = 600)
        val rate = w.rate
        val target = w.target()
        assertTrue("the queue stands", w.queued() > 2 * target)
        w.report(held = 4_096, ms = 1_000, now = 1_100)
        assertEquals("the drain is kept", rate, w.rate)
        assertEquals("and so is the window", target, w.target())
    }

    @Test
    fun aReportThatLandsAChunkGivesTheWindowBack() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(4) { w.wrote(2_048, 0) }
        w.report(held = 2_048, ms = 500, now = 600)
        w.report(held = 2_048, ms = 2_500, now = 2_600)
        w.report(held = 2_048, ms = 4_500, now = 4_600)
        assertTrue("repeats only: paced", w.pacing(4_600))
        w.report(held = 4_096, ms = 5_000, now = 5_100)
        assertFalse("a chunk landed: windowed again", w.pacing(5_100))
    }

    @Test
    fun aTwoSecondReporterStaysPacedInSmallChunks() {
        // Two seconds apart and several small chunks each, its reports never come on time.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        var held = 0L
        for (i in 1..10) {
            repeat(4) { w.wrote(2_048, i * 2_000L - 1_500) }
            held += 4 * 2_048
            w.report(held = held, ms = i * 2_000L, now = i * 2_000L + 100)
        }
        assertTrue("still paced after ten reports", w.pacing(20_100))
    }

    @Test
    fun anOnTimeRunBrokenByALateReportStartsOver() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        repeat(6) { w.wrote(2_048, 6_200) }
        w.report(held = 14_336, ms = 6_500, now = 6_600)
        w.report(held = 16_384, ms = 7_000, now = 7_100)
        w.report(held = 20_480, ms = 9_000, now = 9_100) // a late catch-up of two chunks
        w.report(held = 22_528, ms = 9_500, now = 9_600)
        w.report(held = 24_576, ms = 10_000, now = 10_100)
        assertTrue("the run counts again from the late one", w.pacing(10_100))
    }

    @Test
    fun aWindowedReceiverOnASlowLinkIsNotTakenForATwoSecondReporter() {
        // Its reports come one per chunk, so on a slow link, or across a probe, they are two seconds apart too; read by
        // time alone that paced the slow link's window away (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(4) { w.wrote(2_048, 0) }
        w.report(held = 2_048, ms = 2_000, now = 2_100)
        w.report(held = 4_096, ms = 4_000, now = 4_100)
        w.report(held = 6_144, ms = 6_000, now = 6_100)
        w.report(held = 8_192, ms = 8_000, now = 8_100)
        assertFalse("one chunk a report: a windowed receiver", w.pacing(8_100))
    }

    @Test
    fun theRoundTripIsTheLinksAndOutlivesTheStreamUntilAPhyStep() {
        // A file fed in full leaves its tail in the stack's queue, and the next stream's first chunk waits behind it:
        // taken fresh each stream, the estimate grew with every file sent back to back (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 400, now = 400)
        assertEquals(400L, w.rttMs)
        w.start(offset = 0, now = 1_000)
        w.wrote(4_096, 1_000)
        w.report(held = 4_096, ms = 1_000, now = 2_200) // behind the last file's tail
        assertEquals("the link's round trip, not the queue the last file left", 400L, w.rttMs)
        val rate = w.rate
        w.forgetLink(drain = false) // 1M ↔ 2M
        assertEquals(FeedWindow.INITIAL_RTT_MS, w.rttMs)
        assertEquals("a step between 1M and 2M keeps the drain", rate, w.rate)
        assertFalse(w.probingRtt)
    }

    @Test
    fun aChunkBehindItsWindowIsNotReadAsALinkThatSlowed() {
        // The round trip carried over from a fast lit file is short; a slow dark pair's next file sits at its window's
        // floor, and a chunk at the back of it lands a whole window's drain later. Judged against the round trip alone
        // that read as a slowdown on every report and reset the drain's filter (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(2_048, 0)
        w.report(held = 2_048, ms = 60, now = 60) // a fast file: round trip 60 ms
        w.start(offset = 0, now = 1_000)
        w.wrote(2_048, 1_000)
        w.wrote(2_048, 1_000) // 2 KiB ahead of it
        w.report(held = 2_048, ms = 400, now = 1_400) // 5 KB/s
        val rate = w.rate
        w.report(held = 4_096, ms = 900, now = 1_900) // the second, 900 ms after its write: its 2 KiB ahead at 5 KB/s
        assertEquals("the larger sample stands: no slowdown", rate, w.rate)
    }

    @Test
    fun aSampleClampedToTheCeilingDoesNotRefreshTheEstimatesAge() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 5_000, now = 5_000) // 5 s: stored as the 3 s ceiling
        assertEquals(FeedWindow.MAX_RTT_MS, w.rttMs)
        w.wrote(4_096, 5_000)
        w.report(held = 8_192, ms = 15_100, now = 15_100) // 10.1 s: also the ceiling, and no smaller than the 5 s
        assertTrue("its age ran from the first: the probe is due", w.probingRtt)
    }

    @Test
    fun aProbeTakesTheRoundTripOfAChunkSentUnderItsOneChunkWindow() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 400, now = 400) // round trip 400 ms, stamped at 400
        w.wrote(8_192, 400)
        w.report(held = 6_144, ms = 11_000, now = 11_000) // nothing smaller for ten seconds: probe
        assertTrue(w.probingRtt)
        assertEquals("room for one smallest chunk", FeedWindow.MIN_CHUNK_BYTES + 1L, w.target())
        assertEquals("6 KiB still out: wait", FeedWindow.REPORT_WAIT_MS, w.waitMs())
        w.report(held = 12_288, ms = 11_400, now = 11_400)
        assertEquals(0L, w.waitMs())
        w.wrote(2_048, 11_400)
        w.report(held = 14_336, ms = 12_100, now = 12_100)
        assertFalse("its round trip ends the probe", w.probingRtt)
        assertEquals("and is the trip, though larger than the old one", 700L, w.rttMs)
    }

    @Test
    fun aReportOfMoreThanWasWrittenOrFasterThanTheCeilingProvesNoMore() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(65_536, 0)
        w.report(held = 10_000_000, ms = 500, now = 600)
        assertEquals("held to what was written", 0L, w.queued())
        assertEquals("and the rate to the ceiling", FeedWindow.CEILING_BYTES_PER_SEC, w.rate)
        assertEquals("the round trip, write to report", 600L, w.rttMs)
        assertEquals("the rate over the round trip and the queue", 65_536L * (600 + FeedWindow.QUEUE_MS) / 1000, w.target())
    }

    @Test
    fun aBurstReadBackToBackMovesTheQueueButTakesNoSample() {
        // A receiver's reader catching up 16 KiB reports 8 KiB twice, ms apart: that is no drain to measure.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(24_576, 0)
        w.report(held = 8_192, ms = 1_000, now = 1_000) // 8 KiB/s
        w.report(held = 16_384, ms = 1_003, now = 1_005)
        assertEquals("not 2.7 MB/s", 8_192, w.rate)
        assertEquals("but the queue knows what landed", 8_192L, w.queued())
        w.report(held = 20_480, ms = 1_500, now = 1_500) // measured from the last sample: 12 KiB over 500 ms
        assertEquals(24_576, w.rate)
    }

    @Test
    fun anOlderReportMovesNothing() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(16_384, 0)
        w.report(held = 8_192, ms = 1_000, now = 1_000)
        val rate = w.rate
        w.report(held = 4_096, ms = 500, now = 1_100) // reordered
        assertEquals(rate, w.rate)
        assertEquals(8_192L, w.queued())
    }

    @Test
    fun aResumedStreamCountsFromItsOffset() {
        // ADR 2026-10.wtyc: the receiver reports the whole length held, its kept prefix included.
        val w = FeedWindow().apply { start(offset = 100_000, now = 0) }
        w.wrote(8_192, 0)
        w.report(held = 104_096, ms = 500, now = 500)
        assertEquals("4 KiB in half a second", 8_192, w.rate)
        assertEquals(4_096L, w.queued())
    }

    @Test
    fun withNoReportNothingFreesTheWindowWhateverTheClock() {
        // No timer drains the estimate: a sender whose CPU sleeps is woken by the reports it waits on.
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192, 0)
        w.report(held = 4_096, ms = 500, now = 500)
        w.wrote(32_768, 500)
        assertEquals(36_864L, w.queued())
        assertEquals(FeedWindow.REPORT_WAIT_MS, w.waitMs())
    }

    @Test
    fun aQueuedChunksRoundTripCanOnlyLowerTheEstimate() {
        // A chunk that went out behind a standing queue waited the queue too: taken as the round trip, the window would
        // grow by the queue every round (the bluetooth-reviewer's port: a 3 s queue at 10 KB/s and up).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(4_096, 0)
        w.report(held = 4_096, ms = 400, now = 400) // 10 KB/s, round trip 400 ms
        assertEquals(400L, w.rttMs)
        w.wrote(16_384, 400) // well past the rate over the round trip: a queue stands ahead of the next chunk
        w.wrote(2_048, 400)
        w.report(held = 22_528, ms = 2_400, now = 2_400)
        assertEquals("a queued chunk's 2 s is not the air's round trip", 400L, w.rttMs)
    }

    @Test
    fun chunksCarryAQuarterSecondOfTheRateWithinTheirBounds() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        assertEquals(FeedWindow.INITIAL_CHUNK_BYTES, w.chunkBytes())
        w.wrote(8_192, 0)
        w.report(held = 2_000, ms = 500, now = 500) // 4 KB/s
        assertEquals(FeedWindow.MIN_CHUNK_BYTES, w.chunkBytes())
        w.wrote(32_768, 500)
        w.report(held = 40_960, ms = 1_000, now = 1_000) // 77 KB/s, held to the 64 KiB/s ceiling
        assertEquals(16 * 1024, w.chunkBytes())
    }

    @Test
    fun onCodedTheWindowRunsAtTheDrainWithAShortQueue() {
        // ADR 2026-10.5buf's Coded amendment: S=8 drained about 1.1 KB/s at the edge of the 2026-10-02 walk and about
        // 2.7 at close range (yvn6's 22 kbps). Coded's fixed 1 KiB/s held a photo to a third of the near drain, and fed
        // a drain under it faster than it went: the queue grew for the whole file. Its chunks are records the receiver
        // takes whole, a second each at the edge, and a dark receiver reports only on the one its reader wakes for.
        for (drain in listOf(400, 800, 1_100, 2_700)) {
            for (senderDark in listOf(false, true)) {
                val sim =
                    Sim(
                        drain = drain,
                        lagMs = 150,
                        asleep = senderDark,
                        minChunk = CODED_CHUNK,
                        records = true,
                        darkReceiver = true,
                        fallback = CODED_PACE,
                    )
                sim.run(15_000)
                val heldAt15 = sim.held
                sim.measureFrom(15_000)
                sim.run(60_000)
                val throughput = sim.throughputSince(15_000, heldAt15)
                val what = "$drain B/s, sender ${if (senderDark) "dark" else "lit"}"
                assertTrue("$what: the air is kept busy ($throughput B/s)", throughput >= drain * 95 / 100)
                // About two Coded chunks and the round trip's share of a third: under four seconds at 800 B/s.
                val bound = 3L * CODED_CHUNK * 1000 / drain
                assertTrue("$what: the queue stays short (${sim.maxQueueMs} ms of $bound)", sim.maxQueueMs <= bound)
                assertFalse("$what: every report owed came, however slow the chunk", sim.everPaced)
            }
        }
    }

    @Test
    fun codedsSmallerChunksKeepItsQueueShorterThanThe1mFloorWould() {
        // The 1M floor's 2 KiB chunks, four of them before the first report, held a frame about 3.5 s at the edge and
        // 7.4 s at a file's start; Coded's 1 KiB chunks halve both.
        fun maxQueue(
            minChunk: Int,
            fromMs: Long,
            untilMs: Long,
        ): Long {
            val sim = Sim(drain = 1_100, lagMs = 150, minChunk = minChunk, records = true, darkReceiver = true)
            sim.run(fromMs)
            sim.measureFrom(fromMs)
            sim.run(untilMs)
            return sim.maxQueueMs
        }
        val codedStart = maxQueue(CODED_CHUNK, 0, 10_000)
        val oneMStart = maxQueue(FeedWindow.MIN_CHUNK_BYTES, 0, 10_000)
        assertTrue("a file's start: $codedStart ms against $oneMStart", codedStart <= 4_000 && codedStart * 3 / 2 < oneMStart)
        val codedSteady = maxQueue(CODED_CHUNK, 15_000, 60_000)
        val oneMSteady = maxQueue(FeedWindow.MIN_CHUNK_BYTES, 15_000, 60_000)
        assertTrue("its steady queue: $codedSteady ms against $oneMSteady", codedSteady <= 3_000 && codedSteady < oneMSteady - 500)
    }

    @Test
    fun aStepToCodedMidFileStartsTheWindowOverAtTheSlowerAir() {
        // The transport forgets a link's drain at a step to or from Coded (`FramedLink.forgetDrain`). The window's filter
        // keeps the largest of its samples, so a 1M rate kept past the step sized a window for 1M over Coded's longer
        // round trip: a queue of tens of seconds of the slower air, still there long after. Forgotten, the window
        // probes, so its round trip reads the new air and not the backlog 1M left, which drains at Coded's rate anyway.
        fun afterStep(
            from: Int,
            forget: Boolean,
        ): Sim {
            val sim = Sim(drain = from, lagMs = 100, records = true, darkReceiver = true, fallback = BASE_PACE)
            sim.run(10_000)
            sim.drain = 1_100
            sim.fallback = CODED_PACE // the link's pace, read at the next chunk
            sim.window.minChunk = CODED_CHUNK
            if (forget) sim.window.forgetLink(drain = true)
            sim.measureFrom(10_000)
            return sim
        }
        for (from in listOf(8_000, 20_000)) {
            val sim = afterStep(from, forget = true)
            sim.run(30_000)
            val stepping = sim.maxQueueMs
            // 1M's chunks, 5 KB at 20 KB/s, take 4.6 s each to cross Coded: no silence while they are out, and the probe
            // ends on a chunk the window wrote, not on one the fallback slipped in behind them.
            assertFalse("from $from B/s, no chunk of the backlog was taken for silence", sim.everPaced)
            assertTrue("from $from B/s, the new air's round trip: ${sim.window.rttMs} ms", sim.window.rttMs < 2_500)
            sim.measureFrom(30_000)
            sim.run(60_000)
            val settled = sim.maxQueueMs
            val keeper = afterStep(from, forget = false)
            keeper.run(30_000)
            keeper.measureFrom(30_000)
            keeper.run(60_000)
            val kept = keeper.maxQueueMs
            // What 1M left in the stack drains at Coded's pace whatever the window does; the window adds little to it.
            assertTrue("from $from B/s, across the step: $stepping ms", stepping <= 12_000)
            assertTrue("from $from B/s, settled at Coded's own queue: $settled ms", settled <= 3_000)
            assertTrue("from $from B/s, the drain kept past the step holds a long queue: $kept ms", kept > 2 * settled)
        }
    }

    @Test
    fun aStepToCodedProbesUntilAChunkWrittenAfterItComesBack() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192, 0)
        w.report(held = 8_192, ms = 400, now = 400) // 20 KB/s, round trip 400 ms
        w.wrote(8_192, 400)
        w.forgetLink(drain = true)
        assertEquals("the drain goes", 0, w.rate)
        assertTrue("and the window probes", w.probingRtt)
        w.minChunk = CODED_CHUNK
        assertEquals("one smallest chunk's room", CODED_CHUNK + 1L, w.target())
        w.report(held = 16_384, ms = 7_800, now = 7_900) // the old backlog, back at Coded's pace: not the new air's trip
        assertTrue(w.probingRtt)
        assertEquals("no drain sampled while probing", 0, w.rate)
        w.wrote(2_048, 7_900)
        w.report(held = 18_432, ms = 9_800, now = 9_900)
        assertFalse("a chunk written after the step ends it", w.probingRtt)
        assertEquals("with the new air's trip", 2_000L, w.rttMs)
    }

    @Test
    fun aChunkThePaceSlippedInBehindTheBacklogDoesNotEndTheProbe() {
        // Paced while 1M's records cross Coded, the fallback's chunk waits behind them: its trip is the backlog's, and
        // ending the probe on it locked the round trip at its ceiling for ten seconds (the bluetooth-reviewer's finding).
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        w.wrote(8_192, 0)
        w.report(held = 8_192, ms = 400, now = 400)
        w.wrote(16_384, 400) // 1M's backlog
        w.forgetLink(drain = true)
        w.minChunk = CODED_CHUNK
        w.wrote(2_048, 500) // a paced chunk behind it
        w.report(held = 26_624, ms = 20_000, now = 20_100)
        assertTrue("its trip is the backlog's: still probing", w.probingRtt)
        w.wrote(CODED_CHUNK, 20_100) // under the probe's window, nothing ahead
        w.report(held = 27_648, ms = 21_100, now = 21_200)
        assertFalse(w.probingRtt)
        assertEquals("the new air's trip", 1_100L, w.rttMs)
    }

    @Test
    fun atTheFarEdgeASlowChunkIsNoSilenceAndNoTwoSecondReporter() {
        // At 0.4 KB/s one 1 KiB chunk takes 2.5 s to cross, and a receiver reports it only once whole: by the constants
        // alone every chunk tripped the 3 s silence, and a latched feed could never answer inside 1.9 s.
        val w =
            FeedWindow().apply {
                minChunk = CODED_CHUNK
                start(offset = 0, now = 0)
            }
        repeat(8) { w.wrote(CODED_CHUNK, 0) }
        w.report(held = 1_024, ms = 2_560, now = 2_700) // 400 B/s
        assertEquals(400, w.rate)
        assertFalse("three chunks' time is owed a report: 7.7 s", w.pacing(2_700 + 7_000))
        assertTrue(w.pacing(2_700 + 7_700))
        // A dozing receiver reading two chunks a wake: gaps of two chunks' time, 5.1 s, mark it.
        w.report(held = 3_072, ms = 7_680, now = 7_800)
        w.report(held = 5_120, ms = 12_800, now = 12_900)
        assertTrue(w.pacing(12_900))
        // Paced at Coded's pace in its smallest chunks, awake again: one chunk a report, 2.5 s apart, is on time here.
        w.chunkFor(false, CODED_PACE)
        repeat(3) { w.wrote(CODED_CHUNK, 13_000 + it * 1_000L) }
        var held = 5_120L
        var ms = 12_800L
        repeat(5) {
            held += CODED_CHUNK
            ms += 2_560
            w.report(held = held, ms = ms, now = ms + 100) // the old window's three chunks, then two paced ones
        }
        assertTrue(w.pacing(ms + 100))
        w.report(held = held + CODED_CHUNK, ms = ms + 2_560, now = ms + 2_660)
        assertFalse("three answered on time at the far edge: windowed again", w.pacing(ms + 2_660))
    }

    @Test
    fun oneLowSampleCannotHoldASilentReceiverUnpacedForLong() {
        // A link that nearly stalled for a moment can leave one sample of 100 B/s as the whole filter: three of its
        // chunks' time would be 31 s on Coded, a minute on 1M, with a receiver whose reports stopped.
        for (chunk in listOf(CODED_CHUNK, FeedWindow.MIN_CHUNK_BYTES)) {
            val w =
                FeedWindow().apply {
                    minChunk = chunk
                    start(offset = 0, now = 0)
                }
            repeat(8) { w.wrote(chunk, 0) }
            w.report(held = 100, ms = 1_000, now = 1_000)
            assertEquals(100, w.rate)
            assertTrue(
                "$chunk B chunks: paced within three of the longest crossing",
                w.pacing(1_000 + 3 * FeedWindow.MAX_CHUNK_CROSSING_MS),
            )
        }
    }

    @Test
    fun at1mDrainsTheSilenceAndTheTwoSecondGapAreTheConstants() {
        val w = FeedWindow().apply { start(offset = 0, now = 0) }
        repeat(8) { w.wrote(2_048, 0) }
        w.report(held = 2_048, ms = 1_000, now = 1_100) // 2 KB/s: three 2 KiB chunks are 3 s
        assertTrue(w.pacing(1_100 + FeedWindow.NO_REPORT_MS))
    }

    @Test
    fun theSmallestChunkSizesTheWindowsFloors() {
        val w =
            FeedWindow().apply {
                minChunk = CODED_CHUNK
                start(offset = 0, now = 0)
            }
        assertEquals("a starting chunk is two of them", 2 * CODED_CHUNK, w.chunkBytes())
        assertEquals("the starting window four", 4L * CODED_CHUNK, w.target())
        w.wrote(2 * CODED_CHUNK, 0)
        w.wrote(2 * CODED_CHUNK, 0)
        w.report(held = 1_024, ms = 2_000, now = 2_100) // 512 B/s
        assertEquals("a chunk is never under one", CODED_CHUNK, w.chunkBytes())
        assertEquals("the window never under two", 2L * CODED_CHUNK, w.target())
        w.minChunk = 100
        assertEquals("nor under what a dark receiver reports on", FeedWindow.SMALLEST_CHUNK_BYTES, w.minChunk)
        assertEquals(1_024, FeedWindow.SMALLEST_CHUNK_BYTES)
    }

    @Test
    fun aDozingReceiverOnCodedWinsItsWindowBackAtCodedsPace() {
        // Paced at Coded's 1 KiB/s, latched chunks of its smallest one land a second apart: on time, inside the gap.
        val w =
            FeedWindow().apply {
                minChunk = CODED_CHUNK
                start(offset = 0, now = 0)
            }
        repeat(6) { w.wrote(2_048, 0) }
        w.report(held = 4_096, ms = 2_000, now = 2_100)
        w.report(held = 8_192, ms = 4_000, now = 4_100)
        w.report(held = 12_288, ms = 6_000, now = 6_100)
        assertTrue(w.pacing(6_100))
        assertEquals("paced in Coded's smallest chunks", CODED_CHUNK, w.chunkFor(false, CODED_PACE))
        repeat(3) { w.wrote(CODED_CHUNK, 6_200 + it * 1_000L) }
        w.report(held = 13_312, ms = 7_000, now = 7_100)
        w.report(held = 14_336, ms = 8_000, now = 8_100)
        assertTrue(w.pacing(8_100))
        w.report(held = 15_360, ms = 9_000, now = 9_100)
        assertFalse("three Coded chunks answered on time: windowed again", w.pacing(9_100))
    }

    private companion object {
        // A windowed feed's paced fallback at the 4 KiB/s base, in the pace's 8 KiB chunks.
        val BASE_PACE = PaceConfig(bytesPerSec = 4_096, chunkBytes = 8_192, windowed = true)

        // A windowed feed's paced fallback on Coded: Coded's own pace, in its smallest chunks (`PhyTuning`'s defaults).
        const val CODED_CHUNK = 1_024
        val CODED_PACE = PaceConfig(bytesPerSec = 1_024, chunkBytes = 2_048, windowed = true, windowChunkBytes = CODED_CHUNK)

        const val STEP_MS = 10L
        const val CHUNK_REPORT_BYTES = 2_048L
    }
}
