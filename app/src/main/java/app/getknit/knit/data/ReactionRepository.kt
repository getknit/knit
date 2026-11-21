package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.changes.DataChange
import app.getknit.knit.data.changes.DataChangeSink
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.reaction.MeshcoreReactionDao
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
    // The MeshCore room's queries (ADR 2026-11.gsqu's 2026-11-21 amendment).
    private val meshcore: MeshcoreReactionDao = db.meshcoreReactionDao(),
    // Told each message whose reactions a write here changed (`DataChange`); a no-op outside a debug build.
    private val changes: DataChangeSink = DataChangeSink.NONE,
) {
    fun observeReactions(): Flow<List<ReactionEntity>> = dao.observeAll()

    /**
     * Live reactions on one thread's messages, oldest first — the chat screen's arm. Only reactors in the thread, [me]
     * among them ([ReactionDao.observeForConversation]).
     */
    fun observeReactionsIn(
        conversationId: String,
        me: String,
    ): Flow<List<ReactionEntity>> =
        dao.observeForConversation(conversationId, me, ROOMS, Conversations.COMMONS_PREFIX + "*", Conversations.MESHCORE_PUBLIC)

    /**
     * Live reactions on a single message, oldest first — who reacted, with what (message details). Only reactors in its
     * thread, [me] among them ([ReactionDao.observeForMessage]).
     */
    fun observeReactionsFor(
        messageId: String,
        me: String,
    ): Flow<List<ReactionEntity>> =
        dao.observeForMessage(messageId, me, ROOMS, Conversations.COMMONS_PREFIX + "*", Conversations.MESHCORE_PUBLIC)

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
                rooms = ROOMS,
                commonsGlob = Conversations.COMMONS_PREFIX + "*",
                meshcoreRoom = Conversations.MESHCORE_PUBLIC,
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
    suspend fun apply(reaction: ReactionEntity): Boolean {
        val changed =
            db.withWriteTransaction {
                val current = dao.find(reaction.messageId, reaction.reactorNodeId)
                if (current != null && reaction.updatedAt <= current.updatedAt) return@withWriteTransaction false
                dao.upsert(reaction)
                current?.emoji != reaction.emoji
            }
        if (changed) changes.emit(DataChange.Message(reaction.messageId, null))
        return changed
    }

    /**
     * [apply] for a reaction heard on the MeshCore room's channel under a claimed name ([reaction]'s reactor is `mc:` +
     * the name, ADR 2026-11.gsqu's 2026-11-21 amendment), within the bounds a free name needs: at most
     * [MESHCORE_REACTORS_PER_POST] claimed names on one post and [MESHCORE_REACTIONS_MAX] on this phone, the oldest other
     * claimed name's reaction evicted for a new one (never a Knit reactor's, ours included), in the same transaction.
     */
    suspend fun applyMeshcore(reaction: ReactionEntity): MeshcoreApplied {
        require(reaction.reactorNodeId.startsWith(MESHCORE_REACTOR_PREFIX)) { "not a claimed MeshCore name" }
        var evicted = 0
        val changed =
            db.withWriteTransaction {
                val current = dao.find(reaction.messageId, reaction.reactorNodeId)
                if (current != null && reaction.updatedAt <= current.updatedAt) return@withWriteTransaction false
                dao.upsert(reaction)
                if (current == null) {
                    while (meshcore.meshcoreReactorsOn(reaction.messageId) > MESHCORE_REACTORS_PER_POST &&
                        meshcore.evictOldestMeshcoreOn(reaction.messageId, reaction.reactorNodeId) > 0
                    ) {
                        evicted++
                    }
                    while (meshcore.meshcoreReactions() > MESHCORE_REACTIONS_MAX &&
                        meshcore.evictOldestMeshcore(reaction.messageId, reaction.reactorNodeId) > 0
                    ) {
                        evicted++
                    }
                }
                current?.emoji != reaction.emoji
            }
        if (changed) changes.emit(DataChange.Message(reaction.messageId, null))
        return MeshcoreApplied(changed = changed, evicted = evicted)
    }

    /**
     * The MeshCore room rows MeshCore One's reaction [tag] names, at most two ([MeshcoreReactionDao.taggedIn]): a reaction
     * is placed only when there is exactly one (a tag is 40 bits over public inputs, so a ground collision places nothing).
     */
    suspend fun meshcoreTagged(tag: String): List<MessageEntity> = meshcore.taggedIn(tag, Conversations.MESHCORE_PUBLIC)

    /** What [applyMeshcore] did: whether the reactor's emoji on the post changed, and how many older ones the bounds evicted. */
    data class MeshcoreApplied(
        val changed: Boolean,
        val evicted: Int,
    )

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
     * With the same grace it also reclaims reactions on a group post from someone off its roster
     * ([ReactionDao.deleteOffRosterOlderThan]): stored unchecked because they arrived before the post, and never drawn.
     */
    suspend fun deleteOrphans(
        now: Long,
        me: String,
    ) {
        dao.deleteOrphansOlderThan(now - ORPHAN_GRACE_MILLIS)
        dao.deleteOffRosterOlderThan(now - ORPHAN_GRACE_MILLIS, me)
        dao.deleteOffRoomOlderThan(now - ORPHAN_GRACE_MILLIS, me, BRIDGED, Conversations.MESHCORE_PUBLIC)
    }

    companion object {
        /** A reactor id naming a claimed MeshCore channel name rather than a Knit identity: `mc:` + the name as cleaned. */
        const val MESHCORE_REACTOR_PREFIX = "mc:"

        /** At most this many claimed names hold a reaction on one MeshCore room post: a free name is no bound. */
        const val MESHCORE_REACTORS_PER_POST = 32

        /** At most this many claimed-name reactions on this phone, on any post. */
        const val MESHCORE_REACTIONS_MAX = 2048

        // The rooms every reaction query lets anyone react in; a commons is matched by its prefix beside them. Never a
        // bridged board room: anyone can derive its rows' ids from the air, so no Knit identity's reaction is drawn there
        // (ADR 2026-11.gsqu's Stage 6 amendment, `InboundPipeline.reactsIn`). The MeshCore room draws us and the channel's
        // claimed names by its own clause (`ReactionDao`'s `meshcoreRoom`, the 2026-11-21 amendment); the Meshtastic
        // room draws nobody but us.
        private val ROOMS = listOf(Conversations.NEARBY)

        private val BRIDGED = listOf(Conversations.MESHTASTIC, Conversations.MESHCORE_PUBLIC)

        // A day comfortably exceeds any realistic out-of-order gap between a message and its reactions,
        // while still reclaiming rows left behind by a deleted message or conversation.
        private const val ORPHAN_GRACE_MILLIS = 24L * 60 * 60 * 1000
    }
}
