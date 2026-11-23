package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The chats the user muted (ADR 2026-11.sp7k): conversation id → the moment the mute ends, [ALWAYS] for one with no
 * end. An entry may already be past its moment; every reader judges it through `ChatMute.activeUntil`, so a lapsed
 * mute is no mute anywhere. Presentation only: it gates the notification post and the chat list's marks, never the
 * wire, custody, receipts or unread counts.
 */
interface ChatMutes {
    val mutedUntil: Flow<Map<String, Long>>

    /** Mutes [conversationId] until [until] ([ALWAYS] for good), dropping every entry already past [now]. */
    suspend fun mute(
        conversationId: String,
        until: Long,
        now: Long,
    )

    /** Unmutes [conversationId]; idempotent. */
    suspend fun unmute(conversationId: String)

    companion object {
        /** The deadline of a mute with no end. */
        const val ALWAYS = Long.MAX_VALUE
    }
}

/**
 * [ChatMutes] over the settings DataStore, as `id@until` entries in one string set (the `HiddenKnownStore` shape). It
 * rides a backup like pins and drafts: a mute is the person's choice, and a restored timed one simply lapses.
 */
class ChatMuteStore(
    private val dataStore: DataStore<Preferences>,
) : ChatMutes {
    override val mutedUntil: Flow<Map<String, Long>> =
        dataStore.data.map { decode(it[KEY].orEmpty()) }.distinctUntilChanged()

    override suspend fun mute(
        conversationId: String,
        until: Long,
        now: Long,
    ) = rewrite { (it + (conversationId to until)).filterValues { end -> end > now } }

    override suspend fun unmute(conversationId: String) = rewrite { it - conversationId }

    private suspend fun rewrite(transform: (Map<String, Long>) -> Map<String, Long>) {
        dataStore.edit { prefs ->
            val next = transform(decode(prefs[KEY].orEmpty()))
            if (next.isEmpty()) {
                prefs.remove(KEY)
            } else {
                prefs[KEY] = next.mapTo(HashSet()) { (id, until) -> "$id$SEP$until" }
            }
        }
    }

    internal companion object {
        val KEY = stringSetPreferencesKey("chat_muted_until")
        private const val SEP = '@'

        /** `id@until` entries back to a map; a malformed entry is skipped, never thrown on. */
        fun decode(entries: Set<String>): Map<String, Long> =
            entries
                .mapNotNull { entry ->
                    val at = entry.lastIndexOf(SEP)
                    val until = entry.substring(at + 1).toLongOrNull()
                    if (at <= 0 || until == null) null else entry.substring(0, at) to until
                }.toMap()
    }
}
