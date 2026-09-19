package app.getknit.knit.mesh.lora

import kotlinx.serialization.Serializable

/**
 * Where the LoRa plane's rate limiters live between processes.
 *
 * Every limiter on this plane used to be process-scoped: the airtime ledger ([LoraAirtime]), the profile
 * beacon's floor, the gossip Trickle interval ([LoraGossipPolicy]), the per-publisher serve cap and the
 * 12-hour profile re-fan gate ([LoraMeshTransport.PROFILE_REFAN_MS]) all began each launch empty. A restart
 * therefore handed the plane a **fresh 45-second allowance** and re-ran every "first time" behaviour against
 * it — a self beacon, an OFFER at the Trickle floor rather than at whatever back-off the silence had earned,
 * and a re-offer batch per peer as each was heard again for the first time.
 *
 * That is not a lab artefact, it is the governor's floor falling out. The regional duty cycle is law rather
 * than politeness (ADR 067) and this ledger is the only thing enforcing it, so a crash loop, a force-stop or
 * a reinstall cycle spent air that nothing was accounting for — in the lab, where a reinstall lands many
 * times a day, continuously.
 *
 * The seam is an interface here rather than a store because `mesh/lora/` is Android-free: the implementation
 * is a JSON blob in the settings DataStore (`SettingsStore.loraPlaneState`), and every rule about what a
 * snapshot means is unit-testable without it.
 *
 * Three limiters are deliberately **not** here. [LoraMeshTransport.sigSeen] is the 10-minute "is this frame
 * in flight" window, and restoring it would carry a *transmitted* record across a restart — which on a plane
 * with no acks is not evidence anyone heard, the same reasoning that exempts `serveOne` from it (ADR
 * 2026-09.y8pu). `lastHeardAt` feeds `reachable`, which only fresh frames may write (ADR 2026-09.2ajk):
 * a peer restored from disk would be claimed as reachable on evidence this process never saw. Neither
 * matters much to the burst, because the ledger bounds what they would otherwise let through.
 *
 * The third is [LoraGossipPolicy]'s Trickle interval, and it was tried and taken back out. Persisting the
 * back-off stalls the ADR 044 gateway **election**: an OFFER is the only evidence anyone has a board,
 * `LoraGatewayPolicy` is *not* persisted (`quiesce` calls `forget`), and a restored interval that has since
 * run out makes `ensureInterval` double it and draw a fresh transmit point — so the first OFFER lands up to
 * 15 minutes out while every gateway in the pocket sits ACTIVE and fans out everything. Field-observed on
 * the lab P7/P9 after a fleet install (2026-09-10): `gossip=600000ms` restored, no OFFER in 90 s, both
 * `gatewaysHeard: 0` beside `boardsHeard: 1`. A fresh timer offers within 2.5–5 min instead, and the OFFER
 * it costs is charged to the window this file *does* persist — so a reinstall loop still cannot outspend
 * its allowance on gossip, which is all the back-off was buying.
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
    val selfProfileAtWall: Long? = null,
    val serve: List<ServeWindow> = emptyList(),
    val profileSeen: List<SeenStamp> = emptyList(),
    /** The Meshtastic nodes the DM auto-reply has answered inside its window, by `!hex` id ([DmAutoReplyPolicy]). */
    val autoReplied: List<SeenStamp> = emptyList(),
)

/** One packet already booked against the rolling window: what it cost, when, and out of whose share. */
@Serializable
internal data class AirBooking(
    val atWall: Long,
    val ms: Long,
    /** [AirBucket.name], not the enum: a bucket a newer build added is dropped on load rather than thrown on. */
    val bucket: String,
)

/** One far gateway's hourly serve allowance, keyed by its publisher digest. */
@Serializable
internal data class ServeWindow(
    val publisher: Long,
    val startWall: Long,
    val spent: Int,
)

/** One entry of a `SeenSet` that outlives the process — the profile re-fan gate's 12-hour memory. */
@Serializable
internal data class SeenStamp(
    val id: String,
    val atWall: Long,
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
