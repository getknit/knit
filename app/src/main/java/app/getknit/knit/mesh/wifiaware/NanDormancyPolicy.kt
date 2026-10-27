package app.getknit.knit.mesh.wifiaware

import app.getknit.knit.mesh.TransportHealth

/**
 * When a started Wi-Fi Aware plane with nothing to do **detaches** (goes dormant), when it attaches again, and what
 * its health and its "won't run here" answer say meanwhile — pure, like the sibling `Nan*Policy` objects, with every
 * clock and input passed in so the rules are table tests (GitHub getknit/knit#44).
 *
 * Why it exists. A Pixel 9 trial (ADR 2026-10.486r's results section) put the cost of Aware being **attached** with
 * the screen on at about two thirds of the STA's throughput, ICM or not, and the A2DP controller's choppy events rose
 * from none to several per 35 s. No app API changes the framework's discovery windows; an app can only choose whether
 * it is attached. Knit is the sole Aware client on these phones, so closing our session takes NAN down.
 *
 * What it decides:
 * - **Sleep** ([mustStay], [idleSince], [sleepDue]): every [StayFacts] reason false for [DORMANT_AFTER_MS] running,
 *   observed on the loop tick. One reason is positive evidence that another plane watches for newcomers
 *   ([StayFacts.seesNewcomers]): a phone whose short-range scan is not demonstrably working never goes dormant.
 * - **Wake** ([risingSighting]): a new Aware candidate in the near set; the transport adds a bulk transfer to a
 *   candidate, the scan going blind and a duty or probe window that finds someone. Never `heal()`: the motion heal
 *   fires about once a minute on transit and would undo dormancy, and never the app coming on screen (2t49's
 *   2026-10-26 amendment: that runs a [probe] instead).
 * - **Duty** ([dutyDue], [duty]): while dormant, one attach every [DUTY_INTERVAL_MS] held for [DUTY_WINDOW_MS] from
 *   `onAttached`, so a peer whose Bluetooth is off is still found. Skipped while the attach budget says no.
 * - **Probe** ([probe]): Knit coming on screen while dormant runs one duty window now, at most once per
 *   [PROBE_FLOOR_MS] counted from the last window's start or the last dormancy close, and only when the attach budget
 *   would let a duty run now. It is a duty window in every other way, and leaves the duty schedule as it was.
 * - **Health** ([radioOff], [dormantHealth]): while dormant the plane reads Healthy unless there is evidence the radio
 *   is off — Wi-Fi off, or `isAvailable` false held [UNAVAILABLE_HOLD_MS] outside the beat after our own close
 *   ([OWN_CLOSE_BEAT_MS]) — so our own NAN-down never reads as "Wi-Fi off". Deep idle is not that evidence: the API 37
 *   Pixels keep Aware available and working through it (ADR 2026-10.2t49's 2026-10-24 amendment).
 * - **"Won't run"** ([wontRun]): the advert's half, a wider reader of the same facts — the health's evidence, deep
 *   idle, or a given-up attach. It means "don't count on my Aware right now", not "my Aware is off".
 * - **Refunds** ([availabilityEdge]): an availability broadcast inside that beat is our own close's echo, so it is
 *   not recorded as a false and its true cannot refund the attach streak (ADR 055: only a genuine edge refunds, and
 *   a refund the dormancy cycle could drive is getknit/Knit#9's binder leak).
 */
@Suppress("TooManyFunctions") // one pure rule per question the transport asks (sleep, wake, duty, evidence, refund)
object NanDormancyPolicy {
    /** Why the plane went dormant or woke: the `nan_dormant` field-log line's `why` and the log lines'. */
    enum class Why {
        /** Nothing to do for [DORMANT_AFTER_MS]: went dormant. */
        IDLE,

        /** A new Aware candidate in the near set: woke. */
        SIGHTING,

        /** A bulk transfer to an Aware candidate is about to start: woke (that send rides Bluetooth). */
        BULK,

