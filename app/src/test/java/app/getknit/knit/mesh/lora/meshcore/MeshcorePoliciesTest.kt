package app.getknit.knit.mesh.lora.meshcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session's pure policies: the ack table, the pins, the headroom and the air ring (ADR 2026-11.gsqu, Stage 3b). */
class MeshcorePoliciesTest {
    // --- MeshcoreAckTable (binding rule "sends" 3) ---

    @Test
    fun aCodeIsOursUntilEightNewerSendsFromAnyClientOverwriteIt() {
        val t = MeshcoreAckTable()
        t.onSent(code = 100, handle = 1u, now = 0, windowMs = 5_000)
        repeat(7) { t.onSent(code = 200 + it, handle = null, now = 0, windowMs = 5_000) } // another client's sends
        assertTrue("seven newer sends leave it in the board's table", t.isLive(100))
        t.onSent(code = 300, handle = 2u, now = 0, windowMs = 5_000)
        assertFalse("the eighth overwrote it: its confirmation will never be pushed", t.isLive(100))
        assertNull(t.onConfirmed(100))
        assertEquals(2u, t.onConfirmed(300))
        assertNull("a code is confirmed once: the board clears its slot", t.onConfirmed(300))
        assertNull("another client's confirmation is not ours", t.onConfirmed(201))
    }

    @Test
    fun aConfirmationAfterTheFlightEndedStillNamesItsHandle() {
        val t = MeshcoreAckTable()
        t.onSent(code = 7, handle = 9u, now = 0, windowMs = 1_000)
        // The session ended the DM Unreachable long ago; the board's slot still holds the code.
        assertEquals(9u, t.onConfirmed(7))
    }

    @Test
    fun theTableIsFullOnlyWhileEightCodesAreUnconfirmedInsideTheirWindow() {
        val t = MeshcoreAckTable()
        repeat(7) { t.onSent(code = it + 1, handle = it.toUInt(), now = 0, windowMs = 3_000) }
        assertFalse(t.full(0))
        t.onSent(code = 8, handle = null, now = 0, windowMs = 3_000)
        assertTrue("an eighth unsettled send fills it: a ninth would overwrite a code still owed", t.full(1_000))
        assertFalse("past their windows nothing is owed any more", t.full(3_000))
        t.onConfirmed(3)
        assertFalse("a confirmed slot may be overwritten", t.full(1_000))
    }

    @Test
    fun aRebootEmptiesTheTable() {
        val t = MeshcoreAckTable()
        t.onSent(code = 5, handle = 1u, now = 0, windowMs = 3_000)
        t.clear()
        assertNull(t.onConfirmed(5))
        assertFalse(t.full(0))
    }

    @Test
    fun aSendExpectingNoAckTakesNoSlot() {
        val t = MeshcoreAckTable()
        t.onSent(code = 5, handle = 1u, now = 0, windowMs = 3_000)
        repeat(20) { t.onSent(code = 0, handle = null, now = 0, windowMs = 3_000) }
        assertTrue(t.isLive(5))
    }

    // --- MeshcorePins (binding rules "sends" 1 and 2) ---

    private val alice = FakeMeshcoreAir.keyOf("alice")
    private val bob = FakeMeshcoreAir.keyOf("bob")
    private val mallory = FakeMeshcoreAir.keyOf("mallory")

    @Test
    fun onlyTheContactPlumbingFeedsTheKeyMap() {
        val p = MeshcorePins()
        assertNull(p.keyFor(MeshcorePins.addrOf(bob)!!))
        // A push for a key nobody taught keeps nothing: an advert flood costs no state.
        p.onPush(mallory, deleted = false, now = 0)
        assertNull(p.keyFor(MeshcorePins.addrOf(mallory)!!))
        p.learn(bob)
        assertTrue(p.keyFor(MeshcorePins.addrOf(bob)!!)!!.contentEquals(bob))
        assertNull("a key of the wrong length is refused", p.learn(ByteArray(31)))
    }

