package app.getknit.knit.ui.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Text and/or image handed to Knit from another app's share sheet (an `ACTION_SEND` intent). The
 * image is kept as the [String] form of its `content://` URI — re-parsing that string with
 * `Uri.parse` preserves the temporary read grant (the grant is keyed to the (uid, uri) pair, not a
 * particular `Uri` instance), and keeping it a plain string makes [ShareInbox] JVM-unit-testable.
 */
data class SharedContent(
    val text: String?,
    val imageUri: String?,
)

/**
 * A process-scoped, single-shot handoff for a payload arriving via the system share sheet. The
 * receiving [app.getknit.knit.MainActivity] parses the intent and [offer]s the payload here;
 * `KnitApp` observes [pending] to route to the share-target picker, and the destination
 * [app.getknit.knit.ui.chat.ChatScreen] [consume]s it into its draft. A holder is needed because
 * Compose navigation routes can't cleanly carry a `Uri` or long text.
 */
class ShareInbox {
    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    /** Stage a payload from the share sheet. Fully-empty payloads are ignored. */
    fun offer(content: SharedContent) {
        if (content.text.isNullOrBlank() && content.imageUri == null) return
        _pending.value = content
    }

    /** Take the staged payload (if any) and clear it, so only the first reader consumes it. */
    fun consume(): SharedContent? = _pending.getAndUpdate { null }

    /** Drop any staged payload, e.g. when the user abandons the share-target picker. */
    fun clear() {
        _pending.value = null
    }
}
