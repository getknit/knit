package app.getknit.knit.data.message

import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * The chat's mention window read (ADR 2026-11.p6dk), kept apart from [MessageDao] for detekt's LargeClass as
 * [BridgedRoomQueries] is. [MessageDao] inherits it and Room generates it as its own: this is no DAO of its own, and
 * nothing but [MessageDao] implements it.
 */
interface MentionWindowQueries {
    /**
     * The edge of [conversationId]'s mention window (ADR 2026-11.p6dk's revision 3): the place of its [offset]-th newest
     * counted row (0-based), or null when it holds no more counted rows than that. A counted row is one whose sender is
     * neither [me] nor in [blocked] (revision 5: blocking a flooder brings the honest highlights back) and whose mention
     * list is not empty. Read over every stored row of the thread, never the screen's loaded page,
     * in the window's own order (`sentAt`, then `id`), so the `(conversationId, sentAt, id)` index serves it.
     * `ui/chat/mentionWindowEdge` is the same rule over a list, and `MentionWindowQueriesTest` holds the two to the same
     * answers.
     */
    @Query(
        "SELECT sentAt, id FROM messages WHERE conversationId = :conversationId AND senderId != :me " +
            "AND senderId NOT IN (:blocked) AND mentions != '[]' AND mentions != '' " +
            "ORDER BY sentAt DESC, id DESC LIMIT 1 OFFSET :offset",
    )
    fun observeMentionWindowEdge(
        conversationId: String,
        me: String,
        blocked: Collection<String>,
        offset: Int,
    ): Flow<MessagePlace?>
}

/**
 * A row's place in its thread's display order: [sentAt], then [id] compared as a string, the greater being newer. Ids are
 * ASCII, so Kotlin's order and SQLite's BINARY order agree.
 */
data class MessagePlace(
    val sentAt: Long,
    val id: String,
) : Comparable<MessagePlace> {
    override fun compareTo(other: MessagePlace): Int = compareValuesBy(this, other, { it.sentAt }, { it.id })
}
