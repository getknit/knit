package app.getknit.knit.wear

import android.content.Context
import androidx.wear.tiles.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A read of the phone that no caller waits on, for the surfaces that must answer at once: the tile (a tile
 * request that blocked on a ten-second GATT connect would time out) and the status screen's Refresh. One read
 * at a time; when it lands the tile is asked to redraw, and the complications too if the read was good (a
 * failed one would only send each of them to retry the same phone).
 */
object StatusRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = MutableStateFlow(false)

    /** True while a read started here is running — the tile and the app show "Reading…" meanwhile. */
    val reading: StateFlow<Boolean> = inFlight

    /** When the last forced read started or ended, whichever is later (the tile's click debounce). */
    @Volatile
    var lastForcedMs = 0L
        private set

    /** Starts a read unless one is running. [force] skips the 60 s cache and the failure floor — the user asked. */
    fun kick(
        context: Context,
        force: Boolean,
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        val app = context.applicationContext
        if (force) lastForcedMs = System.currentTimeMillis()
        val before = PhoneStatusReader.cached(app)?.fetchedAtMs
        scope.launch {
            val after =
                try {
                    PhoneStatusReader.read(app, maxAgeMs = if (force) 0 else PhoneStatusReader.FRESH_MS)
                } finally {
                    if (force) lastForcedMs = System.currentTimeMillis()
                    inFlight.value = false
                }
            if (after != null && after.fetchedAtMs != before) StatusComplicationService.requestUpdateAll(app)
            requestTileUpdate(app)
        }
    }

    fun requestTileUpdate(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(MeshTileService::class.java) }
    }
}
