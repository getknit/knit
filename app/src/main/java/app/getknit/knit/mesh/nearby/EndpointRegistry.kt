package app.getknit.knit.mesh.nearby

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.Protocol

/**
 * Tracks the endpoint↔node mappings and per-endpoint connection state for [NearbyTransport].
 *
 * Pure (no GMS/Android types) so it is JVM-unit-testable, and internally synchronized so Nearby's
 * (serialized) callback thread and the transport's reader coroutines — `send`, `heal`, the discovery
 * loop — can share it safely. The injected [clock] (elapsed-realtime millis in production) makes the
 * retry backoff testable with a mutable `now`, mirroring `SeenSet`/`ForwardSync`.
 *
 * Nearby endpoint ids are ephemeral: a peer rediscovered after going out of range generally gets a
 * NEW id. [map] therefore prunes any stale id previously held for the same node, and [onLost] drops a
 * vanished endpoint's state — so a returning peer reconnects cleanly instead of inheriting a stale
 * backoff window or stranding a `connecting` flag, and `heal()` never wastes a connection slot
 * chasing a dead id.
 */
internal class EndpointRegistry(private val clock: () -> Long) {

    private data class Retry(val attemptAt: Long, val delayMs: Long)

    private val lock = Any()
    private val endpointToNode = HashMap<String, String>()
    private val nodeToEndpoint = HashMap<String, String>()
    private val peerWire = HashMap<String, Protocol.PeerWire>()
    private val connected = HashSet<String>()
    private val connecting = HashSet<String>()
    private val retry = HashMap<String, Retry>()

    /** Record an endpoint↔node mapping, pruning any stale (different, idle) id for this node. */
    fun map(endpointId: String, wire: Protocol.PeerWire) = synchronized(lock) {
        val stale = nodeToEndpoint[wire.nodeId]
        // Never prune an id we're connected to OR mid-handshake on: forgetting a connecting id strips
        // its mapping, so a later markConnected would land it in `connected` with no node mapping.
        if (stale != null && stale != endpointId && !isActive(stale)) forget(stale)
        endpointToNode[endpointId] = wire.nodeId
        nodeToEndpoint[wire.nodeId] = endpointId
        peerWire[endpointId] = wire
    }

    /**
     * A discovery loss: forget the endpoint unless a connection is live ([markDisconnected] owns those)
     * or being established on it. A still-`connecting` id must keep its mapping — Nearby commonly emits
     * onEndpointLost mid-handshake (the slow BLE GATT bootstrap outlasts the scan window), and
     * forgetting it here would leave a subsequent successful onConnectionResult in `connected` with no
     * node mapping: empty neighbors, inbound dropped as UNKNOWN_ENDPOINT, and unsendable.
     */
    fun onLost(endpointId: String) = synchronized(lock) {
        if (!isActive(endpointId)) forget(endpointId)
    }

    /** Connected or mid-handshake — an id whose mapping must not be pruned. Caller holds [lock]. */
    private fun isActive(endpointId: String): Boolean =
        endpointId in connected || endpointId in connecting

    /** Drop every trace of an endpoint id. Caller holds [lock]. */
    private fun forget(endpointId: String) {
        endpointToNode.remove(endpointId)?.let { node ->
            if (nodeToEndpoint[node] == endpointId) nodeToEndpoint.remove(node)
        }
        peerWire.remove(endpointId)
        retry.remove(endpointId)
        connecting.remove(endpointId)
    }

    // --- connection lifecycle ---

    /**
     * Atomically reserve an in-flight connection slot: true only if the endpoint is not already
     * connected, is past its retry-backoff window, and has no request already in flight.
     */
    fun beginConnecting(endpointId: String): Boolean = synchronized(lock) {
        when {
            endpointId in connected -> false
            retry[endpointId]?.let { clock() < it.attemptAt } == true -> false
            else -> connecting.add(endpointId)
        }
    }

    fun endConnecting(endpointId: String) = synchronized(lock) {
        connecting.remove(endpointId)
        Unit
    }

    fun markConnected(endpointId: String) = synchronized(lock) {
        connecting.remove(endpointId)
        connected.add(endpointId)
        retry.remove(endpointId) // reset backoff so a later drop reconnects without delay
        // Make this endpoint authoritative for its node: if the peer re-advertised a fresh id mid-
        // handshake, `map` may have repointed nodeToEndpoint at the newer id — the one that actually
        // connected wins, so endpointFor(node)/send() resolve to the live link.
        endpointToNode[endpointId]?.let { node -> nodeToEndpoint[node] = endpointId }
        Unit
    }

    fun markDisconnected(endpointId: String) = synchronized(lock) {
        connecting.remove(endpointId)
        connected.remove(endpointId)
        Unit
    }

    /** Widen this endpoint's retry backoff (exponential, capped) and stamp its next eligible attempt. */
    fun scheduleRetry(endpointId: String) = synchronized(lock) {
        val next = nextBackoff(retry[endpointId]?.delayMs ?: 0L)
        retry[endpointId] = Retry(clock() + next, next)
    }

    /** Force an immediate next attempt for this endpoint (clears its backoff window); used by `heal()`. */
    fun clearRetry(endpointId: String) = synchronized(lock) {
        retry.remove(endpointId)
        Unit
    }

    // --- lookups ---

    fun isConnected(endpointId: String): Boolean = synchronized(lock) { endpointId in connected }

    /** True while any outbound connection request is in flight — the discovery loop yields the radio to it. */
    fun isConnecting(): Boolean = synchronized(lock) { connecting.isNotEmpty() }

    fun isIsolated(): Boolean = synchronized(lock) { connected.isEmpty() }
    fun connectedCount(): Int = synchronized(lock) { connected.size }

    fun nodeFor(endpointId: String): String? = synchronized(lock) { endpointToNode[endpointId] }
    fun endpointFor(nodeId: String): String? = synchronized(lock) { nodeToEndpoint[nodeId] }

    fun connectedEndpoints(): List<String> = synchronized(lock) { connected.toList() }

    /** Known endpoints we are not currently connected to — `heal()` retargets these. */
    fun unconnectedEndpoints(): List<String> =
        synchronized(lock) { endpointToNode.keys.filter { it !in connected } }

    /** The current connected neighbor set, each carrying its advertised version/capabilities. */
    fun neighbors(): Set<Peer> = synchronized(lock) {
        connected.mapNotNull { endpointId ->
            val nodeId = endpointToNode[endpointId] ?: return@mapNotNull null
            val wire = peerWire[endpointId]
            Peer(nodeId, wire?.protoVersion ?: 0, wire?.capabilities ?: 0L)
        }.toSet()
    }

    fun clear() = synchronized(lock) {
        endpointToNode.clear()
        nodeToEndpoint.clear()
        peerWire.clear()
        connected.clear()
        connecting.clear()
        retry.clear()
    }

    companion object {
        // Connection retry backoff: first retry after 5s, doubling to a 1-minute ceiling. The ceiling
        // was lowered from 5 minutes so a peer that returns to range under the same endpoint id (or one
        // that recovers after edge-of-range flapping ramped the delay) isn't stranded behind a stale
        // window — at most one wasted attempt per minute, still well above the 8–12s discovery burst.
        const val RETRY_BASE_MS = 5_000L
        const val RETRY_MAX_MS = 60_000L

        /** Next backoff delay given the previous one (0 ⇒ first retry): doubles, capped at [RETRY_MAX_MS]. */
        fun nextBackoff(prevMs: Long): Long =
            if (prevMs <= 0L) RETRY_BASE_MS else (prevMs * 2).coerceAtMost(RETRY_MAX_MS)
    }
}
