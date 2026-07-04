package app.getknit.knit.data

import androidx.room.withTransaction
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Single source of truth for group chats. */
class GroupRepository(
    private val dao: GroupDao,
    private val messages: MessageRepository,
    private val db: KnitDatabase,
) {
    fun observeGroups(): Flow<List<GroupEntity>> = dao.observeAll()

    fun observeGroup(groupId: String): Flow<GroupEntity?> = dao.observeById(groupId)

    suspend fun find(groupId: String): GroupEntity? = dao.findById(groupId)

    suspend fun upsert(group: GroupEntity) = dao.upsert(group)

    /**
     * Records that [leaverId] left [groupId] (from their own signed `groupleave` frame): drops them from
     * the roster, tombstones them in [GroupEntity.departed] so a straggler's stale full roster can't
     * re-add them, and inserts a "member left" status notice stamped [leftAt] (the frame's sentAt, for
     * stable cross-device ordering). The whole read-modify-write plus the message insert run in one
     * transaction so the count and the notice can't tear apart and so a concurrent rename can't clobber
     * the tombstone. Returns true only when [leaverId] was actually a current member — a no-op (already
     * gone, never a member, or a group we've left) returns false, which both dedups a re-flooded leave
     * and tells the caller not to surface anything. The status row's id is deterministic so a replay
     * upserts the same row rather than duplicating it.
     */
    suspend fun recordDeparture(
        groupId: String,
        leaverId: String,
        leftAt: Long,
    ): Boolean =
        db.withTransaction {
            val group = dao.findById(groupId) ?: return@withTransaction false
            if (group.left) return@withTransaction false
            val members = GroupMembersStore.decode(group.members)
            if (leaverId !in members) return@withTransaction false
            val departed = GroupMembersStore.decode(group.departed)
            dao.upsert(
                group.copy(
                    members = GroupMembersStore.encode(members - leaverId),
                    departed = GroupMembersStore.encode((departed + leaverId).distinct()),
                ),
            )
            messages.save(
                MessageEntity(
                    id = "leave:$groupId:$leaverId",
                    senderId = leaverId,
                    conversationId = groupId,
                    body = "",
                    sentAt = leftAt,
                    received = true,
                    kind = MessageEntity.KIND_MEMBER_LEFT,
                ),
            )
            true
        }

    /**
     * Leaves [groupId]: tombstones the row (so inbound frames are dropped and never resurrect it) and
     * deletes the thread's messages so it vanishes from the chat list. The local user stops receiving;
     * other members still treat them as a roster entry (membership is reconstructed per-device from
     * frames), but their frames are now ignored here.
     */
    suspend fun leave(groupId: String) {
        dao.markLeft(groupId)
        messages.deleteByConversation(groupId)
    }

    /**
     * Deletes [groupId] locally without leaving: hard-deletes the row and clears its messages, so the
     * chat disappears now but the next inbound group frame re-creates it via MeshManager.reconcileGroup
     * (contrast [leave], which tombstones to block re-add).
     */
    suspend fun delete(groupId: String) {
        dao.deleteById(groupId)
        messages.deleteByConversation(groupId)
    }
}
