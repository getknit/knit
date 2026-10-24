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
 *   candidate, the app coming on screen, the scan going blind and the duty attach. Never `heal()`: the motion heal
 *   fires about once a minute on transit and would undo dormancy.
 * - **Duty** ([dutyDue], [duty]): while dormant, one attach every [DUTY_INTERVAL_MS] held for [DUTY_WINDOW_MS] from
 *   `onAttached`, so a peer whose Bluetooth is off is still found. Skipped while the attach budget says no.
 * - **Health and "won't run"** ([radioOff], [dormantHealth], [wontRun]): while dormant the plane reads Healthy unless
 *   there is evidence the radio is off — Wi-Fi off, deep Doze, or `isAvailable` false held [UNAVAILABLE_HOLD_MS]
 *   outside the beat after our own close ([OWN_CLOSE_BEAT_MS]) — so our own NAN-down never reads as "Wi-Fi off".
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

        /** Knit came on screen: woke (and ADR 2026-09.535d's API 31–32 retry needs a visible app). */
        VISIBLE,

        /** The duty attach found someone: woke, keeping its session. */
        DUTY,

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
            !attachAbandoned &&
            now >= attachRetryAfter &&
            now - closedAt >= settleMs

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
        /** `PowerManager.isDeviceIdleMode` — deep Doze disables Aware by default; null when unread. */
        val deviceIdle: Boolean?,
        /** When `isAvailable` was first seen false in the current run of falses; 0 while it reads true. */
        val unavailableSince: Long,
        /** Our own last session close: a dormancy close or a session cycle. Its NAN-down can echo as a false. */
        val lastOwnCloseAt: Long,
        val now: Long,
    )

    /**
     * Whether the radio is off by evidence: Wi-Fi off, deep Doze, or `isAvailable` false held [holdMs] — counted only
     * from the end of [beatMs] after our own close, whose NAN-down the framework may broadcast as unavailable for a
     * moment (unmeasured: trial F samples `mUsageEnabled` across ten dormant minutes).
     */
    fun radioOff(
        f: RadioFacts,
        holdMs: Long = UNAVAILABLE_HOLD_MS,
        beatMs: Long = OWN_CLOSE_BEAT_MS,
    ): Boolean {
        if (f.wifiEnabled == false || f.deviceIdle == true) return true
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
     * This phone will not run Wi-Fi Aware, as far as the plane can tell — the Bluetooth advert's `FLAG_NO_AWARE` half
     * that the plane owns (di/ ORs in the switches and the hardware): the radio is off by evidence, or attaching is
     * given up. Never true for dormancy alone or a Wi-Fi Direct pause: a dormant plane wakes for a peer that asks.
     */
    fun wontRun(
        radioOff: Boolean,
        attachAbandoned: Boolean,
    ): Boolean = radioOff || attachAbandoned

    /**
     * A dormant plane's health: Unavailable only on evidence the radio is off; Degraded only for a give-up the attach
     * budget made (a fault dormancy did not cause, and the one the plane would read awake); Healthy otherwise. Never
     * Degraded for being dormant, and never Unavailable for our own close's NAN-down.
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
        return Edge(record = true, refund = if (available) wasAvailable == false else true)
    }

    /** How long a started plane with nothing to do stays attached before it detaches. */
    const val DORMANT_AFTER_MS = 180_000L

    /** How long a duty attach stays up, from `onAttached`. */
    const val DUTY_WINDOW_MS = 30_000L

    /** How often a dormant plane runs its duty attach (a shorter value through `…debug.NANDORM --el duty`). */
    const val DUTY_INTERVAL_MS = 720_000L

    /** How long `isAvailable` must read false before it counts as the radio off. */
    const val UNAVAILABLE_HOLD_MS = 30_000L

    /**
     * The beat after our own close in which an availability broadcast is its echo: the 2 s settle plus the broadcast's
     * lag, which runs to many seconds on a dark screen (`SESSION_CYCLE_SETTLE_MS`, 7e457907).
     */
    const val OWN_CLOSE_BEAT_MS = 30_000L
}
