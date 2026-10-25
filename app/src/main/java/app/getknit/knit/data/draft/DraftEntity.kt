package app.getknit.knit.data.draft

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * What was typed into one thread's composer and never sent. Keyed by the same conversation id the
 * messages are (the Nearby room, the Meshtastic room, a peer's node id, or a group id), one row per
 * thread, and no row at all once the draft is sent or emptied.
 *
 * It lives in the SQLCipher-encrypted database rather than the preferences DataStore for the obvious
 * reason: this is message text the user wrote, and it is only unsent — everything that argues for
 * encrypting `messages` at rest argues for encrypting the sentence they were still writing. The
 * DataStore holds settings and watermarks, none of which is content.
 *
 * [updatedAt] is our own clock when the row was last written, and it exists for one reader: the chat list
 * shows "Draft: …" in place of the thread's preview when the draft is newer than the newest message in it.
 * Nothing expires on it — a sentence you left in a chat is still the sentence you left there a month later,
 * which is what every other messenger does with one. A draft is deleted when it is sent, when the field is
 * emptied, or when the thread it belongs to is deleted ([DraftRepository.clear]).
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val conversationId: String,
    val text: String,
    val updatedAt: Long,
    /** The draft's formatting, as `StyleStore` writes `messages.styles` (ADR 2026-10.p95v); null for none. */
    val styles: String? = null,
)
