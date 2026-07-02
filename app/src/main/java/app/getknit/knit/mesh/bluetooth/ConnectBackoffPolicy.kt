package app.getknit.knit.mesh.bluetooth

import kotlin.random.Random

/**
 * Pure, JVM-testable escalating backoff for repeated Bluetooth L2CAP connect failures to one peer.
 *
 * [BluetoothMeshTransport] re-drives promotions on a fixed tick, so a peer that keeps failing to link —
 * radio busy (e.g. A2DP audio streaming to a speaker, the reported case) or simply unreachable — would
 * otherwise be re-attempted at a flat cadence forever, and *every* doomed attempt blacks out BLE scanning
 * while it sits in flight. This grows the per-peer retry gap geometrically (base, 2×, 4×, … capped) with
 * jitter, so the churn dies down while the radio is saturated yet still recovers promptly once it frees; the
 * transport resets a peer's streak the moment a link comes up.
 *
 * Kept free of Android and of a clock (the caller stamps the deadline), like [PromotionPolicy] and
 * [BlePresenceTracker], so the curve is asserted with the same unit-test style.
 */
object ConnectBackoffPolicy {

    /**
     * Backoff delay after the [streak]-th consecutive failure (1 = the first failure). Doubles each streak
     * from [config].baseMs, saturating at [config].maxMs, then spread by ±[config].jitterFraction using
     * [rand] — a 0..1 source (the app passes [Random]; tests pass a fixed value for a deterministic result,
     * where 0.5 yields exactly the un-jittered delay).
     */
    fun nextDelayMs(
        streak: Int,
        config: BackoffConfig = BackoffConfig(),
        rand: () -> Double = { Random.nextDouble() },
    ): Long {
        val shift = (streak.coerceAtLeast(1) - 1).coerceAtMost(MAX_SHIFT)
        val raw = (config.baseMs shl shift).coerceAtMost(config.maxMs)
        val delta = raw * config.jitterFraction
        val span = delta + delta // rand()∈[0,1) maps to [raw-delta, raw+delta)
        val jittered = raw - delta + rand() * span
        return jittered.toLong().coerceAtLeast(0L)
    }

    // base shl 16 already dwarfs any sane maxMs; bounding the shift keeps the Long from wrapping on a huge streak.
    private const val MAX_SHIFT = 16
}

/** Tunables for [ConnectBackoffPolicy]; all field-tunable, defaults chosen for an always-on background mesh. */
data class BackoffConfig(
    /** Delay after the first failure — the geometric base. */
    val baseMs: Long = 10_000,
    /** Ceiling the doubling saturates at. */
    val maxMs: Long = 180_000,
    /** ± fraction of each delay applied as jitter, so many nodes don't retry a shared peer in lockstep. */
    val jitterFraction: Double = 0.2,
)