        /** The duty attach found someone: woke, keeping its session. */
        DUTY,

        /**
         * The on-screen probe ([probe]) found someone: woke, keeping its session. A wake reason only, never a close:
         * an empty probe closes DUTY_EMPTY, as any duty window does, because [closeStands] refuses every other reason.
         * (`VISIBLE`, Knit coming on screen as a wake of its own, was retired by 2t49's 2026-10-26 amendment.)
         */
        PROBE,

        /** The duty attach found nobody in its window: dormant again. */
        DUTY_EMPTY,

        /** The other plane stopped seeing newcomers (its scan failed, its adapter went off): woke. */
        BLIND,

        /** `…debug.NANDORM` forced it (`--ez now true`, or a `--ez hold true` that woke it). Debug builds only. */
        FORCED,
    }

    /**
     * One loop tick's reasons to stay attached. Any true one holds the plane awake and restarts the idle clock;
     * [seesNewcomers] is the one that must be **true** to sleep.
     */
    data class StayFacts(
        /** Between `start()` and `stop()`. */
        val started: Boolean,
        /** An Aware session is up: dormancy is entered only from a working session, by its own close. */
        val attached: Boolean,
        /** Someone to cue (`cueTarget` non-empty): an Aware peer is here. */
        val cueTargets: Boolean,
        /** A link, handshake or accept is live (`anyLinkActivity`). */
        val linkActivity: Boolean,
        /** An initiate, a bulk mark or an ICM relight is owed (`initiateOwed`, `bulkWanted`, `needsIcmRelight`). */
        val owed: Boolean,
        /** A call is running: its link may be wanted at any moment. */
        val callLive: Boolean,
        /** A call holds a link ([NanLinkHolds]). */
        val linkHeld: Boolean,
        /** Lent to a Wi-Fi Direct group: the clock is frozen, so a long hand-over never resumes into dormancy. */
        val paused: Boolean,
        /** A session cycle sits in its settle (`sessionCycleSettleStartedAt != 0`). */
        val cycleSettling: Boolean,
        /** A responder re-file waits its turn (`responderRefile`). */
        val refilePending: Boolean,
        /** Discovery is held for ADR 2026-09.535d's off-screen refusal: its health and retry stay as they are. */
        val offScreenBlocked: Boolean,
        /** An Aware candidate is heard by another plane (the stay set, with its hysteresis). */
        val candidatesHeard: Boolean,
        /** Another plane demonstrably watches for newcomers right now; false holds the plane awake. */
        val seesNewcomers: Boolean,
        /** `…debug.NANDORM --ez hold true`. */
        val held: Boolean,
    )

    /** Whether any reason holds the plane attached this tick. */
    fun mustStay(f: StayFacts): Boolean = stayReason(f) != null

    /** The first reason that holds the plane attached, for the state log; null when nothing does. */
    fun stayReason(f: StayFacts): String? =
        when {
            !f.started -> "stopped"
            !f.attached -> "detached"
            f.held -> "held"
            f.paused -> "paused"
            !f.seesNewcomers -> "no newcomer watch"
            else -> activityReason(f)
        }

    /** [stayReason]'s second half: something going on, or owed, here or near. */
    private fun activityReason(f: StayFacts): String? =
        when {
            f.cueTargets -> "cue targets"
            f.linkActivity -> "link activity"
            f.owed -> "owed"
            f.callLive -> "call"
            f.linkHeld -> "link held"
            f.cycleSettling -> "cycle settling"
            f.refilePending -> "responder re-file"
            f.offScreenBlocked -> "off-screen refusal"
            f.candidatesHeard -> "candidate heard"
            else -> null
        }

    /**
     * The idle clock as the loop observes it, [NanLonelyPolicy.lonelySince]'s shape: 0 while [stay], else the stamp
     * already held, or [now] on the first idle observation.
     */
    fun idleSince(
        stay: Boolean,
        prev: Long,
        now: Long,
    ): Long =
        when {
            stay -> 0L
            prev != 0L -> prev
            else -> now
        }

