package app.getknit.knit.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.spoolPresentPeers
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.reachOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A node in the mesh and the evidence we have for reaching it. */
data class NodeInfo(
    val nodeId: String,
    val displayName: String,
    val reach: Reach,
    // When this node's cached profile was last updated (millis); null if we've never received one.
    val profileUpdatedAt: Long?,
    /**
     * The planes this row is claiming, already narrowed to what its [reach] can honestly show: short-range
     * radios (BLE/NAN) on a [Reach.Direct] row, long-range ones (LoRa) on a [Reach.Relay] row, empty
     * otherwise. Never mixed — a LoRa tag beside BLE·NAN read as "this peer has a board" when it only ever
     * meant "somebody's board carried its frames".
     */
    val transports: Set<TransportKind> = emptySet(),
    /** Reached over the Internet plane: this peer put something recent into a scope we share on a spool. */
    val viaSpool: Boolean = false,
)

data class DiagnosticsUiState(
    val myNodeId: String = "",
    val myName: String = "",
    val directNodes: List<NodeInfo> = emptyList(),
    val relayNodes: List<NodeInfo> = emptyList(),
    /** The [Reach.Known] remainder, newest profile first and capped at [DiagnosticsViewModel.KNOWN_LIMIT]. */
    val knownNodes: List<NodeInfo> = emptyList(),
    /** How many [Reach.Known] nodes there are in total, so the screen can say how many it left out. */
    val knownTotal: Int = 0,
    val metrics: MeshMetrics.Snapshot = MeshMetrics.Snapshot(0, 0, 0, 0, 0, 0),
    // One row per radio plane: the live ones the composite built, plus the phone radios it could not.
    val transports: List<TransportRow> = emptyList(),
    /** Which phone radios this device has, so the "radios off" hint can name the one that matters. */
    val radios: RadioSupport = RadioSupport.ALL,
    // Per-spool status for the Internet plane; empty whenever the plane is parked.
    val spools: List<SpoolStatus> = emptyList(),
)

/** The flows folded into the [DiagnosticsViewModel.state] combine's fifth slot (combine tops out at 5). */
private data class DiagExtras(
    val metrics: MeshMetrics.Snapshot,
    val statuses: List<TransportStatus>,
    val peerTransports: Map<String, Set<TransportKind>>,
    val spools: List<SpoolStatus>,
    val reachable: Set<String>,
    val loraPlane: LoraPlane,
)

/**
 * Backs the read-only Diagnostics screen. Sorts the known nodes into the three [Reach] tiers, each from
 * a *current* signal rather than from what is left over: [Reach.Direct] is
 * [MeshController.neighbors] (the short-range planes, the only ones that sight the peer's own radio),
 * [Reach.Relay] is the rest of [MeshController.reachable] plus any spool scope whose peer has recently
 * pushed to it, and
 * [Reach.Known] is the remainder of the peer table, capped. The mesh is a pure flood network with no
 * routing table, so no tier claims a *route* — only that something reached us from that node, or could
 * carry a frame back — and the relay tier deliberately says nothing about how many hops away it is.
 *
 * [MeshMetrics] has no reactive stream, so it's polled on a [REFRESH_MS] timer.
 */