    @Test
    fun aPrefixAnotherTaughtKeyHoldsIsRefusedAndOnlyTaughtKeysAreSentTo() {
        val p = MeshcorePins()
        val sharer = bob.copyOf().also { it[31] = (it[31] + 1).toByte() }
        p.setTaught(listOf(bob))
        assertNull("the board would send to the first contact the prefix matches", p.learn(sharer))
        assertNull("and again: each refusal counted", p.learn(sharer))
        assertFalse(p.prefixFree(sharer))
        assertTrue(p.keyFor(MeshcorePins.addrOf(bob)!!)!!.contentEquals(bob))
        assertEquals(2L, p.refusedPrefixes)
        p.contest(MeshcorePins.addrOf(bob)!!)
        assertNull("contested for the connection", p.keyFor(MeshcorePins.addrOf(bob)!!))
        p.onReady(now = 0)
        assertNull("a new Ready keeps the last listing's contests (lora N3)", p.keyFor(MeshcorePins.addrOf(bob)!!))
        p.setContested(emptyList())
        assertTrue("until a whole listing clears them", p.keyFor(MeshcorePins.addrOf(bob)!!)!!.contentEquals(bob))
        p.setTaught(listOf(alice))
        assertNull("a key dropped from the taught set is never sent to", p.keyFor(MeshcorePins.addrOf(bob)!!))
        assertFalse(p.teaches(bob))
    }

    @Test
    fun contestsAreKeptPerOwnBoardAndReplacedOnlyByAWholeListing() {
        val p = MeshcorePins()
        val boardA = MeshcorePins.addrOf(FakeMeshcoreAir.keyOf("a"))!!
        val boardB = MeshcorePins.addrOf(FakeMeshcoreAir.keyOf("b"))!!
        val addr = MeshcorePins.addrOf(bob)!!
        p.setTaught(listOf(bob))
        p.onReady(now = 0, board = boardA)
        p.setContested(listOf(addr))
        p.onReady(now = 1, board = boardA)
        assertNull("the same board, no whole listing yet (a short or cut one replaces nothing): kept", p.keyFor(addr))
        p.setContested(emptyList())
        assertTrue("a whole listing replaces them", p.keyFor(addr)!!.contentEquals(bob))
        p.setContested(listOf(addr))
        p.onReady(now = 2, board = boardB)
        assertTrue("a rebind to another board clears them", p.keyFor(addr)!!.contentEquals(bob))
    }

    @Test
    fun aPushForATaughtKeyQueuesOneRepinAMinuteAndOnlyMarksInBetween() {
        val p = MeshcorePins()
        p.setTaught(listOf(alice, bob))
        p.onReady(now = -120_000)
        val addr = MeshcorePins.addrOf(bob)!!
        p.onPinned(addr)
        assertEquals(MeshcorePins.PushAction.IGNORED, p.onPush(mallory, deleted = false, now = 0))
        assertEquals(MeshcorePins.PushAction.QUEUED, p.onPush(bob, deleted = false, now = 0))
        assertFalse("a path push unverifies it", p.isVerified(addr))
        repeat(10) { assertEquals(MeshcorePins.PushAction.MARKED, p.onPush(bob, deleted = false, now = 1_000L + it)) }
        val job = p.nextJob()!!
        assertTrue(job.first.contentEquals(bob))
        assertEquals(MeshcorePins.Job.PIN, job.second)
        assertNull("one job, however many pushes", p.nextJob())
        assertEquals(MeshcorePins.PushAction.QUEUED, p.onPush(bob, deleted = true, now = 60_000))
        assertEquals("a deletion is re-taught, not pinned", MeshcorePins.Job.TEACH, p.nextJob()!!.second)
    }

    @Test
    fun readyEmptiesVerifiedAndReturnsTheRankedReassert() {
        val p = MeshcorePins()
        p.setTaught(listOf(bob, alice))
        p.onPinned(MeshcorePins.addrOf(bob)!!)
        val reassert = p.onReady(now = 0)
        assertEquals(listOf(bob.toList(), alice.toList()), reassert.map { it.toList() })
        assertFalse(p.isVerified(MeshcorePins.addrOf(bob)!!))
    }

