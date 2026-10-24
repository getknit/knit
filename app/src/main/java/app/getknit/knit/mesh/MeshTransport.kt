package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * What the originator of a [MeshTransport.longRangeFanout] frame knows about it that the plane cannot read
 * off the sealed bytes. Only a frame *we* originate carries a hint; everything relayed is [CONTENT], because
 * a DM, its ✓✓, a reaction and a group-key seed are wire-indistinguishable (ADR 039 §3) and the plane must
 * not guess.
 */
enum class FanoutHint {
    /**
     * Anything we cannot classify — a relayed frame, or one of our own sealed ctls (a reaction, a session
     * reset, a seed or key reply) — worth its airslot.
     */
    CONTENT,

    /**
     * One of our own 1:1 messages — a DM the user wrote, sent or re-sealed. The one kind that draws a ✓✓ back,
     * so the one a plane that waits for its recipient to be heard may probe with (ADR 2026-10.a4pm).
     */
    CHAT,

    /** Our own delivery receipt: feedback, not content. A scarce medium sheds it first and never spends its last air on it. */
    TICK,
}

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
 * - [ForegroundOnly]: the radio is on and attached, but the OS lets it discover only while Knit is on
 *   screen — on API 29-32 Wi-Fi Aware publish/subscribe are gated on the foreground-only location app-op,
 *   and a service started off screen (a boot, a sticky restart) holds no background exemption for it (ADR
 *   2026-09.535d). Not a fault: whatever was up stays up, and opening Knit lifts it for the rest of the
 *   session. Ranked between [Healthy] and [Degraded] by the composite.
 * - [Degraded]: the radio is on but the last advertise/discover/attach attempt failed — typically
 *   because another app (e.g. Quick Share) has seized it. Usually self-heals; a mesh restart may help.
 * - [Unavailable]: the radio is switched off (the user turned Wi-Fi/Bluetooth off, or airplane mode is
 *   on) or absent — nothing the app can do but wait for the user to turn a radio back on. The UI turns
 *   this into an actionable "turn on Wi-Fi or Bluetooth" hint rather than a generic failure.
 */
enum class TransportHealth { Healthy, ForegroundOnly, Degraded, Unavailable }

/**
 * Which physical radio a [MeshTransport] drives. [CompositeMeshTransport] tags each child with it so the
 * Diagnostics screen can attribute a peer/count to Bluetooth vs Wi-Fi Aware vs LoRa, and stamps it on every
 * [InboundFrame] it merges ([InboundFrame.kind]) so the delivery path can record which plane a frame arrived
 * on (`DeliveryPlane` — a presentation fact only; carry, relay and convergence never read it, ADR 019/040).
 * [Other] is the default for fakes / the demo transport, which have no real radio.
 */
enum class TransportKind { Bluetooth, WifiAware, LoRa, Other }

/**
 * A per-radio status line for the Diagnostics screen, produced by [CompositeMeshTransport.statuses]. [linked]
 * is the count of live data-path links right now (≤1 for Wi-Fi Aware's ephemeral NDP, up to the link budget
 * for Bluetooth); [nearby] is the smoothed coordination-plane [MeshTransport.reachable] count. [contended] is
 * this radio's [MeshTransport.radioContended] hint (Bluetooth ↔ A2DP audio), shown as a diagnostic flag;
 * [initiatorHeld] is its [MeshTransport.initiatorHeld] (Wi-Fi Aware has stopped starting data paths from this
 * phone), shown as a flag and a Diagnostics section with the way back. [switchedOff] is its
 * [MeshTransport.switchedOff]: Knit itself has the plane off (the Settings switch, low-power mode, or a debug build's
 * switch), so its [TransportHealth.Unavailable] is not a radio the user turned off and asks nothing of them (ADR
 * 2026-10.d44b). [dormant] is its [MeshTransport.dormant]: the plane is up but detached because no phone that could use
 * it is near (GitHub #44) — idle, not off, and its health says nothing worse for it.
 */
