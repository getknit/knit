package app.getknit.knit

import app.getknit.knit.mesh.DmAckCoalescer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DM-receipt coalescer (ADR 054) on a virtual clock: one tick per author per hold, and the piggyback seams. */
@OptIn(ExperimentalCoroutinesApi::class)
class DmAckCoalescerTest {
    private val flushes = mutableListOf<Pair<String, List<String>>>()

    @Test
    fun oneTimerPerAuthorFlushesEverythingHeldOnceTheOldestIdIsDue() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            advanceTimeBy(20_000)
            c.hold("alice", "m2")
            c.hold("bob", "b1")
            advanceTimeBy(DmAckCoalescer.HOLD_MS - 20_000 + 1)
            runCurrent()
            // alice's batch is anchored on m1, so m2 did not push it out; bob's own timer is still running.
            assertEquals(listOf("alice" to listOf("m1", "m2")), flushes)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf("alice" to listOf("m1", "m2"), "bob" to listOf("b1")), flushes)
            assertTrue(c.pending("alice").isEmpty())
        }

    @Test
    fun aReDeliveredIdIsHeldOnce() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            c.hold("alice", "m1")
            c.hold("alice", "m1")
            assertEquals(listOf("m1"), c.pending("alice"))
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertEquals(listOf("alice" to listOf("m1")), flushes)
        }

    @Test
    fun aFullBatchFlushesAtOnceAndTheNextIdOpensAFreshOne() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, maxBatch = 2)
            c.hold("alice", "m1")
            assertTrue(flushes.isEmpty())
            c.hold("alice", "m2")
            assertEquals(listOf("alice" to listOf("m1", "m2")), flushes)
            c.hold("alice", "m3")
            assertEquals(listOf("m3"), c.pending("alice"))
        }

    @Test
    fun flushDueIsTheBackstopWhenNoScopeRunsTheTimer() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids })
            c.hold("alice", "m1")
            assertEquals(DmAckCoalescer.HOLD_MS, c.nextDueAt())
            c.flushDue()
            assertTrue("not due yet", flushes.isEmpty())
            advanceTimeBy(DmAckCoalescer.HOLD_MS)
            c.flushDue()
            assertEquals(listOf("alice" to listOf("m1")), flushes)
            assertNull(c.nextDueAt())
        }

    @Test
    fun takeCarriesTheOldestIdsAwayAndAnEmptiedBatchTicksNothing() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            c.hold("alice", "m2")
            c.hold("alice", "m3")
            assertEquals(listOf("m1", "m2"), c.take("alice", 2))
            assertEquals(listOf("m3"), c.pending("alice"))
            assertEquals(listOf("m3"), c.take("alice", 5))
            assertTrue(c.take("alice", 5).isEmpty())
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertTrue("the standalone tick was cancelled with the batch", flushes.isEmpty())
        }

    @Test
    fun givenBackIdsReEnterTheHold() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.hold("alice", "m1")
            val taken = c.take("alice", 1)
            c.giveBack("alice", taken)
            assertEquals(listOf("m1"), c.pending("alice"))
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertEquals(listOf("alice" to listOf("m1")), flushes)
        }

    @Test
    fun pastThePendingCapTheOldestAuthorFlushesEarly() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, pendingCap = 2)
            c.hold("alice", "m1")
            advanceTimeBy(1)
            c.hold("bob", "b1")
            assertEquals(listOf("alice" to listOf("m1")), flushes)
            assertEquals(listOf("b1"), c.pending("bob"))
        }

    // --- the ride-only hold (ADR 2026-10.x9fa's 2026-11-19 amendment) ---

    @Test
    fun aRideOnlyIdNeverFlushesOnTheTimerOrTheHeartbeat() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.holdRiding("alice", listOf("m1", "m2"))
            advanceTimeBy(DmAckCoalescer.HOLD_MS * 10)
            runCurrent()
            c.flushDue()
            assertTrue("a ride-only id waits for a ride, never a timer", flushes.isEmpty())
            assertNull(c.nextDueAt())
            assertEquals(listOf("m1", "m2"), c.riding("alice"))
            assertEquals(2, c.ridingFor("alice"))
            assertTrue("the timed hold is untouched", c.pending("alice").isEmpty())
        }

    @Test
    fun takeRidingCarriesTheOldestAndAGiveBackKeepsThemRideOnly() =
        runTest {
            val c =
                DmAckCoalescer(now = { testScheduler.currentTime }, flush = {
                    a,
                    ids,
                    ->
                    flushes += a to ids
                }, flushScope = { backgroundScope })
            c.holdRiding("alice", listOf("m1"))
            advanceTimeBy(1)
            c.holdRiding("alice", listOf("m2", "m3"))
            assertEquals(listOf("m1", "m2"), c.takeRiding("alice", 2))
            assertEquals(listOf("m3"), c.riding("alice"))
            c.giveBackRiding("alice", listOf("m1", "m2"))
            assertEquals(listOf("m3", "m1", "m2"), c.riding("alice"))
            assertTrue(c.pending("alice").isEmpty())
            advanceTimeBy(DmAckCoalescer.HOLD_MS + 1)
            runCurrent()
            assertTrue("a given-back rider never re-enters the timed hold", flushes.isEmpty())
            assertTrue(c.takeRiding("alice", 0).isEmpty())
        }

    @Test
    fun releaseRidingFlushesTheRidersWithTheTimedBatchAsOneTick() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids })
            c.releaseRiding("alice")
            assertTrue("nothing waiting for a ride: no flush", flushes.isEmpty())
            c.hold("alice", "m3")
            c.releaseRiding("alice")
            assertTrue("the timed hold keeps its own time when no rider waits", flushes.isEmpty())
            assertEquals(listOf("m3"), c.pending("alice"))
            c.holdRiding("alice", listOf("m1", "m2"))
            c.releaseRiding("alice")
            assertEquals(listOf("alice" to listOf("m1", "m2", "m3")), flushes)
            assertTrue(c.riding("alice").isEmpty())
            assertTrue(c.pending("alice").isEmpty())
            c.releaseRiding("alice")
            assertEquals("released once", 1, flushes.size)
        }

    @Test
    fun anIdTheTimedHoldHasIsNotAlsoHeldForARideAndAReDeliveryLeavesTheRide() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids })
            c.hold("alice", "m1")
            c.holdRiding("alice", listOf("m1", "m2"))
            assertEquals("the timed hold ticks m1 anyway", listOf("m2"), c.riding("alice"))
            c.hold("alice", "m2")
            assertTrue("a re-delivery re-holds it for a fresh tick", c.riding("alice").isEmpty())
            assertEquals(listOf("m1", "m2"), c.pending("alice"))
        }

    @Test
    fun theRideOnlyHoldIsCappedPerAuthorAndAgesOut() =
        runTest {
            val c =
                DmAckCoalescer(
                    now = { testScheduler.currentTime },
                    flush = { a, ids -> flushes += a to ids },
                    maxBatch = 2,
                    rideTtlMs = 10_000,
                )
            c.holdRiding("alice", listOf("m1", "m2", "m3"))
            assertEquals("past the cap the oldest goes", listOf("m2", "m3"), c.riding("alice"))
            advanceTimeBy(10_000)
            assertEquals("a rider exactly rideTtlMs old is kept", 2, c.ridingFor("alice"))
            advanceTimeBy(1)
            assertEquals(0, c.ridingFor("alice"))
            // The heartbeat's sweep ages out a whole author.
            c.holdRiding("bob", listOf("b1"))
            advanceTimeBy(10_001)
            c.flushDue()
            assertTrue(c.riding("bob").isEmpty())
            // With no heartbeat in between, the release and the take age out on their own.
            c.holdRiding("carol", listOf("c1"))
            c.holdRiding("dave", listOf("d1"))
            advanceTimeBy(10_001)
            c.releaseRiding("carol")
            assertTrue("an aged-out rider is never ticked", flushes.isEmpty())
            assertTrue("an aged-out rider is never carried", c.takeRiding("dave", 4).isEmpty())
        }

    @Test
    fun pastTheGlobalCapTheLargestAuthorsOldestRidersGoFirst() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, ridingCap = 4)
            c.holdRiding("alice", listOf("a1", "a2", "a3"))
            c.holdRiding("bob", listOf("b1"))
            assertEquals("at the cap nothing goes", listOf("a1", "a2", "a3"), c.riding("alice"))
            c.holdRiding("carol", listOf("c1"))
            assertEquals("the largest author gives way, oldest first", listOf("a2", "a3"), c.riding("alice"))
            assertEquals(listOf("b1"), c.riding("bob"))
            assertEquals("the newcomer is kept", listOf("c1"), c.riding("carol"))
            assertEquals(4, listOf("alice", "bob", "carol").sumOf { c.ridingFor(it) })
        }

    @Test
    fun aGivenBackRiderKeepsItsAgeAndStillAgesOut() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, rideTtlMs = 10_000)
            c.holdRiding("alice", listOf("m1"))
            advanceTimeBy(8_000)
            val taken = c.takeRiding("alice", 1)
            c.giveBackRiding("alice", taken)
            advanceTimeBy(2_001)
            assertEquals("aged from when it first went ride-only, not from the give-back", 0, c.ridingFor("alice"))
            // An id never lent out starts its age at the give-back.
            c.giveBackRiding("alice", listOf("m2"))
            advanceTimeBy(9_999)
            assertEquals(listOf("m2"), c.riding("alice"))
        }

    @Test
    fun anAckOlderThanTheTtlIsForgottenNotTicked() =
        runTest {
            val c = DmAckCoalescer(now = { testScheduler.currentTime }, flush = { a, ids -> flushes += a to ids }, ttlMs = 10_000)
            c.hold("alice", "m1")
            advanceTimeBy(10_001)
            c.flushDue()
            assertTrue(flushes.isEmpty())
            assertTrue(c.pending("alice").isEmpty())
        }
}
