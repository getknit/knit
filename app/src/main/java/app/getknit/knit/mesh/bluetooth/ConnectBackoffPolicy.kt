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
 * The dial keys a held link's refused dials were booked under (hj4a's amendment of 2026-11-06), so the link's end clears
 * exactly those and the real relink is not held off by a backoff the held link earned: an accepted link has no dial key, and
 * a dialed one clears only its own, so no registration would. Keyed by what was booked, never read back from presence: an
 * entry that aged out or a dial target that moved would miss the key, and a read could clear another address's streak,
 * which `ProvenDialStreak`'s unconfirmed end must find as the dial left it (ADR 2026-11.264w).
 *
 * Only a link that outlived [ProvenDialStreak.CONFIRM_BY_AGE_MS] clears anything, a replacement chain counted whole, so a
 * fork that dials in, refuses our dial and closes cannot reset the streak each cycle. What that bounds is refused dials per
 * tag and address: holding each link that long buys the backoff's first two steps, two in 15 s. It does not bound three
 * things. An accepted dial resets its key by design, so a fork that accepts and proves escapes the gate at churn rate, two
 * handshakes a dial. A fresh address is a fresh key (an open follow-up). A faked crossing books nothing here, so its dials
 * are not on this backoff at all: about one free dial per 8 to 12 s. The cost of the gate: an honest link under 15 s leaves
 * its streak standing (hj4a's amendment).
 *
 * [L] is the link, compared by `equals`. Not thread-safe: the transport calls it under its lock, the backoff book's, which
 * orders a booking against the end of the link it names.
 */
internal class HeldStreaks<L : Any> {
    /** What a link earned, oldest first, and how long the links it replaced held before it. */
    private class Earned {
        val keys = LinkedHashSet<String>()
        var priorMs = 0L

        /** The current key is the one that matters: past the cap the oldest goes, left to its own backoff uncleared. */
        fun add(key: String) {
            keys -= key
            keys += key
            if (keys.size > MAX_KEYS_PER_LINK) keys -= keys.first()
        }
    }

    private val earned = HashMap<L, Earned>()

    /** A dial booked as [record] under [dialKey] failed: the link [held] names earns the key, if it began held and that link stands. */
    fun onDialFailed(
        record: DialPlan.InFlight?,
        dialKey: String,
        held: (String) -> L?,
    ) {
        if (record?.linkedAtStart != true) return
        val link = held(record.peerKey) ?: return
        earned.getOrPut(link) { Earned() }.add(dialKey)
    }

    /**
     * [from], held [fromHeldMs], was replaced by [to] under the same id: the peer is still held, so what it earned stays
     * earned and its hold counts towards [to]'s. A link that earned nothing still passes its age on: the chain held that long.
     */
    fun moved(
        from: L,
        to: L,
        fromHeldMs: Long,
    ) {
        val carried = earned.remove(from)
        val next = earned.getOrPut(to) { Earned() }
        next.priorMs += fromHeldMs + (carried?.priorMs ?: 0L)
        carried?.keys?.forEach(next::add)
    }

    /** [link] ended after [heldMs]: the keys it earned leave [book] if its chain outlived the confirm age. Forgotten either way. */
    fun ended(
        link: L,
        heldMs: Long,
        book: MutableMap<String, ConnectBackoffEntry>,
    ) {
        val ended = earned.remove(link) ?: return
        // Borrowed from 264w: the age past which a responder has kept a link. This bound moves if that constant does.
        if (heldMs + ended.priorMs >= ProvenDialStreak.CONFIRM_BY_AGE_MS) book.keys -= ended.keys
    }

    /** The radio went down with every link, and the book with them. */
    fun clear() = earned.clear()

    private companion object {
        const val MAX_KEYS_PER_LINK = 16
    }
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
