package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.ConnectFailReason
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

    /**
     * How long the connection engine may sleep before it must look again: until [nextDueAt], the earliest
     * backoff deadline still ahead of [now] (a backed-off peer becoming eligible is the one thing no event
     * signals), clamped to [minMs]..[maxMs]; [maxMs] alone when nothing is due, since any event wakes it sooner.
     */
    fun nextDueWaitMs(
        now: Long,
        nextDueAt: Long?,
        minMs: Long,
        maxMs: Long,
    ): Long = if (nextDueAt == null) maxMs else (nextDueAt - now).coerceIn(minMs, maxMs)

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

/** Per-peer connect backoff: consecutive-failure streak + the elapsed-time deadline before the next attempt. */
internal class ConnectBackoffEntry(
    var streak: Int = 0,
    var nextAt: Long = 0L,
)

/**
 * The transport's per-peer backoff book: pure over the map [BluetoothMeshTransport] holds under its lock, clocked by the
 * `now` each call passes. A peer backs off while `now` is before its deadline.
 */
internal object ConnectBackoffs {
    /** Advances [nodeId]'s failure streak and sets its next-eligible deadline by [ConnectBackoffPolicy]: (streak, deadline). */
    fun bump(
        entries: MutableMap<String, ConnectBackoffEntry>,
        nodeId: String,
        now: Long,
        config: BackoffConfig,
        rand: () -> Double = { Random.nextDouble() },
    ): Pair<Int, Long> {
        val entry = entries.getOrPut(nodeId) { ConnectBackoffEntry() }
        entry.streak += 1
        entry.nextAt = now + ConnectBackoffPolicy.nextDelayMs(entry.streak, config, rand)
        return entry.streak to entry.nextAt
    }

    /** Whether a dial to [nodeId] may begin at [now]: not [linked], none [inFlight], and not backing off. */
    fun mayBegin(
        entries: Map<String, ConnectBackoffEntry>,
        nodeId: String,
        linked: Set<String>,
        inFlight: Set<String>,
        now: Long,
    ): Boolean = nodeId !in linked && nodeId !in inFlight && entries[nodeId]?.let { now >= it.nextAt } != false

    /**
     * A dial to [nodeId] failed at [now]: its streak advances ([bump]), unless the peer is [linkedNow] — a dial it
     * refused because its own dial to us linked first (a lonely dial crossing the ordinary one, ADR 2026-09.hj4a) is
     * no failure to back off from, and that link reset the streak already. (streak, deadline).
     */
    fun onDialFailed(
        entries: MutableMap<String, ConnectBackoffEntry>,
        nodeId: String,
        linkedNow: Boolean,
        now: Long,
        config: BackoffConfig,
        rand: () -> Double = { Random.nextDouble() },
    ): Pair<Int, Long> = if (linkedNow) 0 to now else bump(entries, nodeId, now, config, rand)

    /**
     * Whether a link that ended on its own counts as a flap on the peer's streak: only when the transport [released] its
     * own link. One replaced by a fresh link from the same peer ([replaced]) holds the slot, and the peer did not flap.
     */
    fun flapped(
        released: Boolean,
        replaced: Boolean,
    ): Boolean = released || !replaced

    /** The peers still backing off at [now]. */
    fun active(
        entries: Map<String, ConnectBackoffEntry>,
        now: Long,
    ): Set<String> = entries.filterValues { now < it.nextAt }.keys.toSet()

    /**
     * Drops the state of every peer whose window has passed and that [keep] does not hold (present, linked or being
     * dialed), so a returning peer starts fresh instead of inheriting a maxed-out streak.
     */
    fun prune(
        entries: MutableMap<String, ConnectBackoffEntry>,
        now: Long,
        keep: (String) -> Boolean,
    ) {
        entries.entries.removeAll { (id, b) -> now >= b.nextAt && !keep(id) }
    }

    /** The earliest deadline still ahead of [now], null with none: when a backed-off peer becomes eligible again. */
    fun nextDueAt(
        entries: Map<String, ConnectBackoffEntry>,
        now: Long,
    ): Long? = entries.values.filter { it.nextAt > now }.minOfOrNull { it.nextAt }
}

/**
 * A best-effort bucket for a connect's (otherwise discarded) exception; the raw cause is logged regardless. A
 * watchdog-forced timeout is failed as [ConnectFailReason.TIMEOUT] at its call site, so it never lands here.
 */
internal object ConnectFailClassifier {
    fun classify(cause: Throwable?): ConnectFailReason {
        val msg = cause?.message.orEmpty().lowercase()
        return when {
            cause is java.net.SocketTimeoutException -> ConnectFailReason.TIMEOUT
            "radio" in msg || "enomem" in msg || "no resources" in msg || "busy" in msg -> ConnectFailReason.RADIO
            "refused" in msg || "reset" in msg -> ConnectFailReason.REFUSED
            else -> ConnectFailReason.OTHER
        }
    }
}
