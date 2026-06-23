package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A directly-connected mesh neighbor, identified by its node id. */
data class Peer(val nodeId: String)

/** A frame received from a neighbor, tagged with the neighbor it arrived from. */
data class InboundFrame(val frame: Frame, val fromNodeId: String)

/** A file (e.g. an avatar) received from a neighbor, already saved at [path]. */
data class ReceivedFile(val fromNodeId: String, val path: String)

/**
 * Abstraction over the radio layer that discovers neighbors and exchanges [Frame]s with them.
 * The current implementation is Nearby Connections; this interface keeps the rest of the app
 * independent of it so Wi-Fi Aware / BLE can be swapped in later.
 */
interface MeshTransport {

    /** Currently-connected neighbors. */
    val neighbors: StateFlow<Set<Peer>>

    /** Frames received from neighbors (after transport-level delivery, before mesh dedup/relay). */
    val inbound: Flow<InboundFrame>

    /** Files received from neighbors (avatars), emitted once fully transferred and saved. */
    val incomingFiles: Flow<ReceivedFile>

    fun start()

    fun stop()

    /** Hints the transport to rescan / reconnect now (e.g. after device motion or a heartbeat). */
    fun heal()

    /** Sends [frame] to one neighbor, or to all neighbors when [to] is null. */
    suspend fun send(frame: Frame, to: Peer? = null)

    /** Sends a file (e.g. an avatar) to a single neighbor. */
    suspend fun sendFile(file: File, to: Peer)
}
