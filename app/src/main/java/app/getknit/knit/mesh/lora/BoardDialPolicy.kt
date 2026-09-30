package app.getknit.knit.mesh.lora

/**
 * How [MeshtasticSession] dials a board it has lost. A **direct** dial is `connectGatt(autoConnect = false)`: the
 * controller scans at high duty for the whole connect timeout, and the dial holds `BleConnectArbiter`, which pauses
 * the mesh's presence scan for that time. A **background** dial is `connectGatt(autoConnect = true)`: the board's
 * address sits on the controller's accept list and the controller connects the moment the board advertises,
 * scanning at low duty and pausing nothing.
 */
internal enum class DialMode { Direct, Background }

/**
 * Picks the [DialMode] for the next dial (ADR 2026-09.hp88). A dropped board is usually a brief blip — a reboot,
 * a pocket, a wall — so the first [DIRECT_ATTEMPTS] dials are direct and fast. After that the board is taken to
 * be off or out of range, and the controller waits for it in the background instead of the session
 * direct-dialling every ~210 s forever: a 30 s high-duty connect that also blacked out the mesh scan (#67).
 *
 * autoConnect is slow or never fires on some stacks — it needs the address in the stack's cache, which a
 * Bluetooth restart can clear — so one direct dial every [DIRECT_NET_MS] stays as the net under it.
 *
 * Pure and clock-free (the session passes `now`), like the backoff curve it sits beside.
 */
internal object BoardDialPolicy {
    /** Failed direct dials in a row before the controller takes over the wait. */
    const val DIRECT_ATTEMPTS = 3

    /** One direct dial this often while waiting in the background, for stacks whose autoConnect never fires. */
    const val DIRECT_NET_MS = 60 * 60_000L

    /**
     * A session held Ready this long resets the failure streak, so the next drop starts again at the short,
     * direct end of the curve. Without it, only a board reboot reset the streak, and after days of drops the
     * wait sat at the ceiling even after hours of good link.
     */
    const val HEALTHY_SESSION_MS = 5 * 60_000L

    /** The mode for the dial after [streak] failures in a row, given the last direct dial was at [lastDirectAt]. */
    fun mode(
        streak: Int,
        now: Long,
        lastDirectAt: Long?,
    ): DialMode =
        when {
            streak < DIRECT_ATTEMPTS -> DialMode.Direct
            lastDirectAt == null || now - lastDirectAt >= DIRECT_NET_MS -> DialMode.Direct
            else -> DialMode.Background
        }

    /** How long a background dial may wait: until the net's direct dial is due. */
    fun backgroundWindowMs(
        now: Long,
        lastDirectAt: Long?,
    ): Long = if (lastDirectAt == null) 0L else (lastDirectAt + DIRECT_NET_MS - now).coerceAtLeast(0L)

    /** Whether a session that went Ready at [readyAt] and ended at [endedAt] earns a fresh streak. */
    fun healthy(
        readyAt: Long?,
        endedAt: Long,
    ): Boolean = readyAt != null && endedAt - readyAt >= HEALTHY_SESSION_MS
}