    /** Whether the idle clock has run [afterMs]. */
    fun sleepDue(
        idleSince: Long,
        now: Long,
        afterMs: Long = DORMANT_AFTER_MS,
    ): Boolean = idleSince != 0L && now - idleSince >= afterMs

    /** How long until [sleepDue] turns true, for the loop's cadence; null while the clock is not running. */
    fun msUntilSleep(
        idleSince: Long,
        now: Long,
        afterMs: Long = DORMANT_AFTER_MS,
    ): Long? = if (idleSince == 0L) null else (idleSince + afterMs - now).coerceAtLeast(0L)

    /**
     * Whether another plane's push wakes a dormant plane: an id in [near] that was not in [prevNear]. A candidate that
     * merely stayed (or one only heard, not near) wakes nothing.
     */
    fun risingSighting(
        prevNear: Set<String>,
        near: Set<String>,
    ): Boolean = near.any { it !in prevNear }

    /**
     * Whether a dormancy close decided on one thread still stands when the handler runs it, against the state there:
     * [stay] is [mustStay] re-read on the handler. IDLE closes an awake plane nothing holds; DUTY_EMPTY closes only a
     * running duty window (a wake that landed first ended it); FORCED closes an awake plane, whatever holds it. A
     * candidate or a wake that arrived between the decision and the close is never undone by it.
     */
    fun closeStands(
        why: Why,
        dormant: Boolean,
        dutyActive: Boolean,
        stay: Boolean,
    ): Boolean =
        when (why) {
            Why.IDLE -> !dormant && !stay
            Why.DUTY_EMPTY -> dormant && dutyActive
            Why.FORCED -> !dormant
            else -> false
        }

    /**
     * The dormant tick's self-heal: a candidate is near while the plane is dormant, which its rising edge should have
     * woken — a push that landed while the close was queued. Wake for it, unless the debug bridge forced the spell
     * ([lastWhy] FORCED), where a candidate already near is the trial's own setup.
     */
    fun nearWhileDormant(
        nearCandidates: Boolean,
        lastWhy: Why?,
    ): Boolean = nearCandidates && lastWhy != Why.FORCED

    /** Whether a dormant plane runs its duty attach now. */
    fun dutyDue(
        now: Long,
        dueAt: Long,
        attachAbandoned: Boolean,
        attachRetryAfter: Long,
        closedAt: Long,
        settleMs: Long,
    ): Boolean =
        dueAt != 0L &&
            now >= dueAt &&
            // Skipped entirely while the attach budget says no: a duty is an attach, and each failed one strands two
            // binder objects in system_server (ADR 052).
            attachBudgetAllows(now, attachAbandoned, attachRetryAfter) &&
            now - closedAt >= settleMs

    /** Whether the attach budget lets a duty or a probe attach now: not given up, and past the streak's backoff. */
    fun attachBudgetAllows(
        now: Long,
        attachAbandoned: Boolean,
        attachRetryAfter: Long,
    ): Boolean = !attachAbandoned && now >= attachRetryAfter

    /** What Knit coming on screen does to the plane ([probe]); [RUN] alone starts a window. */
    enum class Probe {
        /** Start one duty window now, as a probe. */
        RUN,

        /** Awake: nothing to probe for (ADR 2026-09.535d's retry runs from `heal()` on resume, not from here). */
        AWAKE,

        /** A duty or probe window is already running, or a session is up: that window is the probe. */
        WINDOW_RUNNING,

        /**
         * A candidate is near under a dormant plane ([nearWhileDormant]: its rising edge was lost to a queued close):
         * wake SIGHTING now, as the dormant tick would, rather than probe for 30 s and then re-attach on that sighting.
         */
        NEAR,

        /** Lent to a Wi-Fi Direct group: no attach until `resume()`. */
        PAUSED,

        /** A window started, or the plane closed, within [PROBE_FLOOR_MS]: the probe is dropped, not deferred. */
        FLOOR,

