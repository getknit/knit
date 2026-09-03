package app.getknit.knit.presence

import app.getknit.knit.presence.OpenToChatPolicy.ABSENCE_MS
import app.getknit.knit.presence.OpenToChatPolicy.ALERT_GAP_MS
import app.getknit.knit.presence.OpenToChatPolicy.DAY_MS
import app.getknit.knit.presence.OpenToChatPolicy.HOLD_MS
import app.getknit.knit.presence.OpenToChatPolicy.State
import app.getknit.knit.presence.OpenToChatPolicy.dueAt
import app.getknit.knit.presence.OpenToChatPolicy.flush
import app.getknit.knit.presence.OpenToChatPolicy.fold
import app.getknit.knit.presence.OpenToChatPolicy.qualifying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The open-to-chat cue's rules on a fixed clock: one cue per encounter, batched, capped per person and per hour. */
class OpenToChatPolicyTest {
    private val t0 = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    private fun s(vararg ids: String) = ids.toSet()

    /** A first cue about `a`: arrival at [t0], posted at the end of the hold. */
    private fun named(peer: String = "a"): State = flush(fold(State(), s(peer), t0), t0 + HOLD_MS).state

    @Test
    fun qualifyingNeedsTheOwnFlagIntersectsTheTwoSetsAndDropsTheBlocked() {
        assertEquals(emptySet<String>(), qualifying(false, s("a", "b"), s("a", "b"), emptySet(), emptySet()))
        assertEquals(s("a"), qualifying(true, s("a", "b"), s("a", "c"), emptySet(), emptySet()))
        assertEquals(emptySet<String>(), qualifying(true, s("a"), s("a"), s("a"), emptySet()))
    }

    @Test
    fun qualifyingDropsAnyoneWeHaveAlreadyExchangedMessagesWith() {
        // The cue introduces strangers. Two people who already message each other — a couple, say — would
        // otherwise be re-introduced every day for as long as they both left the flag on.
        assertEquals(s("b"), qualifying(true, s("a", "b"), s("a", "b"), emptySet(), s("a")))
        assertEquals(emptySet<String>(), qualifying(true, s("a"), s("a"), emptySet(), s("a")))
    }

    @Test
    fun aFirstArrivalIsHeldForTheWindowThenNamedOnce() {
        val held = fold(State(), s("a"), t0)
        assertEquals(t0 + HOLD_MS, dueAt(held))
        val cue = flush(held, t0 + HOLD_MS)
        assertEquals(listOf("a"), cue.show)
        assertEquals(listOf(t0 + HOLD_MS), cue.state.named["a"])
        assertNull(dueAt(cue.state))
        // Hours of continuous presence: no departure, so no arrival, so nothing pending.
        assertTrue(fold(cue.state, s("a"), t0 + 5 * hour).pending.isEmpty())
    }

    @Test
    fun walkingIntoAFullRoomIsOneCueInArrivalOrder() {
        val ids = (1..12).map { "p$it" }
        var state = State()
        ids.forEachIndexed { i, _ -> state = fold(state, ids.take(i + 1).toSet(), t0 + i * 1_250L) }
        assertEquals("anchored on the oldest arrival", t0 + HOLD_MS, dueAt(state))
        assertEquals(ids, flush(state, t0 + HOLD_MS).show)
    }

