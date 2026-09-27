package app.getknit.knit.mesh.wear

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.relay.planeFor
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.spoolPresentPeers
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearExtra
import app.getknit.knit.wearstatus.WearLink
import app.getknit.knit.wearstatus.WearStatus
import app.getknit.knit.wearstatus.WearStatusCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

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
    val peers: WearPeers = WearPeers(),
    val carrying: Int = 0,
)

/**
 * Who is reachable and over what, raw: [nearby] is [MeshController.neighbors], [reachable] is
 * [MeshController.reachable] (long-range included), [planes] is [MeshController.peerTransports], [spools] the
 * relay workers' status — the four inputs Diagnostics' sections are built from, so the watch's peer map and
 * that screen cannot disagree. Node ids never leave the phone: [WearStatusPolicy] turns these into masks.
 */
data class WearPeers(
    val nearby: Set<String> = emptySet(),
    val reachable: Set<String> = emptySet(),
    val planes: Map<String, Set<TransportKind>> = emptyMap(),
    val spools: List<SpoolStatus> = emptyList(),
)

/**
 * The inputs, from the flows the app's own surfaces already read — nothing new upstream. [inputs] is cold;
 * `WearStatusServer` collects it only while it is open. The relay status polls on its own 5 s ticker while
 * collected (see `RelayStatusRepository`), and the carrying count re-queries once a minute (its rows expire on
 * the clock, as on the Your mesh screen) — the standing costs of an open server.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
internal class WearStatusSource(
    settings: SettingsStore,
    mesh: MeshController,
    lora: LoraStatusRepository,
    relay: RelayStatusRepository,
    ledger: ContributionLedger,
    forward: ForwardRepository,
    identity: Identity,
    private val clock: () -> Long = System::currentTimeMillis,
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

    private val peers: Flow<WearPeers> =
        combine(mesh.neighbors, mesh.reachable, mesh.peerTransports, relay.statuses) { near, reach, planes, spools ->
            WearPeers(
                nearby = near.mapTo(mutableSetOf()) { it.nodeId },
                reachable = reach.mapTo(mutableSetOf()) { it.nodeId },
                planes = planes,
                spools = spools,
            )
        }

    private val carrying: Flow<Int> =
        flow { emit(identity.nodeId()) }.flatMapLatest { me ->
            flow {
                while (true) {
                    emit(Unit)
                    delay(CARRYING_REFRESH_MS)
                }
            }.flatMapLatest { forward.observeCarriedForOthers(me, clock()) }
        }

    val inputs: Flow<WearInputs> =
        combine(
            meshSide,
            combine(lora.facts.map { it.plane }, relay.facts.map(::planeFor)) { l, r -> l to r },
            ledger.totals.map { it.passedAlong },
            peers,
            carrying.onStart { emit(0) },
        ) { (enabled, paused, radio), (loraPlane, relayPlane), relayed, peers, carrying ->
            WearInputs(
                enabled = enabled,
                pausedUntil = paused,
                nearby = radio.first,
                statuses = radio.second,
                lora = loraPlane,
                relay = relayPlane,
                relayed = relayed,
                peers = peers,
                carrying = carrying,
            )
        }.distinctUntilChanged()

    private companion object {
        /** The Your mesh screen's cadence for the same count; the forward store's sweep is slower anyway. */
        const val CARRYING_REFRESH_MS = 60_000L
    }
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
                // What the phone holds is true whether or not its radios are up; who it reaches is not.
                extra = WearExtra(carrying = i.carrying, far = 0, links = emptyList()),
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
            extra = extra(i, nowMs),
        )
    }

    /**
     * The peer map. Short-range peers are Diagnostics' "Direct" section (their BLE / NAN planes); far ones its
     * "Reachable via relay" (a LoRa path, or the relay under [spoolPresentPeers]'s evidence rule) — the same
     * split, so a peer is never drawn nearer than that screen lists it. Sorted by node id so a peer keeps its
     * slot between reads; up to two slots go to far peers when there are any, so a crowd nearby never hides
     * that the long-range planes are carrying someone.
     */
    private fun extra(
        i: WearInputs,
        nowMs: Long,
    ): WearExtra {
        val p = i.peers
        val viaSpool = spoolPresentPeers(p.spools, nowMs)
        val direct = p.nearby.sorted().map { id -> shortRangeMask(p.planes[id].orEmpty()) }
        val far =
            ((p.reachable + viaSpool) - p.nearby).sorted().mapNotNull { id ->
                val mask =
                    (if (TransportKind.LoRa in p.planes[id].orEmpty()) WearLink.LORA else 0) or
                        (if (id in viaSpool) WearLink.SPOOL else 0)
                mask.takeIf { it != 0 }
            }
        val directSlots = WearStatusCodec.MAX_LINKS - minOf(far.size, FAR_RESERVED)
        val shown = direct.take(directSlots)
        return WearExtra(
            carrying = i.carrying,
            far = far.size,
            links = shown + far.take(WearStatusCodec.MAX_LINKS - shown.size),
        )
    }

    /**
     * A nearby peer's short-range planes. The planes map and the nearby set arrive on separate flows, so for
     * a moment a peer can be nearby with no plane known yet; it is drawn as Bluetooth, the plane every Knit
     * phone runs, rather than dropped (which would make the map disagree with the count).
     */
    private fun shortRangeMask(kinds: Set<TransportKind>): Int {
        val mask =
            (if (TransportKind.Bluetooth in kinds) WearLink.BLE else 0) or
                (if (TransportKind.WifiAware in kinds) WearLink.NAN else 0)
        return if (mask == 0) WearLink.BLE else mask
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

    /** Far peers always get up to this many of the map's slots. */
    private const val FAR_RESERVED = 2
}
