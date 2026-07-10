package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * A directly-connected mesh neighbor, identified by its node id. [protoVersion]/[capabilities] are the
 * peer's advertised protocol version and feature bits parsed from the endpoint-info advert (Wi-Fi Aware
 * `serviceSpecificInfo` / the BLE service-data payload; see
 * [app.getknit.knit.mesh.protocol.Protocol]); they default to 0 (unknown) for a bare/legacy peer and in
 * the non-radio fakes. They are an unauthenticated routing hint, never a trust input.
 */
data class Peer(
    val nodeId: String,
    val protoVersion: Int = 0,
    val capabilities: Long = 0L,
)

/**
 * Coarse health of the radio layer, surfaced to the UI so "no neighbors because the radio can't run"
 * is distinguishable from "no neighbors nearby" — and, within that, so an *actionable* radio-off state
 * is distinguishable from a transient fault:
 * - [Healthy]: the radio is attached and advertising/discovering.
 * - [Degraded]: the radio is on but the last advertise/discover/attach attempt failed — typically
 *   because another app (e.g. Quick Share) has seized it. Usually self-heals; a mesh restart may help.
 * - [Unavailable]: the radio is switched off (the user turned Wi-Fi/Bluetooth off, or airplane mode is
 *   on) or absent — nothing the app can do but wait for the user to turn a radio back on. The UI turns
 *   this into an actionable "turn on Wi-Fi or Bluetooth" hint rather than a generic failure.
 */
enum class TransportHealth { Healthy, Degraded, Unavailable }

/**
 * Which physical radio a [MeshTransport] drives. Used only for diagnostics — [CompositeMeshTransport] tags
 * each child so the Diagnostics screen can attribute a peer/count to Bluetooth vs Wi-Fi Aware. [Other] is the
 * default for fakes / the demo transport, which have no real radio.
 */
enum class TransportKind { Bluetooth, WifiAware, Other }

/**
 * A per-radio status line for the Diagnostics screen, produced by [CompositeMeshTransport.statuses]. [linked]
 * is the count of live data-path links right now (≤1 for Wi-Fi Aware's ephemeral NDP, up to the link budget
 * for Bluetooth); [nearby] is the smoothed coordination-plane [MeshTransport.reachable] count. [contended] is
 * this radio's [MeshTransport.radioContended] hint (Bluetooth ↔ A2DP audio), shown as a diagnostic flag.
 */
data class TransportStatus(
    val kind: TransportKind,
    val health: TransportHealth,
    val linked: Int,
    val nearby: Int,
    val contended: Boolean = false,
)

/** Shared "never contended" default for [MeshTransport.radioContended], so a transport without the signal
 *  allocates nothing (only the Bluetooth plane overrides it). */
