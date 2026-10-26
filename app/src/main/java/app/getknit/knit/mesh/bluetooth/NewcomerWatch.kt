package app.getknit.knit.mesh.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Bluetooth plane watches for newcomers (`MeshTransport.seesNewcomers`, GitHub #44, ADR 2026-10.2t49): true
 * only while the last presence window's scan ran its length registered, with the adapter on. A dormant Aware plane
 * counts on it to wake for a newcomer, so a blind plane must say so at once.
 *
 * - **An off edge** ([off]: adapter off, `stop()`) writes false now, not at the window's end, and bumps the
 *   generation, so a window that [begin]s before it can never write true when it [end]s: the adapter receiver runs on
 *   the main thread and the window ends on the scan loop's, and a late end would otherwise undo the edge.
 * - **A window that began after the last off edge** writes whether it ran clean, true or false.
 * - **An unchanged value** neither emits nor calls [onChange].
 *
 * The value, its [StateFlow] and the [onChange] report all move under one lock, so the flow and the log lines follow
 * the order the decisions were taken in: a mirror written after the lock was let go could land a stale value over a
 * newer one. Pure; JVM-tested ([app.getknit.knit.NewcomerWatchTest]).
 */
internal class NewcomerWatch(
    /** Called under the lock on each change, with the off edge's reason (or [NOT_REGISTERED], or "" for true). */
    private val onChange: (sees: Boolean, why: String) -> Unit = { _, _ -> },
) {
    /** A window's claim to write at its end, taken when it began ([begin]); stale once an off edge has passed. */
    @JvmInline
    value class Window internal constructor(
        internal val gen: Int,
    )

    // `internal` so the test can pin that [onChange] runs while it is held.
    internal val lock = Any()
    private var gen = 0
    private val _sees = MutableStateFlow(false)

    /** The plane watches for newcomers; false until a clean window ends. */
    val sees: StateFlow<Boolean> = _sees.asStateFlow()

    /** A presence window begins: the token its [end] hands back. */
    fun begin(): Window = synchronized(lock) { Window(gen) }

    /**
     * The presence [window] ended, its scan registered throughout or not ([ranClean]). Writes [ranClean] unless an off
     * edge came after the window began, which has the last word. Returns whether the value changed.
     */
    fun end(
        window: Window,
        ranClean: Boolean,
    ): Boolean =
        synchronized(lock) {
            window.gen == gen && set(ranClean, if (ranClean) "" else NOT_REGISTERED)
        }

    /** An off edge ([why]): false now, and no window that began before it may write true. Returns whether it changed. */
    fun off(why: String): Boolean =
        synchronized(lock) {
            gen++
            set(false, why)
        }

    // Callers hold [lock].
    private fun set(
        sees: Boolean,
        why: String,
    ): Boolean {
        if (_sees.value == sees) return false
        _sees.value = sees
        onChange(sees, why)
        return true
    }

    companion object {
        /** A window's end found its scan no longer registered (`onScanFailed`, or the adapter going off under it). */
        const val NOT_REGISTERED = "scan not registered"
    }
}
