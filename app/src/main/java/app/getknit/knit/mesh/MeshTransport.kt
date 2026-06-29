package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * A directly-connected mesh neighbor, identified by its node id. [protoVersion]/[capabilities] are the
 * peer's advertised protocol version and feature bits parsed from the Nearby endpoint-info (see
 * [app.getknit.knit.mesh.protocol.Protocol]); they default to 0 (unknown) for a bare/legacy peer and in
 * the non-radio fakes. They are an unauthenticated routing hint, never a trust input.
 */
data class Peer(val nodeId: String, val protoVersion: Int = 0, val capabilities: Long = 0L)

/**
 * Coarse health of the radio layer. [Degraded] means the last advertise/discover attempt failed —
 * typically because another app (e.g. Quick Share) has seized the Nearby radios — so the device may
 * be neither discoverable nor able to find peers until the radio is free again. Surfaced to the UI so
 * "no neighbors because the radio is broken" is distinguishable from "no neighbors nearby".
 */
enum class TransportHealth { Healthy, Degraded }

/**
 * A frame received from a neighbor: the verbatim [wire] (its [WireEnvelope.signed]/[WireEnvelope.sig]
 * are forwarded byte-for-byte on relay) plus the already-decoded [envelope] (so the router and delivery
 * paths don't each re-decode it), tagged with the neighbor it arrived from.
 */
data class InboundFrame(val wire: WireEnvelope, val envelope: RelayEnvelope, val fromNodeId: String)

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

    /** Frames received from neighbors (after transport-level decode, before mesh dedup/relay). */
    val inbound: Flow<InboundFrame>

    /** Files received from neighbors (avatars, attachments), emitted once fully transferred and saved. */
    val incomingFiles: Flow<ReceivedFile>

    fun start()

    fun stop()

    /** Hints the transport to rescan / reconnect now (e.g. after device motion or a heartbeat). */
    fun heal()

    /** Sends [wire] to one neighbor, or to all neighbors when [to] is null. */
    suspend fun send(wire: WireEnvelope, to: Peer? = null)

    /** Sends a file (avatar or attachment) tagged with [meta] to a single neighbor. */
    suspend fun sendFile(file: File, to: Peer, meta: FileMeta)
}
