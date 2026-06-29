package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A directly-connected mesh neighbor, identified by its node id. */
data class Peer(val nodeId: String)

/**
 * Coarse health of the radio layer. [Degraded] means the last advertise/discover attempt failed —
 * typically because another app (e.g. Quick Share) has seized the Nearby radios — so the device may
 * be neither discoverable nor able to find peers until the radio is free again. Surfaced to the UI so
 * "no neighbors because the radio is broken" is distinguishable from "no neighbors nearby".
 */
enum class TransportHealth { Healthy, Degraded }

/** A frame received from a neighbor, tagged with the neighbor it arrived from. */
data class InboundFrame(val frame: Frame, val fromNodeId: String)

/** What a transferred file is, so the receiver can route an avatar apart from a chat attachment. */
enum class FileKind { AVATAR, ATTACHMENT }

/**
 * Metadata sent alongside a file so the receiver can identify it: [kind] (avatar vs attachment),
 * [key] (the avatar's node id, or an attachment's content hash), and the file's [mime] type.
 */
data class FileMeta(val kind: FileKind, val key: String, val mime: String)

/** A file received from a neighbor, already saved at [path], tagged with its [FileMeta] fields. */
data class ReceivedFile(
    val fromNodeId: String,
    val path: String,
    val kind: FileKind,
    val key: String,
    val mime: String,
)

/**
 * Abstraction over the radio layer that discovers neighbors and exchanges [Frame]s with them.
 * The current implementation is Nearby Connections; this interface keeps the rest of the app
 * independent of it so Wi-Fi Aware / BLE can be swapped in later.
 */
interface MeshTransport {

    /** Currently-connected neighbors. */
    val neighbors: StateFlow<Set<Peer>>

    /** Coarse radio health (e.g. flips to [TransportHealth.Degraded] when Quick Share seizes the radios). */
    val health: StateFlow<TransportHealth>

    /** Frames received from neighbors (after transport-level delivery, before mesh dedup/relay). */
    val inbound: Flow<InboundFrame>

    /** Files received from neighbors (avatars, attachments), emitted once fully transferred and saved. */
    val incomingFiles: Flow<ReceivedFile>

    fun start()

    fun stop()

    /** Hints the transport to rescan / reconnect now (e.g. after device motion or a heartbeat). */
    fun heal()

    /** Sends [frame] to one neighbor, or to all neighbors when [to] is null. */
    suspend fun send(frame: Frame, to: Peer? = null)

    /** Sends a file (avatar or attachment) tagged with [meta] to a single neighbor. */
    suspend fun sendFile(file: File, to: Peer, meta: FileMeta)
}
