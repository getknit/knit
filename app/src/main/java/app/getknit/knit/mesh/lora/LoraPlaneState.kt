package app.getknit.knit.mesh.lora

import kotlinx.serialization.Serializable

/**
 * Where the LoRa plane's rate limiters live between processes.
 *
 * Every limiter on this plane used to be process-scoped, so a restart handed the plane a **fresh 45-second
 * allowance** and re-ran every "first time" behaviour against it. The regional duty cycle is law rather than
 * politeness (ADR 067) and the airtime ledger is the only thing enforcing it, so a crash loop, a force-stop or a
 * reinstall cycle spent air that nothing was accounting for — in the lab, where a reinstall lands many times a day,
 * continuously (ADR 2026-09.7svb). What persists now: the ledger, the DM auto-reply's memory, the reach gate's probe
 * window, the re-offer's per-frame hour and the re-probe's backoff. The beacon floor, the serve caps and the profile
 * re-fan gate went with the traffic they limited (ADR 2026-10.rp3s); a blob an older build wrote still loads, its
 * dropped keys ignored by the store.
 *
 * The seam is an interface here rather than a store because `mesh/lora/` is Android-free: the implementation
 * is a JSON blob in the settings DataStore (`SettingsStore.loraPlaneState`), and every rule about what a
 * snapshot means is unit-testable without it.
 *
 * Two pieces of state are deliberately **not** here. [LoraMeshTransport.sigSeen] is the 10-minute "is this frame
 * in flight" window, and restoring it would carry a *transmitted* record across a restart — which on a plane with no
 * acks is not evidence anyone heard (ADR 2026-09.y8pu). `lastHeardAt` feeds `reachable`, which only fresh frames may
 * write (ADR 2026-09.2ajk): a peer restored from disk would be claimed as reachable on evidence this process never
 * saw. Neither matters much to the burst, because the ledger bounds what they would otherwise let through.
 */
internal interface LoraPlaneState {
    /** The last snapshot written, or null on a first run — or when it could not be read or parsed. */
    suspend fun load(): LoraPlaneSnapshot?

    suspend fun save(snapshot: LoraPlaneSnapshot)

    /** The no-op store: what a test — or any caller with nowhere to put a snapshot — runs against. */
    object None : LoraPlaneState {
        override suspend fun load(): LoraPlaneSnapshot? = null

        override suspend fun save(snapshot: LoraPlaneSnapshot) = Unit
    }
}

/**
 * The plane's limiter state as it stood at [savedAtWall], stamped in **wall-clock** milliseconds.
 *
 * Wall clock rather than the monotonic clock the policies themselves run on, because that one is
 * `elapsedRealtime`: it restarts at zero every boot, so a stamp persisted in it reads as the far future on
 * the next boot and would age nothing out for as long as the device stayed up. [WallShift] reconciles the
 * two at each end, so no policy ever leaves its own domain.
 */
@Serializable
internal data class LoraPlaneSnapshot(
    val savedAtWall: Long,
    val air: List<AirBooking> = emptyList(),
    /** The Meshtastic nodes the DM auto-reply has answered inside its window, by `!hex` id ([DmAutoReplyPolicy]). */
    val autoReplied: List<SeenStamp> = emptyList(),
    /**
     * The recipients a DM was last put on the air to as a probe, inside the probe window, by node id
     * ([LoraDmProbe], ADR 2026-10.a4pm). Defaulted, so a blob an older build wrote loads with no probes spent.
     */
    val dmProbes: List<SeenStamp> = emptyList(),
    /** The DM-form frames the first-hearing re-offer aired within its hour, by frame id (ADR 2026-10.a4pm). Defaulted. */
    val reoffered: List<SeenStamp> = emptyList(),
    /** The re-probe's backoff per silent recipient ([LoraReprobe], ADR 2026-10.rp3s). Defaulted. */
    val reprobes: List<ReprobeStamp> = emptyList(),
)

/** One packet already booked against the rolling window: what it cost, when, and out of whose share. */
@Serializable
internal data class AirBooking(
    val atWall: Long,
    val ms: Long,
    /**
     * [AirBucket.name], not the enum: a bucket a newer build added is dropped on load rather than thrown on, and a
     * retired one is mapped by [AirBucket.restoredFrom].
     */
    val bucket: String,
)

/** One stamped id that outlives the process — the auto-reply's memory, a probe's window, a re-offer's hour. */
@Serializable
internal data class SeenStamp(
    val id: String,
    val atWall: Long,
)

/** One recipient's re-probe schedule ([LoraReprobe.Entry]), its due time on the wall clock. */
@Serializable
internal data class ReprobeStamp(
    val id: String,
    val dueAtWall: Long,
    val gapMs: Long,
    val lastFrameId: String? = null,
)

/**
 * Converts between the monotonic clock the plane's policies run on and the wall clock a snapshot is written
 * in, using the offset between the two *right now*. An age measured in one is the same age in the other, and
 * an age is all any of these limiters ever asks for.
 *
 * A stamp restored onto a device that has just booted can land before zero. That is deliberate and harmless
 * — every limiter here compares differences, never absolutes — and it is self-clearing anyway, since an age
 * that large is past every window on this plane.
 */
internal class WallShift(
    private val nowMono: Long,
    private val nowWall: Long,
) {
    fun toWall(mono: Long): Long = nowWall - (nowMono - mono)

    fun toMono(wall: Long): Long = nowMono - (nowWall - wall)
}
