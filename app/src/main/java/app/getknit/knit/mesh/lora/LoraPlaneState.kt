package app.getknit.knit.mesh.lora

import kotlinx.serialization.Serializable

/**
 * Where the LoRa plane's persisted limiters live between processes.
 *
 * Every limiter on this plane used to be process-scoped, so a restart re-ran every "first time" behaviour (ADR
 * 2026-09.7svb). What persists now is the two that a restart would reset: the DM auto-reply's once-a-day memory (the
 * cap per sender, across a restart; the board hands a packet to the phone once, so what comes at reconnect is only
 * what queued while the phone was away — ADR 2026-09.4n5p's read-once amendment) and the
 * re-probe's backoff — the one schedule on this plane that airs without a user action. The airtime ledger is **not**
 * persisted any more (ADR 2026-10.ug4a): the regional duty cycle is the firmware's to enforce (`Router::send`'s
 * `DUTY_CYCLE_LIMIT`), and once the beacon, the bridge and the gossip were gone (ADR 2026-10.rp3s) nothing autonomous
 * was left for a fresh window to hand out but the re-probe, which its own persisted backoff bounds. The beacon floor,
 * the serve caps and the profile re-fan gate went with the traffic they limited (ADR 2026-10.rp3s), the probe window
 * and the re-offer's hour with the reach gate (ADR 2026-10.x9fa); a blob an older build wrote — its ledger included —
 * still loads, its dropped keys ignored by the store.
 *
 * The seam is an interface here rather than a store because `mesh/lora/` is Android-free: the implementation
 * is a JSON blob in the settings DataStore (`SettingsStore.loraPlaneState`), and every rule about what a
 * snapshot means is unit-testable without it.
 *
 * Two pieces of state are deliberately **not** here. [LoraMeshTransport.sigSeen] is the fan-out's "already aired
 * while fresh" memory ([LoraFanoutSeen]), and restoring it would carry a *transmitted* record across a restart — which on a plane with no
 * acks is not evidence anyone heard (ADR 2026-09.y8pu). What it would have stopped, an own room post re-served back to
 * us after a restart and re-aired, the fan-out refuses on its own: a post written before this process started is not
 * one the user just typed (ADR 2026-10.ug4a). `lastHeardAt` feeds `reachable`, which only fresh frames may write (ADR
 * 2026-09.2ajk): a peer restored from disk would be claimed as reachable on evidence this process never saw.
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
 * The plane's limiter state as it stood at [savedAtWall], stamped in **wall-clock** milliseconds. The ledger (`air`) an
 * older build wrote is ignored on load (ADR 2026-10.ug4a): the store's `ignoreUnknownKeys` reads the rest of that blob.
 *
 * Wall clock rather than the monotonic clock the policies themselves run on, because that one is
 * `elapsedRealtime`: it restarts at zero every boot, so a stamp persisted in it reads as the far future on
 * the next boot and would age nothing out for as long as the device stayed up. [WallShift] reconciles the
 * two at each end, so no policy ever leaves its own domain.
 */
@Serializable
internal data class LoraPlaneSnapshot(
    val savedAtWall: Long,
    /** The Meshtastic nodes the DM auto-reply has answered inside its window, by `!hex` id ([DmAutoReplyPolicy]). */
    val autoReplied: List<SeenStamp> = emptyList(),
    /**
     * The re-probe's backoff per peer with own DMs unacked ([LoraReprobe], ADR 2026-10.rp3s, ADR 2026-10.x9fa). Defaulted.
     * The probe window (`dmProbes`) and the re-offer's hour (`reoffered`) a Stage-1 build wrote went with the reach
     * gate; the store ignores those keys, so its blob still loads.
     */
    val reprobes: List<ReprobeStamp> = emptyList(),
)

/** One stamped id that outlives the process — the auto-reply's memory. */
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
