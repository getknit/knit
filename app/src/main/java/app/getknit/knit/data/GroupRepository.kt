package app.getknit.knit.data

import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import kotlinx.coroutines.flow.Flow

/** Single source of truth for group chats. */
class GroupRepository(
    private val dao: GroupDao,
    private val messages: MessageRepository,
) {

    fun observeGroups(): Flow<List<GroupEntity>> = dao.observeAll()

    fun observeGroup(groupId: String): Flow<GroupEntity?> = dao.observeById(groupId)

    suspend fun find(groupId: String): GroupEntity? = dao.findById(groupId)

    suspend fun upsert(group: GroupEntity) = dao.upsert(group)

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
