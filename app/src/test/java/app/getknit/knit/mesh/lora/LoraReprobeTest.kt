package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The re-probe's schedule (ADR 2026-10.rp3s, re-keyed by ADR 2026-10.x9fa): a base gap after the first DM, doubling to
 * the cap, reset by a ✓✓ or by the destination's board acking; a hearing after a failure pulls it forward but never
 * resets it.
 */
class LoraReprobeTest {
    private val base = 15 * 60_000L
    private val cap = 4 * 60 * 60_000L

    private fun policy() = LoraReprobe(baseMs = base, capMs = cap)

    @Test
    fun aHeldRecipientIsDueOneWindowAfterTheHoldAndNotBefore() {
        val p = policy()
        p.onHeld("bob", now = 0)
        assertEquals(emptyList<String>(), p.due(base - 1))
        assertEquals(listOf("bob"), p.due(base))
    }

    @Test
    fun aSecondHoldDoesNotResetTheBackoffItHasEarned() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f1", lastAcked = false, now = base)
        p.onHeld("bob", now = base + 1) // another DM: not evidence bob's phone is back
        assertEquals(2 * base, p.stamps().single().gapMs)
    }

    @Test
    fun eachUnansweredReprobeDoublesTheGapUpToTheCap() {
        val p = policy()
        p.onHeld("bob", now = 0)
        var now = base
        val gaps = mutableListOf<Long>()
        repeat(7) {
            assertEquals(listOf("bob"), p.due(now))
            p.onReprobed("bob", "f1", lastAcked = false, now = now)
            val entry = p.stamps().single()
            gaps += entry.gapMs
            assertTrue("not due again before its gap", p.due(entry.dueAt - 1).isEmpty())
            now = entry.dueAt
        }
        assertEquals(listOf(2 * base, 4 * base, 8 * base, cap, cap, cap, cap), gaps)
    }

    @Test
    fun aTickOnTheLastReprobedDmResetsTheGapToTheBase() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f2", lastAcked = false, now = base)
        p.onReprobed("bob", "f2", lastAcked = false, now = 3 * base)
        assertEquals(4 * base, p.stamps().single().gapMs)
        // f2 — what the last re-probe aired — has its ✓✓ now. The next gap starts over.
        p.onReprobed("bob", "f1", lastAcked = true, now = 7 * base)
        assertEquals(base, p.stamps().single().gapMs)
        assertEquals("f1", p.lastFrameOf("bob"))
    }

    @Test
    fun aDestinationAckOfAFreshDmStartsTheScheduleOver() {
        // The board took a DM the user just wrote; the phone may still be away from it (ADR 2026-10.x9fa).
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f1", lastAcked = false, now = base)
        p.onReprobed("bob", "f1", lastAcked = false, now = 3 * base)
        p.onAcked("bob", now = 4 * base, fresh = true)
        val entry = p.stamps().single()
        assertEquals(base, entry.gapMs)
        assertEquals(5 * base, entry.dueAt)
    }

    @Test
    fun aDestinationAckOfAReairedDmKeepsTheEarnedGap() {
        // A board that acks everything while its phone stays away must not pin the re-probe at the base gap.
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f1", lastAcked = false, now = base) // gap 2·base
        p.onAcked("bob", now = base + 1_000, fresh = false)
        val entry = p.stamps().single()
        assertEquals(2 * base, entry.gapMs)
        assertEquals(base + 1_000 + 2 * base, entry.dueAt)
    }

    @Test
    fun aHearingRightAfterAFailurePullsTheRoundForwardWithoutResettingTheGap() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f1", lastAcked = false, now = base) // gap 2·base
        p.onFailed("bob", now = base + 60_000)
        p.defer("bob", now = 2 * base) // a covered round: due 4·base
        assertFalse("too soon after the failure to resend", p.onHeard("bob", now = 2 * base + 1))
        val entry = p.stamps().single()
        assertEquals("the gap stands", 2 * base, entry.gapMs)
        assertEquals("due one gap after the failure, not later", base + 60_000 + 2 * base, entry.dueAt)
        p.onFailed("bob", now = 3 * base)
        assertFalse(p.onHeard("bob", now = 3 * base + 1))
        assertEquals("never pushed later by a hearing", base + 60_000 + 2 * base, p.stamps().single().dueAt)
    }

    @Test
    fun aHearingAGapAfterTheFailureMayResendNow() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onFailed("bob", now = 10)
        assertTrue(p.onHeard("bob", now = 10 + base))
        assertEquals("a hearing never resets the gap", base, p.stamps().single().gapMs)
    }

    @Test
    fun aHearingWithNoFailureThisProcessSawMayResendNow() {
        val p = policy()
        p.onHeld("bob", now = 0)
        assertTrue(p.onHeard("bob", now = 1))
    }

    @Test
    fun aHearingOfSomeoneWithNothingScheduledDoesNothing() {
        val p = policy()
        assertFalse(p.onHeard("bob", now = 0))
        assertTrue(p.stamps().isEmpty())
    }

    @Test
    fun aDeferredRoundWaitsOneGapWithoutDoublingIt() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.defer("bob", now = base)
        val entry = p.stamps().single()
        assertEquals(base, entry.gapMs)
        assertEquals(2 * base, entry.dueAt)
    }

    @Test
    fun aForgottenRecipientIsNeverDueAgain() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onFailed("bob", now = 1)
        p.forget("bob")
        assertTrue(p.due(Long.MAX_VALUE / 2).isEmpty())
        assertFalse("a hearing does not resurrect a peer with nothing unacked", p.onHeard("bob", now = 0))
        p.onAcked("bob", now = 0, fresh = true)
        assertTrue(p.stamps().isEmpty())
    }

    @Test
    fun restoredSchedulesKeepTheirGapClampedIntoTheRange() {
        val p = policy()
        p.restore(
            listOf(
                LoraReprobe.Entry("bob", dueAt = 100, gapMs = 4 * base, lastFrameId = "f1"),
                LoraReprobe.Entry("carol", dueAt = 50, gapMs = 10 * cap),
                LoraReprobe.Entry("dave", dueAt = 75, gapMs = 1),
            ),
        )
        assertEquals(listOf("carol", "dave", "bob"), p.due(100))
        val gaps = p.stamps().associate { it.recipientId to it.gapMs }
        assertEquals(mapOf("carol" to cap, "dave" to base, "bob" to 4 * base), gaps)
        assertEquals("f1", p.stamps().single { it.recipientId == "bob" }.lastFrameId)
    }
}
