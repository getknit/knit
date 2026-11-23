package app.getknit.knit.mesh.bluetooth

/**
 * One session token shared by the Bluetooth plane's start, stop, adapter `STATE_ON` and link registration, so a stopped
 * plane stays stopped whatever reaches it after `stop()` (#292, ADR 2026-09.wz99's "Not covered").
 *
 * `BluetoothMeshTransport.start()` does its work in one launch that suspends (the node id, the PHY mode, the blind
 * switch, a worker hop) before it registers receivers, brings the radio up and launches the loops, while `stop()` is
 * synchronous and cancels only the jobs already assigned. A notification Pause runs stop, start, stop inside about
 * 100 ms (#292's note), and on the Pixel 9 the start body lost that race: it raised the plane after the second stop,
 * with no session to consume its links, and the next start overwrote the job handles so the orphaned loops outlived
 * every later stop until the process died.
 *
 * So [open] (in `start()`, synchronously) hands out a token, [close] (the first statement of `stop()`) ends it, and
 * every step that raises the plane runs inside [ifLive] with the token it was started under: under this monitor, only
 * while the plane is live on that very session. A [close] on another thread waits for a raise already in progress, so
 * once it returns the stop that follows sees every job the raise assigned; a raise that comes after a [close], or after
 * a newer [open], never runs.
 *
 * Nothing calls [ifLive] or [close] while holding another lock of the plane (the transport's backoff `lock`, `servers`,
 * the writer's, the side channel's, `ScanHold`'s), and nothing inside a raise suspends, blocks on the main thread or
 * waits for the worker: the raise takes those locks inside this monitor, never the other way round. Pure, no
 * `android.*`; `StartFenceTest`.
 */
internal class StartFence {
    private val monitor = Any()
    private var live = false
    private var session = 0L

    /** A new session starts: the plane is live, and every older token is spent. */
    fun open(): Long =
        synchronized(monitor) {
            live = true
            ++session
        }

    /** The plane stopped: no token raises anything until the next [open]. Waits for a raise in progress to finish. */
    fun close() {
        synchronized(monitor) { live = false }
    }

    /** Whether the plane is live on any session. */
    val isLive: Boolean get() = synchronized(monitor) { live }

    /** Whether [token] is the live session's. */
    fun isCurrent(token: Long): Boolean = synchronized(monitor) { live && session == token }

    /** The live session's token, or null while the plane is stopped. */
    fun liveToken(): Long? = synchronized(monitor) { if (live) session else null }

    /**
     * Runs [raise] under the monitor only while [token] is the live session's; true when it ran. [raise] must not
     * suspend, block on another thread's progress or take this fence again.
     */
    fun ifLive(
        token: Long,
        raise: () -> Unit,
    ): Boolean =
        synchronized(monitor) {
            if (!live || session != token) return false
            raise()
            true
        }

    companion object {
        /** A token no session ever holds ([open] starts at 1): what work that began while the plane was stopped carries. */
        const val NO_SESSION = 0L
    }
}
