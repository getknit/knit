package app.getknit.knit.mesh.wear

import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.relay.planeFor
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Everything the watch snapshot is made of, as last seen. Kept raw (the pause is a deadline, not a flag) so
 * [WearStatusPolicy] decides against the clock at read time: a pause that lapsed since the last emission
 * reads as running, and the stamp is the moment of the read, not of the last input change.
 */
data class WearInputs(
    val enabled: Boolean,
    val pausedUntil: Long?,
    val nearby: Int,
    val statuses: List<TransportStatus>,
    val lora: LoraPlane,
    val relay: RelayPlane,
    val relayed: Long,
)

/**
 * The inputs, from the flows the app's own surfaces already read — nothing new upstream. [inputs] is cold;
 * `WearStatusServer` collects it only while it is open. The relay facts poll on their own 5 s ticker while
 * collected (see `RelayStatusRepository`), which is the one standing cost of an open server.
 */
internal class WearStatusSource(
    settings: SettingsStore,
    mesh: MeshController,
    lora: LoraStatusRepository,
    relay: RelayStatusRepository,
    ledger: ContributionLedger,
) {
    private val meshSide: Flow<Triple<Boolean, Long?, Pair<Int, List<TransportStatus>>>> =
        combine(settings.meshEnabled, settings.meshPausedUntil, mesh.neighborCount, mesh.transportStatuses) {
            enabled,
            paused,
            nearby,
            statuses,
            ->
            Triple(enabled, paused, nearby to statuses)
        }

    val inputs: Flow<WearInputs> =
        combine(
            meshSide,
            lora.facts.map { it.plane },
            relay.facts.map(::planeFor),
            ledger.totals.map { it.passedAlong },
        ) { (enabled, paused, radio), loraPlane, relayPlane, relayed ->
            WearInputs(
                enabled = enabled,
                pausedUntil = paused,
                nearby = radio.first,
                statuses = radio.second,
                lora = loraPlane,
                relay = relayPlane,
                relayed = relayed,
            )
        }.distinctUntilChanged()
}

/** Pure: [WearInputs] and a wall clock in, the snapshot the watch draws out. */
object WearStatusPolicy {
    fun of(
        i: WearInputs,
        nowMs: Long,
    ): WearStatus {
        val stampSec = nowMs / MS_PER_S
        val state =
            when {
                !i.enabled -> MeshState.Off
                MeshPause.activeDeadline(i.pausedUntil, nowMs) != null -> MeshState.Paused
                else -> null
            }
        // A stopped transport keeps reporting its last health (the MeshOffBanner rule in ChatListViewModel),
        // so a mesh that is down reports no radio at all rather than a stale "healthy".
        if (state != null) {
            return WearStatus(
                state = state,
                nearby = 0,
                ble = Plane.Absent,
                nan = Plane.Absent,
                lora = Plane.Absent,
                spool = Plane.Absent,
                relayed = i.relayed,
                stampSec = stampSec,
            )
        }
        val shortRange = i.statuses.filter { it.kind == TransportKind.Bluetooth || it.kind == TransportKind.WifiAware }
        return WearStatus(
            state = runningState(i.nearby, shortRange),
            nearby = i.nearby,
            ble = planeOf(i.statuses, TransportKind.Bluetooth),
            nan = planeOf(i.statuses, TransportKind.WifiAware),
            lora = i.lora.toPlane(),
            spool = i.relay.toPlane(),
            relayed = i.relayed,
            stampSec = stampSec,
        )
    }

    /**
     * Peers in range win outright — that is the one thing the wearer most wants to know. Otherwise the best
     * short-range radio decides, by the same ranking `CompositeMeshTransport` merges health with. LoRa is left
     * out on purpose: a board alone is not "nearby" (ADR 2026-09.2ajk), and its own plane letter says it is up.
     */
    private fun runningState(
        nearby: Int,
        shortRange: List<TransportStatus>,
    ): MeshState {
        if (nearby > 0) return MeshState.Linked
        val healths = shortRange.map { it.health }.toSet()
        return when {
            TransportHealth.Healthy in healths -> MeshState.Alone
            TransportHealth.ForegroundOnly in healths || TransportHealth.Degraded in healths -> MeshState.Degraded
            else -> MeshState.NoRadio
        }
    }

    private fun planeOf(
        statuses: List<TransportStatus>,
        kind: TransportKind,
    ): Plane =
        when (statuses.firstOrNull { it.kind == kind }?.health) {
            null -> Plane.Absent
            TransportHealth.Healthy -> Plane.Live
            TransportHealth.ForegroundOnly, TransportHealth.Degraded -> Plane.Degraded
            TransportHealth.Unavailable -> Plane.Down
        }

    private fun LoraPlane.toPlane(): Plane =
        when (this) {
            LoraPlane.Off -> Plane.Absent
            LoraPlane.Down -> Plane.Down
            LoraPlane.Live -> Plane.Live
        }

    private fun RelayPlane.toPlane(): Plane =
        when (this) {
            RelayPlane.Off -> Plane.Absent
            RelayPlane.Down -> Plane.Down
            RelayPlane.Live -> Plane.Live
        }

    private const val MS_PER_S = 1_000L
}