class DiagnosticsViewModel(
    peers: PeerRepository,
    private val meshManager: MeshController,
    identity: Identity,
    private val settings: SettingsStore,
    private val metrics: MeshMetrics,
    relayStatus: RelayStatusRepository,
    private val crashes: CrashReports,
    private val modelGuard: ModelLoadGuard,
    // Which phone radios this device has — a static fact, probed once at wiring. No default: a test states
    // the device it is describing.
    private val radios: RadioSupport,
    // The LoRa plane as the UI sees it (`LoraStatusRepository.facts`): tells "no board bound" from "board
    // out of reach", which the transport's health folds together.
    loraFacts: Flow<LoraFacts>,
    // Wall clock, for ageing the Internet plane's per-scope presence stamps. Injected so a test can drive
    // the linger; the combine re-runs on the [REFRESH_MS] metrics ticker, so an expiry lands within a tick.
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    /**
     * The newest stored crash, or null. Deliberately **not** part of [state]: that combine is already at
     * its five-source limit (hence [DiagExtras]), and a crash cannot change while this screen is alive —
     * a new one would mean the process died and took this ViewModel with it.
     */
    private val _lastCrash = MutableStateFlow<CrashReportRef?>(null)
    val lastCrash: StateFlow<CrashReportRef?> = _lastCrash.asStateFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        refreshLastCrash()
    }

    /** Re-reads the store. Called on resume, so deleting the report on the crash screen clears this row. */
    fun refreshLastCrash() {
        viewModelScope.launch { _lastCrash.value = crashes.latest() }
    }

    /**
     * Whether the poison-pill has turned an on-device model off (ADR 037). Unlike [lastCrash] this is a
     * live flow, not a snapshot: the reset button is on this very screen, so the value *can* change while
     * the ViewModel is alive and a refresh-on-resume would leave the row stale until the user left.
     */
    val moderationLatched: StateFlow<Boolean> =
        combine(ModelLoadGuard.ALL.map(modelGuard::observeLatched)) { latched -> latched.any { it } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Gives every latched model another chance. Takes effect on the next process start — the moderator
     * latches `loaded` in memory on its first attempt, so nothing reloads inside a running app. The
     * confirm dialog says so rather than implying an instant fix.
     */
    fun resetModerationLatch() {
        viewModelScope.launch { ModelLoadGuard.ALL.forEach { modelGuard.clear(it) } }
        _events.tryEmit(R.string.diagnostics_moderation_reset_done)
    }

    /** Whether this build offers the Bluetooth link limit row — debug builds only. */
    val bleLinkCapOffered: Boolean = BuildConfig.DEBUG

    /**
     * The diagnostic Bluetooth link cap, or null for the shipped budget. Its own flow, like
     * [moderationLatched], because [state]'s combine is at its five-source limit; the transport collects the
     * same store key, so a step here reaches the radios without a restart.
     */
    val bleLinkCap: StateFlow<Int?> =
        settings.debugBleLinkCap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sets the Bluetooth link cap; the shipped budget clears it. A no-op outside a debug build. */
    fun setBleLinkCap(cap: Int) {
        if (!bleLinkCapOffered) return
        viewModelScope.launch { settings.setDebugBleLinkCap(cap) }
    }

    /** Live radio health, shown as a status line above the mesh controls. */
    val health: StateFlow<TransportHealth> =
        meshManager.transportHealth
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportHealth.Healthy)

    // One-shot snackbar feedback (a string resource id) for the Restart / Scan actions.
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    /** Bounces the mesh transports (re-advertise, reconnect, clear stale peers); keeps the service. */
    fun restartMesh() {
        viewModelScope.launch { meshManager.restart() }
        _events.tryEmit(R.string.diagnostics_mesh_restarted)
    }

    /** Triggers an immediate rescan / reconnect. */
    fun rescan() {
        viewModelScope.launch { meshManager.heal() }
        _events.tryEmit(R.string.diagnostics_scanning)
    }

    /**
     * Lets a Wi-Fi Aware plane that stopped initiating data paths (its Wi-Fi dropped each time it tried, ADR
     * 2026-09.m8kc) initiate again. Immediate, unlike [resetModerationLatch]: the transport forgets the
     * coincidences and re-evaluates on the spot, and the row's tag clears as its status flow follows.
     */
    fun releaseInitiatorHold() {
        meshManager.releaseInitiatorHold()
        _events.tryEmit(R.string.diagnostics_nan_hold_retry_done)
    }

    private val metricsTicker: Flow<MeshMetrics.Snapshot> =
        flow {
            while (true) {
                emit(metrics.snapshot())
                delay(REFRESH_MS)
            }
        }

    // Metrics + per-transport status + per-peer transport map + the full reach set + the LoRa plane,
    // pre-combined so the main [state] combine stays within its five-source limit. The reach set and the
    // LoRa plane pair up first for the same reason: this combine is at that limit too.
    private val extras: Flow<DiagExtras> =
        combine(
            metricsTicker,
            meshManager.transportStatuses,
            meshManager.peerTransports,
            relayStatus.statuses,
            combine(meshManager.reachable, loraFacts.map { it.plane }.distinctUntilChanged()) { r, l -> r to l },
        ) { snapshot, statuses, peerTransports, spools, (reachable, loraPlane) ->
            DiagExtras(
                snapshot,
                statuses,
                peerTransports,
                spools,
                reachable.mapTo(mutableSetOf()) { it.nodeId },
                loraPlane,
            )
        }

    val state: StateFlow<DiagnosticsUiState> =
        combine(
            peers.observeDirectory(),
            meshManager.neighbors,
            myNodeId,
            settings.displayName,
            extras,
        ) { directory, neighbors, me, myName, extra ->
            val nearby = neighbors.mapTo(mutableSetOf()) { it.nodeId }
            val byNode = directory.byNode
            // `nearby` is folded in as well as `reachable` even though it is a subset of it in the transport:
            // the two arrive here on separate flows, so a peer can briefly be in one and not the other, and a
            // node that dropped out of the list for a frame would flicker the whole section.
            val nodeIds = (directory.peers.map { it.nodeId } + extra.reachable + nearby).toSet() - setOfNotNull(me)
            // The Internet plane (ADR 019) is a path to a peer only when that peer has *itself* put
            // something recent into the scope — the rule is [spoolPresentPeers], shared with the Profile
            // status line so the two screens cannot disagree about who is reachable (ADR 2026-09.2ajk).
            val viaSpool = spoolPresentPeers(extra.spools, clock())
            val nodes =
                nodeIds.map { id ->
                    val planes = extra.peerTransports[id].orEmpty()
                    val spooled = id in viaSpool
                    val reach = reachOf(id, nearby, extra.reachable, viaSpool)
                    NodeInfo(
                        nodeId = id,
                        displayName = directory.label(id).text,
                        reach = reach,
                        profileUpdatedAt = byNode[id]?.updatedAt?.takeIf { it > 0L },
                        // Short-range planes prove the peer's own radio was seen; long-range ones name the
                        // path a frame took. Showing them on the same row conflated the two, so each row
                        // shows only the kind its section is claiming.
                        transports =
                            when (reach) {
                                Reach.Direct -> planes.intersect(meshManager.shortRangeKinds)
                                Reach.Relay -> planes - meshManager.shortRangeKinds
                                Reach.Known -> emptySet()
                            },
                        viaSpool = spooled && reach == Reach.Relay,
                    )
                }
            val known = nodes.filter { it.reach == Reach.Known }.sortedWith(NEWEST_PROFILE_FIRST)
            DiagnosticsUiState(
                myNodeId = me.orEmpty(),
                myName = displayNameFor(myName, me.orEmpty()),
                directNodes = nodes.filter { it.reach == Reach.Direct }.sortedBy { it.displayName.lowercase() },
                relayNodes = nodes.filter { it.reach == Reach.Relay }.sortedBy { it.displayName.lowercase() },
                knownNodes = known.take(KNOWN_LIMIT),
                knownTotal = known.size,
                metrics = extra.metrics,
                transports = transportRows(extra.statuses, radios, extra.loraPlane),
                radios = radios,
                spools = extra.spools,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    companion object {
        /**
         * How many unreachable-but-known nodes the screen lists. The peer table holds everyone whose profile
         * ever arrived, and dumping all of it was the old "reachable via relay" bug in a new section — the
         * few most recent are the ones a field test is actually looking for.
         */
        const val KNOWN_LIMIT = 5

        private const val REFRESH_MS = 2_000L

        /**
         * Newest profile first, unknown profiles last, name as the tiebreak. `updatedAt` is the peer's own
         * profile **version** — the same number the row renders as "Profile 1h" — so the order matches what
         * the reader sees rather than introducing a second, invisible clock.
         */
        private val NEWEST_PROFILE_FIRST =
            compareByDescending<NodeInfo> { it.profileUpdatedAt ?: 0L }
                .thenBy { it.displayName.lowercase() }
    }
}
