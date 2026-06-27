package app.getknit.knit.data.group

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun findById(groupId: String): GroupEntity?

    @Upsert
    suspend fun upsert(group: GroupEntity)

    /** Marks the group left so inbound frames are dropped and it's hidden from the list. */
    @Query("UPDATE groups SET left = 1 WHERE groupId = :groupId")
    suspend fun markLeft(groupId: String)
}