    @Test
    fun aTaughtSetAnswersTheKeysItAddedAndATeachIsQueuedOnceForATaughtKeyOnly() {
        // ADR 2026-11.gsqu's Stage 5c-2b amendment: a key taught mid-connection is written at once.
        val p = MeshcorePins()
        assertEquals(listOf(bob.toList()), p.setTaught(listOf(bob)).map { it.toList() })
        assertEquals("the same set adds nothing", emptyList<List<Byte>>(), p.setTaught(listOf(bob)).map { it.toList() })
        assertEquals("only the newcomer", listOf(alice.toList()), p.setTaught(listOf(bob, alice)).map { it.toList() })
        assertEquals(MeshcorePins.TeachQueue.QUEUED, p.queueTeach(alice, DAY))
        assertEquals(MeshcorePins.TeachQueue.QUEUED, p.queueTeach(alice, DAY))
        assertEquals(MeshcorePins.TeachQueue.REFUSED, p.queueTeach(mallory, DAY))
        val job = p.nextJob()!!
        assertTrue(job.first.contentEquals(alice))
        assertEquals(MeshcorePins.Job.TEACH, job.second)
        assertNull("one job for alice, none for a key not taught", p.nextJob())
        p.queueTeach(alice, DAY)
        p.setTaught(listOf(bob))
        assertNull("a key that left the set drops its job", p.nextJob())
    }

    @Test
    fun liveTeachesOfOneKeyAreBoundedADayAndDeferredNeverDropped() {
        // The 5c-2b review's N2: a board map flapping a key in and out costs a removal and a write each time.
        val p = MeshcorePins()
        p.setTaught(listOf(bob, alice))
        repeat(MeshcorePins.MAX_LIVE_TEACHES_PER_DAY) {
            assertEquals(MeshcorePins.TeachQueue.QUEUED, p.queueTeach(bob, DAY))
            p.nextJob()
            p.onLiveTeachSpent(bob, DAY, written = true)
        }
        assertEquals("past the bound: left to the next Ready", MeshcorePins.TeachQueue.DEFERRED, p.queueTeach(bob, DAY))
        assertNull(p.nextJob())
        assertTrue("deferred, still taught: the Ready's reconcile writes it", p.teaches(bob))
        assertEquals("a new UTC day", MeshcorePins.TeachQueue.QUEUED, p.queueTeach(bob, DAY + 1))
        assertEquals("another key has its own bound", MeshcorePins.TeachQueue.QUEUED, p.queueTeach(alice, DAY))
    }

    @Test
    fun aContestedKeyIsNeverQueuedForATeachAndAFailedOneIsRetriedOnce() {
        // The 5c-2b review's N3 and N4.
        val p = MeshcorePins()
        p.setTaught(listOf(bob, alice))
        p.contest(MeshcorePins.addrOf(bob)!!)
        assertEquals(MeshcorePins.TeachQueue.REFUSED, p.queueTeach(bob, DAY))
        assertFalse(p.retryTeachOnce(bob))
        assertNull(p.nextJob())
        assertTrue("retried once", p.retryTeachOnce(alice))
        assertEquals(MeshcorePins.Job.TEACH, p.nextJob()!!.second)
        assertFalse("never looped", p.retryTeachOnce(alice))
        p.onLiveTeachSpent(alice, DAY, written = false)
        assertFalse("a write that failed its read-back spends no retry anew", p.retryTeachOnce(alice))
        p.onLiveTeachSpent(alice, DAY, written = true)
        assertTrue("a write spends the retry anew", p.retryTeachOnce(alice))
        p.nextJob()
        p.onReady(now = 0)
        assertTrue("and so does a Ready", p.retryTeachOnce(alice))
    }

    // --- MeshcoreHeadroom (binding rule "sends" 5) ---