        /** The attach budget says no ([attachBudgetAllows]): dropped, so it never fires later off screen. */
        BUDGET,

        /** Our own close's settle has not run (unreachable while the floor exceeds the settle): dropped. */
        SETTLE,
    }

    /**
     * Knit came on screen ([now] is the rising edge): whether a dormant plane runs a probe — one duty window, now, to
     * find an Aware peer whose Bluetooth is off sooner than the next duty (2t49's 2026-10-26 amendment). Only between
     * duties with no session and not [paused]; never within [floorMs] of the later of the last window's start
     * ([lastWindowStartedAt], a duty's or a probe's) and the last dormancy close ([closedAt]), so a reopen right after
     * an IDLE close (attached three minutes, nobody found) or after a duty does not attach again; and only when a duty
     * due now would pass [dutyDue]: the attach budget ([attachBudgetAllows]), then the close's [settleMs]. Anything but
     * [Probe.RUN] drops the probe: nothing is armed for later, so a backed-off probe can never fire off screen or pull
     * the duty schedule in.
     * Both stamps are `elapsedRealtime`; 0 is "never". [nearCandidate] is [nearWhileDormant]'s answer (FORCED exempt):
     * a candidate already near wakes the plane instead ([Probe.NEAR]), ahead of the pause, the floor and the budget, as
     * the dormant tick's self-heal is.
     */
    @Suppress("LongParameterList") // the dormant state, the two floor stamps and the budget, each its own input
    fun probe(
        now: Long,
        dormant: Boolean,
        dutyActive: Boolean,
        attached: Boolean,
        nearCandidate: Boolean,
        paused: Boolean,
        lastWindowStartedAt: Long,
        closedAt: Long,
        attachAbandoned: Boolean,
        attachRetryAfter: Long,
        settleMs: Long,
        floorMs: Long = PROBE_FLOOR_MS,
    ): Probe =
        when {
            !dormant -> Probe.AWAKE
            dutyActive || attached -> Probe.WINDOW_RUNNING
            nearCandidate -> Probe.NEAR
            paused -> Probe.PAUSED
            probeFloorLeftMs(now, lastWindowStartedAt, closedAt, floorMs) > 0L -> Probe.FLOOR
            !attachBudgetAllows(now, attachAbandoned, attachRetryAfter) -> Probe.BUDGET
            now - closedAt < settleMs -> Probe.SETTLE
            else -> Probe.RUN
        }

    /**
     * How long until [probe]'s floor lets a probe run: [floorMs] from the later of [lastWindowStartedAt] and
     * [closedAt] (0 is "never"), 0 once it has run. `…debug.NANDORM`'s `nextProbeAllowedInMs`.
     */
    fun probeFloorLeftMs(
        now: Long,
        lastWindowStartedAt: Long,
        closedAt: Long,
        floorMs: Long = PROBE_FLOOR_MS,
    ): Long {
        val from = maxOf(lastWindowStartedAt, closedAt)
        if (from == 0L) return 0L
        return (from + floorMs - now).coerceAtLeast(0L)
    }

    /** What a duty window does on this tick. */
    enum class Duty { CONTINUE, WAKE, EMPTY }

    /**
     * One tick of a duty window. [found]: an Aware peer turned up (a cue target, a link or an accept) — wake, keeping
     * the session. Else the window runs [windowMs] from `onAttached` ([attachedAt]); an attach that never came is
     * given [windowMs] plus [attachGraceMs] from the duty's start, so a refused or silent attach cannot hold the
     * window open. While a session cycle settles inside the window ([cycleSettling]) the window is never judged empty:
     * the dormancy close would refuse under the settle and the loop would skip the settle's re-attach, a dormant plane
     * with no session spinning on its tick for good (the wifi-aware-reviewer's finding); the settle's re-attach goes
     * first and the window judges the fresh session.
     */
    fun duty(
        now: Long,
        startedAt: Long,
        attachedAt: Long,
        found: Boolean,
        windowMs: Long = DUTY_WINDOW_MS,
        attachGraceMs: Long,
        cycleSettling: Boolean = false,
    ): Duty =
        when {
            found -> Duty.WAKE
            cycleSettling -> Duty.CONTINUE
            attachedAt != 0L && now - attachedAt >= windowMs -> Duty.EMPTY
            attachedAt == 0L && now - startedAt >= windowMs + attachGraceMs -> Duty.EMPTY
            else -> Duty.CONTINUE
        }

