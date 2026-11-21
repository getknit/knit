package app.getknit.knit.data.reaction

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    /** Live reactions for the UI — tombstones (retracted, emoji IS NULL) are excluded. */
    @Query("SELECT * FROM reactions WHERE emoji IS NOT NULL ORDER BY updatedAt ASC")
    fun observeAll(): Flow<List<ReactionEntity>>

    /**
     * Live reactions on one thread's messages, oldest first — what a chat screen actually needs. Same
     * tombstone filter as [observeAll]; the join is what narrows it, since a reaction row names only its
     * message. A chat screen used to collect [observeAll] and then group the whole table by message id on
     * every emission, so a reaction in some other conversation rebuilt this one's rows.
     *
     * Only a reactor in the thread is drawn (#301), by the rule a reaction is applied under
     * (`InboundPipeline.reactsIn`): [me], the DM's peer (whose id is the thread's), anyone on a group's founding roster
     * (members or departed), anyone in a room ([rooms], or a commons by [commonsGlob]). A reaction stored before its
     * target landed was never checked, so this is where one from outside the thread stays out. In the MeshCore room
     * ([meshcoreRoom]) the only reactors besides [me] are the channel's claimed names, `mc:`-keyed by the one door that
     * writes them (`InboundPipeline`'s MeshCore reaction branch, ADR 2026-11.gsqu's 2026-11-21 amendment); a Knit node id
     * is hex and never matches, so a Knit identity's reaction there is never drawn. The roster LIKE is exact for the
     * reason [observeNewestOnOwnMessages] gives.
     */
    @Query(
        "SELECT r.* FROM reactions r JOIN messages m ON m.id = r.messageId " +
            "WHERE m.conversationId = :conversationId AND r.emoji IS NOT NULL " +
            "AND (r.reactorNodeId = :me OR m.conversationId = r.reactorNodeId OR m.conversationId IN (:rooms) " +
            "OR (m.conversationId = :meshcoreRoom AND r.reactorNodeId GLOB 'mc:*') " +
            "OR m.conversationId GLOB :commonsGlob OR EXISTS (SELECT 1 FROM groups AS g WHERE g.groupId = m.conversationId " +
            "AND (g.members LIKE '%\"' || r.reactorNodeId || '\"%' OR g.departed LIKE '%\"' || r.reactorNodeId || '\"%'))) " +
            "ORDER BY r.updatedAt ASC",
    )
    fun observeForConversation(
        conversationId: String,
        me: String,
        rooms: Collection<String>,
        commonsGlob: String,
        meshcoreRoom: String,
    ): Flow<List<ReactionEntity>>

    /**
     * Live reactions on one message, oldest first — the per-reactor rows the message-details screen
     * lists by name. Same tombstone filter as [observeAll] (a retraction is `emoji IS NULL`, not a
     * deleted row), narrowed by the existing `messageId` index, and the same reactor rule as
     * [observeForConversation] (#301): only someone in the message's thread is listed, so a reaction from outside it
     * that landed before the message never shows. The join means a message not held lists nobody.
     */
    @Query(
        "SELECT r.* FROM reactions r JOIN messages m ON m.id = r.messageId " +
            "WHERE r.messageId = :messageId AND r.emoji IS NOT NULL " +
            "AND (r.reactorNodeId = :me OR m.conversationId = r.reactorNodeId OR m.conversationId IN (:rooms) " +
            "OR (m.conversationId = :meshcoreRoom AND r.reactorNodeId GLOB 'mc:*') " +
            "OR m.conversationId GLOB :commonsGlob OR EXISTS (SELECT 1 FROM groups AS g WHERE g.groupId = m.conversationId " +
            "AND (g.members LIKE '%\"' || r.reactorNodeId || '\"%' OR g.departed LIKE '%\"' || r.reactorNodeId || '\"%'))) " +
            "ORDER BY r.updatedAt ASC",
    )
    fun observeForMessage(
        messageId: String,
        me: String,
        rooms: Collection<String>,
        commonsGlob: String,
        meshcoreRoom: String,
    ): Flow<List<ReactionEntity>>

    /** The stored last-writer-wins clock for this reactor on this message, or null if none yet. */
    @Query("SELECT updatedAt FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun updatedAtFor(
        messageId: String,
        reactorNodeId: String,
    ): Long?

    /** The reactor's stored row on this message (a tombstone included), or null if none yet — [ReactionRepository.apply]'s read. */
    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun find(
        messageId: String,
        reactorNodeId: String,
    ): ReactionEntity?

    /**
     * Per thread, the newest live reaction someone else made to a message [me] wrote, with that message — the
     * chat list's reaction line ([ReactionHead]). "Wrote" is `MessageEntity.writtenBy`'s rule in SQL: our sender
     * id, no Meshtastic origin (a heard post sits in our sender column by convention), an ordinary message
     * (`kind = 0`, never a notice). Reactors in [blocked] are left out, as their messages are on the list.
     *
     * Only someone who reads the thread counts — the rule the notification keeps (`InboundPipeline.readsThread`,
     * ADR 2026-10.59fu): in a DM the peer (whose id is the thread's), in a group a member of its roster, in a room
     * ([rooms], or a commons by [commonsGlob]) anyone. Every carrier sees a DM's frame ids, so without it a
     * stranger's reaction would head the row, and a DM's line names nobody: it would read as the peer's. A
     * roster is a JSON array of node ids, which carry no quote, `%` or `_`, so the quoted LIKE is exact (the
     * `MessageDao.sealedHolding` precedent).
     *
     * Shape: the driver is the reactions table, joined to `messages` by primary key, so the cost tracks how many
     * reactions this phone holds rather than how many messages. The inner pass takes each thread's newest
     * `updatedAt`; the outer pass joins back to the rows at that instant. A same-millisecond tie returns every
     * tied row and the repository breaks it by id (`ReactionRepository.observeNewestOnOwn`), so the line cannot
     * flip between emissions — the bare `MAX … GROUP BY` idiom would pick an arbitrary one. Tombstones never
     * qualify, so a retraction drops its reaction from here and the row falls back on its own.
     */
    @Query(
        "SELECT m.*, r.reactorNodeId AS reactorNodeId, r.emoji AS emoji, r.updatedAt AS reactedAt " +
            "FROM reactions r JOIN messages m ON m.id = r.messageId " +
            "JOIN (SELECT m2.conversationId AS cid, MAX(r2.updatedAt) AS at " +
            "FROM reactions r2 JOIN messages m2 ON m2.id = r2.messageId " +
            "WHERE r2.emoji IS NOT NULL AND r2.reactorNodeId != :me AND r2.reactorNodeId NOT IN (:blocked) " +
            "AND m2.senderId = :me AND m2.originNode IS NULL AND m2.kind = 0 " +
            "AND (m2.conversationId = r2.reactorNodeId OR m2.conversationId IN (:rooms) " +
            "OR (m2.conversationId = :meshcoreRoom AND r2.reactorNodeId GLOB 'mc:*') " +
            "OR m2.conversationId GLOB :commonsGlob OR EXISTS (SELECT 1 FROM groups AS g2 " +
            "WHERE g2.groupId = m2.conversationId AND g2.members LIKE '%\"' || r2.reactorNodeId || '\"%')) " +
            "GROUP BY m2.conversationId) AS newest ON newest.cid = m.conversationId AND newest.at = r.updatedAt " +
            "WHERE r.emoji IS NOT NULL AND r.reactorNodeId != :me AND r.reactorNodeId NOT IN (:blocked) " +
            "AND m.senderId = :me AND m.originNode IS NULL AND m.kind = 0 " +
            "AND (m.conversationId = r.reactorNodeId OR m.conversationId IN (:rooms) " +
            "OR (m.conversationId = :meshcoreRoom AND r.reactorNodeId GLOB 'mc:*') " +
            "OR m.conversationId GLOB :commonsGlob OR EXISTS (SELECT 1 FROM groups AS g " +
            "WHERE g.groupId = m.conversationId AND g.members LIKE '%\"' || r.reactorNodeId || '\"%'))",
    )
    fun observeNewestOnOwnMessages(
        me: String,
        blocked: Collection<String>,
        rooms: Collection<String>,
        commonsGlob: String,
        meshcoreRoom: String,
    ): Flow<List<ReactionHead>>

    /** The reactor's current emoji on this message (null if none/retracted) — drives toggle logic. */
    @Query("SELECT emoji FROM reactions WHERE messageId = :messageId AND reactorNodeId = :reactorNodeId")
    suspend fun emojiFor(
        messageId: String,
        reactorNodeId: String,
    ): String?

    @Upsert
    suspend fun upsert(reaction: ReactionEntity)

    /** Drops every reaction for a message (there is no FK cascade) when the message is deleted. */
    @Query("DELETE FROM reactions WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    /**
     * Reclaims reaction rows whose target message is no longer stored (the table has no FK cascade) and
     * that are older than [olderThan] (epoch ms). The age floor spares a reaction that legitimately
     * arrived before its message via out-of-order mesh delivery — see [ReactionEntity].
     */
    @Query(
        "DELETE FROM reactions WHERE updatedAt < :olderThan AND " +
            "messageId NOT IN (SELECT id FROM messages)",
    )
    suspend fun deleteOrphansOlderThan(olderThan: Long)

    /**
     * Reclaims reactions older than [olderThan] on a held group post from a reactor on neither the group's members nor its
     * departed (#301). Arrival refuses one of those on a post we hold (`InboundPipeline.reactsIn`), but one that arrived
     * before its post was stored unchecked, as every early reaction is, and the display never draws it. Without this,
     * identities minted to react ahead of a group's posts would keep their rows as long as the posts. A group's roster is
     * its founding set, fixed by its id, so no honest reactor is ever off it. Our own reactions ([me]) are never reaped,
     * and a post whose group row we no longer hold is left to [deleteForMessage]. The roster LIKE is exact for the
     * reason [observeNewestOnOwnMessages] gives.
     */
    @Query(
        "DELETE FROM reactions WHERE updatedAt < :olderThan AND reactorNodeId != :me AND EXISTS (" +
            "SELECT 1 FROM messages AS m JOIN groups AS g ON g.groupId = m.conversationId WHERE m.id = reactions.messageId " +
            "AND g.members NOT LIKE '%\"' || reactions.reactorNodeId || '\"%' " +
            "AND g.departed NOT LIKE '%\"' || reactions.reactorNodeId || '\"%')",
    )
    suspend fun deleteOffRosterOlderThan(
        olderThan: Long,
        me: String,
    )

    /**
     * Reclaims reactions older than [olderThan] on a held bridged-room post ([bridged]) from anyone but [me] and, in
     * [meshcoreRoom], the claimed `mc:` names: a Knit identity's reaction there is refused on arrival
     * (`InboundPipeline.reactsIn`) and never drawn, but one that landed before its post was stored unchecked.
     */
    @Query(
        "DELETE FROM reactions WHERE updatedAt < :olderThan AND reactorNodeId != :me AND EXISTS (" +
            "SELECT 1 FROM messages AS m WHERE m.id = reactions.messageId AND m.conversationId IN (:bridged) " +
            "AND NOT (m.conversationId = :meshcoreRoom AND reactions.reactorNodeId GLOB 'mc:*'))",
    )
    suspend fun deleteOffRoomOlderThan(
        olderThan: Long,
        me: String,
        bridged: Collection<String>,
        meshcoreRoom: String,
    )
}
