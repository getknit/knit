package app.getknit.knit.data

import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.ThreadMediaQueries
import kotlinx.coroutines.flow.Flow

/**
 * The reads of a chat's "Media, links and files" screen (ADR 2026-11.dgvf), one per tab, each **newest first**, each
 * bounded by its [limit], each from senders not in its block list. See [ThreadMediaQueries] for the SQL and its cost.
 */
class ThreadMediaRepository(
    private val dao: MessageDao,
) {
    /** The thread's photos and videos — the Media tab. See [ThreadMediaQueries.observeMediaIn]. */
    fun observeMedia(
        conversationId: String,
        blocked: Set<String>,
        limit: Int,
    ): Flow<List<MessageEntity>> = dao.observeMediaIn(conversationId, blocked, limit)

    /** The thread's files and voice messages — the Files tab. See [ThreadMediaQueries.observeFilesIn]. */
    fun observeFiles(
        conversationId: String,
        blocked: Set<String>,
        limit: Int,
    ): Flow<List<MessageEntity>> = dao.observeFilesIn(conversationId, blocked, limit)

    /**
     * The thread's messages whose body may hold a link, without flagged text when [hideFlagged] — the Links tab. See
     * [ThreadMediaQueries.observeLinkRowsIn].
     */
    fun observeLinkRows(
        conversationId: String,
        blocked: Set<String>,
        hideFlagged: Boolean,
        limit: Int,
    ): Flow<List<MessageEntity>> = dao.observeLinkRowsIn(conversationId, blocked, hideFlagged, limit)
}
