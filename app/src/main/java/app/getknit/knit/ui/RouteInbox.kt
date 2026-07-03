package app.getknit.knit.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A process-scoped, single-shot handoff for a navigation route arriving from outside Compose — namely a
 * notification tap (deep-link to `chat/<conversationId>`). [app.getknit.knit.MainActivity] reads the
 * route extra in `onCreate`/`onNewIntent` and [offer]s it here; `KnitApp` observes [pending] and
 * navigates, then [consume]s it. Mirrors [app.getknit.knit.ui.share.ShareInbox]: a holder is needed
 * because a warm (already-running) instance can't restart at a new NavHost start destination, and a
 * navigation route can't ride Compose state directly from an Activity intent.
 */
class RouteInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Stage a route to navigate to (e.g. "chat/nearby"). Blank routes are ignored. */
    fun offer(route: String) {
        if (route.isNotBlank()) _pending.value = route
    }

    /** Take the staged route (if any) and clear it, so only the first reader navigates. */
    fun consume(): String? = _pending.getAndUpdate { null }

    /** Drop any staged route (e.g. a deep-link that arrived before onboarding). */
    fun clear() {
        _pending.value = null
    }
}
