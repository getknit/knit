package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * A store-and-forward digest received from a neighbor: the message [ids] it currently holds in custody, so we
 * push back only the frames it lacks (see [MeshTransport.sendDigest] / `ForwardSync.onDigest`).
 */
data class ReceivedDigest(val fromNodeId: String, val ids: List<String>)

/**
 * Abstraction over the radio layer that discovers neighbors and exchanges [Frame]s with them.
 * The current implementation is Nearby Connections; this interface keeps the rest of the app
 * independent of it so Wi-Fi Aware / BLE can be swapped in later.
 */
interface MeshTransport {

    /**
     * Currently-connected neighbors — the peers we hold a live data-path link to *right now*. Under the
     * Wi-Fi Aware cue-driven transport this is at most one and flaps as ephemeral syncs come and go, so it
     * is the routing target for [send]/[sendFile] and the sync-on-contact hooks, **not** a UI signal; use
     * [reachable] for "who's nearby".
     */
    val neighbors: StateFlow<Set<Peer>>

    /**
     * Smoothed "who's nearby" set for the UI: peers seen recently over the coordination plane (discovery +
     * cues), which does not require a data path — so it stays steady while [neighbors] flaps through
     * ephemeral data-path syncs. Defaults to [neighbors] for transports (fakes, demo) that don't
     * distinguish the two.
     */
    val reachable: StateFlow<Set<Peer>> get() = neighbors

    /** Coarse radio health (e.g. flips to [TransportHealth.Degraded] when Quick Share seizes the radios). */
    val health: StateFlow<TransportHealth>

    /** Frames received from neighbors (after transport-level decode, before mesh dedup/relay). */
    val inbound: Flow<InboundFrame>

    /** Files received from neighbors (avatars, attachments), emitted once fully transferred and saved. */
    val incomingFiles: Flow<ReceivedFile>

    /**
     * Store-and-forward digests received from neighbors: each advertises the custody ids it holds on link-up so
     * we reply with just the frames it lacks (the data-path id-diff — see `ForwardSync.onDigest`). Default empty
     * for transports (fakes, demo) without a data path; only Wi-Fi Aware overrides it.
     */
    val incomingDigests: Flow<ReceivedDigest> get() = emptyFlow()

    fun start()

    fun stop()

    /** Hints the transport to rescan / reconnect now (e.g. after device motion or a heartbeat). */
    fun heal()

    /** Sends [wire] to one neighbor, or to all neighbors when [to] is null. */
    suspend fun send(wire: WireEnvelope, to: Peer? = null)

    /**
     * Best-effort **coordination-plane** fan-out of [wire] to every neighbor at once, with **no data path** —
     * a fast path for a frame small enough to ride the tiny Wi-Fi Aware message channel (~255 B). Because it
     * needs no NDP it reaches every neighbor simultaneously (the closest thing to a star on hardware capped at
     * one data path at a time), instead of waiting for a cue-driven pairwise sync. The reliable path (the
     * normal [send] flood + store-and-forward custody) always runs regardless; this only makes a small frame
     * *also* arrive near-instantly, deduped by the receiver's [MeshRouter] SeenSet. Default no-op — only a
     * transport with a message channel (Wi-Fi Aware) overrides it; the fakes ignore it.
     */
    fun fastFanout(wire: WireEnvelope) {}

    /**
     * Best-effort **coordination-plane** send of [wire] to a single peer [to] — the targeted sibling of
     * [fastFanout], for a small point-to-point reply that must reach one node with **no data path** (e.g. a
     * broadcast/group delivery receipt back to the message's author, which works whether the message arrived
     * over an NDP flood or a coordination-plane fast-fanout). No-op if [to] isn't currently reachable over the
     * coordination plane or [wire] won't fit the ~255 B message channel. Default no-op — only a transport with
     * a message channel (Wi-Fi Aware) overrides it; the fakes ignore it.
     */
    fun fastSend(wire: WireEnvelope, to: Peer) {}

    /** Sends a file (avatar or attachment) tagged with [meta] to a single neighbor. */
    suspend fun sendFile(file: File, to: Peer, meta: FileMeta)

    /**
     * Advertises the custody message [ids] we hold to a single neighbor [to] over the data-path socket (an
     * [app.getknit.knit.mesh.wifiaware.AwareFraming.Type.DIGEST] record), so it replies with only the frames we
     * lack. Default no-op — only Wi-Fi Aware (which has a data path) overrides it; the fakes/demo ignore it.
     */
    suspend fun sendDigest(to: Peer, ids: List<String>) {}
}
