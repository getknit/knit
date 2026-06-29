package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * A no-op [MeshTransport] used only by the demo-screenshot build (`-PseedDemo=true`, see
 * [app.getknit.knit.demo.DemoSeeder]). It never touches the radios — it simply *reports* a fixed set
 * of connected neighbors so the "connected" header ([MeshManager.neighborCount]) and the contact
 * "online" dots light up against the seeded data, with no real mesh. Every other operation is inert.
 */
class DemoTransport(onlineNodeIds: Set<String>) : MeshTransport {

    override val neighbors: StateFlow<Set<Peer>> =
        MutableStateFlow(onlineNodeIds.map { Peer(it) }.toSet())

    override val health: StateFlow<TransportHealth> = MutableStateFlow(TransportHealth.Healthy)

    override val inbound: Flow<InboundFrame> = emptyFlow()
    override val incomingFiles: Flow<ReceivedFile> = emptyFlow()

    override fun start() = Unit
    override fun stop() = Unit
    override fun heal() = Unit
    override suspend fun send(wire: WireEnvelope, to: Peer?) = Unit
    override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) = Unit
}
