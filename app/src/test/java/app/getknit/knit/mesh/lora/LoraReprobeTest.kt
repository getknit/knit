package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The re-probe's schedule (ADR 2026-10.rp3s): a window after the hold, doubling to the cap, reset by a hearing or an ack. */
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
        p.onReprobed("bob", "f1", held = setOf("f1"), now = base)
        p.onHeld("bob", now = base + 1) // another DM, still unheard: not evidence bob is back
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
            p.onReprobed("bob", "f1", held = setOf("f1"), now = now)
            val entry = p.stamps().single()
            gaps += entry.gapMs
            assertTrue("not due again before its gap", p.due(entry.dueAt - 1).isEmpty())
            now = entry.dueAt
        }
        assertEquals(listOf(2 * base, 4 * base, 8 * base, cap, cap, cap, cap), gaps)
    }

    @Test
    fun anAckedDmResetsTheGapToTheBase() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f2", held = setOf("f1", "f2"), now = base)
        p.onReprobed("bob", "f2", held = setOf("f1", "f2"), now = 3 * base)
        assertEquals(4 * base, p.stamps().single().gapMs)
        // f2 — what the last re-probe aired — is no longer held: acked. The next gap starts over.
        p.onReprobed("bob", "f1", held = setOf("f1"), now = 7 * base)
        assertEquals(base, p.stamps().single().gapMs)
    }

    @Test
    fun aHearingResetsTheScheduleToOneBaseGap() {
        val p = policy()
        p.onHeld("bob", now = 0)
        p.onReprobed("bob", "f1", held = setOf("f1"), now = base)
        p.onHeard("bob", now = 2 * base)
        val entry = p.stamps().single()
        assertEquals(base, entry.gapMs)
        assertEquals(3 * base, entry.dueAt)
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
        p.forget("bob")
        assertTrue(p.due(Long.MAX_VALUE / 2).isEmpty())
        p.onHeard("bob", now = 0) // a hearing does not resurrect a recipient with nothing held
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
