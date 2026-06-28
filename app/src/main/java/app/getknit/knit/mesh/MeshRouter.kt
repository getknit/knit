package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.cappedTtl
import app.getknit.knit.mesh.protocol.incrementHop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Transport-agnostic mesh logic: deduplicates incoming frames, delivers new ones locally, and floods
 * them onward bounded by the frame's TTL.
 *
 * Rather than rebroadcasting immediately (blind flooding, which storms a dense cluster with O(n²)
 * redundant sends), a first-seen frame's relay is scheduled after a small random [jitter] delay. If,
 * during that window, the same frame is overheard from enough neighbors ([suppressThreshold]) — i.e.
 * a neighbor already relayed it — the pending relay is cancelled. This counter-based suppression cuts
 * redundant traffic in dense meshes while still relaying reliably in sparse ones (where no duplicate
 * is overheard). Locally-originated frames bypass this and send immediately.
 *
 * Kept free of Android/Room/Nearby dependencies so it can be unit-tested with a fake transport.
 */
class MeshRouter(
    private val transport: MeshTransport,
    private val scope: CoroutineScope,
    private val seen: SeenSet = SeenSet(),
    private val metrics: MeshMetrics = MeshMetrics(),
    private val jitterWindowMs: Long = DEFAULT_JITTER_WINDOW_MS,
    private val suppressThreshold: Int = DEFAULT_SUPPRESS_THRESHOLD,
    private val jitter: () -> Long = { Random.nextLong(jitterWindowMs) },
    private val onDeliver: suspend (frame: Frame, fromNodeId: String) -> Unit,
) {

    /**
     * A relay scheduled but not yet fired. [relayed] is the hop-incremented frame; [heardFrom] is
     * every neighbor we've heard this frame id from (all excluded from the eventual relay — split
     * horizon across every source, not just the first); [count] is how many copies we've seen so far
     * (starts at 1 for the first sighting); [job] is the delay-then-send coroutine.
     */
    private class PendingRelay(
        val relayed: Frame,
        val heardFrom: MutableSet<String>,
        var count: Int,
        var job: Job? = null,
    )

    // Relays awaiting their jitter window, keyed by frame id. Guarded by [pendingLock] so count/fire/
    // cancel stay race-free under the multi-threaded production dispatcher.
    private val pending = mutableMapOf<String, PendingRelay>()
    private val pendingLock = Mutex()

    /** Begins consuming inbound frames from the transport. */
    fun start() {
        scope.launch {
            transport.inbound.collect { (frame, fromNodeId) ->
                handleInbound(frame, fromNodeId)
            }
        }
    }

    /** Processes one inbound frame: deliver+schedule if new, else count it toward overhear suppression. */
    suspend fun handleInbound(frame: Frame, fromNodeId: String) {
        if (!seen.add(frame.id)) {
            // Duplicate: never re-deliver or start a second relay, but it IS evidence the frame is
            // already propagating — count it against any relay we still have pending.
            metrics.onDeduped()
            countOverheard(frame.id, fromNodeId)
            return
        }
        metrics.onDelivered()
        onDeliver(frame, fromNodeId)
        scheduleRelay(frame, fromNodeId)
    }

    /** Sends a locally-originated frame to the whole mesh. Bypasses suppression — sends immediately. */
    suspend fun originate(frame: Frame) = sendOwn(frame, to = null)

    /**
     * Sends a locally-originated frame to [to] (or the whole mesh when null), marking it seen so an
     * echo arriving back from the mesh isn't re-delivered or re-relayed.
     */
    suspend fun sendOwn(frame: Frame, to: Peer? = null) {
        seen.add(frame.id)
        metrics.onOriginated()
        transport.send(frame, to)
    }

    /** Cancels all pending relays and clears bookkeeping. Call from [MeshManager.stop]. */
    suspend fun stop() {
        val jobs = pendingLock.withLock {
            val snapshot = pending.values.mapNotNull { it.job }
            pending.clear()
            snapshot
        }
        jobs.forEach { it.cancel() }
    }

    /**
     * Schedules a first-seen relayable frame to be forwarded after a jitter delay. Non-relayable and
     * TTL-exhausted frames are dropped synchronously, exactly as before (so blob requests and dead
     * frames behave identically to immediate flooding).
     */
    private suspend fun scheduleRelay(frame: Frame, fromNodeId: String) {
        if (!frame.relayable) return // point-to-point control frames propagate hop-by-hop, not flooded
        // [ttl] is attacker-controlled; cap it to the local default so a forged oversized value can't
        // keep a frame alive past the dedup window and flood the mesh. Every relayer caps independently,
        // so the hop count alone bounds propagation regardless of what ttl a peer claims.
        val effectiveTtl = minOf(frame.ttl, DEFAULT_TTL)
        if (frame.hops >= effectiveTtl) return
        val entry = PendingRelay(
            relayed = frame.incrementHop().cappedTtl(DEFAULT_TTL),
            heardFrom = mutableSetOf(fromNodeId),
            count = 1,
        )
        pendingLock.withLock { pending[frame.id] = entry }
        entry.job = scope.launch {
            delay(jitter())
            // Re-check under lock: an overhear may have removed us during the delay.
            val live = pendingLock.withLock { pending.remove(frame.id) } ?: return@launch
            val excluded = live.heardFrom.toSet()
            transport.neighbors.value
                .filter { it.nodeId !in excluded }
                .forEach { neighbor -> transport.send(live.relayed, neighbor) }
            metrics.onRelayed()
        }
    }

    /**
     * A duplicate of [frameId] arrived from [fromNodeId]. Record the source (so we never relay back to
     * it) and bump the overhear count; once it reaches [suppressThreshold], cancel the pending relay.
     */
    private suspend fun countOverheard(frameId: String, fromNodeId: String) {
        val jobToCancel = pendingLock.withLock {
            val entry = pending[frameId] ?: return // already fired, or never relayable
            entry.heardFrom += fromNodeId
            entry.count += 1
            if (entry.count >= suppressThreshold) {
                pending.remove(frameId)
                entry.job
            } else {
                null
            }
        }
        if (jobToCancel != null) {
            jobToCancel.cancel() // cancel OUTSIDE the lock to avoid blocking other inbound frames
            metrics.onSuppressed()
        }
    }

    private companion object {
        /** Max jitter before a first-seen relayable frame is forwarded. */
        const val DEFAULT_JITTER_WINDOW_MS = 150L

        /** Overhear count (including our own pending copy) at which we cancel our relay. */
        const val DEFAULT_SUPPRESS_THRESHOLD = 2
    }
}
