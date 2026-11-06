package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The era, floor, bound and age of the sealed DM re-ack ledger (#176, ADR 2026-10.8hn6). Pure: the clock is a
 * parameter. The custody half of the rule, and who records, are `InboundPipelineTest`'s.
 */
class DmReAckLedgerTest {
    /** #300: a claim excludes a second sealer of one id, and of that id alone, until it is let go. */
    @Test
    fun aClaimIsOnePathsUntilItIsLetGo() {
        val ledger = DmReAckLedger()

        assertTrue(ledger.claim(A, "dm"))
        assertFalse("a second path reads the id as answered", ledger.claim(A, "dm"))
        assertTrue("another id is another seal", ledger.claim(A, "other"))

        ledger.release(A, listOf("dm"))
        assertTrue(ledger.claim(A, "dm"))
        assertFalse("the other id's claim stands", ledger.claim(A, "other"))
    }

    /** The author chooses the id, so a claim and an entry are keyed by author: one author's touch nothing of another's (#300). */
    @Test
    fun twoAuthorsOfOneIdShareNeitherAClaimNorAnEntry() {
        val ledger = DmReAckLedger()

        assertTrue(ledger.claim(B, "dm"))
        assertTrue("another author's claim is another seal", ledger.claim(A, "dm"))
        ledger.release(B, listOf("dm"))
        assertFalse("and letting go of one leaves the other", ledger.claim(A, "dm"))

        ledger.record(A, "dm", rootTag = 7L, receiptId = "ra", now = T)
        ledger.record(A, "dm", rootTag = 7L, receiptId = "ra2", now = T + 1)
        ledger.record(B, "dm", rootTag = 9L, receiptId = "rb", now = T + 2)

        assertEquals("her root and floor step stand", 1, ledger.entry(A, "dm")?.step)
        assertEquals("ra2", ledger.heldReceipt(A, "dm", 7L, T + 3))
        assertEquals("rb", ledger.heldReceipt(B, "dm", 9L, T + 3))
        assertNull("a root binds to its own author", ledger.heldReceipt(B, "dm", 7L, T + 3))
    }

    /** A loser that held its id tells the winner, whose release then says so; with the winner gone, the loser hears that. */
    @Test
    fun aContestIsHeardByWhicheverSideActsLast() {
        val ledger = DmReAckLedger()

        ledger.claim(A, "dm")
        assertTrue(ledger.contest(A, "dm"))
        assertTrue("the winner has the hold flushed", ledger.release(A, listOf("dm", "other")))

        assertFalse("the winner is gone: the loser asks for it itself", ledger.contest(A, "dm"))
        ledger.claim(A, "dm")
        assertFalse("no contest, no flush", ledger.release(A, listOf("dm")))
    }

    /** A contested id early in a batch must not cut the release short: the ids after it are let go as well. */
    @Test
    fun releasingABatchLetsGoOfEveryIdPastAContestedOne() {
        val ledger = DmReAckLedger()
        ledger.claim(A, "x")
        ledger.claim(A, "y")
        ledger.contest(A, "x")

        assertTrue(ledger.release(A, listOf("x", "y")))
        assertTrue(ledger.claim(A, "y"))
    }

    /** A claim says only that a seal is in flight: it files nothing, so a seal that gave up leaves no answer behind. */
    @Test
    fun aClaimFilesNoReceipt() {
        val ledger = DmReAckLedger()

        ledger.claim(A, "dm")
        ledger.release(A, listOf("dm"))

        assertNull(ledger.heldReceipt(A, "dm", 7L, T))
        assertEquals(0, ledger.size)
    }

    /**
     * The race itself, which `MeshManagerTest`'s `aHeldTickSealedMeanwhileIsNotSealedAgain` cannot see on an unconfined
     * dispatcher (#300): threads released together on one id, on real cores, and exactly one takes it.
     */
    @Test
    fun threadsRacingForOneIdLeaveOneWinner() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(ROUNDS) { round ->
                val ledger = DmReAckLedger()
                val gate = CountDownLatch(1)
                val wins = AtomicInteger()
                val done =
                    List(threads) {
                        pool.submit {
                            gate.await()
                            if (ledger.claim(A, "dm-$round")) wins.incrementAndGet()
                        }
                    }
                gate.countDown()
                done.forEach { it.get() }
                assertEquals(1, wins.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aReceiptAnswersARedeliveryUnderItsOwnRootInsideTheFloor() {
        val ledger = DmReAckLedger()
        ledger.record(A, "dm", rootTag = 7L, receiptId = "r1", now = T)

        assertEquals("r1", ledger.heldReceipt(A, "dm", currentRootTag = 7L, now = T + DmReAckLedger.FLOOR_MS - 1))
        assertNull("past the floor", ledger.heldReceipt(A, "dm", 7L, T + DmReAckLedger.FLOOR_MS))
    }

    @Test
    fun anotherRootOrNoSessionReSeals() {
        val ledger = DmReAckLedger()
        ledger.record(A, "dm", rootTag = 7L, receiptId = "r1", now = T)

        assertNull("a reset moved the root", ledger.heldReceipt(A, "dm", currentRootTag = 8L, now = T + 1))
        assertNull("no session held now", ledger.heldReceipt(A, "dm", currentRootTag = null, now = T + 1))
        assertNull("never acked", ledger.heldReceipt(A, "other", 7L, T + 1))
    }

    @Test
    fun theFloorDoublesPerSameEraReSealUpToFourHours() {
        assertEquals(15 * MIN, DmReAckLedger.floorMs(0))
        assertEquals(30 * MIN, DmReAckLedger.floorMs(1))
        assertEquals(4 * 60 * MIN, DmReAckLedger.floorMs(4))
        assertEquals(4 * 60 * MIN, DmReAckLedger.floorMs(40))

        val ledger = DmReAckLedger()
        var now = T
        ledger.record(A, "dm", 7L, "r0", now)
        repeat(6) { i ->
            now += DmReAckLedger.floorMs(i)
            ledger.record(A, "dm", 7L, "r${i + 1}", now)
        }
        assertEquals(4, ledger.entry(A, "dm")?.step)
        assertEquals("r6", ledger.heldReceipt(A, "dm", 7L, now + DmReAckLedger.MAX_FLOOR_MS - 1))
    }

    @Test
    fun aNewEraStartsItsOwnFloor() {
        val ledger = DmReAckLedger()
        ledger.record(A, "dm", 7L, "r0", T)
        ledger.record(A, "dm", 7L, "r1", T + DmReAckLedger.FLOOR_MS)
        assertEquals(1, ledger.entry(A, "dm")?.step)

        ledger.record(A, "dm", 8L, "r2", T + DmReAckLedger.FLOOR_MS + 1)
        assertEquals(0, ledger.entry(A, "dm")?.step)
        assertEquals(8L, ledger.entry(A, "dm")?.rootTag)
    }

    @Test
    fun anEntryAgesOutATtlAfterItsLastReceipt() {
        val ledger = DmReAckLedger(ttlMs = HOUR)
        ledger.record(A, "dm", 7L, "r1", T)

        assertNull(ledger.heldReceipt(A, "dm", 7L, T + HOUR))
        assertNull("and is dropped", ledger.entry(A, "dm"))
        assertEquals(0, ledger.size)
    }

    /** Our clock stepped back past the receipt's stamp: read as past the floor, so the re-arm is not held back by the jump. */
    @Test
    fun aClockSteppedBackBeforeTheReceiptReSeals() {
        val ledger = DmReAckLedger()
        ledger.record(A, "dm", 7L, "r1", T)

        assertNull(ledger.heldReceipt(A, "dm", 7L, T - 1))
        assertNull("and the entry is dropped", ledger.entry(A, "dm"))
    }

    @Test
    fun theBoundEvictsTheOldestReceiptFirst() {
        val ledger = DmReAckLedger(cap = 3)
        ledger.record(A, "a", 7L, "ra", T)
        ledger.record(A, "b", 7L, "rb", T + 1)
        ledger.record(A, "c", 7L, "rc", T + 2)
        // A re-seal of the oldest moves it to the newest end.
        ledger.record(A, "a", 7L, "ra2", T + 3)
        ledger.record(A, "d", 7L, "rd", T + 4)

        assertEquals(3, ledger.size)
        assertNull("b was the oldest receipt", ledger.entry(A, "b"))
        assertEquals("ra2", ledger.entry(A, "a")?.receiptId)
        assertEquals("rd", ledger.heldReceipt(A, "d", 7L, T + 5))
    }

    private companion object {
        const val T = 1_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val ROUNDS = 200
        const val A = "alice"
        const val B = "bob"
    }
}
