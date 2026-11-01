package app.getknit.knit.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The block list's presentation hooks (ADR 2026-11.nsbf): diffs [blocked] and hands each id that joined it to
 * [onBlocked] and each that left to [onUnblocked], whatever path changed the list — the Blocked screen, device-tag
 * continuity, a restore, the debug bridge. Owned and started by `MeshManager` on its session scope, a sibling of
 * `OpenToChatWatch`.
 *
 * The first value is diffed against [actedOn], the list as this watch last acted on it ([recordActed] keeps it outside
 * Room), so a block or an unblock made while the mesh was stopped is acted on at the next start. With nothing recorded
 * yet (the first start of a build that has the watch) the first value is a baseline: the start's storage sweep settles
 * those blocks. A hook that throws costs its own id alone ([onFailure]), and the id is recorded as not acted on, so the
 * next change or the next start tries it again; the watch goes on. Presentation only — never custody, relay or a key want (ADR
 * 2026-09.bts9).
 */
class BlockListWatch(
    private val blocked: Flow<Set<String>>,
    private val actedOn: suspend () -> Set<String>?,
    private val recordActed: suspend (Set<String>) -> Unit,
    private val onBlocked: suspend (nodeId: String) -> Unit,
    private val onUnblocked: suspend (nodeId: String) -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val baselined = MutableStateFlow(false)

    /** True once the first value is acted on: a change after it is a diff. What a test awaits before changing the list. */
    val ready: StateFlow<Boolean> = baselined.asStateFlow()

    fun start(session: CoroutineScope): Job =
        session.launch {
            var last: Set<String>? = null
            blocked.distinctUntilChanged().collect { now ->
                val before = last ?: actedOn()
                var acted = now
                if (before != null) {
                    for (id in now - before) if (!tried(id, onBlocked)) acted = acted - id
                    for (id in before - now) if (!tried(id, onUnblocked)) acted = acted + id
                }
                // The next value diffs against what was acted on, so a failed id is tried again then, as at the next start.
                last = acted
                runRound(onFailure) { recordActed(acted) }
                baselined.value = true
            }
        }

    private suspend fun tried(
        id: String,
        hook: suspend (String) -> Unit,
    ): Boolean {
        var ok = false
        runRound(onFailure) {
            hook(id)
            ok = true
        }
        return ok
    }
}
