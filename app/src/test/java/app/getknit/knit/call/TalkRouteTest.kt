package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walkie's reports (docs/VOICE_CALLS.md C-3.11-10): the speaker's [TalkRoute] and the listener's [HeardLedger]. */
class TalkRouteTest {
    // ---- TalkRoute ----

    @Test
    fun aBurstTheRadiosCarriedWholeKeepsTheRadiosAlone() {
        val route = TalkRoute(waitMs = 7_000)
        route.sent(b = 0, chunks = 5, at = 1_000)
        route.report(TalkReport(b = 0, radio = 5, relay = 0))
        route.expire(now = 60_000)
        assertFalse(route.castToo)
    }

    @Test
    fun aBurstTheRadiosLostMoreThanAFifthOfTurnsTheCastOn() {
        val route = TalkRoute(waitMs = 7_000, lossMax = 0.2)
        route.sent(b = 0, chunks = 10, at = 1_000)
        // Two of ten lost is a fifth: still the radios.
        route.report(TalkReport(b = 0, radio = 8, relay = 0))
        assertFalse(route.castToo)
        route.sent(b = 1, chunks = 3, at = 2_000)
        // One of three is past it.
        route.report(TalkReport(b = 1, radio = 2, relay = 0))
        assertTrue(route.castToo)
    }

    /** The field case of 2026-10-18: every chunk flooded into a neighbour that had lost the peer, and nothing came back. */
    @Test
    fun aBurstWithNoReportByItsDeadlineIsTakenAsLost() {
        val route = TalkRoute(waitMs = 7_000)
        route.sent(b = 0, chunks = 4, at = 1_000)
        route.expire(now = 7_999)
        assertFalse(route.castToo)
        route.expire(now = 8_000)
        assertTrue(route.castToo)
        // Its report coming late changes nothing: it was judged already.
        route.report(TalkReport(b = 0, radio = 4, relay = 0))
        assertTrue(route.castToo)
    }

    @Test
    fun twoWholeRadioReportsInARowTurnTheCastOffAndALossyOneBetweenResetsTheCount() {
        val route = TalkRoute(waitMs = 7_000, wholeToReturn = 2)
        route.sent(b = 0, chunks = 4, at = 0)
        route.report(TalkReport(b = 0, radio = 0, relay = 4))
        assertTrue(route.castToo)

        route.sent(b = 1, chunks = 4, at = 10)
        route.report(TalkReport(b = 1, radio = 4, relay = 4))
        assertTrue("one whole report is not enough", route.castToo)
        route.sent(b = 2, chunks = 4, at = 20)
        route.report(TalkReport(b = 2, radio = 1, relay = 4))
        route.sent(b = 3, chunks = 4, at = 30)
        route.report(TalkReport(b = 3, radio = 4, relay = 4))
        assertTrue("the lossy one between reset the count", route.castToo)
        route.sent(b = 4, chunks = 4, at = 40)
        route.report(TalkReport(b = 4, radio = 4, relay = 4))
        assertFalse(route.castToo)
    }

    @Test
    fun aReportOfABurstNeverSentIsIgnored() {
        val route = TalkRoute(waitMs = 7_000)
        route.report(TalkReport(b = 9, radio = 0, relay = 0))
        assertFalse(route.castToo)
        // Nor does a burst of no chunks wait on a report.
        route.sent(b = 0, chunks = 0, at = 0)
        route.expire(now = 60_000)
        assertFalse(route.castToo)
    }

    @Test
    fun eachBurstJudgedGoesToTheCardWithWhatItsReportCountedOrNoneWithoutOne() {
        val judged = mutableListOf<Pair<Int, Int?>>()
        val route = TalkRoute(waitMs = 7_000, judged = { chunks, heard -> judged += chunks to heard })
        route.sent(b = 0, chunks = 10, at = 0)
        route.sent(b = 1, chunks = 4, at = 1_000)
        // Both planes' counts, as the report gives them.
        route.report(TalkReport(b = 0, radio = 3, relay = 6))
        route.expire(now = 8_000)
        // A late report of a burst judged already, and a duplicate, are not judged again.
        route.report(TalkReport(b = 1, radio = 4, relay = 0))
        route.report(TalkReport(b = 0, radio = 3, relay = 6))
        assertEquals(listOf(10 to 9, 4 to null), judged)
    }

    // ---- HeardLedger ----

    private fun chunk(
        b: Int,
        q: Int,
        e: Boolean = false,
    ) = TalkChunk(b = b, q = q, a = ByteArray(0), e = e)

    @Test
    fun aBurstIsReportedOnceItsLastChunkHasSettledCountingEachPlane() {
        val ledger = HeardLedger(settleMs = 500, endMs = 4_000)
        ledger.note(chunk(0, 0), viaRelay = false, at = 0)
        ledger.note(chunk(0, 1), viaRelay = true, at = 100)
        ledger.note(chunk(0, 2, e = true), viaRelay = true, at = 200)
        assertEquals(700L, ledger.nextDueAt())
        assertTrue(ledger.due(now = 699).isEmpty())
        // The radio copy of the last chunk landing behind the cast one still counts.
        ledger.note(chunk(0, 2, e = true), viaRelay = false, at = 650)
        val reports = ledger.due(now = 700)
        assertEquals(1, reports.size)
        assertEquals(0, reports[0].b)
        assertEquals(2, reports[0].radio)
        assertEquals(2, reports[0].relay)
        // Once.
        assertTrue(ledger.due(now = 10_000).isEmpty())
        assertNull(ledger.nextDueAt())
    }

    @Test
    fun aBurstWhoseLastChunkNeverCameIsReportedWhenThePlayerWouldGiveItUp() {
        val ledger = HeardLedger(settleMs = 500, endMs = 4_000)
        ledger.note(chunk(3, 0), viaRelay = false, at = 0)
        ledger.note(chunk(3, 1), viaRelay = false, at = 400)
        assertTrue(ledger.due(now = 4_399).isEmpty())
        val reports = ledger.due(now = 4_400)
        assertEquals(listOf(3), reports.map { it.b })
        assertEquals(2, reports[0].radio)
    }

    @Test
    fun aStragglerOfAReportedBurstIsNotTakenForANewOne() {
        val ledger = HeardLedger(settleMs = 500, endMs = 4_000)
        ledger.note(chunk(0, 0, e = true), viaRelay = false, at = 0)
        assertEquals(1, ledger.due(now = 500).size)
        ledger.note(chunk(0, 0, e = true), viaRelay = true, at = 600)
        assertNull(ledger.nextDueAt())
        assertTrue(ledger.due(now = 60_000).isEmpty())
    }

    @Test
    fun aDuplicateOnOnePlaneCountsOnce() {
        val ledger = HeardLedger(settleMs = 500, endMs = 4_000)
        ledger.note(chunk(0, 0), viaRelay = false, at = 0)
        ledger.note(chunk(0, 0), viaRelay = false, at = 10)
        ledger.note(chunk(0, 1, e = true), viaRelay = false, at = 20)
        assertEquals(2, ledger.due(now = 520).single().radio)
    }
}
