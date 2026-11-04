package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.LinkSilence
import app.getknit.knit.mesh.bluetooth.LinkSilence.Action
import app.getknit.knit.mesh.bluetooth.LinkSilence.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * [LinkSilence] (#203, ADR 2026-10.697m): when a Bluetooth link shown dead goes down, and that a healthy one never
 * does. Pure: the clock is the test's, and the link's bytes are the stamps it passes in.
 */
class LinkSilenceTest {
    private val s = LinkSilence.QUIET_MS
    private val p = LinkSilence.ANSWER_MS
    private val w = LinkSilence.WRITE_STALL_MS
    private val cw = LinkSilence.CARRIER_WAIT_MS

    // The uptime clock, which only the carrier wait reads: a test moves it beside its elapsed `now`, or leaves it still
    // for a doze.
    private val up = AtomicLong(0)

    private companion object {
        // One-way delays a keepalive may take: a quiet link, a busy one, and one queued behind a stream.
        val DELAYS = longArrayOf(5, 400, 3_000, 8_000, 14_000)
    }

    /** A link whose announces crossed at link-up: the peer has shown it answers. */
    private fun answering(uptime: () -> Long = up::get): LinkSilence =
        LinkSilence(exempt = false, uptime = uptime).also {
            assertTrue(it.announce())
            it.onKeepaliveWritten(10)
            assertFalse("the peer's announce crossing ours is not answered", it.onKeepaliveIn())
            assertTrue(it.answers)
        }

    @Test
    fun anAwakeIdlePairNeverProbes() {
        // Each side's 60 s re-offer is a byte in for the other well inside the 90 s quiet.
        val link = answering()
        var rx = 0L
        for (t in 0L..1_800_000L step 5_000L) {
            if (t % 60_000L == 0L) rx = t
            assertEquals("at $t", Action.None, link.step(t, rx, null, null))
        }
    }

    @Test
    fun aPeerThatNeverAnsweredIsNeverProbedNorClosedOnItsSilence() {
        val link = LinkSilence(exempt = false, uptime = up::get)
        assertTrue(link.announce())
        link.onKeepaliveWritten(5)
        assertFalse(link.answers)
        // An hour of silence from an older build: only a stall or its own controller would close it.
        assertEquals(Action.None, link.step(3_600_000, 0, null, null))
        assertEquals(Action.None, link.step(3_600_000, 0, null, LinkSilence.Local(lost = false)))
    }

    @Test
    fun anAnsweringLinkIsProbedTwiceThenClosedAboutAHundredAndFiftySecondsAfterItsLastByte() {
        val link = answering()
        assertEquals(Action.None, link.step(s - 1, 0, null, null))
        assertEquals(s, link.msUntilDue(0, 0, null, null))
        assertEquals("due: the first probe waits for a carrier", Action.None, link.step(s, 0, null, null))
        assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(s, 0))
        assertEquals("one probe at a time: queued, not yet written", Action.None, link.step(s + p, 0, null, null))
        link.onKeepaliveWritten(s + 100)
        assertEquals(p, link.msUntilDue(s + 100, 0, null, null))
        assertEquals(Action.None, link.step(s + 100 + p - 1, 0, null, null))
        assertEquals(Action.Probe(2, LinkSilence.Trigger.TIMER), link.step(s + 100 + p, 0, null, null))
        link.onKeepaliveWritten(s + 200 + p)
        assertEquals(Action.None, link.step(s + 200 + 2 * p - 1, 0, null, null))
        val close = link.step(s + 200 + 2 * p, 0, null, null)
        assertEquals(Action.Close(Why.PROBES, s + 200 + 2 * p), close)
        assertTrue((close as Action.Close).quietMs in 150_000L..151_000L)
    }

    @Test
    fun anyByteInAfterAProbeEndsItsSilence() {
        val link = answering()
        assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(s, 0))
        link.onKeepaliveWritten(s + 50)
        // The answer, or any record, lands: the run ends, and the next probe waits for a full quiet again.
        val rx = s + 400
        assertEquals(Action.None, link.step(s + 50 + p, rx, null, null))
        assertEquals(Action.None, link.carry(rx + s - 1, rx))
        assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(rx + s, rx))
    }

    @Test
    fun aPeerThatHasSentAKeepaliveAnswersEveryOneNotMatchingOneOfItsOwn() {
        val link = answering()
        assertTrue("the peer's probe", link.onKeepaliveIn())
        assertTrue("and its second, each answered", link.onKeepaliveIn())
        // Our probe waits: the next one in is its answer, or a probe that crossed it.
        assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(s, 0))
        assertFalse(link.onKeepaliveIn())
        assertTrue("then the peer's next probe is answered again", link.onKeepaliveIn())
    }

    @Test
    fun anAnswerToTheAnnounceGoesFirstWhenThePeersAnnounceArrivesBeforeOurs() {
        val link = LinkSilence(exempt = false, uptime = up::get)
        assertTrue(link.onKeepaliveIn())
        assertFalse("our answer is our announce", link.announce())
        assertTrue(link.answers)
    }

    @Test
    fun twoAnsweringSidesEndEveryExchangeWhetherProbesCrossOrNot() {
        // Offsets cross the probes or not; delays run past half the answer wait, where a time-only rule bounced.
        for (offset in listOf(0L, 300L, 5_000L, 20_000L, 40_000L)) {
            for (delay in listOf(5L, 400L, 10_000L, 16_000L, 25_000L)) {
                val keepalives = exchange(offsetMs = offset, delayMs = delay)
                // A round trip past the answer wait sends the second probe before the first one's answer is back: four.
                val most = if (2 * delay < p) 2 else 4
                assertTrue("offset $offset delay $delay: $keepalives keepalives for one silence", keepalives in 1..most)
            }
        }
    }

    /**
     * Two policies over one link, every keepalive taking [delayMs] to cross, B's last byte in [offsetMs] after A's, with no
     * carrier at all, so each first probe falls back. Runs one silence to its end, asserts no close and nothing still in
     * flight, and returns how many keepalives crossed after the announces.
     */
    private fun exchange(
        offsetMs: Long,
        delayMs: Long,
    ): Int {
        val clock = AtomicLong(0) // uptime keeps pace with the elapsed clock: neither side dozes
        val a = answering(clock::get)
        val b = answering(clock::get)
        val rx = longArrayOf(0, offsetMs) // A's last byte in, B's
        val sides = listOf(a, b)
        val air = ArrayList<Pair<Long, Int>>() // (arrival, to side)
        var sent = 0
        var t = 0L
        // Past every first exchange (the slowest ends at 290 s), short of either side's next probe (420 s and on).
        val until = 2 * s + cw - 1
        while (t <= until) {
            clock.set(t)
            val landed = air.filter { it.first <= t }
            air.removeAll(landed)
            landed.forEach { (_, to) ->
                rx[to] = t
                if (sides[to].onKeepaliveIn()) {
                    sides[to].onKeepaliveWritten(t)
                    air += (t + delayMs) to (1 - to)
                    sent++
                }
            }
            sides.forEachIndexed { i, side ->
                val action = side.step(t, rx[i], null, null)
                assertFalse("no close while every keepalive is answered: $action", action is Action.Close)
                if (action is Action.Probe) {
                    side.onKeepaliveWritten(t)
                    air += (t + delayMs) to (1 - i)
                    sent++
                }
            }
            t += 5
        }
        assertTrue("nothing left in flight: $air", air.isEmpty())
        return sent
    }

    @Test
    fun aSeededPairRunsSilenceAfterSilenceWithNoCloseAndNoKeepaliveLeftUnmatched() {
        // A leak in the answer count would show only in a later silence: each run spans about four. Each direction has its
        // own delay, and each write may wait behind a stream before it returns, the round trip kept inside the answer wait.
        var carried = 0
        var fallbacks = 0
        for (seed in 0 until 60) {
            val r = Random(seed)
            val delays = longArrayOf(DELAYS[r.nextInt(DELAYS.size)], DELAYS[r.nextInt(DELAYS.size)]) // to A, to B
            val lagMost = (p - delays.sum() - 1_000).coerceAtLeast(0)
            val (rode, fell) = longRun(r, delays, lagMost, "seed $seed delays ${delays.toList()} lag ≤ $lagMost")
            carried += rode
            fallbacks += fell
        }
        // Carriers at most 200 s apart against a 120 s wait: nearly every first probe rides one.
        assertTrue("first probes rode carriers: $carried carried, $fallbacks fell back", carried > 0 && fallbacks * 10 <= carried)
    }

    /**
     * Two policies over one link from the announces to [s] × 6: [delays] per direction (index = the side it reaches), every
     * keepalive write returning up to [lagMost] after it was queued, in the side's queue order, and each direction first
     * in, first out. Each side's carrier — its re-offer digest, stretched by doze to 60–200 s apart — offers the first probe
     * a ride and is a byte in for the other side. Ends once nothing is queued or in flight, and checks the count by
     * behaviour: a fresh keepalive is then answered on both sides.
     */
    private fun longRun(
        r: Random,
        delays: LongArray,
        lagMost: Long,
        what: String,
    ): Pair<Int, Int> {
        val run = Duplex(r, delays, lagMost)
        val end = 6 * s
        var t = 0L
        while (t <= end || run.busy()) {
            run.clock.set(t)
            for (i in 0..1) run.deliver(i, t)
            // Past the horizon nothing new starts: the run drains what is out.
            if (t <= end) {
                for (i in 0..1) {
                    run.carrier(i, t)
                    val action = run.step(i, t)
                    assertFalse("$what: closed at $t: $action", action is Action.Close)
                }
            }
            t += 5
        }
        run.sides.forEachIndexed { i, side -> assertTrue("$what: side $i still counts an unmatched keepalive", side.onKeepaliveIn()) }
        return run.carried to run.fallbacks
    }

    /** [longRun]'s link: two policies, their writes, the air between them and each side's carrier. */
    private inner class Duplex(
        private val r: Random,
        private val delays: LongArray,
        private val lagMost: Long,
    ) {
        val clock = AtomicLong(0)
        val sides = List(2) { LinkSilence(exempt = false, uptime = clock::get) }
        private val rx = longArrayOf(0, 0)
        private val pending = List(2) { ArrayDeque<Long>() } // each side's keepalive writes, by when they return
        private val air = List(2) { ArrayDeque<Long>() } // keepalive arrivals, by the side they reach
        private val digests = List(2) { ArrayDeque<Long>() } // carrier arrivals, by the side they reach
        private val nextCarrier = longArrayOf(nextGap(), nextGap())

        /** First probes that rode a carrier, and those that fell back for want of one. */
        var carried = 0
            private set

        var fallbacks = 0
            private set

        init {
            sides.forEachIndexed { i, side -> if (side.announce()) queue(i, 0) }
        }

        fun busy(): Boolean = (pending + air + digests).any { it.isNotEmpty() }

        /** Side [i]'s writes that returned by [t] go on the air; what reached it by [t] lands. */
        fun deliver(
            i: Int,
            t: Long,
        ) {
            due(digests[i], t).forEach { _ -> rx[i] = t }
            due(pending[i], t).forEach { _ ->
                sides[i].onKeepaliveWritten(t)
                send(air[1 - i], t + delays[1 - i])
            }
            due(air[i], t).forEach { _ ->
                rx[i] = t
                if (sides[i].onKeepaliveIn()) queue(i, t)
            }
        }

        /** Side [i]'s re-offer, when due: a byte for the other side, and a ride for a due first probe. */
        fun carrier(
            i: Int,
            t: Long,
        ) {
            if (t < nextCarrier[i]) return
            nextCarrier[i] = t + nextGap()
            send(digests[1 - i], t + delays[1 - i])
            if (sides[i].carry(t, rx[i]) is Action.Probe) {
                carried++
                queue(i, t)
            }
        }

        fun step(
            i: Int,
            t: Long,
        ): Action =
            sides[i].step(t, rx[i], null, null).also {
                if (it is Action.Probe) {
                    if (it.via == LinkSilence.Trigger.FALLBACK) fallbacks++
                    queue(i, t)
                }
            }

        private fun queue(
            i: Int,
            t: Long,
        ) = send(pending[i], t + if (lagMost > 0) r.nextLong(lagMost + 1) else 0)

        // First in, first out: nothing overtakes what went before it.
        private fun send(
            line: ArrayDeque<Long>,
            at: Long,
        ) = line.addLast(maxOf(at, line.lastOrNull() ?: 0))

        // A dozing side's re-offer: 60 s on the awake clock, stretched by sleep to up to 200 s.
        private fun nextGap(): Long = 60_000 + r.nextLong(140_001)
    }

    /** Takes from the head of [times] every entry due by [t], in order. */
    private fun due(
        times: ArrayDeque<Long>,
        t: Long,
    ): List<Long> = buildList { while (times.firstOrNull()?.let { it <= t } == true) add(times.removeFirst()) }

    @Test
    fun aDueFirstProbeWithNoCarrierFallsBackAfterTheCarrierWaitOnTheUptimeClock() {
        val link = answering()
        up.set(1_000_000)
        assertEquals("due: waiting for a carrier", Action.None, link.step(s, 0, null, null))
        // Waiting, the next look is the fallback's deadline, never the quiet's (past now), so never a look a second.
        assertEquals(cw, link.msUntilDue(s, 0, null, null))
        up.set(1_000_000 + cw - 1)
        assertEquals(Action.None, link.step(s + cw - 1, 0, null, null))
        assertEquals(1L, link.msUntilDue(s + cw - 1, 0, null, null))
        up.set(1_000_000 + cw)
        assertEquals(Action.Probe(1, LinkSilence.Trigger.FALLBACK), link.step(s + cw, 0, null, null))

        // A new silence waits for a carrier afresh: a byte lands, and its own wait starts at its own due look.
        val again = answering()
        up.set(2_000_000)
        assertEquals(Action.None, again.step(s, 0, null, null))
        val rx = s + 1_000
        up.set(2_000_000 + cw)
        assertEquals(Action.None, again.step(rx + s, rx, null, null))
        assertEquals(cw, again.msUntilDue(rx + s, rx, null, null))
    }

    @Test
    fun thisSidesOwnDozeNeverTripsTheCarrierFallback() {
        // The re-offer's delay stops while this CPU sleeps, and so does the uptime clock: an hour asleep is no miss.
        val link = answering()
        up.set(5_000)
        assertEquals(Action.None, link.step(s, 0, null, null))
        up.set(5_000 + cw / 2)
        val woke = s + 3_600_000
        assertEquals(Action.None, link.step(woke, 0, null, null))
        val wait = link.msUntilDue(woke, 0, null, null)!!
        assertTrue("waits out the uptime left, never 0: $wait", wait >= cw / 2)
        assertEquals("and the re-offer that wakes it carries the probe", Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(woke, 0))
    }

    @Test
    fun aCarrierDecidesAtItsEnqueueAndNothingIsArmed() {
        val link = answering()
        assertEquals("not quiet yet", Action.None, link.carry(s - 1, 0))
        assertEquals("due, but no carrier yet", Action.None, link.step(s, 0, null, null))
        // Nothing counts before a probe is queued: a peer's keepalive arriving now is the peer's own, and is answered.
        assertTrue(link.onKeepaliveIn())
        link.onKeepaliveWritten(s + 2) // that answer went out
        // A byte came in first: the carrier after it carries nothing.
        assertEquals(Action.None, link.carry(s + 10, rxAt = s + 5))
        assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(2 * s + 5, rxAt = s + 5))
        // Written, so no probe is queued any more: only "one per silence" keeps the next carrier from carrying a second.
        val written = 2 * s + 6
        link.onKeepaliveWritten(written)
        assertEquals("one probe of a silence rides a carrier, the second goes on the timer", Action.None, link.carry(written + 1, s + 5))
        assertEquals(Action.None, link.step(written + p - 1, s + 5, null, null))
        assertEquals(Action.Probe(2, LinkSilence.Trigger.TIMER), link.step(written + p, s + 5, null, null))
    }

    @Test
    fun noCarrierProbesAPeerThatNeverAnsweredNorAnIphone() {
        val older = LinkSilence(exempt = false, uptime = up::get)
        assertTrue(older.announce())
        assertEquals(Action.None, older.carry(10 * s, 0))
        assertEquals(Action.None, LinkSilence(exempt = true, uptime = up::get).carry(10 * s, 0))
    }

    @Test
    fun anIphoneLinkIsNeverAnnouncedProbedAnsweredOrClosed() {
        val link = LinkSilence(exempt = true, uptime = up::get)
        assertFalse(link.announce())
        assertFalse(link.onKeepaliveIn())
        assertFalse(link.onEdge(100_000))
        val local = LinkSilence.Local(lost = true)
        assertEquals(Action.None, link.step(3_600_000, 0, blockedSince = 0, local = local))
        assertNull(link.msUntilDue(3_600_000, 0, 0, local))
    }

    @Test
    fun aWriteThatSitsTwoMinutesClosesTheLinkWhatever() {
        val link = LinkSilence(exempt = false, uptime = up::get) // an older peer: the stall is its only bound
        assertEquals(Action.None, link.step(1_000 + w - 1, rxAt = 1_000 + w - 1, blockedSince = 1_000, local = null))
        assertEquals(1L, link.msUntilDue(1_000 + w - 1, 1_000 + w - 1, 1_000, null))
        // Bytes still coming in do not keep it: the writer is what is stuck.
        assertEquals(Action.Close(Why.WRITE, 0), link.step(1_000 + w, rxAt = 1_000 + w, blockedSince = 1_000, local = null))
    }

    @Test
    fun aLinkNotSittingInAWriteIsLookedAtAgainWithinTheStallBound() {
        val link = LinkSilence(exempt = false, uptime = up::get)
        assertEquals(w, link.msUntilDue(500, 500, null, null))
    }

    @Test
    fun towardAnOlderPeerOnlyALostClientClosesAQuietLinkNeverUnansweredReads() {
        // The 2026-10-19 trial: after a 4-minute process freeze a P8's link-RSSI reads timed out 27 in a row on two
        // healthy links to older peers. Unanswered reads are no input at all: a quiet link whose client was not lost
        // stays up for as long as it is quiet, its handle's reads unanswered or not.
        val quiet = LinkSilence(exempt = false, uptime = up::get)
        val readsOnly = LinkSilence.Local(lost = false)
        for (t in listOf(s, s + 27 * 6_000L, 3_600_000L)) assertEquals("at $t", Action.None, quiet.step(t, 0, null, readsOnly))
        assertEquals("looked at again only on the stall bound", w, quiet.msUntilDue(s, 0, null, readsOnly))

        val lost = LinkSilence(exempt = false, uptime = up::get)
        val gone = LinkSilence.Local(lost = true)
        assertEquals(Action.None, lost.step(s - 1, 0, null, gone))
        assertEquals(s - 1, lost.msUntilDue(1, 0, null, gone))
        assertEquals(Action.Close(Why.LOST, s), lost.step(s, 0, null, gone))
        assertTrue(Why.LOST.local && !Why.PROBES.local && !Why.WRITE.local)
    }

    @Test
    fun towardAPeerThatAnswersItsOwnControllersReadingNeverClosesTheLink() {
        // An ATT timeout tells every client the connection ended while the CoC holds the ACL.
        LinkSilence.Local(lost = true).let { local ->
            val link = answering()
            assertEquals(s, link.msUntilDue(0, 0, null, local))
            assertEquals("$local closes nothing and waits for a carrier", Action.None, link.step(s, 0, null, local))
            assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(s, 0))
            link.onKeepaliveWritten(s + 10)
            // The reading moves no deadline: the next look is the answer wait, not every second past the quiet.
            assertEquals(p, link.msUntilDue(s + 10, 0, null, local))
            // The answer lands: a byte after the reading clears it, and the link stays up.
            val rx = s + 500
            val after = LinkSilence.Local(lost = false)
            assertEquals(Action.None, link.step(s + 10 + p, rx, null, after))
            assertEquals(Action.None, link.step(rx + s - 1, rx, null, after))
        }
        // Unanswered, the probes close it, as Why.PROBES, never on the reading.
        val link = answering()
        val lost = LinkSilence.Local(lost = true)
        assertEquals(Action.Probe(1, LinkSilence.Trigger.CARRIER), link.carry(s, 0))
        link.onKeepaliveWritten(s)
        assertEquals(Action.Probe(2, LinkSilence.Trigger.TIMER), link.step(s + p, 0, null, lost))
        link.onKeepaliveWritten(s + p)
        assertEquals(Action.Close(Why.PROBES, s + 2 * p), link.step(s + 2 * p, 0, null, lost))
    }

    @Test
    fun anEdgeProbesAnAnsweringLinkAtOnceButNeverClosesItBeforeTheQuiet() {
        val link = answering()
        assertTrue(link.onEdge(5_000))
        assertEquals(0L, link.msUntilDue(5_000, 4_000, null, null))
        assertEquals(Action.Probe(1, LinkSilence.Trigger.EDGE), link.step(5_000, 4_000, null, null))
        link.onKeepaliveWritten(5_010)
        assertEquals(Action.Probe(2, LinkSilence.Trigger.TIMER), link.step(5_010 + p, 4_000, null, null))
        link.onKeepaliveWritten(5_020 + p)
        // Two probes unanswered, but only 66 s since the last byte: the close waits for the quiet.
        assertEquals(Action.None, link.step(5_020 + 2 * p, 4_000, null, null))
        assertEquals(4_000 + s - (5_020 + 2 * p), link.msUntilDue(5_020 + 2 * p, 4_000, null, null))
        assertEquals(Action.Close(Why.PROBES, s), link.step(4_000 + s, 4_000, null, null))
    }

    @Test
    fun anEdgeAByteHasAnsweredAlreadyAsksForNothing() {
        val link = answering()
        link.onEdge(5_000)
        assertEquals(Action.None, link.step(5_100, rxAt = 5_050, blockedSince = null, local = null))
        assertEquals(Action.None, link.step(5_050 + s - 1, rxAt = 5_050, blockedSince = null, local = null))
    }

    @Test
    fun anEdgeOnALinkWhosePeerNeverAnsweredProbesNothing() {
        val link = LinkSilence(exempt = false, uptime = up::get)
        assertTrue(link.onEdge(5_000))
        assertEquals(Action.None, link.step(5_000, 4_000, null, null))
    }

    @Test
    fun aHeldLinkDialProbesAtMostOncePerAnswerWaitAndNeverAnExemptOrSilentPeer() {
        val link = answering()
        assertTrue(link.onHeldDial(5_000))
        assertFalse("a run is going", link.onHeldDial(5_001))
        assertEquals(Action.Probe(1, LinkSilence.Trigger.EDGE), link.step(5_000, 4_000, null, null))
        link.onKeepaliveWritten(5_010)
        link.onKeepaliveIn()
        link.step(5_100, 5_050, null, null) // answered: the run ends
        assertFalse("inside the answer wait of the last", link.onHeldDial(5_000 + p - 1))
        assertTrue(link.onHeldDial(5_000 + p))
        assertFalse(LinkSilence(exempt = true, uptime = up::get).onHeldDial(5_000))
        assertFalse("a peer that never answered", LinkSilence(exempt = false, uptime = up::get).onHeldDial(5_000))
    }
}