data class TransportStatus(
    val kind: TransportKind,
    val health: TransportHealth,
    val linked: Int,
    val nearby: Int,
    val contended: Boolean = false,
    val initiatorHeld: Boolean = false,
    val switchedOff: Boolean = false,
    val dormant: Boolean = false,
)

/** Shared "never contended" default for [MeshTransport.radioContended], so a transport without the signal
 *  allocates nothing (only the Bluetooth plane overrides it). */
internal val NOT_CONTENDED: StateFlow<Boolean> = MutableStateFlow(false)

/** Shared "never held" default for [MeshTransport.initiatorHeld] (only the Wi-Fi Aware plane overrides it). */
internal val NEVER_HELD: StateFlow<Boolean> = MutableStateFlow(false)

/** Shared "never switched off" default for [MeshTransport.switchedOff] (only `SwitchableTransport` overrides it). */
internal val NEVER_SWITCHED_OFF: StateFlow<Boolean> = MutableStateFlow(false)

/** Shared "never dormant" default for [MeshTransport.dormant] (only the Wi-Fi Aware plane overrides it). */
internal val NEVER_DORMANT: StateFlow<Boolean> = MutableStateFlow(false)

/** Shared empty default for [MeshTransport.awareCandidatesNear] / [MeshTransport.awareCandidatesHeard]. */
internal val NO_AWARE_CANDIDATES: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/** Shared "no newcomer watch" default for [MeshTransport.seesNewcomers]: a plane that does not say so never counts. */
internal val SEES_NO_NEWCOMERS: StateFlow<Boolean> = MutableStateFlow(false)

/**
 * A frame received from a neighbor: the verbatim [wire] (its [WireEnvelope.signed]/[WireEnvelope.sig]
 * are forwarded byte-for-byte on relay) plus the already-decoded [envelope] (so the router and delivery
 * paths don't each re-decode it), tagged with the neighbor it arrived from and the radio it arrived over.
 *
 * [kind] is stamped by [CompositeMeshTransport] as it merges its children — the one place that knows which
 * child emitted the frame (`FramedLink` is shared by Bluetooth and Wi-Fi Aware and cannot tell) — so a
 * transport constructs frames without it and the default [TransportKind.Other] holds for fakes and the demo
 * transport. Read only by the delivery path to record the plane a message arrived on, and by the indirect-reach
 * tracker to leave LoRa out of it (ADR 2026-10.fw8g); never by routing.
 */