    @Test
    fun tableFullHoldsTheHeadroomAtZeroUntilAPollShowsProgress() {
        val h = MeshcoreHeadroom()
        assertEquals(14, h.onPoll(outboundQueue = 2, sent = 100))
        assertEquals(0, h.onTableFull())
        assertEquals("the queue reads low, but the pool is shared with received packets", 0, h.onPoll(2, 100))
        assertEquals(0, h.onPoll(3, 100))
        assertEquals("sent rose: the board is draining", 15, h.onPoll(1, 101))
        assertFalse(h.holding)
    }

    @Test
    fun aFallingQueueLiftsTheHoldAndAnOverlongQueueIsClamped() {
        val h = MeshcoreHeadroom()
        h.onPoll(outboundQueue = 9, sent = 5)
        h.onTableFull()
        assertEquals(0, h.onPoll(9, null))
        assertEquals(MeshcoreHeadroom.POOL - 8, h.onPoll(8, null))
        assertEquals("a lying board's queue never reads negative headroom", 0, h.onPoll(250, 6))
    }

    @Test
    fun aHoldWithNothingKnownBeforeItTakesItsFirstPollAsTheBaseline() {
        val h = MeshcoreHeadroom()
        h.onTableFull()
        assertEquals(0, h.onPoll(4, 10))
        assertEquals(0, h.onPoll(4, 10))
        assertEquals(13, h.onPoll(3, 10))
    }

    @Test
    fun aTableFullDrawnByReceivedPacketsOnAQuietBoardLiftsAtTheNextEmptyQueue() {
        // Received packets filled the shared pool while our queue read 0; a quiet board then shows no progress at all.
        val h = MeshcoreHeadroom()
        h.onPoll(outboundQueue = 0, sent = 40)
        assertEquals(0, h.onTableFull())
        assertEquals(MeshcoreHeadroom.POOL, h.onPoll(0, 40))
        assertFalse(h.holding)
    }

    @Test
    fun aHoldWithNoProgressLiftsAfterItsBoundedPolls() {
        val h = MeshcoreHeadroom()
        h.onPoll(outboundQueue = 5, sent = 40)
        h.onTableFull()
        repeat(MeshcoreHeadroom.MAX_HELD_POLLS) { assertEquals(0, h.onPoll(5, 40)) }
        assertEquals("a hold lifted early costs one refused write, which holds again", 11, h.onPoll(5, 40))
    }

    @Test
    fun aRebootLiftsTheHold() {
        val h = MeshcoreHeadroom()
        h.onPoll(16, 500)
        h.onTableFull()
        assertEquals(16, h.onPoll(0, 3))
    }

    // --- MeshcoreAirRing (ADR 2026-11.gsqu §6) ---

    @Test
    fun theRingReadsOnlyOnceItSpansAMinuteAndThenOverItsWholeSpan() {
        val r = MeshcoreAirRing()
        assertNull(r.onSample(0, txAirSecs = 0, rxAirSecs = 0))
        for (t in 1..59) assertNull(r.onSample(t * 1_000L, 0, t * 3L / 10))
        val reading = r.onSample(60_000, txAirSecs = 6, rxAirSecs = 18)!!
        assertEquals(40f, reading.channelUtilPercent, 0.01f)
        assertEquals(10f, reading.airUtilTxPercent, 0.01f)
        // A minute on, the baseline has moved with the ring.
        val later = r.onSample(120_000, txAirSecs = 6, rxAirSecs = 24)!!
        assertEquals(10f, later.channelUtilPercent, 0.01f)
    }

    @Test
    fun aCounterGoingBackwardsRestartsTheRing() {
        val r = MeshcoreAirRing()
        r.onSample(0, 100, 100)
        r.onSample(60_000, 110, 110)
        assertNull("the board rebooted: no reading across it", r.onSample(61_000, 1, 1))
        assertNull(r.onSample(100_000, 2, 2))
        assertTrue(r.onSample(121_000, 3, 3) != null)
    }
}

/** A UTC day number, as the session's live-teach bound counts them. */
private const val DAY = 20_400L
