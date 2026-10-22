package app.getknit.knit.data.relay

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolUploads
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The Internet-relay plane's state, as the UI needs it: the two settings that arm it plus a periodic
 * read of `ScopeSync`'s live per-spool status.
 *
 * Polled rather than pushed because `ScopeSync` exposes a snapshot (`status()`), not a stream, and
 * giving it one would mean threading a `MutableStateFlow` through the worker loop for a value that
 * changes on connect/disconnect and scope-table edits — never per frame. [POLL_MS] is deliberately
 * slower than the Diagnostics ticker: this feeds an ambient chat indicator, not a debug readout.
 *
 * [statuses] stays available separately because the relay settings screen and Diagnostics both want the
 * per-spool detail (url, `lastError`, per-scope counts) that [facts] flattens away.
 *
 * The flow is cold, so each collector runs its own ticker rather than sharing one. That is deliberate:
 * `spoolStatus()` is an in-memory read over a handful of workers, at most two of these screens are alive
 * at once, and sharing would mean giving a repository its own `CoroutineScope` to `shareIn` — lifecycle
 * for no measurable saving. The singleton exists to share the *derivation*, not a subscription.
 */
class RelayStatusRepository(
    private val settings: SettingsStore,
    private val mesh: MeshController,
) {
    /**
     * Live per-spool status, re-read on a slow ticker. Empty whenever the plane is parked.
     *
     * In a build where the plane is dark (`BuildConfig.INTERNET_PLANE` false) the ticker does not run at
     * all: `ScopeSync` holds no workers, so every poll would return the same empty list, and this flow is
     * collected by the chat list, every open chat and Profile — a permanent 5 s wake-up for a feature the
     * user cannot reach. One emission is enough to keep [facts]' `combine` live.
     */
    val statuses: Flow<List<SpoolStatus>> =
        flow {
            if (!BuildConfig.INTERNET_PLANE) {
                emit(emptyList())
                return@flow
            }
            while (true) {
                emit(mesh.spoolStatus())
                delay(POLL_MS)
            }
        }

    /**
     * The flattened facts the chat UI reasons about.
     *
     * `distinctUntilChanged` matters here rather than being hygiene: the ticker re-emits on a fixed
     * interval whether or not anything moved, and without it every chat row would recompose every
     * [POLL_MS] for a value that is usually identical.
     */
    val facts: Flow<RelayFacts> =
        combine(
            settings.spoolEnabled,
            settings.spoolUrls,
            settings.activeSpoolUrls,
            statuses,
        ) { enabled, urls, active, spools ->
            val live = spools.filter { it.connected }
            RelayFacts(
                enabled = enabled,
                configured = urls.size,
                active = active.size,
                connected = live.size,
                coveredLabels = coveredLabelsOf(live),
                // The union rule (§9.1): one relay willing to carry the bytes is enough.
                maxAttachBytes = live.mapNotNull { it.maxAttachBytes }.maxOrNull(),
                // And for sending, one relay that takes this phone's uploads (C-7.6-24), with its ticket's limit (C-7.6-32).
                uploadRooms =
                    live
                        .filter { it.uploads == SpoolUploads.Open }
                        .mapNotNull { spool -> spool.maxAttachBytes?.let { UploadRoom(it, spool.uploadLimitChunks) } },
                textOnlyRooms = textOnlyRoomsOf(live),
                staleRooms = spools.flatMapTo(HashSet()) { it.staleCommons },
            )
        }.distinctUntilChanged()

    /**
     * Whether some connected relay would issue this phone TURN now ([SpoolStatus.turnForUs]): what the Calls screen's
     * "Relay all calls" is offered on, since a relay-only call with no TURN server gathers nothing and never connects.
     */
    val turnAvailable: Flow<Boolean> =
        statuses.map { spools -> spools.any { it.connected && it.turnForUs } }.distinctUntilChanged()

    private companion object {
        const val POLL_MS = 5_000L
    }
}
