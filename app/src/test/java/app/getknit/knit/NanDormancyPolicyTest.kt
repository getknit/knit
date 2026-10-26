package app.getknit.knit

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.wifiaware.NanAttachPolicy
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy.Duty
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy.Probe
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy.RadioFacts
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy.StayFacts
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanDormancyPolicy] — when an idle Wi-Fi Aware plane detaches, and what it says meanwhile (GitHub #44). */
class NanDormancyPolicyTest {
    // Nothing holds the plane attached: started, attached, Bluetooth watching for newcomers, nobody around.
    private val idle =
        StayFacts(
            started = true,
            attached = true,
            cueTargets = false,
            linkActivity = false,
            owed = false,
            callLive = false,
            linkHeld = false,
            paused = false,
            cycleSettling = false,
            refilePending = false,
            offScreenBlocked = false,
            candidatesHeard = false,
            seesNewcomers = true,
            held = false,
        )

    @Test
    fun nothingToDoAndAWatchedNeighbourhoodLetsThePlaneSleep() {
        assertFalse(NanDormancyPolicy.mustStay(idle))
        assertNull(NanDormancyPolicy.stayReason(idle))
    }

    @Test
    fun everyReasonAloneHoldsThePlaneAttached() {
        val holds =
            listOf(
                idle.copy(started = false),
                idle.copy(attached = false), // dormancy is entered only from a working session, by its own close
                idle.copy(cueTargets = true),
                idle.copy(linkActivity = true),
                idle.copy(owed = true),
                idle.copy(callLive = true),
                idle.copy(linkHeld = true),
                idle.copy(paused = true), // the clock is frozen, so a long hand-over never resumes into dormancy
                idle.copy(cycleSettling = true),
                idle.copy(refilePending = true),
                idle.copy(offScreenBlocked = true),
                idle.copy(candidatesHeard = true),
                idle.copy(held = true),
            )
        for (f in holds) assertTrue("$f", NanDormancyPolicy.mustStay(f))
    }

    @Test
    fun aPhoneWhoseScanIsNotDemonstrablyWorkingNeverSleeps() {
        // The defaults the Bluetooth plane reports until bluetooth-owner lands, and a NAN-only phone: no watch, no sleep.
        val blind = idle.copy(seesNewcomers = false)
        assertTrue(NanDormancyPolicy.mustStay(blind))
        assertEquals("no newcomer watch", NanDormancyPolicy.stayReason(blind))
    }

    @Test
    fun theIdleClockRunsThreeMinutesOfQuietTicksAndAnyReasonRestartsIt() {
        val t0 = 1_000_000L
        var since = NanDormancyPolicy.idleSince(stay = false, prev = 0L, now = t0)
        assertEquals(t0, since)
        since = NanDormancyPolicy.idleSince(stay = false, prev = since, now = t0 + 60_000)
        assertEquals("the first quiet tick holds the stamp", t0, since)
        assertFalse(NanDormancyPolicy.sleepDue(since, t0 + NanDormancyPolicy.DORMANT_AFTER_MS - 1))
        assertTrue(NanDormancyPolicy.sleepDue(since, t0 + NanDormancyPolicy.DORMANT_AFTER_MS))
        assertEquals(1L, NanDormancyPolicy.msUntilSleep(since, t0 + NanDormancyPolicy.DORMANT_AFTER_MS - 1))
        // One tick with a reason (a cue target, a sync) and the clock starts again from the next quiet tick.
        since = NanDormancyPolicy.idleSince(stay = true, prev = since, now = t0 + 170_000)
        assertEquals(0L, since)
        assertFalse(NanDormancyPolicy.sleepDue(since, t0 + 400_000))
        assertNull(NanDormancyPolicy.msUntilSleep(since, t0 + 400_000))
        since = NanDormancyPolicy.idleSince(stay = false, prev = since, now = t0 + 175_000)
        assertFalse(NanDormancyPolicy.sleepDue(since, t0 + 180_000))
    }

    @Test
    fun onlyANewNearCandidateWakesThePlane() {
        assertTrue(NanDormancyPolicy.risingSighting(emptySet(), setOf("a")))
        assertTrue(NanDormancyPolicy.risingSighting(setOf("a"), setOf("a", "b")))
        assertFalse("one that merely stayed", NanDormancyPolicy.risingSighting(setOf("a"), setOf("a")))
        assertFalse("one that left", NanDormancyPolicy.risingSighting(setOf("a", "b"), setOf("a")))
        assertFalse(NanDormancyPolicy.risingSighting(emptySet(), emptySet()))
    }

    @Test
    fun theDutyAttachWaitsForItsTimeTheAttachBudgetAndTheCloseSettle() {
        val closedAt = 10_000L
        val dueAt = closedAt + NanDormancyPolicy.DUTY_INTERVAL_MS

        fun due(
            now: Long,
            abandoned: Boolean = false,
            retryAfter: Long = 0L,
            at: Long = dueAt,
        ) = NanDormancyPolicy.dutyDue(now, at, abandoned, retryAfter, closedAt, settleMs = 2_000L)
        assertFalse(due(dueAt - 1))
        assertTrue(due(dueAt))
        assertFalse("given up: no duty, ever", due(dueAt, abandoned = true))
        assertFalse("inside the attach backoff", due(dueAt, retryAfter = dueAt + 1))
        assertTrue(due(dueAt + 1, retryAfter = dueAt + 1))
        assertFalse("no duty scheduled", due(dueAt, at = 0L))
        assertFalse("inside the close's settle", NanDormancyPolicy.dutyDue(closedAt + 1_000, closedAt, false, 0L, closedAt, 2_000L))
    }

    @Test
    fun aDutyWindowWakesOnAFindAndEndsThirtySecondsAfterItsAttach() {
        val start = 100_000L
        val grace = 10_000L

        fun duty(
            now: Long,
            attachedAt: Long,
            found: Boolean = false,
        ) = NanDormancyPolicy.duty(now, start, attachedAt, found, attachGraceMs = grace)
        assertEquals(Duty.CONTINUE, duty(start + 1_000, attachedAt = 0L))
        assertEquals(Duty.WAKE, duty(start + 1_000, attachedAt = start + 500, found = true))
        val attached = start + 3_000
        assertEquals(Duty.CONTINUE, duty(attached + NanDormancyPolicy.DUTY_WINDOW_MS - 1, attached))
        assertEquals(Duty.EMPTY, duty(attached + NanDormancyPolicy.DUTY_WINDOW_MS, attached))
        // An attach that never came cannot hold the window open: it gets the window plus the attach's own grace.
        assertEquals(Duty.CONTINUE, duty(start + NanDormancyPolicy.DUTY_WINDOW_MS + grace - 1, attachedAt = 0L))
        assertEquals(Duty.EMPTY, duty(start + NanDormancyPolicy.DUTY_WINDOW_MS + grace, attachedAt = 0L))
    }

    /**
     * The reviewer's wedge: a session cycle (the responder give-up, `NANICM --ez on`) settling as the window ends must not
     * be judged EMPTY — the close would refuse under the settle and the loop would never run the settle's re-attach.
     */
    @Test
    fun aDutyWindowIsNeverJudgedEmptyWhileACycleSettlesInIt() {
        val start = 100_000L
        val attached = start + 3_000
        val over = attached + NanDormancyPolicy.DUTY_WINDOW_MS + 5_000
        assertEquals(Duty.EMPTY, NanDormancyPolicy.duty(over, start, attached, found = false, attachGraceMs = 10_000))
        assertEquals(
            Duty.CONTINUE,
            NanDormancyPolicy.duty(over, start, attached, found = false, attachGraceMs = 10_000, cycleSettling = true),
        )
        assertEquals(
            Duty.CONTINUE,
            NanDormancyPolicy.duty(over, start, attachedAt = 0L, found = false, attachGraceMs = 10_000, cycleSettling = true),
        )
        assertEquals(
            "a find still wakes",
            Duty.WAKE,
            NanDormancyPolicy.duty(over, start, attached, found = true, attachGraceMs = 10_000, cycleSettling = true),
        )
    }

    @Test
    fun aCloseDecidedOnTheLoopStandsOnlyIfTheHandlerStillAgrees() {
        // IDLE: an awake plane nothing holds now — not one a candidate or a link reached since the tick.
        assertTrue(NanDormancyPolicy.closeStands(Why.IDLE, dormant = false, dutyActive = false, stay = false))
        assertFalse(NanDormancyPolicy.closeStands(Why.IDLE, dormant = false, dutyActive = false, stay = true))
        assertFalse(NanDormancyPolicy.closeStands(Why.IDLE, dormant = true, dutyActive = false, stay = false))
        // DUTY_EMPTY: only a duty window still running — a wake that landed first ended it.
        assertTrue(NanDormancyPolicy.closeStands(Why.DUTY_EMPTY, dormant = true, dutyActive = true, stay = false))
        assertFalse(NanDormancyPolicy.closeStands(Why.DUTY_EMPTY, dormant = false, dutyActive = false, stay = false))
        assertFalse(NanDormancyPolicy.closeStands(Why.DUTY_EMPTY, dormant = true, dutyActive = false, stay = false))
        // FORCED: any awake plane, whatever holds it.
        assertTrue(NanDormancyPolicy.closeStands(Why.FORCED, dormant = false, dutyActive = false, stay = true))
        assertFalse(NanDormancyPolicy.closeStands(Why.FORCED, dormant = true, dutyActive = false, stay = false))
        // A wake's why never closes anything.
        assertFalse(NanDormancyPolicy.closeStands(Why.SIGHTING, dormant = false, dutyActive = false, stay = false))
    }

    @Test
    fun aNearCandidateUnderADormantPlaneWakesItUnlessTheSpellWasForced() {
        assertTrue(NanDormancyPolicy.nearWhileDormant(nearCandidates = true, lastWhy = Why.IDLE))
        assertTrue(NanDormancyPolicy.nearWhileDormant(nearCandidates = true, lastWhy = Why.DUTY_EMPTY))
        assertFalse(NanDormancyPolicy.nearWhileDormant(nearCandidates = true, lastWhy = Why.FORCED))
        assertFalse(NanDormancyPolicy.nearWhileDormant(nearCandidates = false, lastWhy = Why.IDLE))
    }

    @Test
    fun theDormantLoopSleepsToTheNextDutyWithinBounds() {
        val closedAt = 1_000L
        val dueAt = closedAt + NanDormancyPolicy.DUTY_INTERVAL_MS

        fun tick(
            now: Long,
            abandoned: Boolean = false,
            retryAfter: Long = 0L,
            at: Long = dueAt,
        ) = NanDormancyPolicy.dormantTickMs(now, at, abandoned, retryAfter, closedAt, 2_000L, idleMs = 120_000L, floorMs = 3_000L)
        assertEquals("never past the idle bound", 120_000L, tick(closedAt))
        assertEquals(50_000L, tick(dueAt - 50_000))
        assertEquals("a due duty is a short tick, not a spin", 3_000L, tick(dueAt + 5_000))
        assertEquals("the attach backoff pushes it out", 60_000L, tick(dueAt - 10_000, retryAfter = dueAt + 50_000))
        assertEquals(120_000L, tick(dueAt, abandoned = true))
        assertEquals(120_000L, tick(dueAt, at = 0L))
        assertEquals(
            "lent to a Wi-Fi Direct group: no duty runs, so no short tick through the hand-over",
            120_000L,
            NanDormancyPolicy.dormantTickMs(dueAt + 5_000, dueAt, false, 0L, closedAt, 2_000L, 120_000L, 3_000L, paused = true),
        )
    }

    private fun radio(
        now: Long,
        wifiEnabled: Boolean? = true,
        deviceIdle: Boolean? = false,
        unavailableSince: Long = 0L,
        lastOwnCloseAt: Long = 0L,
    ) = RadioFacts(wifiEnabled, deviceIdle, unavailableSince, lastOwnCloseAt, now)

    @Test
    fun theRadioIsOffOnlyOnEvidence() {
        val now = 10_000_000L
        assertFalse(NanDormancyPolicy.radioOff(radio(now)))
        assertTrue("Wi-Fi off", NanDormancyPolicy.radioOff(radio(now, wifiEnabled = false)))
        assertFalse(
            "deep idle alone is no radio off: the API 37 Pixels keep Aware working through it (2t49 trial N)",
            NanDormancyPolicy.radioOff(radio(now, deviceIdle = true)),
        )
        assertFalse("an unread Wi-Fi state is never off", NanDormancyPolicy.radioOff(radio(now, wifiEnabled = null, deviceIdle = null)))
        val hold = NanDormancyPolicy.UNAVAILABLE_HOLD_MS
        assertFalse("unavailable, not yet held", NanDormancyPolicy.radioOff(radio(now, unavailableSince = now - hold + 1)))
        assertTrue("unavailable, held", NanDormancyPolicy.radioOff(radio(now, unavailableSince = now - hold)))
    }

    @Test
    fun ourOwnClosesNanDownIsNeverTheRadioOff() {
        // Knit is the sole Aware client: our close takes NAN down, and the framework may say unavailable for a moment.
        val closedAt = 5_000_000L
        val beat = NanDormancyPolicy.OWN_CLOSE_BEAT_MS
        val hold = NanDormancyPolicy.UNAVAILABLE_HOLD_MS
        val since = closedAt + 50 // the false that followed the close
        assertFalse(NanDormancyPolicy.radioOff(radio(closedAt + hold + 1_000, unavailableSince = since, lastOwnCloseAt = closedAt)))
        assertFalse(NanDormancyPolicy.radioOff(radio(closedAt + beat + hold - 1, unavailableSince = since, lastOwnCloseAt = closedAt)))
        assertTrue(
            "held a full hold past the beat: that is no echo",
            NanDormancyPolicy.radioOff(radio(closedAt + beat + hold, unavailableSince = since, lastOwnCloseAt = closedAt)),
        )
        // A false that began well after the beat counts from its own start.
        val late = closedAt + beat + 60_000
        assertFalse(NanDormancyPolicy.radioOff(radio(late + hold - 1, unavailableSince = late, lastOwnCloseAt = closedAt)))
        assertTrue(NanDormancyPolicy.radioOff(radio(late + hold, unavailableSince = late, lastOwnCloseAt = closedAt)))
    }

    @Test
    fun theUnavailableRunStartsAtTheFirstFalse() {
        assertEquals(7L, NanDormancyPolicy.unavailableSince(available = false, prev = 0L, now = 7L))
        assertEquals(7L, NanDormancyPolicy.unavailableSince(available = false, prev = 7L, now = 9L))
        assertEquals(0L, NanDormancyPolicy.unavailableSince(available = true, prev = 7L, now = 9L))
    }

    @Test
    fun dormantHealthIsHealthyUnlessTheRadioIsOffOrAttachingWasGivenUp() {
        assertEquals(TransportHealth.Healthy, NanDormancyPolicy.dormantHealth(radioOff = false, attachAbandoned = false))
        assertEquals(TransportHealth.Unavailable, NanDormancyPolicy.dormantHealth(radioOff = true, attachAbandoned = false))
        assertEquals(TransportHealth.Unavailable, NanDormancyPolicy.dormantHealth(radioOff = true, attachAbandoned = true))
        assertEquals(TransportHealth.Degraded, NanDormancyPolicy.dormantHealth(radioOff = false, attachAbandoned = true))
    }

    @Test
    fun wontRunIsTheRadioOffDeepIdleOrAGiveUpNeverDormancy() {
        val now = 10_000_000L
        val hold = NanDormancyPolicy.UNAVAILABLE_HOLD_MS
        assertFalse(NanDormancyPolicy.wontRun(radio(now), attachAbandoned = false))
        assertTrue("Wi-Fi off", NanDormancyPolicy.wontRun(radio(now, wifiEnabled = false), attachAbandoned = false))
        assertTrue("unavailable, held", NanDormancyPolicy.wontRun(radio(now, unavailableSince = now - hold), attachAbandoned = false))
        assertFalse("unavailable, not yet held", NanDormancyPolicy.wontRun(radio(now, unavailableSince = now - hold + 1), false))
        assertTrue("deep idle: don't count on my Aware", NanDormancyPolicy.wontRun(radio(now, deviceIdle = true), attachAbandoned = false))
        assertFalse("an unread idle state is never idle", NanDormancyPolicy.wontRun(radio(now, deviceIdle = null), attachAbandoned = false))
        assertTrue("given up", NanDormancyPolicy.wontRun(radio(now), attachAbandoned = true))
    }

    @Test
    fun deepIdleSetsTheAdvertsBitButNeverMakesADormantPlaneUnavailable() {
        // The two readers of one instant, as refreshAwareEvidence reads them (2t49's 2026-10-24 amendment).
        val dozing = radio(10_000_000L, deviceIdle = true)
        assertTrue(NanDormancyPolicy.wontRun(dozing, attachAbandoned = false))
        assertEquals(
            TransportHealth.Healthy,
            NanDormancyPolicy.dormantHealth(NanDormancyPolicy.radioOff(dozing), attachAbandoned = false),
        )
        // The Pixel 3 (API 31) does turn Aware off in Doze: its isAvailable false, held, is the health's evidence.
        val p3 = radio(10_000_000L, deviceIdle = true, unavailableSince = 10_000_000L - NanDormancyPolicy.UNAVAILABLE_HOLD_MS)
        assertEquals(
            TransportHealth.Unavailable,
            NanDormancyPolicy.dormantHealth(NanDormancyPolicy.radioOff(p3), attachAbandoned = false),
        )
    }

    @Test
    fun anAvailabilityBroadcastInsideTheCloseBeatIsItsEchoAndRefundsNothing() {
        val closedAt = 3_000_000L
        val inBeat = closedAt + 5_000
        assertEquals(
            NanDormancyPolicy.Edge(record = false, refund = false),
            NanDormancyPolicy.availabilityEdge(available = false, wasAvailable = true, now = inBeat, closedAt = closedAt),
        )
        assertEquals(
            NanDormancyPolicy.Edge(record = false, refund = false),
            NanDormancyPolicy.availabilityEdge(available = true, wasAvailable = false, now = inBeat, closedAt = closedAt),
        )
        // Outside the beat, the receiver's rules as they stand (ADR 055): the negative control.
        val after = closedAt + NanDormancyPolicy.OWN_CLOSE_BEAT_MS
        assertEquals(
            NanDormancyPolicy.Edge(record = true, refund = true),
            NanDormancyPolicy.availabilityEdge(available = false, wasAvailable = true, now = after, closedAt = closedAt),
        )
        assertEquals(
            NanDormancyPolicy.Edge(record = true, refund = true),
            NanDormancyPolicy.availabilityEdge(available = true, wasAvailable = false, now = after, closedAt = closedAt),
        )
        assertEquals(
            "a repeated true is not news",
            NanDormancyPolicy.Edge(record = true, refund = false),
            NanDormancyPolicy.availabilityEdge(available = true, wasAvailable = true, now = after, closedAt = closedAt),
        )
        assertEquals(
            "no dormancy close yet",
            NanDormancyPolicy.Edge(record = true, refund = true),
            NanDormancyPolicy.availabilityEdge(available = true, wasAvailable = false, now = 1L, closedAt = 0L),
        )
    }

    /**
     * The budget walk, on the real cadence: a chipset whose every attach fails, dormant, its duty attaching every
     * [NanDormancyPolicy.DUTY_INTERVAL_MS] through the attach budget's backoff, each duty ending in a dormancy close whose
     * NAN-down echoes false then true inside the beat. The streak never drops at a dormancy transition, the give-up is
     * reached and holds, and no duty runs after it (getknit/Knit#9: each failed attach strands two binder objects).
     */
    @Test
    fun theAttachStreakAndTheGiveUpSurviveDormantDutyDormant() {
        var now = 0L
        var streak = 0
        var total = 0
        var retryAfter = 0L
        var abandoned = false
        var lastAvailable: Boolean? = true
        var closedAt = 0L
        var dueAt = NanDormancyPolicy.DUTY_INTERVAL_MS
        var duties = 0
        var dutiesAfterGiveUp = 0
        val settle = 2_000L
        val month = 30L * 24 * 3_600_000
        while (now < month) {
            if (NanDormancyPolicy.dutyDue(now, dueAt, abandoned, retryAfter, closedAt, settle)) {
                if (abandoned) dutiesAfterGiveUp++
                duties++
                val before = streak
                // The duty's attach fails, through the ordinary accounting.
                streak++
                total++
                if (NanAttachPolicy.giveUp(streak) || NanAttachPolicy.leakBudgetSpent(total)) {
                    abandoned = true
                } else {
                    retryAfter = now + NanAttachPolicy.backoffMs(streak) { 0.5 }
                }
                // The window ends empty: dormant again, and its close's NAN-down echoes inside the beat.
                val end = now + NanDormancyPolicy.DUTY_WINDOW_MS + 10_000
                closedAt = end
                dueAt = end + NanDormancyPolicy.DUTY_INTERVAL_MS
                for ((i, available) in listOf(false, true).withIndex()) {
                    val edge = NanDormancyPolicy.availabilityEdge(available, lastAvailable, end + 1_000L * (i + 1), closedAt)
                    if (edge.record) lastAvailable = available
                    if (edge.refund) {
                        streak = 0
                        retryAfter = 0L
                        abandoned = false
                    }
                }
                assertTrue("the streak dropped at a dormancy transition ($before → $streak)", streak > before)
            }
            now += 60_000L // the dormant loop's bounded tick, or sooner
        }
        assertTrue("the give-up is reached and holds", abandoned)
        assertEquals(NanAttachPolicy.MAX_FAILURES, streak)
        assertEquals(NanAttachPolicy.MAX_FAILURES, duties)
        assertEquals(0, dutiesAfterGiveUp)
    }

    // --- The on-screen probe (2t49's 2026-10-26 amendment) ---

    private val floor = NanDormancyPolicy.PROBE_FLOOR_MS

    // Dormant between duties, detached, unpaused, the budget clear: everything the probe asks but its two floor stamps.
    @Suppress("LongParameterList")
    private fun probe(
        now: Long,
        lastWindowStartedAt: Long = 0L,
        closedAt: Long = 1_000L,
        dormant: Boolean = true,
        dutyActive: Boolean = false,
        attached: Boolean = false,
        near: Boolean = false,
        paused: Boolean = false,
        abandoned: Boolean = false,
        retryAfter: Long = 0L,
        settle: Long = 2_000L,
        floorMs: Long = floor,
    ) = NanDormancyPolicy.probe(
        now = now,
        dormant = dormant,
        dutyActive = dutyActive,
        attached = attached,
        nearCandidate = near,
        paused = paused,
        lastWindowStartedAt = lastWindowStartedAt,
        closedAt = closedAt,
        attachAbandoned = abandoned,
        attachRetryAfter = retryAfter,
        settleMs = settle,
        floorMs = floorMs,
    )

    @Test
    fun theFirstOpenWhileDormantProbesAndAnotherInsideFiveMinutesDoesNot() {
        val closedAt = 1_000L
        val opened = closedAt + floor + 60_000L
        assertEquals("first open, long after the close", Probe.RUN, probe(opened, closedAt = closedAt))
        // That probe started a window; its DUTY_EMPTY close lands about 40 s later and stamps the close too.
        val probeClose = opened + 40_000L
        assertEquals(Probe.FLOOR, probe(opened + 60_000L, lastWindowStartedAt = opened, closedAt = probeClose))
        assertEquals(Probe.FLOOR, probe(opened + floor - 1, lastWindowStartedAt = opened, closedAt = opened))
        // The floor counts from the later stamp, so the empty close's 40 s push it out to there.
        assertEquals(Probe.FLOOR, probe(opened + floor, lastWindowStartedAt = opened, closedAt = probeClose))
        assertEquals(Probe.RUN, probe(probeClose + floor, lastWindowStartedAt = opened, closedAt = probeClose))
    }

    @Test
    fun theFloorCountsFromAWindowStartLaterThanTheClose() {
        // A pause() mid-window ends it with no close stamp: the last close is the one 10 min before the window began.
        val start = 40_000_000L
        val closedAt = start - 600_000L
        assertEquals(Probe.FLOOR, probe(start + floor - 1, lastWindowStartedAt = start, closedAt = closedAt))
        assertEquals(Probe.RUN, probe(start + floor, lastWindowStartedAt = start, closedAt = closedAt))
    }

    @Test
    fun aScheduledDutyOrAnIdleCloseInsideFiveMinutesSuppressesTheProbe() {
        // A scheduled duty started 2 min ago (it found nobody; the close is its own, 40 s later).
        val dutyAt = 20_000_000L
        assertEquals(
            "a duty inside the floor",
            Probe.FLOOR,
            probe(
                dutyAt + 120_000L,
                lastWindowStartedAt = dutyAt,
                closedAt =
                    dutyAt + 40_000L,
            ),
        )
        // The plane was just attached three minutes and found nobody: an IDLE close 1 min ago, no window ever.
        val idleClose = 30_000_000L
        assertEquals("an IDLE close inside the floor", Probe.FLOOR, probe(idleClose + 60_000L, closedAt = idleClose))
        assertEquals(Probe.RUN, probe(idleClose + floor, closedAt = idleClose))
        // 0 is "never" for both stamps: the floor never holds a plane that has no history.
        assertEquals(0L, NanDormancyPolicy.probeFloorLeftMs(5_000L, lastWindowStartedAt = 0L, closedAt = 0L))
        assertEquals(floor - 60_000L, NanDormancyPolicy.probeFloorLeftMs(idleClose + 60_000L, 0L, idleClose))
        assertEquals(0L, NanDormancyPolicy.probeFloorLeftMs(idleClose + floor + 1, 0L, idleClose))
    }

    @Test
    fun anAwakePlaneARunningWindowOrAPauseProbesNothing() {
        val now = 50_000_000L
        assertEquals(Probe.AWAKE, probe(now, dormant = false))
        assertEquals(Probe.AWAKE, probe(now, dormant = false, attached = true))
        assertEquals("mid-duty: that window is the probe", Probe.WINDOW_RUNNING, probe(now, dutyActive = true))
        assertEquals(Probe.WINDOW_RUNNING, probe(now, dutyActive = true, attached = true))
        assertEquals(Probe.WINDOW_RUNNING, probe(now, attached = true))
        assertEquals(Probe.PAUSED, probe(now, paused = true))
    }

    @Test
    fun aProbeIsNeverACloseReason() {
        for (dormant in listOf(false, true)) {
            for (dutyActive in listOf(false, true)) {
                for (stay in listOf(false, true)) {
                    assertFalse(
                        "closeStands(PROBE, dormant=$dormant, dutyActive=$dutyActive, stay=$stay)",
                        NanDormancyPolicy.closeStands(Why.PROBE, dormant, dutyActive, stay),
                    )
                }
            }
        }
    }

    @Test
    fun aCandidateAlreadyNearWakesSightingInsteadOfAProbe() {
        val now = 50_000_000L
        assertEquals("a lost rising edge wakes, not a 30 s probe", Probe.NEAR, probe(now, near = true))
        // Ahead of the floor, the budget and the pause, as the dormant tick's self-heal is.
        assertEquals(Probe.NEAR, probe(now, near = true, lastWindowStartedAt = now - 1_000L))
        assertEquals(Probe.NEAR, probe(now, near = true, abandoned = true))
        assertEquals(Probe.NEAR, probe(now, near = true, paused = true))
        // Never for an awake plane or a running window.
        assertEquals(Probe.AWAKE, probe(now, near = true, dormant = false))
        assertEquals(Probe.WINDOW_RUNNING, probe(now, near = true, dutyActive = true))
        // The transport feeds nearWhileDormant, so a FORCED spell's near candidate probes as usual.
        assertEquals(Probe.RUN, probe(now, near = NanDormancyPolicy.nearWhileDormant(true, Why.FORCED)))
        assertEquals(Probe.NEAR, probe(now, near = NanDormancyPolicy.nearWhileDormant(true, Why.IDLE)))
    }

    @Test
    fun theAttachBudgetStillGatesTheProbe() {
        val now = 50_000_000L
        assertEquals(Probe.RUN, probe(now))
        assertEquals("given up", Probe.BUDGET, probe(now, abandoned = true))
        assertEquals("inside the streak's backoff", Probe.BUDGET, probe(now, retryAfter = now + 1))
        assertEquals("the backoff ends", Probe.RUN, probe(now, retryAfter = now))
        assertTrue(NanDormancyPolicy.attachBudgetAllows(now, attachAbandoned = false, attachRetryAfter = now))
        assertFalse(NanDormancyPolicy.attachBudgetAllows(now, attachAbandoned = true, attachRetryAfter = 0L))
        // The close's settle, reachable only with a floor shorter than it: dropped, never armed.
        assertEquals(Probe.SETTLE, probe(now, closedAt = now - 1_000L, floorMs = 0L))
    }

    /**
     * A person opening Knit every minute for a day, alone and dormant, with the regular duty every 12 min on a radio
     * that attaches fine, the model rescheduling each duty from its window's close as the transport does: probes are at
     * most one per [NanDormancyPolicy.PROBE_FLOOR_MS] and none starts within the floor of any window or close. That the
     * transport's probe never writes the schedule or refunds the budget is [NanDormancyWiringTest]'s to pin.
     */
    @Test
    fun opensEveryMinuteForADayProbeAtMostOncePerFloor() {
        val settle = 2_000L
        var now = 0L
        var closedAt = 0L
        var lastWindow = 0L
        var dueAt = NanDormancyPolicy.DUTY_INTERVAL_MS
        val starts = mutableListOf<Long>()
        var probes = 0
        val day = 24L * 3_600_000
        while (now < day) {
            // Each window runs 40 s and closes DUTY_EMPTY, rescheduling the duty from its close.
            fun window(probe: Boolean) {
                if (probe) {
                    probes++
                    if (starts.isNotEmpty()) assertTrue("a probe inside the floor", now - maxOf(starts.last(), closedAt) >= floor)
                }
                starts += now
                lastWindow = now
                closedAt = now + 40_000L
                dueAt = closedAt + NanDormancyPolicy.DUTY_INTERVAL_MS
                now = closedAt
            }
            if (NanDormancyPolicy.dutyDue(now, dueAt, false, 0L, closedAt, settle)) {
                window(probe = false)
            } else if (probe(now, lastWindowStartedAt = lastWindow, closedAt = closedAt) == Probe.RUN) {
                window(probe = true)
            }
            now += 60_000L
        }
        assertTrue("probes ran", probes > 0)
        assertTrue("at most one per floor", probes <= day / floor)
        // Every window, duty or probe, sits at least a floor's worth after the previous one's close or start.
        starts.zipWithNext().forEach { (a, b) -> assertTrue("windows $a and $b", b - a >= floor) }
    }

    /**
     * The budget walk with the person opening Knit every 5 min: a chipset whose every attach fails, the model charging
     * each window to the streak with no refund. No probe starts while the streak's backoff or the give-up says no, and
     * once the budget has given up no window of either kind runs again (getknit/Knit#9). That the transport refunds
     * nothing at a probe is [NanDormancyWiringTest]'s to pin.
     */
    @Test
    fun aProbeNeverRunsWhileTheAttachBudgetSaysNo() {
        var now = 0L
        var streak = 0
        var total = 0
        var retryAfter = 0L
        var abandoned = false
        var closedAt = 0L
        var lastWindow = 0L
        var dueAt = NanDormancyPolicy.DUTY_INTERVAL_MS
        var refusedForBudget = 0
        var windowsAfterGiveUp = 0
        val settle = 2_000L
        val week = 7L * 24 * 3_600_000
        while (now < week) {
            val duty = NanDormancyPolicy.dutyDue(now, dueAt, abandoned, retryAfter, closedAt, settle)
            val verdict = probe(now, lastWindowStartedAt = lastWindow, closedAt = closedAt, abandoned = abandoned, retryAfter = retryAfter)
            if (verdict == Probe.BUDGET) refusedForBudget++
            if (duty || verdict == Probe.RUN) {
                if (abandoned) windowsAfterGiveUp++
                assertTrue("a window inside the backoff", now >= retryAfter)
                streak++
                total++
                if (NanAttachPolicy.giveUp(streak) || NanAttachPolicy.leakBudgetSpent(total)) {
                    abandoned = true
                } else {
                    retryAfter = now + NanAttachPolicy.backoffMs(streak) { 0.5 }
                }
                lastWindow = now
                closedAt = now + NanDormancyPolicy.DUTY_WINDOW_MS + 10_000
                dueAt = closedAt + NanDormancyPolicy.DUTY_INTERVAL_MS
            }
            now += floor
        }
        assertTrue("the backoff refused probes", refusedForBudget > 0)
        assertTrue("the give-up is reached and holds", abandoned)
        assertEquals(0, windowsAfterGiveUp)
    }
}