    @Test
    fun aSightingLingerFlapNeverReCues() {
        var state = named()
        val gone = t0 + 3 * hour // well past the per-peer cooldown, so only the absence rule is in play
        state = fold(state, emptySet(), gone)
        state = fold(state, s("a"), gone + 150_000L) // back inside the Wi-Fi Aware linger
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun aRealDepartureInsideTheCooldownStaysQuiet() {
        var state = named()
        state = fold(state, emptySet(), t0 + 30 * 60_000L)
        state = fold(state, s("a"), t0 + hour) // away 30 min, but only an hour since the cue
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun aRealDepartureAfterTheCooldownReCues() {
        var state = named()
        state = fold(state, emptySet(), t0 + 2 * hour)
        state = fold(state, s("a"), t0 + 2 * hour + ABSENCE_MS)
        assertEquals(s("a"), state.pending.keys)
    }

    @Test
    fun aThirdCueAboutOnePersonInsideADayIsRefused() {
        var state = named() // cue 1 at t0 + hold
        state = fold(state, emptySet(), t0 + hour)
        state = fold(state, s("a"), t0 + 3 * hour)
        assertEquals("second encounter of the day", s("a"), state.pending.keys)
        state = flush(state, t0 + 3 * hour + HOLD_MS).state // cue 2
        state = fold(state, emptySet(), t0 + 4 * hour)
        state = fold(state, s("a"), t0 + 6 * hour)
        assertTrue("a third inside 24 h is refused", state.pending.isEmpty())
        state = fold(state, emptySet(), t0 + 7 * hour)
        state = fold(state, s("a"), t0 + DAY_MS + 30_000L)
        assertEquals("the first cue has aged out of the day", s("a"), state.pending.keys)
    }

    @Test
    fun theOwnFlagOffEmptiesTheBatchAndOnReQueuesEveryoneEligible() {
        var state = fold(State(), s("a", "b"), t0)
        state = fold(state, emptySet(), t0 + 5_000L) // own flag off: nobody qualifies
        assertTrue(state.pending.isEmpty())
        assertNull(dueAt(state))
        state = fold(state, s("a", "b"), t0 + 6_000L) // back on: never named, so both arrive
        assertEquals(s("a", "b"), state.pending.keys)
    }

    @Test
    fun aNewcomerInsideTheHourlyGapIsHeldUntilItEndsAndDroppedIfGoneByThen() {
        var state = named()
        val posted = t0 + HOLD_MS
        state = fold(state, s("a", "b"), posted + 30 * 60_000L)
        assertEquals(posted + ALERT_GAP_MS, dueAt(state))
        state = fold(state, s("a"), posted + 40 * 60_000L) // b left before the gap ended
        assertNull(dueAt(state))
        state = fold(state, s("a", "b"), posted + 50 * 60_000L) // b is back: held, still on the same gap
        assertEquals(posted + ALERT_GAP_MS, dueAt(state))
        assertEquals(listOf("b"), flush(state, posted + ALERT_GAP_MS).show)
    }

    @Test
    fun aDepartedPeerIsDroppedFromThePendingBatch() {
        var state = fold(State(), s("a", "b"), t0)
        state = fold(state, s("a"), t0 + 5_000L)
        assertEquals(listOf("a"), flush(state, t0 + HOLD_MS).show)
    }

    @Test
    fun afterARestartTheCooldownAndTheCapDecideWithoutADepartureClock() {
        val remembered = State(named = mapOf("a" to listOf(t0)))
        assertTrue("inside the cooldown", fold(remembered, s("a"), t0 + hour).pending.isEmpty())
        assertEquals("past it, absence unknowable", s("a"), fold(remembered, s("a"), t0 + 3 * hour).pending.keys)
        val capped = State(named = mapOf("a" to listOf(t0, t0 + 3 * hour)))
        assertTrue("two today already", fold(capped, s("a"), t0 + 6 * hour).pending.isEmpty())
    }

    @Test
    fun pruneKeepsOnlyTheLastDayOfStamps() {
        val named = mapOf("a" to listOf(t0 - DAY_MS - 1, t0 - hour), "b" to listOf(t0 - 2 * DAY_MS))
        assertEquals(mapOf("a" to listOf(t0 - hour)), OpenToChatPolicy.prune(named, t0))
    }

    @Test
    fun theNamedCodecRoundTripsSeveralStampsPerPeerAndSkipsGarbage() {
        val named = mapOf("a" to listOf(1L, 5L), "b|c" to listOf(7L))
        val encoded = OpenToChatPolicy.encodeNamed(named)
        assertEquals(setOf("a|1", "a|5", "b|c|7"), encoded)
        assertEquals(named, OpenToChatPolicy.decodeNamed(encoded + setOf("garbage", "|9", "x|notanumber")))
    }
}
