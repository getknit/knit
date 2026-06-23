package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.Frame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * In-process [MeshTransport] used for development and tests without any radios. Instances are
 * wired into an arbitrary topology with [connect]; a [send] is delivered to the linked peers'
 * [inbound] streams, so a multi-node mesh (including multi-hop relay) can be exercised on the JVM.
 */
class FakeLoopTransport(val nodeId: String) : MeshTransport {

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    override val incomingFiles = emptyFlow<ReceivedFile>()

    private val links = mutableMapOf<String, FakeLoopTransport>()

    /** Bidirectionally links this transport with [other] so they become neighbors. */
    fun connect(other: FakeLoopTransport) {
        if (other.nodeId == nodeId) return
        links[other.nodeId] = other
        other.links[nodeId] = this
        refreshNeighbors()
        other.refreshNeighbors()
    }

    override fun start() = Unit
    override fun stop() = Unit
    override fun heal() = Unit

    override suspend fun send(frame: Frame, to: Peer?) {
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.nodeId])
        targets.forEach { it.deliver(frame, nodeId) }
    }

    override suspend fun sendFile(file: File, to: Peer) = Unit

    private suspend fun deliver(frame: Frame, fromNodeId: String) {
        _inbound.emit(InboundFrame(frame, fromNodeId))
    }

    private fun refreshNeighbors() {
        _neighbors.value = links.keys.map { Peer(it) }.toSet()
    }
}
