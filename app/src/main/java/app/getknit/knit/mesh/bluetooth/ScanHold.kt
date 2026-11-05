package app.getknit.knit.mesh.bluetooth

/**
 * A debug build's scan hold (`…debug.SCAN --ez hold true`, #285): while it stands, the gap after every presence window is
 * the hunt's ([ScanPlan.gapAfterWindow]'s `held`), so a trial phone scans densely whatever its links or its lonely clock
 * say. A trial forging blinded adverts from a dongle (ADR 2026-11.xp6d's phase-1 trials) counted about 7 handles out of a
 * 600-tag burst on a settled Pixel 8, whose scan sat at its floor, and on the same phone alone, whose scan relaxed to a
 * 300 s gap after three minutes.
 *
 * Bounded so a forgotten hold never drains a phone: it lasts [minutes] (30 unless asked, at most [MAX_MINUTES]) on the
 * clock the caller passes (the transport's elapsed one, which keeps running while the CPU sleeps), and ends at
 * [release] or at the transport's stop. Pure and locked: the bridge's thread starts and releases it, the scan loop reads
 * it and is the one that finds it [expire]d. Nothing here logs; the transport writes `bt scan hold on|off`.
 */
internal class ScanHold {
    private var until: Long? = null
    private var windows = 0

    /**
     * A [start]'s outcome: the [minutes] granted, and whether a hold already stood at `now` ([renewed]). Only a new hold
     * wakes the scan: the wake channel is conflated, so a wake per renewal adds a back-to-back window each time, and four
     * inside 30 s spend Android's five scan starts per 30 s (`scan failed: 6`).
     */
    data class Started(
        val minutes: Int,
        val renewed: Boolean,
    )

    /** Starts or renews the hold at [now] for [asked] minutes ([minutes]). */
    @Synchronized
    fun start(
        now: Long,
        asked: Int?,
    ): Started {
        val renewed = until?.let { now < it } == true
        val granted = minutes(asked)
        until = now + granted * MS_PER_MINUTE
        windows = 0
        return Started(granted, renewed)
    }

    /** Ends the hold now (a release or the transport's stop); returns whether one stood. */
    @Synchronized
    fun end(): Boolean {
        val stood = until != null
        until = null
        return stood
    }

    /** Ends the hold if its time ran out by [now]; returns true exactly once per hold that expired. */
    @Synchronized
    fun expire(now: Long): Boolean {
        val at = until ?: return false
        if (now < at) return false
        until = null
        return true
    }

    /** Whether the hold stands at [now]. */
    @Synchronized
    fun held(now: Long): Boolean = until?.let { now < it } == true

    /** How long the hold has left at [now], null when none stands. */
    @Synchronized
    fun remainingMs(now: Long): Long? = until?.takeIf { now < it }?.let { it - now }

    /** A gap was taken under the hold: counted, so a read shows the hold is in force window after window. */
    @Synchronized
    fun onHeldGap() {
        windows++
    }

    /** The gaps taken under the hold since it last started. */
    @Synchronized
    fun heldGaps(): Int = windows

    companion object {
        const val DEFAULT_MINUTES = 30
        const val MAX_MINUTES = 120
        private const val MS_PER_MINUTE = 60_000L

        /** The minutes a hold asked for [asked] gets: the default when unasked, at least one, at most [MAX_MINUTES]. */
        fun minutes(asked: Int?): Int = (asked ?: DEFAULT_MINUTES).coerceIn(1, MAX_MINUTES)
    }
}

/**
 * What `…debug.SCAN` reports: whether the hold stands and for how long more ([expiresInMs], null when not held), the
 * [minutes] this call granted (null unless it started one), and the
 * scan as the last gap left it: its [posture] ([ScanPlan.Posture.wire], null before the first window), the gap's [gapMs]
 * and whether it keeps wall time ([wallTime]), the next window's length ([windowMs]) and scan mode ([mode],
 * `balanced` or `low_power`, which the power state picks and the hold leaves alone), the gaps taken under the hold
 * ([heldGaps]), the links held and whether the last window hunted.
 */
internal data class ScanStatus(
    val held: Boolean,
    val expiresInMs: Long?,
    val minutes: Int?,
    val posture: String?,
    val gapMs: Long?,
    val wallTime: Boolean?,
    val windowMs: Long,
    val mode: String,
    val heldGaps: Int,
    val links: Int,
    val hunting: Boolean,
)

/**
 * The debug bridge's handle on the scan hold (#285), bound by [BluetoothMeshTransport] while it runs and only on a debug
 * build, the same shape as [LinkTuneDiag] and [AdvertDiag]: [hold] takes `true` to start or renew a hold for the minutes
 * asked (null for the default), `false` to release one, and null to only read. Null while the transport is not running.
 */
internal object ScanDiag {
    @Volatile var hold: ((Boolean?, Int?) -> ScanStatus)? = null
}
