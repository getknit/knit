package app.getknit.knit.mesh.wifiaware

/**
 * Pure decision for the coordination-plane **ack-starvation watchdog** (work item #81, ADR 2026-09.jjhg): the
 * Wi-Fi Aware follow-up message plane — cues and the fast path — can die on a phone while discovery keeps
 * working, and nothing else in the transport notices. [NanWatchdogPolicy] keys on a sync owed with no data-path
 * link; a dead message plane owes nothing (custody converges over Bluetooth) and holds no link, so it never fires.
 * The fifth of the transport's pure policies beside [NanConnectPolicy], [NanAttachPolicy], [NanResponderPolicy]
 * and [NanInitiatorPolicy]; the transport owns the bookkeeping and the side effect, this object maps them to an
 * [Action] plus the next episode state.
 *
 * The field shape (`soak-20260921-bursts`, three Pixels, 11 h): at the end of a chat burst `nanMsgsAcked` goes
 * flat and `nanMsgSendsFailed` climbs by the whole send rate, for hours, while `onServiceDiscovered` keeps
 * re-firing and every health surface reads `Healthy`. Two mechanisms produce that one signature, and the record
 * held both:
 *
 * - **The framework's send queue deadlocked.** `WifiAwareStateManager` blocks its queue on a firmware
 *   `FOLLOWUP_TX_QUEUE_FULL`, but arms its 10 s send timeout only off messages it still tracks — with none
 *   tracked nothing ever unblocks. The next 50 sends from our uid queue forever with **no callback at all**, and
 *   every send after that fails at once (`MESSAGE_QUEUE_DEPTH_PER_UID`). `dumpsys wifiaware`: `mSendQueueBlocked:
 *   true`, `mFwQueuedSendMessages` empty, 50 host-queued.
 * - **The firmware stopped delivering unicast.** Queue idle, every follow-up accepted by the firmware and failed
 *   ~4 s later — including one to a peer matched seconds earlier under its current NAN address, so this is not
 *   a stale `PeerHandle`.
 *
 * Both are cleared by exactly one thing the app can do: a **NAN restart**. Knit is the only Aware client on these
 * phones, so closing our attach is the framework's last-client detach — `onAwareDownCleanupSendQueueState()`,
 * then a firmware disable/enable with a fresh address. Every recovery in the run followed one (a Doze
 * NAN-down, a `reattach()`, a session cycle); the one phone that never restarted stayed frozen five hours.
 *
 * So the verdict is [Action.CycleSession], and the two mechanisms are the two tests:
 * - **swallowed** — the oldest send still waiting for a callback is older than [Tuning.swallowedMs] and no ack
 *   has arrived since it went out. The framework's own timeout is 10 s, so a message it has held for a whole
 *   watchdog tick is one it is not tracking.
 * - **starved** — at least [Tuning.minFails] failures since the last ack, no ack for [Tuning.starvedMs] (two cue
 *   heartbeats), and a peer *sighted* over the coordination plane within [Tuning.sightingFreshMs]. The sighting
 *   is load-bearing: a peer that walked out of range fails its cues too, and cycling the session for that is
 *   the churn ADR 2026-09.9dnk exists to forbid. A frozen phone keeps discovering the peers it cannot reach.
 *
 * **The budget is the same shape as Tier 1's, and for the same reason.** [Tuning.maxCycles] per episode, on the
 * shared reattach cooldown, and the episode ends only when an ack lands *after* it began — never on the cycle
 * itself, because a cycle that did not cure must not refund its own budget (that is the 9dnk livelock) — nor on
 * the beat the cycle's own NAN-down leaves the node unhealthy and alone, for the same reason. A spent episode
 * goes quiet; after [Tuning.refundMs] with nothing cycled it earns one more set, so a plane that stays dead — a
 * peer that never acks anyone — costs at most [Tuning.maxCycles] restarts per [Tuning.refundMs].
 */
object NanMessagePlanePolicy {
    enum class Action { None, CycleSession }

    /**
     * [action] to take now, plus the episode state the transport writes back: [nextEpisodeSince] (0 = no
     * episode) and [nextCycles] (session cycles spent in this episode). They reset together, like Tier 1's.
     */
    data class Decision(
        val action: Action,
        val nextEpisodeSince: Long,
        val nextCycles: Int,
    )

