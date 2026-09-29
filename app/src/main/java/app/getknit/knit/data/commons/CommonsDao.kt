package app.getknit.knit.data.commons

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Row-level operations for the commons, its outbox and its members. Thin by rule
 * (`.agents/rules/coding.md`): no `@Transaction` methods — the multi-table writes (join, leave, post)
 * take `db.withWriteTransaction` in [CommonsRepository].
 */
@Suppress("TooManyFunctions") // three tables' row operations behind one DAO, the MessageDao shape
@Dao
interface CommonsDao {
    @Query("SELECT * FROM commons WHERE conversationId = :conversationId")
    suspend fun find(conversationId: String): CommonsEntity?

    @Query("SELECT * FROM commons")
    suspend fun all(): List<CommonsEntity>

    @Query("SELECT * FROM commons ORDER BY joinedAt")
    fun observeAll(): Flow<List<CommonsEntity>>

    @Query("SELECT * FROM commons WHERE conversationId = :conversationId")
    fun observe(conversationId: String): Flow<CommonsEntity?>

    @Query("SELECT conversationId FROM commons WHERE spoolUrl = :spoolUrl")
    suspend fun idsBoundTo(spoolUrl: String): List<String>

    // REPLACE rather than Room's upsert on all three writes: the upsert tries the insert first and logs the
    // unique-key exception it then recovers from, and a member row is re-written on every frame pulled.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CommonsEntity)

    @Query("DELETE FROM commons WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("SELECT * FROM commons_outbox WHERE conversationId = :conversationId")
    suspend fun outboxFor(conversationId: String): List<CommonsOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutbox(row: CommonsOutboxEntity)

    @Query("DELETE FROM commons_outbox WHERE conversationId = :conversationId")
    suspend fun deleteOutboxFor(conversationId: String)

    @Query("DELETE FROM commons_outbox WHERE sentAt < :before")
    suspend fun sweepOutbox(before: Long)

    @Query("SELECT * FROM commons_members WHERE conversationId = :conversationId")
    suspend fun membersOf(conversationId: String): List<CommonsMemberEntity>

    @Query("SELECT * FROM commons_members")
    suspend fun allMembers(): List<CommonsMemberEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM commons_members WHERE conversationId = :conversationId AND nodeId = :nodeId)")
    suspend fun isMember(
        conversationId: String,
        nodeId: String,
    ): Boolean

    @Query("SELECT nodeId FROM commons_members WHERE conversationId = :conversationId")
    fun observeMemberIds(conversationId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(row: CommonsMemberEntity)

    @Query("DELETE FROM commons_members WHERE conversationId = :conversationId")
    suspend fun deleteMembersOf(conversationId: String)
}
