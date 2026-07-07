package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * A no-op [MeshTransport] used only by the demo builds. It never touches the radios — it simply
 * *reports* a set of connected neighbors so the "connected" header ([MeshManager.neighborCount]) and the
 * contact "online" dots light up against the seeded data, with no real mesh. Every other operation is inert.
 *
 * The neighbor set is injected as a [StateFlow] the caller owns: the static screenshot build
 * (`-PseedDemo=true`, [app.getknit.knit.demo.DemoSeeder]) supplies a fixed set, while the trailer build
 * (`-PdemoDirector=true`, [app.getknit.knit.demo.DemoDirector]) supplies a [MutableStateFlow] it grows over
 * the timeline so the "connected to N" header climbs live. [MeshTransport.reachable] defaults to
 * [neighbors], so this drives both.
 */
class DemoTransport(
    override val neighbors: StateFlow<Set<Peer>>,
) : MeshTransport {
    override val health: StateFlow<TransportHealth> = MutableStateFlow(TransportHealth.Healthy)

    override val inbound: Flow<InboundFrame> = emptyFlow()
    override val incomingFiles: Flow<ReceivedFile> = emptyFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) = Unit

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean = false // inert: nothing is ever sent

    companion object {
        /** Wraps a set of node ids as bare [Peer]s for the neighbor flow. */
        fun peersOf(ids: Set<String>): Set<Peer> = ids.map { Peer(it) }.toSet()
    }
}
