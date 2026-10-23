package app.getknit.knit.data.scopeconfig

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScopeConfigDao {
    /** Every issuer's row for [conversationId]. */
    @Query("SELECT * FROM scope_configs WHERE conversationId = :conversationId")
    suspend fun configsOf(conversationId: String): List<ScopeConfigEntity>

    /** [configsOf] as it changes. */
    @Query("SELECT * FROM scope_configs WHERE conversationId = :conversationId")
    fun observeConfigsOf(conversationId: String): Flow<List<ScopeConfigEntity>>

    /** Every row of every conversation, as it changes. */
    @Query("SELECT * FROM scope_configs")
    fun observeAll(): Flow<List<ScopeConfigEntity>>

    /** Writes one issuer's row whole; the caller read the conversation's rows in the same transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(config: ScopeConfigEntity)

    /** Drops one row: a version of [issuer]'s that a newer one supersedes, or a reservation given back. */
    @Query("DELETE FROM scope_configs WHERE conversationId = :conversationId AND issuer = :issuer AND version = :version")
    suspend fun deleteRow(
        conversationId: String,
        issuer: String,
        version: Int,
    )

    /** Drops every row of [issuer]'s for [conversationId] — a group member's, at their leave. */
    @Query("DELETE FROM scope_configs WHERE conversationId = :conversationId AND issuer = :issuer")
    suspend fun deleteIssuer(
        conversationId: String,
        issuer: String,
    )

    /** Records [nodeId] as declared at [at], keeping the first stamp a node got. */
    @Query("INSERT OR IGNORE INTO spool_declared (nodeId, at) VALUES (:nodeId, :at)")
    suspend fun declare(
        nodeId: String,
        at: Long,
    )

    @Query("SELECT nodeId FROM spool_declared")
    suspend fun declared(): List<String>

    @Query("SELECT nodeId FROM spool_declared")
    fun observeDeclared(): Flow<List<String>>
}