internal val NOT_CONTENDED: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * A frame received from a neighbor: the verbatim [wire] (its [WireEnvelope.signed]/[WireEnvelope.sig]
 * are forwarded byte-for-byte on relay) plus the already-decoded [envelope] (so the router and delivery
 * paths don't each re-decode it), tagged with the neighbor it arrived from.
 */
data class InboundFrame(
    val wire: WireEnvelope,
    val envelope: RelayEnvelope,
    val fromNodeId: String,
)

/** What a transferred file is, so the receiver can route an avatar apart from a chat attachment. */
enum class FileKind(
    val wire: String,
) {
    AVATAR("AVATAR"),
    ATTACHMENT("ATTACHMENT"),
    ;

    companion object {
        /**
         * Resolve an on-wire file-header `kind` token to a [FileKind]. The token is an explicit literal
         * (frozen for peer interop), NOT the enum constant name — so R8 obfuscation may rename the constants
         * without moving the JSON file-header sidecar. Unknown tokens fall back to [ATTACHMENT] (route as a
         * chat attachment, never an avatar) — the same safe default the prior `valueOf(...)` read used.
         */
        fun fromWire(token: String): FileKind = entries.firstOrNull { it.wire == token } ?: ATTACHMENT
    }
}

/**
 * Metadata sent alongside a file so the receiver can identify it: [kind] (avatar vs attachment),
 * [key] (the avatar's node id, or an attachment's content hash), and the file's [mime] type.
 */
data class FileMeta(
    val kind: FileKind,
    val key: String,
    val mime: String,
)

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
data class ReceivedDigest(
    val fromNodeId: String,
    val ids: List<String>,
)

/**
 * Abstraction over the radio layer that discovers neighbors and exchanges [WireEnvelope] frames with
 * them. Implemented by [app.getknit.knit.mesh.wifiaware.WifiAwareTransport] (Wi-Fi Aware / NAN) and
 * [app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport] (Bluetooth LE), run **simultaneously** by
 * [CompositeMeshTransport]; keeping the rest of the app behind this interface is what lets the two planes
 * compose — and another sibling transport drop in — without touching orchestration.
 */
@Suppress("TooManyFunctions") // the radio seam is inherently wide: lifecycle + sends + files + cross-plane hints
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

    /**
     * Coarse radio health: [TransportHealth.Degraded] when Quick Share seizes the radios (on but failing),
     * [TransportHealth.Unavailable] when the radio is switched off (Wi-Fi/Bluetooth off or airplane mode).
     */
    val health: StateFlow<TransportHealth>

    /**
     * True if this transport has a **coordination plane** — a small best-effort message channel that reaches
     * neighbors with no data path (Wi-Fi Aware cues) — so [CompositeMeshTransport] routes the fast path
     * ([fastFanout]/[fastSend]) here. A transport whose reliable [send] already rides persistent links
     * (Bluetooth) leaves this false: for it, a normal [send] over its live links *is* the fast path.
     */
    val hasFastPlane: Boolean get() = false

    /**
     * Which physical radio this transport is, for the Diagnostics screen — so a merged
     * [CompositeMeshTransport] can attribute peers and counts to Bluetooth vs Wi-Fi Aware. Defaults to
     * [TransportKind.Other]; only the real radio transports override it.
     */
    val kind: TransportKind get() = TransportKind.Other

    /**
     * True for a transport whose data path is high-throughput relative to its siblings (a Wi-Fi Aware NDP
     * moves megabytes in well under a second; Bluetooth's L2CAP CoC takes seconds-to-minutes), so
     * [CompositeMeshTransport] can prefer it for **large file payloads** ([FileKind.ATTACHMENT] blobs) while
     * frames, digests, and avatars keep the normal preference order. A routing capability, deliberately
     * distinct from [kind] (which is diagnostics-only). Default false; only Wi-Fi Aware overrides it.
     */
    val highThroughput: Boolean get() = false

    /**
     * Whether this transport currently believes its radio is contended by another user of the same radio —
     * for Bluetooth, A2DP audio streaming to a speaker (which can starve an L2CAP connect). Diagnostic-only:
     * surfaced in the Diagnostics transport row; connections are **not** gated on it. Defaults to never
     * contended ([NOT_CONTENDED]); only the Bluetooth plane overrides it.
     */
    val radioContended: StateFlow<Boolean> get() = NOT_CONTENDED

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

    /**
     * Hints that [peers] (by nodeId) are currently served by a **higher-preference** plane, so this transport
     * should not spend an on-demand data-path sync bringing up its own link to them — the other plane already
     * carries their data. It still relays/floods over any link it does hold and stays discoverable; only the
     * cue-driven sync is suppressed. When a peer leaves the set (loses the other plane), syncing to it resumes.
     * Driven by [CompositeMeshTransport] from the higher-preference children's live links. Default no-op — only
     * a transport with a scarce on-demand data path (Wi-Fi Aware) overrides it; link-based/fake transports and
     * the always-linked Bluetooth plane ignore it.
     */
    fun suppressDataPath(peers: Set<String>) {}

    /**
     * Reverse of [suppressDataPath]: hints that [peers] (by nodeId) are currently reachable over some **other**
     * plane's coordination layer but not necessarily linked here — cross-plane presence this transport can't see
     * itself. A radio that duty-cycles its discovery (Bluetooth) uses it to briefly boost its scan to try to catch
     * a peer another radio already sees, so a Wi-Fi Aware sighting can be promoted onto the cheaper persistent BLE
     * plane before the peer is even in BLE range. Default no-op — only the Bluetooth plane overrides it; Wi-Fi
     * Aware / fakes ignore it. Driven by [CompositeMeshTransport] from the *other* children's [reachable] sets.
     */
    fun onForeignReachable(peers: Set<String>) {}

    /**
     * Hints that a **large file transfer** with [peer nodeId] is imminent (an attachment blob is about to be
     * requested from or served to it), so a transport with an on-demand data path (Wi-Fi Aware) may arm a
     * link bring-up even when digest parity or higher-plane suppression would otherwise skip it — in the
     * steady state a BLE-linked pair has converged digests and a suppressed NAN sync, so the fast plane's
     * NDP never exists exactly when an image needs it. Best-effort and bounded: the mark is TTL'd, respects
     * the transport's own freshness/backoff/admission gates, and never feeds recovery machinery (wedge
     * watchdog, subscribe re-arm). Must be cheap and non-blocking — callers sit on the inbound dispatch
     * path. Returns whether any on-demand plane actually armed (false ⇒ don't wait for a link that won't
     * come). Default no-op false — a link-based transport (Bluetooth) is always "up" and needs no arming.
     */
    fun expectBulkTransfer(nodeId: String): Boolean = false

    /** Sends [wire] to one neighbor, or to all neighbors when [to] is null. */
    suspend fun send(
        wire: WireEnvelope,
        to: Peer? = null,
    )

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
    fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {}

    /**
     * Sends a file (avatar or attachment) tagged with [meta] to a single neighbor. Returns whether the
     * transfer was **accepted for delivery** (enqueued on a live link, or scheduled by the composite's
     * fast-plane wait); false means no live route existed — the file went nowhere and the caller's
     * event-driven retry (neighbor join / periodic re-offer) is the recovery. Best-effort: true does not
     * guarantee the bytes arrive (the link can still die mid-stream).
     */
    suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean

    /**
     * Advertises the custody message [ids] we hold to a single neighbor [to] over the data-path socket (an
     * [app.getknit.knit.mesh.link.LinkFraming.Type.DIGEST] record), so it replies with only the frames we
     * lack. Default no-op — only a transport with a data path overrides it; the fakes/demo ignore it.
     */
    suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {}
}
