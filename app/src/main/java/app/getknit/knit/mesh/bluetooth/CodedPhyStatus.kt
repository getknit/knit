package app.getknit.knit.mesh.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One sighted peer as the Coded PHY experiment sees it: its 1M-scale RSSI, each PHY's own smoothed advert RSSI, and
 * when each PHY last heard it.
 */
data class PhyPeerStatus(
    val nodeId: String,
    val smoothedRssi: Double,
    val oneMSeenAgoMs: Long?,
    val codedSeenAgoMs: Long?,
    val rssi1m: Double? = null,
    val rssiCoded: Double? = null,
)

/** The Coded PHY experiment right now (ADR 2026-10.yvn6), for `…debug.PHY`. */
data class CodedPhyStatus(
    val mode: CodedPhyMode,
    /** The controller has the Coded PHY and extended advertising (read at each bring-up). */
    val supported: Boolean,
    /** The Coded advert: `off`, `live`, `starting`, or `dark <status>` after a refused start. */
    val advert: String,
    val txPower: String,
    val links: List<PhyLinkStatus>,
    val peers: List<PhyPeerStatus>,
)

/**
 * The debug bridge's and Diagnostics' handle on the experiment, bound by [BluetoothMeshTransport] while it runs — the
 * same shape as the Wi-Fi Aware fault injector, so neither needs an `android.bluetooth` import or a transport
 * reference, and nothing is added to the `MeshTransport` seam for an experiment dark in release. [tuning] is read by
 * every link's [PhyStepper] on each decision and by the presence tracker's Coded credit; `…debug.PHY` overrides it on
 * a debug build.
 */
internal object CodedPhyDiag {
    @Volatile var status: (() -> CodedPhyStatus)? = null

    @Volatile var tuning: PhyTuning = PhyTuning()

    /** Re-raises the Coded advert at `high` or `medium` power; null while the transport is not running. */
    @Volatile var setTxPower: ((String) -> Boolean)? = null

    private val links = MutableStateFlow<Map<String, LinkPhy>>(emptyMap())

    /** The PHY each link with a PHY handle is on, by peer node id — Diagnostics' per-link chip. Empty while off. */
    val linkPhys: StateFlow<Map<String, LinkPhy>> = links.asStateFlow()

    /** The transport's write side of [linkPhys]. */
    fun publishLinkPhys(phys: Map<String, LinkPhy>) {
        links.value = phys
    }
}
