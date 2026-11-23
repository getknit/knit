package app.getknit.knit.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [ChatMutes] in memory, with [ChatMuteStore]'s rules: a mute replaces the chat's deadline and drops what is past. */
class FakeChatMutes(
    initial: Map<String, Long> = emptyMap(),
) : ChatMutes {
    val state = MutableStateFlow(initial)

    override val mutedUntil: Flow<Map<String, Long>> get() = state

    override suspend fun mute(
        conversationId: String,
        until: Long,
        now: Long,
    ) {
        state.value = (state.value + (conversationId to until)).filterValues { it > now }
    }

    override suspend fun unmute(conversationId: String) {
        state.value = state.value - conversationId
    }
}
