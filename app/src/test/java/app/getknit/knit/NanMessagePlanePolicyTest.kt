package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanMessagePlanePolicy
import app.getknit.knit.mesh.wifiaware.NanMessagePlanePolicy.Action
import app.getknit.knit.mesh.wifiaware.NanMessagePlanePolicy.Decision
import app.getknit.knit.mesh.wifiaware.NanMessagePlanePolicy.Facts
import app.getknit.knit.mesh.wifiaware.NanMessagePlanePolicy.Tuning
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [NanMessagePlanePolicy] — the pure ack-starvation verdict and its per-episode cycle budget. */
class NanMessagePlanePolicyTest {
    private val t = Tuning.PRODUCTION
    private val wedgeCheckMs = 30_000L // the watchdog's own poll period
    private val start = 1_000_000L

    /** A healthy, acking node with two peers by default; each test overrides the axis it exercises. */
    @Suppress("LongParameterList") // mirrors the policy's Facts, one axis per parameter
    private fun decide(
        now: Long,
        healthy: Boolean = true,
        cueTargets: Int = 2,
        oldestUnansweredSentAt: Long = 0L,
        lastAckAt: Long = start,
        failsSinceAck: Int = 0,
        lastSightingAt: Long = now - 10_000L,
        episodeSince: Long = 0L,
        cycles: Int = 0,
        lastReattachAt: Long = 0L,
    ) = NanMessagePlanePolicy.decide(
        Facts(
            healthy = healthy,
            cueTargets = cueTargets,
            now = now,
            oldestUnansweredSentAt = oldestUnansweredSentAt,
            lastAckAt = lastAckAt,
            failsSinceAck = failsSinceAck,
            lastSightingAt = lastSightingAt,
            episodeSince = episodeSince,
            cycles = cycles,
            lastReattachAt = lastReattachAt,
        ),
        t,
    )

    @Test
    fun aHealthyAckingNodeIsNeverStalled() {
        val now = start + 600_000L
        assertEquals(Decision(Action.None, 0L, 0), decide(now = now, lastAckAt = now - 5_000L, failsSinceAck = 1))
    }

    @Test
    fun aSendTheFrameworkNeverAnswersIsSwallowed() {
        // Mechanism 1: the blocked framework queue. No callback for a whole watchdog tick, no ack since it went out.
        val now = start + 120_000L
        assertEquals(
            Decision(Action.CycleSession, now, 1),
            decide(now = now, oldestUnansweredSentAt = now - t.swallowedMs, lastAckAt = now - 40_000L),
        )
    }

    @Test
    fun anUnansweredSendIsNotSwallowedWhileAcksStillFlow() {
        // A lost callback with newer sends acked is a leak the transport reaps, not a wedge.
        val now = start + 120_000L
        assertEquals(
            Decision(Action.None, 0L, 0),
            decide(now = now, oldestUnansweredSentAt = now - 45_000L, lastAckAt = now - 2_000L),
        )
    }

    @Test
    fun aSlowButHealthyBurstIsNotSwallowed() {
        // Eight firmware slots each held a few seconds: the oldest send is young, and acks arrive.
        val now = start + 120_000L
        assertEquals(
            Decision(Action.None, 0L, 0),
            decide(now = now, oldestUnansweredSentAt = now - 12_000L, lastAckAt = now - 15_000L),
        )
    }

    @Test
    fun twoHeartbeatsOfFailuresWithAFreshSightingIsStarved() {
        // Mechanism 2: the firmware fails every follow-up while discovery keeps re-firing.
        val now = start + t.starvedMs
        assertEquals(
            Decision(Action.CycleSession, now, 1),
            decide(now = now, failsSinceAck = t.minFails, lastSightingAt = now - 30_000L),
        )
    }

    @Test
    fun failuresWithoutARecentSightingAreAPeerOutOfRangeNotAStall() {
        val now = start + 300_000L
        assertEquals(
            Decision(Action.None, 0L, 0),
            decide(now = now, failsSinceAck = 20, lastSightingAt = now - t.sightingFreshMs - 1L),
        )
    }

    @Test
    fun fewerFailuresThanTwoHeartbeatsProduceIsNotYetStarved() {
        val now = start + 300_000L
        assertEquals(Decision(Action.None, 0L, 0), decide(now = now, failsSinceAck = t.minFails - 1))
    }

    @Test
    fun aLonelyNodeIsNeverStarvedAndHoldsItsEpisode() {
        val now = start + 300_000L
        assertEquals(
            Decision(Action.None, start, 2),
            decide(now = now, cueTargets = 0, failsSinceAck = 20, lastAckAt = start - 1L, episodeSince = start, cycles = 2),
        )
        assertEquals(Decision(Action.None, 0L, 0), decide(now = now, cueTargets = 0, failsSinceAck = 20))
    }

    @Test
    fun anUnhealthyPlaneHoldsItsEpisode() {
        val now = start + 300_000L
        assertEquals(
            Decision(Action.None, start, 2),
            decide(now = now, healthy = false, failsSinceAck = 20, lastAckAt = start - 1L, episodeSince = start, cycles = 2),
        )
    }

