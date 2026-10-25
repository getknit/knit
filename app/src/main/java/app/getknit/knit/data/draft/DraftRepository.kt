package app.getknit.knit.data.draft

import app.getknit.knit.data.message.StyleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Keeps what was typed into a thread's composer, so leaving the screen doesn't throw it away.
 *
 * Two properties shape the implementation:
 *
 * **The write outlives the screen.** [scope] is the application scope, not a `viewModelScope`: the
 * whole point is that the draft survives the user navigating away, and a write started on the way out
 * would be cancelled by the very act it is meant to record. The same is true of [clear] after a send —
 * a send followed immediately by the back gesture must still leave the field empty next time.
 *
 * **One write per pause, not per keystroke.** Each edit cancels the previous pending write and schedules
 * another [debounceMs] out, so a burst of typing costs one row write once the user stops.
 *
 * Every entry point launches on [dispatcher] (the main thread in production), which is what makes the
 * pending-write map safe to touch without a lock — the callers are already on it, and Room's suspend DAO
 * functions do their SQL on the database's own dispatcher. Ordering follows from the same place: the
 * writes queue in the order they were scheduled, so the last edit wins and a [clear] is not undone by a
 * write it cancelled.
 *
 * A draft belongs to its thread and dies with it — see the [clear] callers on the delete paths.
 */
class DraftRepository(
    private val dao: DraftDao,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Confined to [dispatcher]. Holds the last write scheduled for each conversation, pending or finished;
    // cancelling a finished one is a no-op, and there is one entry per thread the user has typed in.
    private val writes = mutableMapOf<String, Job>()

    /**
     * Every draft on the device, keyed by conversation — the chat list's source for the rows that show
     * "Draft: …" instead of their last message. Emits on each write, which is once per typing pause.
     */
    val all: Flow<Map<String, DraftEntity>> =
        dao.observeAll().map { rows -> rows.associateBy { it.conversationId } }

    /** What [conversationId]'s composer was left with, formatting included, or [Draft.EMPTY] if there is none. */
    suspend fun load(conversationId: String): Draft =
        dao.find(conversationId)?.let { Draft(it.text, StyleStore.decode(it.styles)) } ?: Draft.EMPTY

    /** Records an edit. Blank text deletes the row rather than storing an empty one, whatever its styles. */
    fun save(
        conversationId: String,
        draft: Draft,
    ) {
        writes.remove(conversationId)?.cancel()
        writes[conversationId] =
            scope.launch(dispatcher) {
                delay(debounceMs)
                if (draft.text.isBlank()) {
                    dao.deleteFor(conversationId)
                } else {
                    dao.upsert(conversationId, draft.text, StyleStore.encode(draft.spans), now())
                }
            }
    }

    /** Drops [conversationId]'s draft now: on an accepted send, and when the thread itself is deleted. */
    fun clear(conversationId: String) {
        writes.remove(conversationId)?.cancel()
        scope.launch(dispatcher) { dao.deleteFor(conversationId) }
    }

    private companion object {
        // Long enough that ordinary typing writes once at the end of a word or sentence, short enough that
        // the row is already there when a user who stops typing and leaves gets to the back gesture.
        const val DEBOUNCE_MS = 500L
    }
}
