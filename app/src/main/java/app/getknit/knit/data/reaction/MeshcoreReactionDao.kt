package app.getknit.knit.data.reaction

import androidx.room3.Dao
import androidx.room3.Query
import app.getknit.knit.data.message.MessageEntity

/**
 * The MeshCore room's reaction queries (ADR 2026-11.gsqu's 2026-11-21 amendment): finding the post a MeshCore One reaction's
 * tag names, and the bounds on the claimed names (`mc:` reactors) that react there. Every write here runs inside
 * `ReactionRepository.applyMeshcore`'s transaction.
 */
@Dao
interface MeshcoreReactionDao {
    /**
     * At most two rows in [room] carrying MeshCore One's reaction [tag] ([MessageEntity.meshcoreTag]): a reaction is placed
     * only when exactly one is held. Ordered by id, so the answer never depends on the scan.
     */
    @Query("SELECT * FROM messages WHERE meshcoreTag = :tag AND conversationId = :room ORDER BY id LIMIT 2")
    suspend fun taggedIn(
        tag: String,
        room: String,
    ): List<MessageEntity>

    /** How many claimed MeshCore names (`mc:` reactors) hold a reaction on [messageId]. */
    @Query("SELECT COUNT(*) FROM reactions WHERE messageId = :messageId AND reactorNodeId GLOB 'mc:*'")
    suspend fun meshcoreReactorsOn(messageId: String): Int

    /** How many `mc:` reactions this phone holds, on any post. */
    @Query("SELECT COUNT(*) FROM reactions WHERE reactorNodeId GLOB 'mc:*'")
    suspend fun meshcoreReactions(): Int

    /**
     * Drops the oldest `mc:` reaction on [messageId] other than [keep]'s (ties by reactor id): the per-post bound on
     * claimed names (ADR 2026-11.gsqu's 2026-11-21 amendment). Never touches a Knit reactor's row, ours included.
     */
    @Query(
        "DELETE FROM reactions WHERE messageId = :messageId AND reactorNodeId = (SELECT reactorNodeId FROM reactions " +
            "WHERE messageId = :messageId AND reactorNodeId GLOB 'mc:*' AND reactorNodeId != :keep " +
            "ORDER BY updatedAt ASC, reactorNodeId ASC LIMIT 1)",
    )
    suspend fun evictOldestMeshcoreOn(
        messageId: String,
        keep: String,
    ): Int

    /** Drops the oldest `mc:` reaction this phone holds, other than ([keepMessage], [keepReactor]): the room-wide bound. */
    @Query(
        "DELETE FROM reactions WHERE rowid = (SELECT rowid FROM reactions WHERE reactorNodeId GLOB 'mc:*' " +
            "AND NOT (messageId = :keepMessage AND reactorNodeId = :keepReactor) " +
            "ORDER BY updatedAt ASC, messageId ASC, reactorNodeId ASC LIMIT 1)",
    )
    suspend fun evictOldestMeshcore(
        keepMessage: String,
        keepReactor: String,
    ): Int
}