    @Test
    fun theCyclesOwnNanDownDoesNotRefundTheBudget() {
        // A session cycle is a NAN disable/enable: for a beat the node is unhealthy and holds no cue targets.
        // Walk the watchdog cadence with that beat landing on every tick after a cycle; still three cycles.
        var now = start + t.starvedMs
        var episodeSince = 0L
        var cycles = 0
        var lastReattachAt = 0L
        var justCycled = false
        val actions = mutableListOf<Action>()
        repeat(12) {
            val d =
                decide(
                    now = now,
                    healthy = !justCycled,
                    cueTargets = if (justCycled) 0 else 2,
                    failsSinceAck = 20,
                    episodeSince = episodeSince,
                    cycles = cycles,
                    lastReattachAt = lastReattachAt,
                )
            actions += d.action
            episodeSince = d.nextEpisodeSince
            cycles = d.nextCycles
            justCycled = d.action == Action.CycleSession
            if (justCycled) lastReattachAt = now
            now += wedgeCheckMs
        }
        assertEquals(t.maxCycles, actions.count { it == Action.CycleSession })
        assertEquals(start + t.starvedMs, episodeSince)
    }

    @Test
    fun anAckAfterTheEpisodeBeganEndsItAndRefundsTheBudget() {
        val episode = start + 100_000L
        val now = episode + 50_000L
        assertEquals(
            Decision(Action.None, 0L, 0),
            decide(now = now, lastAckAt = episode + 1L, episodeSince = episode, cycles = 3, lastReattachAt = now - 1_000L),
        )
    }

    @Test
    fun theCycleItselfDoesNotEndTheEpisode() {
        // The cure must not refund its own budget: after a cycle with no ack yet, the episode stays open.
        val episode = start + 100_000L
        val now = episode + 10_000L
        assertEquals(
            Decision(Action.None, episode, 1),
            decide(now = now, failsSinceAck = 0, lastAckAt = start, episodeSince = episode, cycles = 1, lastReattachAt = episode),
        )
    }

    @Test
    fun aSecondCycleWaitsOutTheReattachCooldown() {
        val episode = start + 100_000L
        val now = episode + 10_000L // 10 s after the first cycle: inside the 20 s cooldown
        assertEquals(
            Decision(Action.None, episode, 1),
            decide(now = now, failsSinceAck = 8, lastAckAt = start, episodeSince = episode, cycles = 1, lastReattachAt = episode),
        )
        val later = episode + t.reattachCooldownMs
        assertEquals(
            Decision(Action.CycleSession, episode, 2),
            decide(now = later, failsSinceAck = 8, lastAckAt = start, episodeSince = episode, cycles = 1, lastReattachAt = episode),
        )
    }

    @Test
    fun theBudgetIsSpentInThreeCyclesAndThenTheEpisodeGoesQuiet() {
        // Walk the real watchdog cadence from a starved plane that nothing cures.
        var now = start + t.starvedMs
        var episodeSince = 0L
        var cycles = 0
        var lastReattachAt = 0L
        val actions = mutableListOf<Action>()
        repeat(12) {
            val d = decide(now = now, failsSinceAck = 20, episodeSince = episodeSince, cycles = cycles, lastReattachAt = lastReattachAt)
            actions += d.action
            episodeSince = d.nextEpisodeSince
            cycles = d.nextCycles
            if (d.action == Action.CycleSession) lastReattachAt = now
            now += wedgeCheckMs
        }
        assertEquals(t.maxCycles, actions.count { it == Action.CycleSession })
        assertEquals(t.maxCycles, cycles)
        assertEquals(start + t.starvedMs, episodeSince)
        // Spent by the third tick; every later tick is quiet.
        assertEquals(listOf(Action.CycleSession, Action.CycleSession, Action.CycleSession), actions.take(3))
        assertEquals(List(9) { Action.None }, actions.drop(3))
    }

    @Test
    fun aSpentEpisodeEarnsAFreshBudgetAfterTheRefundPeriod() {
        val episode = start + 100_000L
        val lastCycle = episode + 60_000L
        val justBefore = lastCycle + t.refundMs - 1L
        assertEquals(
            Decision(Action.None, episode, 3),
            decide(now = justBefore, failsSinceAck = 50, lastAckAt = start, episodeSince = episode, cycles = 3, lastReattachAt = lastCycle),
        )
        val due = lastCycle + t.refundMs
        assertEquals(
            Decision(Action.CycleSession, episode, 1),
            decide(now = due, failsSinceAck = 50, lastAckAt = start, episodeSince = episode, cycles = 3, lastReattachAt = lastCycle),
        )
    }

    @Test
    fun aCycleThatCuresEndsTheEpisodeOnTheNextAck() {
        // The field shape of p8 at 10:27:58: starved, one cycle, the first inbound frame 14 s later, acked cues after.
        val now0 = start + t.starvedMs
        val first = decide(now = now0, failsSinceAck = 6)
        assertEquals(Decision(Action.CycleSession, now0, 1), first)
        val now1 = now0 + wedgeCheckMs
        val cured =
            decide(
                now = now1,
                failsSinceAck = 0,
                lastAckAt = now0 + 14_000L,
                episodeSince = first.nextEpisodeSince,
                cycles = first.nextCycles,
                lastReattachAt = now0,
            )
        assertEquals(Decision(Action.None, 0L, 0), cured)
    }
}
