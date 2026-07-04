package app.getknit.knit.mesh.wifiaware

import kotlin.random.Random

/**
 * Pure, JVM-testable policy for how the NAN **initiator** reacts to a failed data-path handshake — the fix for
 * the discover→initiate→fast-fail→re-arm rediscovery livelock (docs/NAN_CONCURRENCY_REAUDIT.md; field-drilled
 * on 3 Pixels 2026-07-04). Kept Android-free like [NanServePolicy] so the curve/threshold are unit-asserted.
 *
 * A **fast** `onUnavailable` (well under the handshake timeout — see [WifiAwareTransport.FAST_FAIL_MS]) is
 * ambiguous: it is the signature of BOTH a genuinely stale subscribe handle (the peer restarted, so
 * re-discovering for a fresh handle is the cure) AND a peer whose responder is wedged or busy on its one NDI
 * (our handle is fine — re-discovering just yields an identical handle that fast-fails the same way, and the
 * repeated subscribe re-arm it drives is itself a wedge trigger on these chipsets). The two are
 * indistinguishable at the instant of failure, so this policy discriminates **behaviorally**, by the
 * consecutive-fast-fail streak:
 *
 * - The **first** fast-fail ([dropHandleOnFastFail] true) is treated as a stale handle → drop it + let
 *   `needsRediscovery` re-arm subscribe. The restart drill (single initiator, 8/8 genuine restarts) showed this
 *   is correct *and* sufficient: a real restart links on the fresh handle first-try, so the streak tops out at 1.
 * - A **second+ consecutive** fast-fail means the *fresh* handle also failed → the fault is the peer's
 *   responder, not our handle. Keep the handle (which suppresses the re-arm, since `needsRediscovery` only
 *   fires for peers *absent* from `discovered`) and let the escalating [backoffMs] + peer-side recovery
 *   (its responder self-heal / freed contention) clear it. Provably safe: a benign restart never reaches 2.
 *
 * Backoff escalates geometrically from a **short** base — the drill showed a flat 10 s base needlessly added
 * ~10 s to every genuine reconnection (the fresh MATCH lands in a few seconds, but the initiate waited out the
 * backoff) — doubling to a cap so a durably-unlinkable peer polls at a low rate instead of churning, spread
 * with jitter so two initiators contending for one responder (the larger-id peers P and Q both initiating to R)
 * don't retry it in lockstep.
 */
internal object NanConnectPolicy {
    /** At/above this consecutive fast-fail count, keep the handle ⇒ this peer stops driving the subscribe re-arm. */
    const val REARM_SUPPRESS_STREAK = 2

    private const val BASE_BACKOFF_MS = 2_000L

    // Cap the retry interval at ~the responder self-heal window ([WifiAwareTransport] Tier-1 checkWedge, ~45-60 s):
    // a peer wedged-then-healed is retried within one cap instead of waiting out a longer BLE-style 180 s backoff,
    // so worst-case reconvergence after a heal stays ~1 min. The curve is 2→4→8→16→32→60(cap) s.
    private const val MAX_BACKOFF_MS = 60_000L
    private const val JITTER_FRACTION = 0.2
    private const val MAX_SHIFT = 16 // BASE shl 16 already dwarfs the cap; bound the shift so the Long can't wrap

    /** Only the first fast-fail is a (probable) stale handle worth re-discovering; a 2nd+ is a peer-side fault. */
    fun dropHandleOnFastFail(streak: Int): Boolean = streak < REARM_SUPPRESS_STREAK

    /**
     * Backoff after the [streak]-th consecutive fast-fail (1 = the first): [BASE_BACKOFF_MS] doubling each
     * streak, saturating at [MAX_BACKOFF_MS], then spread ±[JITTER_FRACTION] by [rand] (a 0..1 source; 0.5
     * yields exactly the un-jittered delay).
     */
    fun backoffMs(
        streak: Int,
        rand: () -> Double = { Random.nextDouble() },
    ): Long {
        val shift = (streak.coerceAtLeast(1) - 1).coerceAtMost(MAX_SHIFT)
        val raw = (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        val delta = raw * JITTER_FRACTION
        val jittered = raw - delta + rand() * (delta + delta)
        return jittered.toLong().coerceAtLeast(0L)
    }
}
