package app.getknit.knit.mesh.bluetooth

/** One sighted peer as the Coded PHY experiment sees it: its 1M-scale RSSI and when each PHY last heard it. */
data class PhyPeerStatus(
    val nodeId: String,
    val smoothedRssi: Double,
    val oneMSeenAgoMs: Long?,
    val codedSeenAgoMs: Long?,
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
 * The debug bridge's handle on the experiment, bound by [BluetoothMeshTransport] while it runs — the same shape as
 * the Wi-Fi Aware fault injector, so the receiver needs no `android.bluetooth` import and no transport reference.
 * [tuning] is read by every link's [PhyStepper] on each decision; `…debug.PHY` overrides it on a debug build.
 */
internal object CodedPhyDiag {
    @Volatile var status: (() -> CodedPhyStatus)? = null

    @Volatile var tuning: PhyTuning = PhyTuning()

    /** Re-raises the Coded advert at `high` or `medium` power; null while the transport is not running. */
    @Volatile var setTxPower: ((String) -> Boolean)? = null
}