    /**
     * The loop's sleep while dormant between duties: until the next duty may run (its due time, the attach backoff and
     * our own close's settle, whichever is last), or [idleMs] when duties are given up or the radio is lent to a Wi-Fi
     * Direct group ([paused]: no duty runs then, and `resume()` pokes the loop). Any wake pokes the loop.
     */
    fun dormantTickMs(
        now: Long,
        dueAt: Long,
        attachAbandoned: Boolean,
        attachRetryAfter: Long,
        closedAt: Long,
        settleMs: Long,
        idleMs: Long,
        floorMs: Long,
        paused: Boolean = false,
    ): Long {
        if (attachAbandoned || paused || dueAt == 0L) return idleMs
        val at = maxOf(dueAt, attachRetryAfter, closedAt + settleMs)
        return (at - now).coerceIn(floorMs, idleMs)
    }

    /** What the plane can read about the radio being off, at one instant. */
    data class RadioFacts(
        /** `WifiManager.isWifiEnabled`; null when the read failed (never read as off). */
        val wifiEnabled: Boolean?,
        /**
         * `PowerManager.isDeviceIdleMode`; null when unread. Read by [wontRun] only, never by [radioOff]: AOSP's
         * `WifiAwareStateManager` answers the idle broadcast with `reconfigure()`, and the API 37 Pixels keep Aware
         * available and discovering through deep idle (2t49's trials N and Q).
         */
        val deviceIdle: Boolean?,
        /** When `isAvailable` was first seen false in the current run of falses; 0 while it reads true. */
        val unavailableSince: Long,
        /** Our own last session close: a dormancy close or a session cycle. Its NAN-down can echo as a false. */
        val lastOwnCloseAt: Long,
        val now: Long,
    )

    /**
     * The health's reader: whether the radio is off by evidence — Wi-Fi off, or `isAvailable` false held [holdMs],
     * counted only from the end of [beatMs] after our own close, whose NAN-down the framework may broadcast as
     * unavailable for a moment. Never deep idle alone ([RadioFacts.deviceIdle]): a dozing phone whose Aware works must
     * not raise the "turn on Wi-Fi" banner (trial N); a phone whose Aware does go off in Doze (the Pixel 3, API 31)
     * reads `isAvailable` false, which the hold covers.
     */
    fun radioOff(
        f: RadioFacts,
        holdMs: Long = UNAVAILABLE_HOLD_MS,
        beatMs: Long = OWN_CLOSE_BEAT_MS,
    ): Boolean {
        if (f.wifiEnabled == false) return true
        if (f.unavailableSince == 0L) return false
        val countsFrom = if (f.lastOwnCloseAt == 0L) f.unavailableSince else maxOf(f.unavailableSince, f.lastOwnCloseAt + beatMs)
        return f.now - countsFrom >= holdMs
    }

    /**
     * The `isAvailable`-false run's start, as each read observes it: 0 while available, the stamp held, or [now] on the
     * first false.
     */
    fun unavailableSince(
        available: Boolean,
        prev: Long,
        now: Long,
    ): Long =
        when {
            available -> 0L
            prev != 0L -> prev
            else -> now
        }

