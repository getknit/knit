package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
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
 * A point-to-point frame (`relay = false`) is never flooded, but it is not necessarily finished either: if it
 * is addressed to a peer this node holds a live link to, [handOn] takes it that one hop.
 *
 * Kept free of Android/Room/transport dependencies so it can be unit-tested with a fake transport.
 */
class MeshRouter(
    private val transport: MeshTransport,
    private val scope: CoroutineScope,
    private val seen: SeenSet = SeenSet(),
    private val metrics: MeshMetrics = MeshMetrics(),
    private val jitterWindowMs: Long = DEFAULT_JITTER_WINDOW_MS,
    private val suppressThreshold: Int = DEFAULT_SUPPRESS_THRESHOLD,
    private val jitter: () -> Long = { Random.nextLong(jitterWindowMs) },
    private val budget: IngressBudget = IngressBudget(),
    // A send on someone else's behalf that actually went somewhere: the frame and the peers it was sent to —
    // the flood's relay fan-out, and [handOn]'s last hop to a point-to-point frame's addressee. The router
    // reports the fact and nothing else — whether it counts as this phone helping anyone is `ContributionLedger`'s
    // call. Not invoked for a relay that fired with no neighbor to send to. Defaulted to a no-op so the tests
    // that bind `onDeliver` as a trailing lambda are untouched; keep it before `onDeliver` for that reason.
    private val onRelayed: suspend (envelope: RelayEnvelope, to: Set<String>) -> Unit = { _, _ -> },
    private val onDeliver: suspend (wire: WireEnvelope, envelope: RelayEnvelope, fromNodeId: String, kind: TransportKind) -> Unit,
) {
    /**
     * A relay scheduled but not yet fired. [relayed] is the hop-incremented wrapper (its signed blob +
     * signature are forwarded verbatim); [heardFrom] is every neighbor we've heard this frame id from
     * (all excluded from the eventual relay — split horizon across every source, not just the first) and
     * its size is the overhear count the suppression threshold is judged on; [job] is the delay-then-send
     * coroutine.
     */
    private class PendingRelay(
        val relayed: WireEnvelope,
        // The decoded envelope, held by reference for the `onRelayed` report — nothing is re-encoded from it.
        val envelope: RelayEnvelope,
        val heardFrom: MutableSet<String>,
        var job: Job? = null,
    )

    // Relays awaiting their jitter window, keyed by frame id. Guarded by [pendingLock] so count/fire/
    // cancel stay race-free under the multi-threaded production dispatcher.
    private val pending = mutableMapOf<String, PendingRelay>()
    private val pendingLock = Mutex()

    /** Begins consuming inbound frames from the transport. */
    fun start() {
        scope.launch {
            // Deliberately not destructured: a positional destructuring silently drops any component past
            // the ones named, and the frame's kind (the plane it arrived on) would read as Other for every frame.
            transport.inbound.collect { frame ->
                handleInbound(frame.wire, frame.envelope, frame.fromNodeId, frame.kind)
            }
        }
    }

    /**
     * Processes one inbound frame: deliver+schedule if new, else count it toward overhear suppression.
     * [kind] is the radio the frame arrived over, handed to [onDeliver] for the delivery-plane record only —
     * dedup, relay scheduling and split horizon never read it. Defaulted so a source with no radio (the
     * Internet plane's `ScopeSync` bridge) calls the three-argument form.
     */
    suspend fun handleInbound(
        wire: WireEnvelope,
        envelope: RelayEnvelope,
        fromNodeId: String,
        kind: TransportKind = TransportKind.Other,
    ) {
        // The ingress meter sits between the dedup *check* and the dedup *add*: a duplicate is never metered
        // (it is evidence of propagation, not a cost — see countOverheard), and a refused frame is never
        // marked seen, so the custody re-serve can bring it through later. The gap between the two lets the
        // same first-seen frame arriving over two links at once spend two tokens; harmless.
        if (!seen.contains(envelope.id) && budget.meters(envelope) && !budget.admit(fromNodeId)) {
            metrics.onDropped(DropReason.INGRESS_REFUSED)
            return
        }
        if (!seen.add(envelope.id)) {
            // Duplicate: never re-deliver or start a second relay, but it IS evidence the frame is
            // already propagating — count it against any relay we still have pending.
            metrics.onDeduped()
            countOverheard(envelope.id, fromNodeId)
            return
        }
        metrics.onDelivered()
        onDeliver(wire, envelope, fromNodeId, kind)
        scheduleRelay(wire, envelope, fromNodeId)
    }

    /**
     * Admits one more copy of [id] through the dedup gate — for a sealed DM addressed to us that we could
     * not open, whose sender will answer our session reset with a fresh seal under the same id
     * (`MeshManager.resealRecentDmsTo`). See [SeenSet.reopen] for the bound.
     */
    fun reopen(id: String): Boolean = seen.reopen(id)

    /** Sends a locally-originated frame ([id] = its dedup key) to the whole mesh, immediately. */
    suspend fun originate(
        wire: WireEnvelope,
        id: String,
    ) = sendOwn(wire, id, to = null)

    /**
     * Sends a locally-originated frame to [to] (or the whole mesh when null), marking [id] seen so an
     * echo arriving back from the mesh isn't re-delivered or re-relayed.
     */
    suspend fun sendOwn(
        wire: WireEnvelope,
        id: String,
        to: Peer? = null,
    ) {
        seen.add(id)
        metrics.onOriginated()
        transport.send(wire, to)
    }

    /** Cancels all pending relays and clears bookkeeping. Call from [MeshManager.stop]. */
    suspend fun stop() {
        val jobs =
            pendingLock.withLock {
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
    private suspend fun scheduleRelay(
        wire: WireEnvelope,
        envelope: RelayEnvelope,
        fromNodeId: String,
    ) {
        val id = envelope.id
        // Point-to-point control frames are never flooded; they propagate hop-by-hop, and [handOn] is the
        // one hop this node can take on their behalf.
        if (!wire.relay) return handOn(wire, envelope, fromNodeId)
        // [ttl] is attacker-controlled; cap it to the local default so a forged oversized value can't
        // keep a frame alive past the dedup window and flood the mesh. Every relayer caps independently,
        // so the hop count alone bounds propagation regardless of what ttl a peer claims.
        val effectiveTtl = minOf(wire.ttl, DEFAULT_TTL)
        if (wire.hops >= effectiveTtl) return
        val entry =
            PendingRelay(
                relayed = wire.relayed(), // only ttl/hops mutate; signed + sig pass through verbatim
                envelope = envelope,
                heardFrom = mutableSetOf(fromNodeId),
            )
        pendingLock.withLock { pending[id] = entry }
        entry.job =
            scope.launch {
                delay(jitter())
                // Re-check under lock: an overhear may have removed us during the delay.
                val live = pendingLock.withLock { pending.remove(id) } ?: return@launch
                val excluded = live.heardFrom.toSet()
                val targets = transport.neighbors.value.filter { it.nodeId !in excluded }
                targets.forEach { neighbor -> transport.send(live.relayed, neighbor) }
                metrics.onRelayed() // the diagnostic counts the relay decision, targets or not
                if (targets.isNotEmpty()) onRelayed(live.envelope, targets.mapTo(HashSet()) { it.nodeId })
            }
    }

    /**
     * Hands a **point-to-point** frame (`relay = false`) the last hop: if it is addressed to a peer we hold a
     * live link to, send it over that link, once. The flood's counterpart for a frame the flood never carries.
     *
     * The case it exists for (issue #48): a far pocket's room tick leaves its acker as LoRa's targeted
     * `send:chat` (ADR 2026-09.y5f3's second route), the near pocket's gateway hears it off the air, and it is
     * addressed to a phone with no board sitting one link behind that gateway. Nothing else could carry it —
     * the router does not flood a `relay = false` frame, `InboundPipeline.onDeliver` drops one that is not
     * ours, and a room tick never escalates into custody (ADR 2026-09.aa27). So the gateway does for the tick
     * exactly what it already does for the post that earned it.
     *
     * Five things bound it, and each is the same rule the flood path already follows:
     *  - **DM-form chat only** ([shouldFastSend]): the sealed `CTL_RECEIPT` tick and any future sealed ctl DM.
     *    A `typing` cue is worthless a moment later, and `blobreq`/`keyreq` name no recipient at all — they
     *    propagate through their own handlers.
     *  - **Split horizon**: never back at the hop that just handed it to us.
     *  - **The hop count**, capped to the local [DEFAULT_TTL] exactly as [scheduleRelay] caps it, so an
     *    attacker-controlled `ttl` cannot keep a frame alive past the dedup window.
     *  - **A live link, never a sighting** — `neighbors`, not `reachable` (ADR 044: a sighting is not a data
     *    path, and [app.getknit.knit.mesh.lora.LoraMeshTransport.fastSend] reads the link set for the same
     *    reason).
     *  - **Once per frame**, from the [SeenSet] gate in [handleInbound] that got us here.
     *
     * A frame addressed to *us* is never handed on, and that needs no identity here: our own node id is never
     * in our own neighbor set. Sent immediately rather than jittered — overhear suppression says nothing about
     * a unicast to one named peer.
     *
     * The frame may be one this node cannot verify (ADR 059's unsigned tick authenticates at its addressee and
     * nowhere else). That is the flood path's position too: an unverifiable frame is relayed, never delivered,
     * because a relay that drops what it cannot read is a propagation black hole.
     */
    private suspend fun handOn(
        wire: WireEnvelope,
        envelope: RelayEnvelope,
        fromNodeId: String,
    ) {
        if (!shouldFastSend(envelope)) return
        val to = envelope.recipientId ?: return
        if (to == fromNodeId) return
        if (wire.hops >= minOf(wire.ttl, DEFAULT_TTL)) return
        val peer = transport.neighbors.value.firstOrNull { it.nodeId == to } ?: return
        transport.send(wire.relayed(), peer) // only ttl/hops mutate; relay stays false, so it goes no further
        metrics.onHandedOn()
        onRelayed(envelope, setOf(to))
    }

    /**
     * A duplicate of [frameId] arrived from [fromNodeId]. Record the source (so we never relay back to
     * it) and, once the frame has been heard from [suppressThreshold] **distinct** neighbors, cancel the
     * pending relay. A second copy from the same neighbor is not an overhear: it says nothing about whether
     * anyone else has relayed the frame, and it is routine — a link-up pushes a peer's profile live and the
     * custody digest exchange that follows re-serves the very same frame from the same peer moments later.
     * Counting that copy cancelled the relay of a newcomer's profile past the first hop, so a node two hops
     * away learned of it only on the 60 s custody re-offer (found by the `mesh/lab` line-topology case).
     */
    private suspend fun countOverheard(
        frameId: String,
        fromNodeId: String,
    ) {
        val jobToCancel =
            pendingLock.withLock {
                val entry = pending[frameId] ?: return // already fired, or never relayable
                entry.heardFrom += fromNodeId
                if (entry.heardFrom.size >= suppressThreshold) {
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
