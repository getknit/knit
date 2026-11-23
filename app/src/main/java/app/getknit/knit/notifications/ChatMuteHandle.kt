package app.getknit.knit.notifications

import app.getknit.knit.data.settings.ChatMutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One chat's mute (ADR 2026-11.sp7k), for the screens that show and change it: the chat, a DM's profile, a group's and
 * a commons' details. Built over the ViewModel's own scope.
 */
class ChatMuteHandle(
    private val store: ChatMutes,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * When the mute ends ([ChatMutes.ALWAYS] for good), null while the chat is not muted. A timed mute turns back to
     * null on its own as it lapses ([ChatMute.active]).
     */
    val mutedUntil: StateFlow<Long?> =
        ChatMute
            .active(store.mutedUntil, now)
            .map { it[conversationId] }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Mutes the chat for [span]: its posts are kept and counted, only the shade is spared. */
    fun mute(span: MuteFor) {
        scope.launch {
            val at = now()
            store.mute(conversationId, span.deadline(at), at)
        }
    }

    fun unmute() {
        scope.launch { store.unmute(conversationId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
