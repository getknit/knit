package app.getknit.knit.ui.devview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** One console line as the screen draws it. */
data class ConsoleRow(
    val time: String,
    val kind: String,
    val plane: ConsolePlane,
    val fields: String,
)

data class EventConsoleUiState(
    val rows: List<ConsoleRow> = emptyList(),
    // Null shows every plane.
    val plane: ConsolePlane? = null,
    val paused: Boolean = false,
    // How many the console holds, before the filter.
    val held: Int = 0,
)

/**
 * Backs the event console's screen (ADR 2026-10.s7wg): the [EventConsole]'s events, newest first, filtered by plane,
 * each peer by the reader's label for it. Paused, the screen keeps the events it showed while the console goes on
 * recording, so a line can be read without the list moving under it.
 */
class EventConsoleViewModel(
    private val console: EventConsole,
    peers: PeerRepository,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val plane = MutableStateFlow<ConsolePlane?>(null)

    // The events the screen shows while paused; null while live.
    private val frozen = MutableStateFlow<List<ConsoleEvent>?>(null)

    val state: StateFlow<EventConsoleUiState> =
        combine(console.version.map { console.snapshot() }, frozen, plane, peers.observeDirectory()) { live, held, only, directory ->
            val events = held ?: live
            EventConsoleUiState(
                rows = events.filter { only == null || ConsolePlane.of(it.event) == only }.map { row(it, directory) },
                plane = only,
                paused = held != null,
                held = events.size,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EventConsoleUiState())

    fun showPlane(only: ConsolePlane?) {
        plane.value = only
    }

    fun togglePause() {
        frozen.value = if (frozen.value == null) console.snapshot() else null
    }

    /** Empties the console itself, and lets a paused screen go live again. */
    fun clear() {
        console.clear()
        frozen.value = null
    }

    private fun row(
        e: ConsoleEvent,
        directory: PeerDirectory,
    ): ConsoleRow =
        ConsoleRow(
            time = ConsoleLine.time(e.at, zone()),
            kind = e.event.kind,
            plane = ConsolePlane.of(e.event),
            fields = ConsoleLine.fields(e.event) { id -> ConsoleLine.peer(directory.label(id).text, id) },
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
