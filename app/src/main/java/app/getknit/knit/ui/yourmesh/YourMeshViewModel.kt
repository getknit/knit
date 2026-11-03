@file:OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest is an experimental kotlinx API

package app.getknit.knit.ui.yourmesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.TransportHealth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Your mesh screen shows: the phone's part in the mesh right now, and all time. */
data class YourMeshUiState(
    /** Phones in radio range now — the same number the chat list's header carries. */
    val nearbyCount: Int = 0,
    val health: TransportHealth = TransportHealth.Healthy,
    /** Messages held in custody for other people right now. */
    val carryingNow: Int = 0,
    /** Other people's messages this phone has ever sent on. */
    val passedAlong: Long = 0L,
    /** Of those, the ones it sent over a live link to the very person they were for. */
    val handedDirect: Long = 0L,
    /** Distinct phones this one has ever been in range of. */
    val peopleMet: Int = 0,
    /** The local clock at the first credit; 0 until there has been one (the lifetime block then reads "All time"). */
    val since: Long = 0L,
)

/**
 * Backs the read-only Your mesh screen from four sources that already exist for other reasons, so it can
 * never disagree with them: "nearby" is [MeshController.neighborCount] (what the header shows, ADR
 * 2026-09.2ajk), the lifetime numbers are [ContributionLedger.totals] (persisted plus what this session has
 * not banked yet, so a hand-off shows at once), "met" is the met-peers table, and "carrying now" is a live
 * `forward_store` count. Nothing here is polled from `MeshMetrics`.
 *
 * The carrying count is a Room flow, which re-emits on every custody write, but its `now` is fixed per
 * subscription; a ticker re-subscribes every [REFRESH_MS] so an expired-but-unswept row ages out of the
 * number between the 10-minute sweeps. The ticker lives inside the `stateIn`, so it stops with the screen.
 */
class YourMeshViewModel(
    meshManager: MeshController,
    ledger: ContributionLedger,
    metPeers: MetPeerRepository,
    forward: ForwardRepository,
    identity: Identity,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    private val ticker: Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(REFRESH_MS)
            }
        }

    private val carryingNow: Flow<Int> =
        myNodeId.filterNotNull().flatMapLatest { me ->
            ticker.flatMapLatest { forward.observeCarriedForOthers(me, clock()) }
        }

    val state: StateFlow<YourMeshUiState> =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            carryingNow,
            ledger.totals,
            metPeers.observeCount(),
        ) { nearby, health, carrying, totals, met ->
            YourMeshUiState(
                nearbyCount = nearby,
                health = health,
                carryingNow = carrying,
                passedAlong = totals.passedAlong,
                handedDirect = totals.deliveredToRecipient,
                peopleMet = met,
                since = totals.since,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), YourMeshUiState())

    companion object {
        /** How often the carrying count's `now` is refreshed — the 10-min sweep is the slower bound anyway. */
        const val REFRESH_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