    /**
     * The advert's reader: don't count on this phone's Wi-Fi Aware right now — the Bluetooth advert's `FLAG_NO_AWARE`
     * half that the plane owns (di/ ORs in the switches and the hardware). True on the health's evidence ([radioOff]),
     * in deep idle, or with attaching given up. Never for dormancy alone or a Wi-Fi Direct pause: a dormant plane wakes
     * for a peer that asks.
     *
     * Deep idle is here and not in [radioOff] on purpose (2t49's 2026-10-24 amendment): its Aware works on the phones
     * measured, but not shown harmless on every phone, and a false "don't count on me" only delays a peer's wake, where
     * a false "Wi-Fi off" banner misleads the person. On the API 37 Pixels deep idle holds until unlock, so a lit lock
     * screen still sets the bit. The transport re-reads this on `ACTION_DEVICE_IDLE_MODE_CHANGED`, so both edges land
     * at once; a deep maintenance window reads not-idle and starts the bit's 60 s clear (`NoAwareFlag`), so a window
     * shorter than that leaves the bit set, and a longer one clears it until idle resumes.
     */
    fun wontRun(
        f: RadioFacts,
        attachAbandoned: Boolean,
        holdMs: Long = UNAVAILABLE_HOLD_MS,
        beatMs: Long = OWN_CLOSE_BEAT_MS,
    ): Boolean = radioOff(f, holdMs, beatMs) || f.deviceIdle == true || attachAbandoned

    /**
     * A dormant plane's health: Unavailable only on evidence the radio is off ([radioOff], never deep idle); Degraded
     * only for a give-up the attach budget made (a fault dormancy did not cause, and the one the plane would read
     * awake); Healthy otherwise. Never Degraded for being dormant, and never Unavailable for our own close's NAN-down.
     */
    fun dormantHealth(
        radioOff: Boolean,
        attachAbandoned: Boolean,
    ): TransportHealth =
        when {
            radioOff -> TransportHealth.Unavailable
            attachAbandoned -> TransportHealth.Degraded
            else -> TransportHealth.Healthy
        }

    /** What one availability broadcast may do to the attach budget's edge memory. */
    data class Edge(
        /** Write the broadcast into `lastAvailable` (and so let a later true read it as an edge). */
        val record: Boolean,
        /** Clear the attach backoff: a genuine false→true edge, or the radio genuinely going off. */
        val refund: Boolean,
    )

    /**
     * An availability broadcast, judged against the dormancy close. Inside [beatMs] after it ([closedAt]), a broadcast
     * is that close's own NAN-down echo: not recorded and refunding nothing, so dormant → duty → dormant cannot pay
     * for its own retries. Outside it, the receiver's rules as they stand (ADR 055): a false refunds and is recorded;
     * a true refunds only after a recorded false.
     */
    fun availabilityEdge(
        available: Boolean,
        wasAvailable: Boolean?,
        now: Long,
        closedAt: Long,
        beatMs: Long = OWN_CLOSE_BEAT_MS,
    ): Edge {
        if (closedAt != 0L && now - closedAt < beatMs) return Edge(record = false, refund = false)
        return Edge(record = true, refund = !available || wasAvailable == false)
    }

    /** How long a started plane with nothing to do stays attached before it detaches. */
    const val DORMANT_AFTER_MS = 180_000L

    /** How long a duty attach stays up, from `onAttached`. */
    const val DUTY_WINDOW_MS = 30_000L

    /** How often a dormant plane runs its duty attach (a shorter value through `…debug.NANDORM --el duty`). */
    const val DUTY_INTERVAL_MS = 720_000L

    /**
     * The least time between an on-screen probe and the last duty or probe window's start, or the last dormancy close
     * ([probe]): a person who opens Knit often re-pays the attached-screen-on cost once per this, not once per open.
     */
    const val PROBE_FLOOR_MS = 300_000L

    /** How long `isAvailable` must read false before it counts as the radio off. */
    const val UNAVAILABLE_HOLD_MS = 30_000L

    /**
     * The beat after our own close in which an availability broadcast is its echo: the 2 s settle plus the broadcast's
     * lag, which runs to many seconds on a dark screen (`SESSION_CYCLE_SETTLE_MS`, 7e457907).
     */
    const val OWN_CLOSE_BEAT_MS = 30_000L
}
