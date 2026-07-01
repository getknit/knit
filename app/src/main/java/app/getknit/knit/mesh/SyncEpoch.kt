package app.getknit.knit.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Monotonic version of this node's syncable state — bumped whenever the forward-store gains a message
 * ([ForwardSync.onSeen]) or this device's own profile changes. The Wi-Fi Aware **cue plane**
 * (`WifiAwareTransport`) advertises it over the tiny no-data-path message channel so a neighbor can tell,
 * without bringing up a scarce NAN data path, when we hold something new and a data-path sync is worth it.
 *
 * Owned as a DI singleton — *not* inside `MeshManager`, which depends on the transport — so both the
 * transport (reads [state] and re-cues on change) and `MeshManager` ([bump] on store/profile writes) can
 * share one instance without a construction cycle. A plain counter is enough: peers compare it to the value
 * at their last completed sync (see [CueTracker]); it never needs to be meaningful across restarts.
 */
class SyncEpoch {
    private val _state = MutableStateFlow(0L)
    val state: StateFlow<Long> = _state.asStateFlow()

    /** Advance the epoch; the transport re-cues neighbors on the change. */
    fun bump() = _state.update { it + 1 }
}
