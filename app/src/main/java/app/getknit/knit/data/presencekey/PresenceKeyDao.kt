package app.getknit.knit.data.presencekey

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * This phone's presence keys and the ones its peers handed it (ADR 2026-11.xp6d). Never log a row: they are keys. A
 * write that must land with another (own rows replaced together, a peer's keys swapped for a new era) goes inside the
 * caller's `withWriteTransaction`.
 */
@Dao
@Suppress("TooManyFunctions") // one small query each for the engine, the resolver, the bridge and Diagnostics
interface PresenceKeyDao {
    /** Every own generation, newest first: at most three (previous, current, next). */
    @Query("SELECT * FROM own_presence_keys ORDER BY generation DESC")
    suspend fun ownKeys(): List<OwnPresenceKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOwn(rows: List<OwnPresenceKeyEntity>)

    /** A newly minted own key; never over an existing generation (a mint always takes a fresh one). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOwn(row: OwnPresenceKeyEntity)

    /**
     * Moves one own key's role and audience, leaving its mark columns as the advertiser last wrote them
     * (`TagSchedule.MarkStore`): a rotation never rewrites a mark it read before the advertiser moved it.
     */
    @Query("UPDATE own_presence_keys SET activatedAt = :activatedAt, audience = :audience WHERE generation = :generation")
    suspend fun updateOwn(
        generation: Long,
        activatedAt: Long?,
        audience: String,
    ): Int

    @Query("DELETE FROM own_presence_keys WHERE generation NOT IN (:keep)")
    suspend fun deleteOwnExcept(keep: List<Long>)

    /** The advertiser's mark under [generation] (`TagSchedule.MarkStore.read`); null fields when none, no row when gone. */
    @Query("SELECT markSlot, markRestart FROM own_presence_keys WHERE generation = :generation")
    suspend fun mark(generation: Long): OwnMark?

    /**
     * Sets the mark under [generation] only while it is the current key: activated and the newest activated (ADR
     * 2026-11.xp6d, step 5b). 0 when the row is gone or a rotation demoted it to the previous key, which is never aired
     * again; the caller treats that as a failed write, so no tag goes on air under a key a leaver holds.
     */
    @Query(
        "UPDATE own_presence_keys SET markSlot = :slot, markRestart = :restart WHERE generation = :generation " +
            "AND activatedAt IS NOT NULL " +
            "AND generation = (SELECT MAX(generation) FROM own_presence_keys WHERE activatedAt IS NOT NULL)",
    )
    suspend fun setCurrentMark(
        generation: Long,
        slot: Long,
        restart: Int,
    ): Int

    /** [peers]' stored keys, newest generation first within each peer. */
    @Query("SELECT * FROM peer_presence_keys WHERE nodeId IN (:peers) ORDER BY nodeId, generation DESC")
    suspend fun peerKeys(peers: List<String>): List<PeerPresenceKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPeerKeys(rows: List<PeerPresenceKeyEntity>)

    @Query("DELETE FROM peer_presence_keys WHERE nodeId = :nodeId")
    suspend fun deletePeerKeys(nodeId: String)

    /**
     * Each peer key's (node, generation, arrival), as tokens: a change re-lists the resolver's keys. The arrival is there
     * for a new era that reuses a generation number with other bytes (a sender restored twice from one backup): `applyHeld`
     * keeps a row's `receivedAt` only while its bytes and root both match, so the token moves exactly when the key or its
     * era does, with neither in it. Never a key or a root tag, and no own row: own keys reach the advertiser through the
     * engine, and the listing does not read them.
     */
    @Query("SELECT nodeId || ':' || generation || ':' || receivedAt FROM peer_presence_keys")
    fun observeKeyRows(): Flow<List<String>>

    /** Every peer key's row without its key, for the debug bridge's `KEYS`. */
    @Query("SELECT nodeId, generation, receivedAt FROM peer_presence_keys ORDER BY nodeId, generation DESC")
    suspend fun peerKeyRows(): List<PeerKeyRow>

    /** Every peer we hold a key for. */
    @Query("SELECT DISTINCT nodeId FROM peer_presence_keys")
    suspend fun peersWithKeys(): List<String>

    /** [peersWithKeys] as it changes: the node ids alone, never a generation or an arrival, for the Diagnostics screen. */
    @Query("SELECT DISTINCT nodeId FROM peer_presence_keys")
    fun observePeersWithKeys(): Flow<List<String>>

    @Query("DELETE FROM peer_presence_keys WHERE nodeId = :nodeId AND generation NOT IN (:keep)")
    suspend fun deletePeerKeysExcept(
        nodeId: String,
        keep: List<Long>,
    )
}

/** The advertiser's mark columns of one own generation. */
data class OwnMark(
    val markSlot: Long?,
    val markRestart: Int?,
)

/** A restore's link-owed resets ([LinkOwedResetEntity], ADR 2026-11.xp6d). Never logs a row: it names contacts. */
@Dao
interface LinkOwedResetDao {
    /** A restore's link-owed resets ([LinkOwedResetEntity]). */
    @Query("SELECT * FROM link_owed_resets")
    suspend fun linkOwedResets(): List<LinkOwedResetEntity>

    @Query("DELETE FROM link_owed_resets")
    suspend fun clearLinkOwedResets()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLinkOwedResets(rows: List<LinkOwedResetEntity>)

    /** Records the sealed reset for [nodeId]; 0 when it is no longer owed one. */
    @Query("UPDATE link_owed_resets SET wire = :wire, rootTag = :rootTag WHERE nodeId = :nodeId")
    suspend fun recordLinkOwedReset(
        nodeId: String,
        wire: ByteArray?,
        rootTag: Long?,
    ): Int

    @Query("DELETE FROM link_owed_resets WHERE nodeId = :nodeId")
    suspend fun dropLinkOwedReset(nodeId: String)
}
