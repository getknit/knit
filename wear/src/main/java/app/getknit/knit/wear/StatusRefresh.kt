package app.getknit.knit.wear

import android.content.Context
import androidx.wear.tiles.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A read of the phone that no caller waits on, for every surface: a complication or tile request must answer
 * at once and draws the cache, and the app's Refresh must not block its screen. The read itself runs in
 * [StatusReadJob], the one place the freezer cannot cut it short. One read at a time; when it lands the tile is
 * asked to redraw, and the complications too if it changed what they show — a good read, or one that found no
 * phone, which turns an aged reading into "Phone out of reach". Neither starts another read: a fresh cache and
 * the failure floor both leave [PhoneStatusReader.due] false.
 */
object StatusRefresh {
    private val inFlight = MutableStateFlow(false)

    /** True while a read started here is pending or running — the tile and the app show "Reading…" meanwhile. */
    val reading: StateFlow<Boolean> = inFlight

    /** When the last forced read started or ended, whichever is later (the tile's click debounce). */
    @Volatile
    var lastForcedMs = 0L
        private set

    /**
     * Starts a read unless one is pending or running, or the Bluetooth grant is missing ([BluetoothGrant]).
     * [force] skips the 60 s cache and the failure floor — the user asked; an unforced kick that the reader would
     * answer from its cache starts nothing, so a surface redrawn by a read's own landing never starts another.
     */
    fun kick(
        context: Context,
        force: Boolean,
    ) {
        val app = context.applicationContext
        // Without the grant a read cannot connect, and its failure would read as "Phone out of reach".
        if (!BluetoothGrant.held(app)) return
        if (!force && !PhoneStatusReader.due(app)) return
        if (!inFlight.compareAndSet(false, true)) return
        if (force) lastForcedMs = System.currentTimeMillis()
        if (!StatusReadJob.schedule(app, force)) inFlight.value = false
    }

    /** The job's body: one read, then the redraws. */
    suspend fun run(
        context: Context,
        force: Boolean,
    ) {
        val app = context.applicationContext
        val before = PhoneStatusReader.cached(app)?.fetchedAtMs
        val failedBefore = PhoneStatusReader.failedAt(app)
        val after =
            try {
                PhoneStatusReader.read(app, maxAgeMs = if (force) 0 else PhoneStatusReader.FRESH_MS)
            } finally {
                if (force) lastForcedMs = System.currentTimeMillis()
                inFlight.value = false
            }
        val landed = after != null && after.fetchedAtMs != before
        if (landed || PhoneStatusReader.failedAt(app) != failedBefore) StatusComplicationService.requestUpdateAll(app)
        requestTileUpdate(app)
    }

    fun requestTileUpdate(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(MeshTileService::class.java) }
    }
}
