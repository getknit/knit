package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * A [MeshTransport] that runs several sibling transports **simultaneously** behind the single-transport seam,
 * so `MeshManager`/`MeshRouter` and every collaborator stay unchanged. [children] are in descending
 * send-preference order — put Bluetooth (persistent, cheap links) before Wi-Fi Aware (ephemeral, one NDP at a
 * time), so a peer reachable over both is served over Bluetooth.
 *
 * How the interface members combine:
 * - [neighbors]/[reachable]: the union of the children's sets, **collapsed to one [Peer] per nodeId** (the
 *   richer advertised protoVersion/capabilities wins). Collapsing by nodeId is what makes
 *   `MeshManager.watchNeighbors` fire its newcomer hooks exactly once even when a peer appears on both planes.
 * - [health]: Healthy if **any** child is Healthy (the node can still mesh if one radio works); otherwise
 *   Degraded if any plane is on-but-failing (seized), else Unavailable when every plane is switched off/absent.
 * - [inbound]/[incomingFiles]/[incomingDigests]: merged. A frame that arrives over both planes is de-duped
 *   downstream by `MeshRouter`'s `SeenSet` (10-min TTL), so simultaneous multi-path delivery is safe and free.
 * - [send]: for a specific peer, route to the preferred child holding a live link to it; for a broadcast
 *   (`to == null`), reach each merged neighbor once over its preferred child.
 * - [fastFanout]/[fastSend]: the coordination-plane fast path goes to children that have one ([hasFastPlane],
 *   Wi-Fi Aware); a link-based child (Bluetooth) instead gets a normal [send] over its persistent links, which
 *   already reaches every live neighbor at once.
 *
 * A 0- or 1-child list is handled gracefully (empty ⇒ inert + Degraded; single ⇒ transparent pass-through),
 * so DI can gate each plane on hardware support and hand over whatever is present.
 *
 * Because neighbors are collapsed by nodeId, a peer that is continuously reachable (e.g. a persistent Bluetooth
 * link, or a NAN→BT handoff that never leaves the merged set) fires `MeshManager.watchNeighbors`' newcomer
 * hooks only **once**. Store-and-forward convergence relies on the digest re-offer re-running (NAN gets that for
 * free from its flapping ephemeral links), so `MeshManager.reofferToNeighborsPeriodically` re-runs those hooks
 * on a timer for currently-linked neighbors — the anti-entropy a non-flapping link needs.
 */
