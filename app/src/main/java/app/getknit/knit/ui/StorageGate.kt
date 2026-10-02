package app.getknit.knit.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.getknit.knit.data.crypto.keystoreUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Opens what the app's first frame reads — the database, the identity keys and, through them, the mesh graph —
 * on a worker before `KnitApp` composes, and says whether it could (ADR 2026-10.47rw).
 *
 * Two reasons it is a gate rather than `KnitApp` resolving them in its first composition, as it used to. That
 * read ran on the main thread, and a Keystore refusal now costs [app.getknit.knit.data.crypto.KeystoreSecret]'s
 * retries (about two seconds) before it gives up. And a refusal is no longer a wipe but a
 * [app.getknit.knit.data.crypto.KeystoreUnavailableException]: thrown from a composition it would be a crash on
 * every open, which reads as data loss and invites Clear storage — the very wipe the change exists to prevent.
 * Here it is [State.Unavailable], which `MainActivity` shows as a Try again screen, and every open — the button,
 * the next resume — tries again. Any other failure is re-thrown on the main thread, as a failed graph always was.
 *
 * A process singleton, so the wedge watchdog's `recreate()` and a configuration change find it already [State.Ready].
 * [openStorage] is the Koin reads; Koin caches nothing a factory failed to build, so a retry rebuilds.
 */
class StorageGate(
    private val scope: CoroutineScope,
    private val openStorage: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val crash: (Throwable) -> Unit = ::rethrowOnMain,
) {
    sealed interface State {
        /** The first open is running; nothing is drawn yet. */
        data object Opening : State

        /** Everything the app reads is open. Terminal. */
        data object Ready : State

        /** The Keystore refused; [trying] while a retry runs. */
        data class Unavailable(
            val trying: Boolean,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Opening)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Opens, unless already open or already opening. Safe to call from every resume and every tap. */
    @Synchronized
    fun open() {
        if (_state.value == State.Ready || job?.isActive == true) return
        if (_state.value is State.Unavailable) _state.value = State.Unavailable(trying = true)
        job =
            scope.launch(io) {
                runCatching { openStorage() }
                    .onSuccess { _state.value = State.Ready }
                    .onFailure { failure ->
                        if (failure.keystoreUnavailable() != null) {
                            Log.w(TAG, "storage not opened: the Keystore refused", failure)
                            _state.value = State.Unavailable(trying = false)
                        } else {
                            crash(failure)
                        }
                    }
            }
    }

    private companion object {
        const val TAG = "StorageGate"
    }
}

/** Past the app scope's log-only handler, so a graph that cannot be built is the crash `CrashHandler` records. */
private fun rethrowOnMain(failure: Throwable) {
    Handler(Looper.getMainLooper()).post { throw failure }
}