data class InboundFrame(
    val wire: WireEnvelope,
    val envelope: RelayEnvelope,
    val fromNodeId: String,
    val kind: TransportKind = TransportKind.Other,
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
 * [key] (the blob's content hash, an avatar's too), and the file's [mime] type.
 *
 * [offset] is the first byte to send: the asker already holds the ones before it from a transfer a link drop cut
 * off (work item #116, ADR 2026-10.wtyc). 0 sends the whole file, as every send did before.
 *
 * [fastOnly] is the asker's `blobreq` saying it takes these bytes over Wi-Fi Aware only (ADR 2026-10.js2p): the
 * composite sends them over a live Wi-Fi Aware link or one it raises for them, and otherwise drops them, never handing
 * them to the Bluetooth link. A send that `sendFile` accepted and then dropped that way calls [onDropped], so the
 * holder forgets the serve and answers the asker's next query truthfully rather than SENDING for a copy that went
 * nowhere. Local to this node: nothing here rides the link.
 */
data class FileMeta(
    val kind: FileKind,
    val key: String,
    val mime: String,
    val offset: Long = 0,
    val fastOnly: Boolean = false,
    val onDropped: (() -> Unit)? = null,
)

/**
 * A file received from a neighbor, already saved at [path], tagged with its [FileMeta] fields. [resumedFrom] is
 * the byte the stream started at when it was the rest of a cut transfer, spliced onto the prefix this node kept
 * ([PartialBlobs]); 0 for a whole file. A spliced file that fails its hash check drops that prefix (#116).
 */
data class ReceivedFile(
    val fromNodeId: String,
    val path: String,
    val kind: FileKind,
    val key: String,
    val mime: String,
    val resumedFrom: Long = 0,
)

/**
 * A copy of a file being fed to a neighbour on a link whose feeds share one pace budget, as the link reads it
 * ([MeshTransport.fileFeed], ADR 2026-10.nsr7). [etaMs] is how long the rest should take, by the rate the receiver's
 * progress reports measured or, before the first, by the pace; null while the copy is queued and its pace not known.
 * [live] is false once the feed stalled (no progress, or no write, for the stall window) or ran past the deadline its
 * first measurement set: a feed that is not live holds no parked ask. [measured] says [etaMs] came from the receiver.
 */
data class FileFeed(
    val etaMs: Long?,
    val live: Boolean,
    val measured: Boolean = false,
    // How soon [live] can next flip on its own (a stall or a deadline coming due), so a holder polls then and not every
    // second; null when nothing is due.
    val recheckMs: Long? = null,
)

/**
 * A receiver's note on a file streaming in ([MeshTransport.fileProgress], ADR 2026-10.nsr7): from [from], under [key],
 * [held] bytes of it (the whole length so far, a kept prefix included) [ms] after its header arrived.
 */
data class FileProgress(
    val from: String,
    val key: String,
    val held: Long,
    val ms: Long,
)

/**
 * A file streaming in on a live link right now (#115): its [key], the [bytes] in so far, and the [total] its
 * sender declared in the `FILE_HEADER` — null from a build that predates the field. The total is a label, never
 * a bound (the link holds its own ceiling); one the bytes have overrun is no total at all, so [fraction] is null
 * and the bubble shows the bytes alone.
 */
data class ArrivingFile(
    val key: String,
    val bytes: Long,
    val total: Long?,
) {
    /** The share in so far, 0..1, or null when there is no total to hold the bytes against. */
    val fraction: Float?
        get() = total?.takeIf { it > 0 && bytes <= it }?.let { bytes.toFloat() / it }
}

/**
 * How a file from a neighbour would most likely reach us ([MeshTransport.bulkRouteTo]): [Linked] when a
 * high-throughput plane (Wi-Fi Aware) holds a live link to it now, [Reachable] when that plane sees it but holds no
 * link (a holder serving a large attachment arms one and waits for it, `CompositeMeshTransport.sendFile`), else
 * [None]. `BlobExchange` orders its first ask by it (ADR 2026-10.f7gg); nothing routes on it.
 */
enum class BulkRoute { Linked, Reachable, None }

/** One entry per key; when two links stream the same blob, the copy furthest along is the one that counts. */
fun Iterable<ArrivingFile>.furthestByKey(): Map<String, ArrivingFile> =
    groupingBy { it.key }.reduce { _, kept, other -> if (other.bytes > kept.bytes) other else kept }

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
     * The peers this short-range plane hears close enough to corroborate that they are physically present — a subset
     * of [reachable], which a long-reach PHY widens past a sibling's range (Bluetooth's Coded PHY holds a peer from one
     * advert at −101 dBm, far past a Wi-Fi Aware NDP). [CompositeMeshTransport] pushes the short-range siblings' sets
     * to each child through [onForeignNear]; the Wi-Fi Aware wedge watchdog's process kill reads it (ADR 2026-09.9dnk).
     * Defaults to [reachable] for a transport whose reach is its range.
     */
    val near: StateFlow<Set<Peer>> get() = reachable

    /**
     * Coarse radio health: [TransportHealth.Degraded] when Quick Share seizes the radios (on but failing),
     * [TransportHealth.Unavailable] when the radio is switched off (Wi-Fi/Bluetooth off or airplane mode).
     */
    val health: StateFlow<TransportHealth>

    /**
     * True if this transport has a **coordination plane** — a small best-effort message channel that reaches
     * neighbors with no data path (Wi-Fi Aware cues, the BLE side channel's advertising pages) — so
     * [CompositeMeshTransport] routes the fast path ([fastFanout]/[fastSend]) here rather than turning it
     * into a plain [send]. A transport whose reliable [send] rides persistent links and declares this
     * (Bluetooth) sends that link copy itself inside [fastFanout]/[fastSend], side channel or not.
     */
    val hasFastPlane: Boolean get() = false

    /**
     * Which physical radio this transport is — so a merged [CompositeMeshTransport] can attribute peers and
     * counts to Bluetooth vs Wi-Fi Aware vs LoRa for the Diagnostics screen, and stamp [InboundFrame.kind] so
     * the delivery path can record the plane a message arrived on. Never a routing input. Defaults to
     * [TransportKind.Other]; only the real radio transports override it.
     */
    val kind: TransportKind get() = TransportKind.Other

    /**
     * True for a transport whose data path is high-throughput relative to its siblings (a Wi-Fi Aware NDP
     * moves megabytes in well under a second; Bluetooth's L2CAP CoC takes seconds-to-minutes), so
     * [CompositeMeshTransport] can prefer it for **large file payloads** ([FileKind.ATTACHMENT] blobs) while
     * frames, digests, and avatars keep the normal preference order. A routing capability, deliberately
     * distinct from [kind] (which never steers routing). Default false; only Wi-Fi Aware overrides it.
     */
    val highThroughput: Boolean get() = false

    /**
     * Whether a sighting on this transport implies the peer is **physically near** (BLE/NAN range), so a
     * sibling plane may act on its sets — the Bluetooth scan-boost chase and the attachment-upload deferral read
     * its [reachable], and the Wi-Fi Aware wedge watchdog's "owed peer genuinely nearby" corroboration reads its
     * narrower [near] (a Coded-PHY advert keeps a peer reachable far past an NDP's range, ADR 2026-09.9dnk).
     * A long-range plane (LoRa, whose peer may be kilometres away) overrides this to false so those siblings
     * ignore its sightings; [CompositeMeshTransport] excludes non-short-range children from every foreign set
     * and from [CompositeMeshTransport.shortRangeReachable]. A routing/semantics flag, deliberately distinct
     * from [kind] (which never steers routing). Default true — only a long-range transport overrides it.
     */
    val shortRange: Boolean get() = true

    /**
     * Whether this transport currently believes its radio is contended by another user of the same radio —
     * for Bluetooth, A2DP audio streaming to a speaker (which can starve an L2CAP connect). Diagnostic-only:
     * surfaced in the Diagnostics transport row; connections are **not** gated on it. Defaults to never
     * contended ([NOT_CONTENDED]); only the Bluetooth plane overrides it.
     */
    val radioContended: StateFlow<Boolean> get() = NOT_CONTENDED

    /**
     * Whether this transport has stopped **initiating** data paths because doing so kept costing the phone
     * its own Wi-Fi (`mesh/wifiaware/NanInitiatorPolicy`, ADR 2026-09.m8kc, work item #78). Diagnostic-only:
     * the plane is still [TransportHealth.Healthy] — discovery, cues, the responder and the fast plane run,
     * and nearby phones can still connect to this one. Surfaced in the Diagnostics transport row with
     * [releaseInitiatorHold] as the way back. Defaults to never held ([NEVER_HELD]); only Wi-Fi Aware overrides it.
     */
    val initiatorHeld: StateFlow<Boolean> get() = NEVER_HELD

    /**
     * Whether Knit itself has this plane switched off — low-power mode or a debug build's switch, applied by
     * `SwitchableTransport` (ADR 2026-10.d44b). The plane then reads [TransportHealth.Unavailable] like a radio the
     * user turned off, and this is what tells the two apart, so no surface asks the user to turn on a radio that is
     * on. Defaults to never ([NEVER_SWITCHED_OFF]).
     */
    val switchedOff: StateFlow<Boolean> get() = NEVER_SWITCHED_OFF

    /**
     * Whether this plane is up but **detached** on purpose because nothing nearby could use it — Wi-Fi Aware's dormancy
     * (`mesh/wifiaware/NanDormancyPolicy`, GitHub #44): attached Aware costs a screen-on phone about two thirds of its
     * Wi-Fi throughput. Not [switchedOff]: the plane wakes by itself for a phone that can use it, and its health reads
     * Healthy unless the radio is off by evidence. Carried as [TransportStatus.dormant]. Defaults to never
     * ([NEVER_DORMANT]); only Wi-Fi Aware overrides it, and `SwitchableTransport` passes its child's through.
     */
    val dormant: StateFlow<Boolean> get() = NEVER_DORMANT

    /**
     * The peers this short-range plane has positive evidence can run Wi-Fi Aware, heard **near** enough to be worth
     * waking a dormant Aware plane for (the Bluetooth plane: a service-data advert whose flags say Aware runs there,
     * GitHub #44). A subset of [awareCandidatesHeard]. [CompositeMeshTransport] pushes the union of the siblings' sets
     * through [onForeignAwareCandidates]. Default empty: a plane that cannot tell never names one.
     */
    val awareCandidatesNear: StateFlow<Set<String>> get() = NO_AWARE_CANDIDATES

    /**
     * As [awareCandidatesNear], but every candidate this plane still **hears**, with a linger long enough that a peer
     * heard in every scan window never drops out between them: what keeps a dormant-able Aware plane attached. Default
     * empty.
     */
    val awareCandidatesHeard: StateFlow<Set<String>> get() = NO_AWARE_CANDIDATES

    /**
     * Whether this plane demonstrably watches for newcomers right now (Bluetooth: adapter on, started, and its last scan
     * window really ran). Wi-Fi Aware goes dormant only while some sibling says so, since that sibling is then what
     * tells it a phone came; [CompositeMeshTransport] ORs it across the short-range siblings. Default false.
     */
    val seesNewcomers: StateFlow<Boolean> get() = SEES_NO_NEWCOMERS

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
     * Hints that [peers] (by nodeId) were recently seen on a connected Internet spool (`ScopeSync`'s
     * `peerSeenAt`, within `SPOOL_COVER_MS`), so a plane with no data path of its own need not spend air on
     * a DM-form frame to them — the frame is in the spool anyway, and the spool is the reliable, free path
     * (ADR 2026-09.y5f3). A **cover** hint and nothing more: it is fed from spool presence, never from a
     * link, and must never become an election input (`LoraGatewayPolicy` reads [suppressDataPath]'s link
     * set for that). Driven by `MeshManager`; forwarded to every child by [CompositeMeshTransport]. Default
     * no-op — only the LoRa plane overrides it.
     */
    fun coveredByInternet(peers: Set<String>) {}

    /**
     * Lends the Wi-Fi radio to another **same-app** role — the one-shot Wi-Fi Direct group of a direct file
     * transfer (`transfer/`). On Android 12+ two interface requests from one app carry equal priority and
     * neither evicts the other, so a transport whose radio cannot share (Wi-Fi Aware) has to let go of its
     * session first — and must stop re-attaching while the other role holds the slot, or its attach loop
     * burns `NanAttachPolicy`'s leak budget failing every 3 s. Availability never flaps for a same-app
     * hand-over, so there is no edge to recover on: [resume] is the explicit way back. Default no-op —
     * Bluetooth, LoRa and fakes ignore both.
     */
    fun pause() {}

    /** Reverse of [pause]: takes the radio back and attaches again at once, clearing any attach backoff. */
    fun resume() {}

    /**
     * The user's "Try again" for [initiatorHeld]: forgets the coincidences that held the initiator role and
     * lets the next owed sync initiate. Three more Wi-Fi drops re-hold it. Default no-op — only Wi-Fi Aware
     * has the role; [CompositeMeshTransport] fans it out to every child.
     */
    fun releaseInitiatorHold() {}

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
     * Hints that [peers] (by nodeId) are heard **near** over some other short-range plane — its [near] set, a subset of
     * what [onForeignReachable] carries. Driven by [CompositeMeshTransport] from the other short-range children's
     * [near]; read by the Wi-Fi Aware wedge watchdog's corroboration, where a merely reachable peer (a far Coded
     * advert) must not corroborate a process kill (ADR 2026-09.9dnk). Default no-op.
     */
    fun onForeignNear(peers: Set<String>) {}

    /**
     * The other short-range planes' word on phones that can run Wi-Fi Aware (GitHub #44): the union of their
     * [awareCandidatesNear] ([near], what wakes a dormant Aware plane) and [awareCandidatesHeard] ([heard], what keeps
     * it attached), and the OR of their [seesNewcomers]. Driven by [CompositeMeshTransport] beside [onForeignNear];
     * LoRa is excluded as there. Default no-op — only Wi-Fi Aware overrides it.
     */
    fun onForeignAwareCandidates(
        near: Set<String>,
        heard: Set<String>,
        seesNewcomers: Boolean,
    ) {}

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

    /**
     * Keeps a link to [nodeId] open, or raises one, for a live call that wants its link between its bursts (a walkie
     * call, docs/VOICE_CALLS.md §3.11): a transport that tears idle links down keeps this one for a short TTL, which the
     * call renews by calling again. True when this transport holds or is raising a link. The default is
     * [expectBulkTransfer]: a transport whose links never idle out has nothing to hold.
     */
    fun holdLink(nodeId: String): Boolean = expectBulkTransfer(nodeId)

    /**
     * How a file from [nodeId] would most likely reach us: over a live high-throughput link, over one such a plane
     * can raise, or over neither ([BulkRoute]). A read of the plane's own sets, cheap and non-blocking, for
     * `BlobExchange` to put a Wi-Fi Aware neighbour ahead of a Bluetooth-only one when it picks whom to ask first.
     * The default answers from this transport's own [neighbors] and [reachable], and only for a
     * [highThroughput] one; the composite asks its high-throughput child.
     */
    fun bulkRouteTo(nodeId: String): BulkRoute =
        when {
            !highThroughput -> BulkRoute.None
            neighbors.value.any { it.nodeId == nodeId } -> BulkRoute.Linked
            reachable.value.any { it.nodeId == nodeId } -> BulkRoute.Reachable
            else -> BulkRoute.None
        }

    /**
     * The files whose bytes are streaming **in** on a live link right now — a `FILE_HEADER` has arrived and
     * its `FILE_END` has not — by key, with how far each has got. `BlobExchange` reads only the keys, so a blob
     * already on the way is neither wanted again nor re-asked for on the 60 s tick: a re-ask against a slow
     * BLE transfer bought a second full copy from every holder (work item #79). The chat reads the bytes and
     * the declared total to draw the attachment's progress (#115). A pull, like the side scan's
     * `streamInFlight` (ADR 2026-09.u8qj), so there is no memo to expire or abort to signal — a torn link
     * clears its own state and the next tick asks again. Default empty: a plane with no data path (LoRa)
     * carries no files.
     *
     * A file stays here past its `FILE_END`, whole, until the consumer of [incomingFiles] has taken it
     * ([fileTaken]): between the link's end and the store's own mark (`BlobStore.storing`) it sits in the cache, in
     * this transport's buffer and behind the consumer's earlier files, and a want or a first ask's timer that read
     * the link and the store there found the bytes nowhere and asked again (#138).
     */
    fun arrivingFiles(): Map<String, ArrivingFile> = emptyMap()

    /**
     * The consumer of [incomingFiles] is done with [key] from [fromNodeId] — stored, refused, or dropped — so it
     * stops counting in [arrivingFiles]. Idempotent; a key no live link holds is a no-op. Default no-op: a plane
     * with no data path carries no files.
     */
    fun fileTaken(
        fromNodeId: String,
        key: String,
    ) {}

    /**
     * True while a file from [nodeId] is streaming in on a live link: its `FILE_HEADER` is in and its `FILE_END` is
     * not. A link delivers in order, so anything that peer has already fed us is behind this file in the stack's
     * buffer. `BlobExchange` holds the 60 s re-ask to that peer until it is false: an ask for a blob still in the
     * buffer finds the holder done feeding it, and buys a second copy (#121). Default false: a plane with no data
     * path carries no files.
     */
    fun fileArrivingFrom(nodeId: String): Boolean = false

    /**
     * How long the file streaming in from [nodeId] has left, at its rate so far: what `BlobExchange` weighs before its
     * fetch waits out that peer's stream of another file (ADR 2026-10.nsr7). Null when nothing streams in from it, or
     * no rate or size is known yet. Default null.
     */
    fun fileArrivingEtaMs(nodeId: String): Long? = null

    /**
     * [nodeId] withdrew its ask for [key] (a `blobcancel`, ADR 2026-10.nsr7): one copy of it queued to that peer and not
     * yet streaming is dropped from its link. True when one was. Default false: a plane with no data path queues none.
     */
    fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = false

    /**
     * True while a file under [key] is queued on, or streaming over, a live link toward [nodeId] — from the
     * enqueue [sendFile] accepted to the end of the stream, and after it for as long as that link goes on
     * feeding [nodeId] a later file, which the stack delivers behind it (#121). Read by `BlobExchange.onRequest`
     * so a re-ask (an older build's, one whose serve is still queued behind a multi-minute blob to the same peer,
     * or one that crossed the copy in the stack's buffer) never queues a second copy behind the first (#79).
     * Default false.
     */
    fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = false

    /**
     * The copy of the file under [key] a link toward [nodeId] is feeding, when that link's feeds share one pace budget —
     * a Bluetooth link not on Coded (ADR 2026-10.8jwn) — and the copy is streaming, or queued with nothing else
     * streaming so the writer is about to take it. Null otherwise: a copy queued behind another file is not fed yet,
     * and one fed in full no longer is, unlike [fileInFlightTo]. Read by `BlobExchange.onRequest`, which feeds one such
     * copy of a blob at a time and parks the rest while it is [FileFeed.live] (ADR 2026-10.g24x, 2026-10.nsr7). Default
     * null: a plane whose feeds take nothing from each other is never waited on.
     */
    fun fileFeed(
        nodeId: String,
        key: String,
    ): FileFeed? = null

    /**
     * The rate, in bytes a second, a file fed to [nodeId] now would most likely go at: the rate its link last measured
     * from a receiver's reports, else the share its pace would give it beside the feeds already going. `BlobExchange`
     * serves the fastest parked ask first, and serves one beside a slower feed only when its own copy would end first
     * (ADR 2026-10.nsr7). Default 0: unknown, which never goes ahead of a feed.
     */
    fun expectedFileRate(nodeId: String): Int = 0

    /**
     * [nodeId] reported how much of the file under [key] it holds ([FrameType.BLOB_PROGRESS], ADR 2026-10.nsr7): the
     * link streaming that key to it takes the report, and every other link ignores it. Default: ignored.
     */
    fun onFileProgress(
        nodeId: String,
        key: String,
        held: Long,
        ms: Long,
    ) {}

    /**
     * Every couple of seconds of chunks of a file streaming in on a link whose feeds share one pace budget, how much of
     * it is held ([FileProgress]): `BlobExchange` sends it back to the sender as a `blobprog` (ADR 2026-10.nsr7). A
     * stall emits nothing. Default: never.
     */
    val fileProgress: Flow<FileProgress> get() = emptyFlow()

    /**
     * Fires when a link of this transport changes how long a file's feed has left: it ends the stream, fed or not (on the
     * writer the write woke, so a holder that parked asks behind that feed serves them while the CPU is up, ADR
     * 2026-10.g24x); the pace settles or moves; the first progress report measures it (ADR 2026-10.nsr7). A bare poke:
     * the holder re-reads its parked asks. Default: never.
     */
    val fileFeedsChanged: Flow<Unit> get() = emptyFlow()

    /**
     * How many files the plane a file to [nodeId] would ride is feeding right now, this node's own sends: what a
     * `blobhas` answer names as its `load` (ADR 2026-10.6e5t). A Bluetooth holder splits one pace budget across every
     * link it is feeding (ADR 2026-10.8jwn), so an asker choosing between two holders takes the less loaded one.
     * Default 0: a plane with no data path feeds nothing.
     */
    fun feedingLoad(nodeId: String): Int = 0

    /**
     * True while the live link to [nodeId] runs far below its plane's pace — Bluetooth on the Coded PHY, which the
     * experiment paces apart (ADR 2026-10.yvn6). An asker ranks a holder on such a link after one on a full-speed
     * link (ADR 2026-10.6e5t). Default false.
     */
    fun slowLinkTo(nodeId: String): Boolean = false

    /** Sends [wire] to one neighbor, or to all neighbors when [to] is null. */
    suspend fun send(
        wire: WireEnvelope,
        to: Peer? = null,
    )

    /**
     * Sends [wire] to the neighbor [to] over the quickest link this transport holds to it: for a frame that is live or
     * lost and wants latency over the steadiest plane — a walkie chunk (docs/VOICE_CALLS.md §3.11). The default is
     * [send]; the composite prefers its [highThroughput] child when that child holds a link to [to].
     */
    suspend fun sendLive(
        wire: WireEnvelope,
        to: Peer,
    ) = send(wire, to)

    /**
     * True while the link [sendLive] would carry a frame to [nodeId] on is near its floor: its link-RSSI reads put it at
     * the fade threshold, or they stopped answering. A walkie chunk then goes through a relay as well as over that link
     * (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb's ninth amendment), since a dying link keeps counting as a path until
     * its supervision timeout. False with no such link, or on a plane that cannot tell. Default false.
     */
    fun liveLinkFading(nodeId: String): Boolean = false

    /**
     * Best-effort **coordination-plane** fan-out of [wire] to every neighbor at once, with **no data path** —
     * a fast path for a frame small enough to ride the tiny Wi-Fi Aware message channel (~255 B/message;
     * toward capable peers the Wi-Fi Aware transport compacts/deflates the framing and can split one frame
     * across ≤ 3 messages, so somewhat larger frames ride too — `mesh/link/FastFrameCodec`). Because it
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
     * coordination plane or no eligible encoding fits (~255 B/message; the Wi-Fi Aware transport compacts and
     * fragments ≤ 3 messages toward capable peers, which is what lets a sealed receipt ride). Default no-op —
     * only a transport with
     * a message channel (Wi-Fi Aware) overrides it; the fakes ignore it.
     */
    fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {}

    /**
     * Best-effort **long-range** fan-out of [wire] — a frame the reliable flood + store-and-forward custody
     * already carry — offered only to a plane with **no data path at all** ([shortRange] false, [neighbors]
     * always empty; today the LoRa bridge). Such a plane never receives a frame by flood, custody sync or a
     * re-serve, so this is its *only* path — which is why, unlike [fastFanout], it admits the sealed DM-form
     * chat frames (`FrameFanout.shouldLongRangeFanout`, ADR 039). The transport size-gates and dedups; the
     * receiver's [MeshRouter] SeenSet drops any copy that also arrives another way. Default no-op — a radio
     * with a data path (Bluetooth, Wi-Fi Aware) and the fakes ignore it.
     *
     * [hint] is what the originator knows and the plane cannot read off a sealed frame: a [FanoutHint.TICK]
     * is our own delivery receipt, which a scarce medium may shed before content (ADR 054), and a
     * [FanoutHint.CHAT] our own DM, the one frame whose ✓✓ can tell the plane its recipient is there (ADR
     * 2026-10.a4pm). A relayed frame is opaque and stays [FanoutHint.CONTENT].
     */
    fun longRangeFanout(
        wire: WireEnvelope,
        hint: FanoutHint = FanoutHint.CONTENT,
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
