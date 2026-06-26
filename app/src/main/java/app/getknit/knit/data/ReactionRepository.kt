package app.getknit.knit.data

import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for message reactions. Owns the last-writer-wins rule so the send and
 * receive paths in [app.getknit.knit.mesh.MeshManager] share one definition of "newer wins".
 */
class ReactionRepository(private val dao: ReactionDao) {

    fun observeReactions(): Flow<List<ReactionEntity>> = dao.observeAll()

    /**
     * Applies a reaction, keeping the existing one when this update is not strictly newer. This is
     * the single guard that makes out-of-order add/retract/replace frames (and plain duplicates)
     * converge: a stale frame for an already-newer ([messageId], [reactorNodeId]) is dropped.
     */
    suspend fun apply(reaction: ReactionEntity) {
        val current = dao.updatedAtFor(reaction.messageId, reaction.reactorNodeId)
        if (current == null || reaction.updatedAt > current) dao.upsert(reaction)
    }

    /** The reactor's current emoji on a message (null if none/retracted) — used for toggle decisions. */
    suspend fun currentEmoji(messageId: String, reactorNodeId: String): String? =
        dao.emojiFor(messageId, reactorNodeId)

    /** Removes all reactions for a deleted message, since the reactions table has no FK cascade. */
    suspend fun deleteForMessage(messageId: String) = dao.deleteForMessage(messageId)
}
