package app.getknit.knit.usbcontrol

import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The changes no repository reports, because they live in settings rather than the database: a thread read or
 * accepted, the blocked set, our own name and photo, a commons joined, left or renamed, content filtering switched,
 * and the filter's verdicts ([flaggedHashes], written by the screening service rather than a repository). Each flow's
 * first value is the baseline; after it, what moved is recorded in the [ChangeLog] like a repository write.
 */
internal object ChangeWatch {
    fun launch(
        scope: CoroutineScope,
        settings: SettingsStore,
        commons: CommonsRepository,
        flaggedHashes: Flow<List<String>>,
        log: ChangeLog,
    ): Job =
        scope.launch {
            launch { settings.lastReadAll.diffs(::changedKeys).collect { it.forEach { c -> log.record(ChangeKey.Chat(c)) } } }
            launch { settings.acceptedConversations.diffs(::changedMembers).collect { it.forEach { c -> log.record(ChangeKey.Chat(c)) } } }
            launch {
                commons
                    .observeAll()
                    .map { rooms -> rooms.associate { it.conversationId to it.name } }
                    .diffs(::changedKeys)
                    .collect { it.forEach { c -> log.record(ChangeKey.Chat(c)) } }
            }
            launch { settings.blockedNodeIds.diffs(::changedMembers).collect { log.record(ChangeKey.Resync("the blocked set changed")) } }
            // Every attachment's `flagged` reads the switch, so a flip moves too many objects to name one by one.
            launch {
                settings.contentFilteringEnabled
                    .diffs { old, new -> if (old == new) emptySet() else setOf(Unit) }
                    .collect { log.record(ChangeKey.Resync("content filtering switched")) }
            }
            launch {
                flaggedHashes
                    .map { it.toSet() }
                    .diffs(::changedMembers)
                    .collect { it.forEach { hash -> log.record(ChangeKey.Attachment(hash)) } }
            }
            launch {
                combine(settings.displayName, settings.ownAvatarHash) { name, avatar -> name to avatar }
                    .diffs { old, new -> if (old == new) emptySet() else setOf(Unit) }
                    .collect { log.record(ChangeKey.Self) }
            }
        }

    /** The keys whose value differs between [old] and [new], present in either. */
    fun <K, V> changedKeys(
        old: Map<K, V>,
        new: Map<K, V>,
    ): Set<K> = (old.keys + new.keys).filterTo(HashSet()) { old[it] != new[it] || (it in old) != (it in new) }

    /** What is in one of [old] and [new] and not the other. */
    fun <T> changedMembers(
        old: Set<T>,
        new: Set<T>,
    ): Set<T> = (old - new) + (new - old)

    /** What moved between each value and the next, skipping the baseline and every step that moved nothing. */
    private fun <T, K> Flow<T>.diffs(diff: (T, T) -> Set<K>): Flow<Set<K>> =
        flow {
            var previous: Any? = NONE
            distinctUntilChanged().collect { value ->
                @Suppress("UNCHECKED_CAST")
                if (previous !== NONE) diff(previous as T, value).takeIf { it.isNotEmpty() }?.let { emit(it) }
                previous = value
            }
        }

    private val NONE = Any()
}
