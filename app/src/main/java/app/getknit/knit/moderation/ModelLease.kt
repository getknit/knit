package app.getknit.knit.moderation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A bundled model's engine, held only while it is being used. Both moderators used to own a mutex, a
 * `loaded` flag and a nullable engine each, and kept the engine for the life of the process — about 32 MB
 * resident in the foreground service for a classifier that runs once per message (work item #68). This is
 * that trio in one place, plus the reaper that lets go.
 *
 * Three states, every transition under [mutex]:
 *
 * - **Unloaded** (`loaded = false`): the next [use] runs [load]. `loaded` is set only *after* [load]
 *   returns — the load suspends inside [ModelLoadGuard] (two DataStore round trips), and a caller cancelled
 *   there (Back out of a chat, a link-preview fetch timing out) must leave the next caller free to try
 *   again, not a process with screening silently off.
 * - **Resident** (`loaded`, `engine != null`): [use] hands the engine to its block and stamps [lastUsedAt].
 *   A reaper on [scope] sleeps until [idleMs] have passed since the last use, then closes the engine under
 *   the mutex and drops back to unloaded, so the next use reloads — through [load], which is the guarded
 *   path, so the poison-pill decides again.
 * - **Spent** (`loaded`, `engine == null`): [load] returned nothing — a missing asset, a Java-level failure,
 *   or a model [ModelLoadGuard] has latched off. Permanent for the process: no reaper is armed, nothing
 *   retries, and that is exactly how the latch survives an unload — only a *real* engine is ever released.
 *
 * [block] is not a suspend lambda on purpose: the mutex is never held across a suspension, and the
 * interpreter is not thread-safe, so closing it must serialise behind any inference the same way.
 */
class ModelLease<T : Any>(
    private val scope: CoroutineScope,
    private val idleMs: Long = DEFAULT_IDLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val load: suspend () -> T?,
    private val close: (T) -> Unit,
) {
    init {
        require(idleMs > 0) { "idleMs must be positive" }
    }

    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    @Volatile
    private var engine: T? = null

    @Volatile
    private var lastUsed = 0L

    /** Guarded by [mutex]. One per residency; cancelled before its successor is armed. */
    private var reaper: Job? = null

    /**
     * Whether a load has been attempted and not since released: resident **or** spent. This is the flag
     * `warmUp()` gates on, so a warm-up after an idle unload reloads and one after a failed load does not.
     */
    val isLoaded: Boolean get() = loaded

    /** Whether an engine is in memory right now. */
    val isResident: Boolean get() = engine != null

    /** The clock reading of the last [use] (or load), `0` before the first. */
    val lastUsedAt: Long get() = lastUsed

    /**
     * Runs [block] with the engine, or with `null` when there is none to be had; loads first when the lease
     * is unloaded. Serialised behind [mutex] with every other use and with the reaper's close.
     */
    suspend fun <R> use(block: (T?) -> R): R =
        mutex.withLock {
            if (!loaded) acquire()
            lastUsed = clock()
            block(engine)
        }

    /**
     * Close the engine now and return to unloaded, so the next [use] reloads through [load]. The debug
     * bridge's fast path through the idle cycle, and how a test proves the reload. A spent lease stays spent.
     */
    suspend fun unload() {
        mutex.withLock {
            reaper?.cancel()
            reaper = null
            engine?.let(::release)
        }
    }

    /** Under [mutex]. Cancellation propagates with `loaded` still false; any other failure spends the lease. */
    private suspend fun acquire() {
        val result =
            try {
                load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The loaders already swallow their own failures; this keeps a caller's no-throw promise
                // (classify sits on the inbound path) even if one ever leaks.
                null
            }
        loaded = true
        engine = result ?: return
        lastUsed = clock()
        reaper?.cancel()
        reaper = scope.launch { reap() }
    }

    /**
     * Sleep until the engine has been idle for [idleMs], then release it. Re-checks under the mutex because
     * a use can land while it sleeps; the sleep is clamped to [idleMs] so a wall clock stepped backwards
     * cannot park it for hours (stepped forwards, it merely releases early). Cannot throw: the app scope's
     * handler would log it as a mesh failure.
     */
    private suspend fun reap() {
        while (true) {
            delay((lastUsed + idleMs - clock()).coerceIn(1L, idleMs))
            val done =
                mutex.withLock {
                    val current = engine ?: return@withLock true
                    if (clock() - lastUsed < idleMs) return@withLock false
                    release(current)
                    true
                }
            if (done) return
        }
    }

    /** Under [mutex]. A close that throws still drops the engine: holding a broken one helps nobody. */
    private fun release(current: T) {
        runCatching { close(current) }
        engine = null
        loaded = false
    }

    companion object {
        /** Ten quiet minutes. Long enough that a conversation keeps its engine; short enough to matter overnight. */
        const val DEFAULT_IDLE_MS = 10 * 60_000L
    }
}