    /**
     * One instant's evidence. Every time is `elapsedRealtime`.
     *
     * @param healthy hardware present, Aware healthy, a live session — else there is no plane to judge.
     * @param cueTargets peers we hold a send handle for; 0 means alone, and alone is not starved.
     * @param oldestUnansweredSentAt when the oldest send still waiting for its callback went out; 0 = none.
     * @param lastAckAt the last `onMessageSendSucceeded`; the transport stamps it at start so the clock runs
     *   from a known point rather than from 0.
     * @param failsSinceAck `onMessageSendFailed` callbacks since [lastAckAt].
     * @param lastSightingAt the last discovery or inbound coordination-plane message from any peer.
     * @param episodeSince start of the current stalled episode (0 = none).
     * @param cycles session cycles already spent in this episode.
     * @param lastReattachAt the transport's shared cycle stamp (Tier 1 and this policy pace off one clock).
     */
    @Suppress("LongParameterList") // one fact per axis the decision reads; a snapshot, not a call site
    data class Facts(
        val healthy: Boolean,
        val cueTargets: Int,
        val now: Long,
        val oldestUnansweredSentAt: Long,
        val lastAckAt: Long,
        val failsSinceAck: Int,
        val lastSightingAt: Long,
        val episodeSince: Long,
        val cycles: Int,
        val lastReattachAt: Long,
    )

    /** The tuning, so the transport and the tests read one set of numbers. */
    data class Tuning(
        val swallowedMs: Long,
        val starvedMs: Long,
        val minFails: Int,
        val sightingFreshMs: Long,
        val reattachCooldownMs: Long,
        val maxCycles: Int,
        val refundMs: Long,
    ) {
        companion object {
            /**
             * [swallowedMs] is one watchdog tick — three times the framework's own send timeout, so a healthy
             * but slow burst (eight firmware slots, each completed in well under a second) can never look
             * swallowed. [starvedMs] is two cue heartbeats; [minFails] is what two heartbeats to two peers fail.
             * [sightingFreshMs] sits under the 150 s reachability linger so a peer that walked away is pruned
             * before it can be mistaken for a dead plane. [reattachCooldownMs] and [maxCycles] are Tier 1's.
             */
            val PRODUCTION =
                Tuning(
                    swallowedMs = 30_000L,
                    starvedMs = 60_000L,
                    minFails = 4,
                    sightingFreshMs = 90_000L,
                    reattachCooldownMs = 20_000L,
                    maxCycles = 3,
                    refundMs = 15 * 60_000L,
                )
        }
    }

    fun decide(
        f: Facts,
        t: Tuning = Tuning.PRODUCTION,
    ): Decision {
        // An ack after the episode began is the cure; the budget refunds with it.
        if (f.episodeSince != 0L && f.lastAckAt >= f.episodeSince) return Decision(Action.None, 0L, 0)
        // No plane, or nobody to send to: nothing to judge — and nothing to refund. The cycle's own NAN-down
        // makes the node unhealthy and alone for a beat; an episode that cleared on that would refund its
        // budget every cycle, which is the 9dnk livelock. The episode waits for an ack or the refund period.
        if (!f.healthy || f.cueTargets == 0) return Decision(Action.None, f.episodeSince, f.cycles)
        if (!swallowed(f, t) && !starved(f, t)) return Decision(Action.None, f.episodeSince, f.cycles)
        val since = if (f.episodeSince == 0L) f.now else f.episodeSince
        val cycles = cyclesAfterRefund(f, t)
        if (cycles < t.maxCycles && f.now - f.lastReattachAt >= t.reattachCooldownMs) {
            return Decision(Action.CycleSession, since, cycles + 1)
        }
        return Decision(Action.None, since, cycles)
    }

    /** Mechanism 1: the oldest send still unanswered is a whole watchdog tick old, and nothing was acked since. */
    private fun swallowed(
        f: Facts,
        t: Tuning,
    ): Boolean =
        f.oldestUnansweredSentAt != 0L &&
            f.now - f.oldestUnansweredSentAt >= t.swallowedMs &&
            f.lastAckAt < f.oldestUnansweredSentAt

    /** Mechanism 2: two heartbeats of failures with no ack, while a peer is still being sighted. */
    private fun starved(
        f: Facts,
        t: Tuning,
    ): Boolean =
        f.failsSinceAck >= t.minFails &&
            f.now - f.lastAckAt >= t.starvedMs &&
            f.now - f.lastSightingAt <= t.sightingFreshMs

    /** A spent episode that has been quiet for the refund period earns a fresh set of cycles. */
    private fun cyclesAfterRefund(
        f: Facts,
        t: Tuning,
    ): Int = if (f.cycles >= t.maxCycles && f.now - f.lastReattachAt >= t.refundMs) 0 else f.cycles
}
