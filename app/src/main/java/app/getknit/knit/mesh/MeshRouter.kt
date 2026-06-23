package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.incrementHop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Transport-agnostic mesh logic: deduplicates incoming frames, delivers new ones locally, and
 * floods them onward to every neighbor except the one they came from, bounded by the frame's TTL.
 *
 * Kept free of Android/Room/Nearby dependencies so it can be unit-tested with a fake transport.
 */
class MeshRouter(
    private val transport: MeshTransport,
    private val scope: CoroutineScope,
    private val seen: SeenSet = SeenSet(),
    private val onDeliver: suspend (frame: Frame, fromNodeId: String) -> Unit,
) {

    /** Begins consuming inbound frames from the transport. */
    fun start() {
        scope.launch {
            transport.inbound.collect { (frame, fromNodeId) ->
                handleInbound(frame, fromNodeId)
            }
        }
    }

    /** Processes one inbound frame: drop if already seen, else deliver locally and relay. */
    suspend fun handleInbound(frame: Frame, fromNodeId: String) {
        if (!seen.add(frame.id)) return
        onDeliver(frame, fromNodeId)
        relay(frame, fromNodeId)
    }

    /** Sends a locally-originated frame to the whole mesh. */
    suspend fun originate(frame: Frame) = sendOwn(frame, to = null)

    /**
     * Sends a locally-originated frame to [to] (or the whole mesh when null), marking it seen so an
     * echo arriving back from the mesh isn't re-delivered or re-relayed.
     */
    suspend fun sendOwn(frame: Frame, to: Peer? = null) {
        seen.add(frame.id)
        transport.send(frame, to)
    }

    private suspend fun relay(frame: Frame, fromNodeId: String) {
        if (frame.hops >= frame.ttl) return
        val relayed = frame.incrementHop()
        transport.neighbors.value
            .filter { it.nodeId != fromNodeId }
            .forEach { neighbor -> transport.send(relayed, neighbor) }
    }
}
