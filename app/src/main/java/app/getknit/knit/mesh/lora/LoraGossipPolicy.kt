package app.getknit.knit.mesh.lora

import kotlin.random.Random

/**
 * When to publish a [LoraCtl] OFFER — a Trickle timer (RFC 6206) over the LoRa hop.
 *
 * An OFFER is one packet, but on a ~1 kbps shared medium one packet is ~2 seconds, so publishing on a fixed
 * timer would be a standing tax on a mesh where nothing is happening. Trickle's shape fits exactly: the
 * interval **doubles while the world looks unchanged** and **snaps back to the floor the moment it doesn't**,
 * and each interval transmits at a random point in its second half so two gateways that came up together do
 * not talk over each other forever.
 *
 * ## What counts as "consistent"
 *
 * Trickle suppresses a transmission after hearing enough consistent ones. Here the only genuinely redundant
 * OFFER is one announcing the **same set** we would: our OFFER's whole job is to tell a far gateway what we
 * lack, and a peer holding a *superset* of ours has not said that on our behalf — it has said the opposite.
 * So [onOffer] counts an offer as consistent only on set equality, which in practice is the converged
 * two-pocket steady state — precisely when gossip is worth the least.
 *
 * This does **not** make a crowd of listeners free: every node with a different set still publishes. What
 * bounds a crowd is the serving side (the per-publisher cap and the bridge airtime budget), not this.
 *
 * Pure and clock-driven by the caller; [random] is injected so tests run on a fixed schedule, the way
 * `MeshRouter`'s relay jitter is.
 */
internal class LoraGossipPolicy(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val redundancy: Int = REDUNDANCY,
    private val random: (Long) -> Long = { bound -> if (bound <= 0L) 0L else Random.nextLong(bound) },
) {
    private var intervalStart = NEVER
    private var intervalMs = minIntervalMs
    private var transmitAt = 0L
    private var consistent = 0
    private var spent = false

    /** The current interval length, for logs and tests. */
    val interval: Long get() = intervalMs

    /**
     * Records an OFFER we heard. [sameSet] is whether it announced exactly the set ours would — see the class
     * doc for why nothing weaker counts.
     *
     * Both halves of Trickle live here. A same-set OFFER is redundancy, and counts toward suppressing ours.
     * A **different**-set OFFER is news — RFC 6206 resets on an inconsistent transmission whether or not we
     * act on it, and here it is the first sign a divergent pocket is on the channel, which is precisely when
     * our own OFFER (the one that says what we *lack*) is worth putting on the air soon rather than at
     * whatever backoff the silence had earned.
     *
     * It snaps only a **backed-off** timer, and that guard is load-bearing rather than an optimisation. Two
     * gateways whose sets cannot converge — the hourly serve cap spent, or a far pocket holding a permanent
     * superset — would otherwise reset each other on every offer forever, beating the floor cadence and
     * spending the BRIDGE budget on offers instead of on the backfill those offers exist to drive. A timer
     * already at [minIntervalMs] is already as fast as this policy goes; there is nothing to accelerate.
     */
    fun onOffer(
        sameSet: Boolean,
        now: Long,
    ) {
        ensureInterval(now)
        if (sameSet) {
            consistent++
        } else if (intervalMs > minIntervalMs) {
            reset(now)
        }
    }

    /**
     * Snaps the interval back to the floor: something changed that a far gateway needs to know about soon —
     * a gateway we had not heard from, an OFFER announcing a set that is not ours, or a frame that crossed
     * the bridge in either direction.
     *
     * A reset means **sooner, never later**. The fresh interval draws a fresh transmit point, which on its
     * own can land after one this interval had already picked and not yet spent — so an unspent point still
     * ahead of us keeps its slot, and one already due stays due. Without that, news arriving just before our
     * own transmit point would push it back by up to a floor interval, which is the opposite of the intent.
     */
    fun reset(now: Long) {
        val pending = if (intervalStart != NEVER && !spent) transmitAt else Long.MAX_VALUE
        intervalMs = minIntervalMs
        intervalStart = NEVER
        ensureInterval(now)
        if (pending < transmitAt) transmitAt = maxOf(pending, now)
    }

    /** When the caller should next wake: this interval's transmit point, or its end once we are past that. */
    fun nextDueAt(now: Long): Long {
        ensureInterval(now)
        return if (spent) intervalStart + intervalMs else transmitAt
    }

    /**
     * Whether to publish an OFFER right now. Consumes this interval's single transmit slot either way, so a
     * suppressed interval stays quiet rather than retrying every wake-up.
     */
    fun takeTransmitSlot(now: Long): Boolean {
        ensureInterval(now)
        if (spent || now < transmitAt) return false
        spent = true
        return consistent < redundancy
    }

    /**
     * Starts a new interval when the previous one has run out (doubling up to [maxIntervalMs]), and picks a
     * transmit point in its second half — the listen-first window that lets a peer's OFFER suppress ours.
     *
     * A new interval begins at [now], not at the old one's nominal end, so a caller that sleeps through a
     * boundary resumes cleanly instead of waking to a transmit point already in the past. The caller wakes at
     * [nextDueAt], so in practice the two coincide.
     */
    private fun ensureInterval(now: Long) {
        if (intervalStart != NEVER && now - intervalStart < intervalMs) return
        if (intervalStart != NEVER) intervalMs = minOf(intervalMs * 2, maxIntervalMs)
        intervalStart = now
        consistent = 0
        spent = false
        val half = intervalMs / 2
        transmitAt = now + half + random(half)
    }

    companion object {
        /** The floor a reset snaps back to: fast enough that a newly-arrived pocket is served within minutes. */
        const val MIN_INTERVAL_MS = 5 * 60_000L

        /**
         * The ceiling. Deliberately far below [LoraGatewayPolicy.STALE_MS] — an active gateway's OFFER doubles
         * as its liveness beacon, so a co-pocket spare must never age it out while it is healthy.
         */
        const val MAX_INTERVAL_MS = 15 * 60_000L

        /** Trickle's `k`: one identical OFFER from someone else is enough to make ours redundant. */
        const val REDUNDANCY = 1

        private const val NEVER = Long.MIN_VALUE
    }
}