class CompositeMeshTransport(
    private val children: List<MeshTransport>,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics? = null,
) : MeshTransport {
    override val hasFastPlane: Boolean = children.any { it.hasFastPlane }

    override val highThroughput: Boolean = children.any { it.highThroughput }

    override val neighbors: StateFlow<Set<Peer>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptySet())
        } else {
            combine(children.map { it.neighbors }) { sets -> mergePeers(sets) }
                .stateIn(scope, SharingStarted.Eagerly, emptySet())
        }

    override val reachable: StateFlow<Set<Peer>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptySet())
        } else {
            combine(children.map { it.reachable }) { sets -> mergePeers(sets) }
                .stateIn(scope, SharingStarted.Eagerly, emptySet())
        }

    override val health: StateFlow<TransportHealth> =
        if (children.isEmpty()) {
            MutableStateFlow(TransportHealth.Degraded)
        } else {
            combine(children.map { it.health }) { hs ->
                when {
                    // The node can still mesh if any radio is up.
                    hs.any { it == TransportHealth.Healthy } -> TransportHealth.Healthy

                    // At least one radio is on but failing (seized) — a fault, not a user-off state, so it
                    // outranks Unavailable: "radio busy" is the more informative message.
                    hs.any { it == TransportHealth.Degraded } -> TransportHealth.Degraded

                    // Every radio is switched off / absent — actionable "turn on Wi-Fi or Bluetooth".
                    else -> TransportHealth.Unavailable
                }
            }.stateIn(scope, SharingStarted.Eagerly, TransportHealth.Healthy)
        }

    /**
     * A per-radio status line for each child, in preference order (Bluetooth first) — the Diagnostics screen's
     * window into the individual planes that [neighbors]/[reachable]/[health] otherwise merge away. Read-only;
     * derived purely from the children's existing flows, so it never perturbs routing.
     */
    val statuses: StateFlow<List<TransportStatus>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptyList())
        } else {
            combine(
                children.map { child ->
                    combine(
                        child.neighbors,
                        child.reachable,
                        child.health,
                        child.radioContended,
                    ) { linked, nearby, health, contended ->
                        TransportStatus(child.kind, health, linked.size, nearby.size, contended)
                    }
                },
            ) { it.toList() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
        }

    /**
     * nodeId → the set of radios it's currently reachable over, so the Diagnostics screen can tag a connected
     * node BLE / NAN / both. Keyed off each child's [reachable] (not [neighbors]) to match the "directly
     * connected" classification, which reads the smoothed [reachable] set, and to avoid Wi-Fi Aware's ≤1
     * flapping live-link set.
     */
    val peerTransports: StateFlow<Map<String, Set<TransportKind>>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptyMap())
        } else {
            combine(children.map { it.reachable }) { sets ->
                val byNode = HashMap<String, MutableSet<TransportKind>>()
                children.forEachIndexed { i, child ->
                    sets[i].forEach { byNode.getOrPut(it.nodeId) { mutableSetOf() }.add(child.kind) }
                }
                byNode.mapValues { it.value.toSet() }
            }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
        }

    override val inbound: Flow<InboundFrame> =
        if (children.isEmpty()) emptyFlow() else children.map { it.inbound }.merge()

    override val incomingFiles: Flow<ReceivedFile> =
        if (children.isEmpty()) emptyFlow() else children.map { it.incomingFiles }.merge()

    override val incomingDigests: Flow<ReceivedDigest> =
        if (children.isEmpty()) emptyFlow() else children.map { it.incomingDigests }.merge()

    init {
        // Suppress the on-demand data-path sync in a lower-preference child for any peer a higher-preference
        // child already holds a **live link** to — e.g. don't run a Wi-Fi Aware NDP sync to a peer that's on a
        // Bluetooth link, since it gets its data over Bluetooth. Recomputed whenever any child's live-link set
        // changes, so a peer that drops off the preferred plane is un-suppressed and the fallback plane resumes.
        if (children.size > 1) {
            scope.launch {
                combine(children.map { it.neighbors }) { it }.collect { sets ->
                    for (i in children.indices) {
                        val covered = HashSet<String>()
                        for (j in 0 until i) sets[j].forEach { covered.add(it.nodeId) }
                        children[i].suppressDataPath(covered)
                    }
                }
            }
            // Reverse hint: tell each child which peers the *other* planes can currently see, so a duty-cycling
            // radio (Bluetooth) can boost its scan to catch a peer another plane sighted first (the NAN→BT
            // early-warning). Keyed off `reachable` (coordination-plane presence), not live links.
            scope.launch {
                combine(children.map { it.reachable }) { it }.collect { sets ->
                    for (i in children.indices) {
                        val foreign = HashSet<String>()
                        for (j in children.indices) if (j != i) sets[j].forEach { foreign.add(it.nodeId) }
                        children[i].onForeignReachable(foreign)
                    }
                }
            }
        }
    }

    override fun start() {
        children.forEach { it.start() }
    }

    override fun stop() {
        children.forEach { it.stop() }
    }

    override fun heal() {
        children.forEach { it.heal() }
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        if (to != null) {
            childHoldingLinkTo(to.nodeId)?.send(wire, to) // prefer BT; no-op if no child holds the link
            return
        }
        // Broadcast (originate): reach each merged neighbor exactly once, over its preferred child.
        val done = HashSet<String>()
        for (child in children) {
            val targets = child.neighbors.value.filter { done.add(it.nodeId) }
            for (peer in targets) child.send(wire, peer)
        }
    }

    /**
     * Forwards the bulk-transfer hint to **every** child (each applies its own gates; only an on-demand
     * plane arms anything). True if any armed — the serve path uses it to decide whether a fast-plane link
     * is worth a grace wait.
     */
    override fun expectBulkTransfer(nodeId: String): Boolean {
        var armed = false
        for (child in children) if (child.expectBulkTransfer(nodeId)) armed = true
        return armed
    }

    /**
     * Files route like frames — preference-order link holder — EXCEPT large attachment blobs (≥
     * [BULK_MIN_BYTES]), which prefer the [highThroughput] plane: ride its live link if one exists, else arm
     * its on-demand bring-up ([expectBulkTransfer]) and wait ≤ [BULK_GRACE_MS] for the link **off the
     * caller's coroutine** (sendFile is invoked inline from the serial inbound dispatch — the router's
     * single collector — so suspending here would stall every frame on both radios), then fall back to the
     * link holder (Bluetooth). Each invocation resolves to exactly one route, so a file is never sent twice;
     * a child refusing the enqueue (link died in the check→enqueue window) falls back the same way instead
     * of silently losing the file. Below the size gate the NDP setup (~1-4 s) costs more than BLE's whole
     * transfer, so small blobs — and avatars/digests — keep today's path byte-for-byte.
     */
    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val fast = children.firstOrNull { it.highThroughput }
        if (meta.kind != FileKind.ATTACHMENT || fast == null || file.length() < BULK_MIN_BYTES) {
            return childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta) ?: false
        }
        if (fast.neighbors.value.any { it.nodeId == to.nodeId }) {
            // Fast link already up (e.g. a custody sync in flight) — ride it; fall back on a teardown race.
            return fast.sendFile(file, to, meta) ||
                (childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta) ?: false)
        }
        if (!fast.expectBulkTransfer(to.nodeId)) {
            // Peer isn't fresh on the fast plane (or its marking is cooling down) — don't wait for a link
            // that won't come.
            return childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta) ?: false
        }
        // Armed: whichever side of the pair is larger initiates the data path (both sides mark — the
        // requester at pull time, this serve side here). Wait for it detached, then send exactly once.
        scope.launch {
            val linked =
                withTimeoutOrNull(BULK_GRACE_MS) {
                    fast.neighbors.first { set -> set.any { it.nodeId == to.nodeId } }
                } != null
            if (!linked) metrics?.onBulkGraceTimeout()
            val overFast = linked && fast.sendFile(file, to, meta)
            if (!overFast) childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta)
        }
        return true // scheduled: the waiter above commits to exactly one plane (or drops if the peer left)
    }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        childHoldingLinkTo(to.nodeId)?.sendDigest(to, ids)
    }

    override fun fastFanout(wire: WireEnvelope) {
        for (child in children) {
            if (child.hasFastPlane) {
                child.fastFanout(wire) // coordination-plane blast (Wi-Fi Aware)
            } else {
                scope.launch { child.send(wire, null) } // link-based child: its normal flood is already fast
            }
        }
    }

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        for (child in children) {
            if (child.hasFastPlane) {
                child.fastSend(wire, to)
            } else if (child.neighbors.value.any { it.nodeId == to.nodeId }) {
                scope.launch { child.send(wire, to) }
            }
        }
    }

    /** First child (preference order) that currently holds a live data-path link to [nodeId], or null. */
    private fun childHoldingLinkTo(nodeId: String): MeshTransport? =
        children.firstOrNull { c -> c.neighbors.value.any { it.nodeId == nodeId } }

    private fun mergePeers(sets: Array<Set<Peer>>): Set<Peer> {
        val best = HashMap<String, Peer>()
        for (set in sets) for (peer in set) best.merge(peer.nodeId, peer, ::richer)
        return best.values.toSet()
    }

    // A peer seen over two planes keeps the richer advertised hint (unauthenticated, routing-only): higher
    // protoVersion wins, tie-break on higher capabilities.
    private fun richer(
        a: Peer,
        b: Peer,
    ): Peer =
        when {
            a.protoVersion != b.protoVersion -> if (a.protoVersion > b.protoVersion) a else b
            else -> if (a.capabilities >= b.capabilities) a else b
        }

    internal companion object {
        // How long the serve path waits for an armed fast-plane link before falling back to the link holder.
        // Healthy NDP bring-up is 1-3 s with a free slot; a contended slot pays the current serve's
        // contended linger (2 s) + teardown + settle (1.5 s) + handshake (~1-3 s) — field-measured 2026-07-04
        // at ~8.3 s for receiver #2 of a two-receiver room, so 10 s lets the second pull ride NAN instead of
        // missing the freed slot by a hair. Kept under the NAN handshake timeout (15 s) so a genuinely
        // failing handshake falls back here rather than shadowing it.
        const val BULK_GRACE_MS = 10_000L

        // Attachments below this ride the normal (Bluetooth-first) route: for a small blob, BLE finishes in
        // ~2-3 s — less than the NDP setup it would replace — and skipping the fast plane spares the single
        // NDI the per-file bring-up/teardown churn. User-tuned: typical 1280px photos sit well above it.
        const val BULK_MIN_BYTES = 256L * 1024
    }
}
