package app.getknit.knit.ui.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped single-shot signal that the rate/review prompt should be shown. [app.getknit.knit.review]
 * `ReviewPrompter` evaluates the engagement gates off the chat-list route and [offer]s here when they pass;
 * `KnitApp` observes [pending] and shows [RateReviewDialog] over the current screen, then [consume]s it.
 * Mirrors [app.getknit.knit.ui.RouteInbox] — the prompt is decided outside Compose but must surface at the
 * NavHost root, above whichever screen is showing.
 */
class ReviewPromptInbox {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Signal that the prompt should be shown. */
    fun offer() {
        _pending.value = true
    }

    /** Clear the signal once the dialog is shown-and-answered (or dismissed), so it fires only once. */
    fun consume() {
        _pending.value = false
    }
}
