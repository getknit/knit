package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.reaction.ReactionHead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for message reactions. Owns the last-writer-wins rule so the send and
 * receive paths in [app.getknit.knit.mesh.MeshManager] share one definition of "newer wins".
 */
class ReactionRepository(
    private val dao: ReactionDao,
    private val db: KnitDatabase,
) {
    fun observeReactions(): Flow<List<ReactionEntity>> = dao.observeAll()

    /** Live reactions on one thread's messages, oldest first — the chat screen's arm. */
    fun observeReactionsIn(conversationId: String): Flow<List<ReactionEntity>> = dao.observeForConversation(conversationId)

    /** Live reactions on a single message, oldest first — who reacted, with what (message details). */
    fun observeReactionsFor(messageId: String): Flow<List<ReactionEntity>> = dao.observeForMessage(messageId)

    /**
     * Per thread, the newest live reaction someone who reads it made to a message [me] wrote, keyed by
     * conversation id — the chat list's reaction line. A same-millisecond tie the query returns whole is broken by message id,
     * then reactor id, so the line is the same on every emission.
     */
    fun observeNewestOnOwn(
        me: String,
        blocked: Collection<String>,
    ): Flow<Map<String, ReactionHead>> =
        dao
            .observeNewestOnOwnMessages(
                me,
                blocked,
                rooms = listOf(Conversations.NEARBY, Conversations.MESHTASTIC),
                commonsGlob = Conversations.COMMONS_PREFIX + "*",
            ).map { rows ->
                rows
                    .groupBy { it.target.conversationId }
                    .mapValues { (_, tied) -> tied.maxWith(compareBy({ it.target.id }, { it.reactorNodeId })) }
            }

    /**
     * Applies a reaction, keeping the existing one when this update is not strictly newer. This is
     * the single guard that makes out-of-order add/retract/replace frames (and plain duplicates)
     * converge: a stale frame for an already-newer ([messageId], [reactorNodeId]) is dropped.
     *
     * Returns whether what the reactor shows on the message changed — an add, a replace or a retraction that
     * this call wrote. A stale frame, a newer stamp carrying the same emoji, or a retraction of nothing returns
     * false: that is how a reaction notifies once however many times custody or a replay re-delivers it
     * (ADR 2026-10.59fu).
     *
     * The read-compare-upsert runs in one [db] transaction so a concurrent [apply] for the same
     * ([messageId], [reactorNodeId]) — a local reaction tap racing the inbound echo of that reaction
     * — can't read the same `current` and clobber a newer write out of `updatedAt` order (the send
     * and receive paths run on different coroutines, so this is not hypothetical).
     */
    suspend fun apply(reaction: ReactionEntity): Boolean =
        db.withWriteTransaction {
            val current = dao.find(reaction.messageId, reaction.reactorNodeId)
            if (current != null && reaction.updatedAt <= current.updatedAt) return@withWriteTransaction false
            dao.upsert(reaction)
            current?.emoji != reaction.emoji
        }

    /** The reactor's current emoji on a message (null if none/retracted) — used for toggle decisions. */
    suspend fun currentEmoji(
        messageId: String,
        reactorNodeId: String,
    ): String? = dao.emojiFor(messageId, reactorNodeId)

    /** Removes all reactions for a deleted message, since the reactions table has no FK cascade. */
    suspend fun deleteForMessage(messageId: String) = dao.deleteForMessage(messageId)

    /**
     * Reclaims reaction rows orphaned by a deleted message or whole conversation, since the reactions
     * table has no FK cascade. Mirrors [BlobRepository.deleteOrphans]; run once on startup. Orphans
     * younger than [ORPHAN_GRACE_MILLIS] are kept so a reaction that arrived just ahead of its message
     * (out-of-order mesh delivery) isn't reaped — see [app.getknit.knit.data.reaction.ReactionEntity].
     */
    suspend fun deleteOrphans(now: Long) = dao.deleteOrphansOlderThan(now - ORPHAN_GRACE_MILLIS)

    private companion object {
        // A day comfortably exceeds any realistic out-of-order gap between a message and its reactions,
        // while still reclaiming rows left behind by a deleted message or conversation.
        const val ORPHAN_GRACE_MILLIS = 24L * 60 * 60 * 1000
    }
}
