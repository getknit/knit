package app.getknit.knit.mesh.wifiaware

import kotlin.random.Random

/**
 * Pure, JVM-testable pacing for the accept-any **responder** request after the framework declares it
 * unfulfillable (`NetworkCallback.onUnavailable`). The third of the transport's retry policies: [NanConnectPolicy]
 * paces a handshake to one peer, [NanAttachPolicy] bounds a failing attach, and this one bounds a re-file.
 *
 * The framework removes the standing responder request from its cache and fires `onUnavailable` on it when
 * an inbound NDP request finds no interface to land on — on a single-NDI chipset, when this node's own
 * initiator link holds the one interface (`onDataPathRequest → selectInterfaceForRequest → null`, P0 of
 * `docs/NAN_CONCURRENCY_REAUDIT.md`). Until 2026-09-18 the transport answered every verdict with an immediate
 * `stopResponder(); startResponder()`, on the reasoning that the refused knocker backs off and retries against the
 * fresh request. That reasoning holds for a knock. It does not hold when the verdict is about the request itself —
 * a publish handle the framework no longer knows, an interface it will not hand out — and then the answer to
 * the fresh request is another verdict ~10 ms later: the lab Pixel 3 re-filed **174 times in 130 ms** with no
 * link up, until an unrelated re-attach tore the session down (work item #77). Every `requestNetwork` is a
 * binder object in `system_server` until its callback is unregistered, and the churn itself is the cost.
 *
 * ## Two kinds of verdict
 *
 * The transport tells them apart by what it knows about the interface at the moment the verdict lands:
 *
 * - **Contended** — a link, a handshake or an accept of ours is live (or the post-link settle has not run
 *   out), so the interface really was busy and the verdict is the framework doing what P0 documented. It says
 *   nothing about the responder and is not counted; it is re-filed after the [contendedRefileMs] floor, so a
 *   framework that refuses in a loop costs two files a second for the life of the link rather than a hundred.
 *   Knocks themselves are rate-limited by the knockers' own [NanConnectPolicy] curve.
 * - **Uncontended** — nothing of ours holds the interface, and the framework refused anyway. This is the one
 *   that indicts the request, so it counts: the [streak][refileDelayMs] backs the re-file off along a doubling
 *   curve from the same floor, and at [CYCLE_AT_STREAK] consecutive refusals the request is given up on and the
 *   session cycled ([cycleSession]) — a fresh publish, a fresh handle, the framework's request cache wiped —
 *   because a request refused five times in a row with the interface free is not going to take on the sixth.
 *
 * ## Why the cycle is capped
 *
 * A session cycle closes the attach and both discovery sessions, which orphans every `PeerHandle` the
 * initiator would dial with; a cycle that fires every cooldown for as long as a refusal persists is the
 * livelock [NanWatchdogPolicy] documents (three Pixels, 2026-09-07 — the recovery was the fault). So
 * [MAX_CYCLES] cycles per episode, and then the re-file simply continues along the curve, saturating at
 * [MAX_BACKOFF_MS]: a chipset that refuses the responder forever costs one request a minute, and the responder
 * is still retried in case the framework changes its mind. An episode ends only when the request is
 * **fulfilled** — the responder's `onAvailable`, an NDP that actually landed on it — never on a fresh session
 * (the escalation's own cycle would refund itself) and never on an Aware availability edge (the cycle produces
 * one). The [NanAttachPolicy] lesson: a budget is only worth what its refund path is worth.
 */
internal object NanResponderPolicy {
    /**
     * Base of the curve, and the floor for a contended re-file. Half a second is invisible to the knocker — its
     * own first retry is two seconds out — and bounds a refusing framework at two files a second.
     */
    const val BASE_BACKOFF_MS = 500L

    /**
     * A minute, aligned with [NanConnectPolicy]'s cap and the Tier-1 responder heal window: a responder the
     * framework refuses for good is retried at the rate the rest of the transport already polls a dead peer.
     */
    const val MAX_BACKOFF_MS = 60_000L

    /**
     * Consecutive uncontended refusals before the request is abandoned for a session cycle. Along the curve
     * that is four re-files over ~7.5 s of a free interface — long past the ~10 ms the framework takes to
     * answer, and well short of the 45 s Tier-1 watchdog that would otherwise be the first recovery.
     */
    const val CYCLE_AT_STREAK = 5

    /** Session cycles one episode may spend, matching `WifiAwareTransport.MAX_RESPONDER_REFRESHES`. */
    const val MAX_CYCLES = 3

    private const val JITTER_FRACTION = 0.2
    private const val MAX_SHIFT = 16 // BASE shl 16 already dwarfs the cap; bound the shift so the Long can't wrap

    /**
     * Whether the [streak]-th consecutive uncontended refusal should cycle the session instead of re-filing,
     * given [cyclesSpent] cycles already taken this episode. The transport additionally holds it to the shared
     * reattach cooldown; a refusal that arrives inside that window re-files along the curve instead.
     */
    fun cycleSession(
        streak: Int,
        cyclesSpent: Int,
    ): Boolean = streak >= CYCLE_AT_STREAK && cyclesSpent < MAX_CYCLES

    /**
     * How long to wait before re-filing after the [streak]-th consecutive uncontended refusal (1 = the first):
     * [BASE_BACKOFF_MS] doubling per refusal, saturating at [MAX_BACKOFF_MS], then spread ±[JITTER_FRACTION] by
     * [rand] (a 0..1 source; 0.5 yields exactly the un-jittered delay). The curve is 0.5→1→2→4→8→16→32→60(cap) s.
     */
    fun refileDelayMs(
        streak: Int,
        rand: () -> Double = { Random.nextDouble() },
    ): Long {
        val shift = (streak.coerceAtLeast(1) - 1).coerceAtMost(MAX_SHIFT)
        val raw = (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        val delta = raw * JITTER_FRACTION
        val jittered = raw - delta + rand() * (delta + delta)
        return jittered.toLong().coerceAtLeast(0L)
    }

    /**
     * How long to wait before re-filing after a verdict that arrived while our own link, handshake or accept held
     * the interface: the floor, jittered, whatever the streak says — it is not evidence against the responder.
     */
    fun contendedRefileMs(rand: () -> Double = { Random.nextDouble() }): Long = refileDelayMs(1, rand)
}
