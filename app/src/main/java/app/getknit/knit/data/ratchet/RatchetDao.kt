package app.getknit.knit.data.ratchet

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * Ratchet-state queries. Unlike the carry store there is no convergence constraint here — this state is
 * strictly per-device — so retention sweeps are plain local GC. All quantities are tiny (sessions = DM
 * peers; epochs/skipped keys bounded by `RatchetRepository`'s caps).
 */
@Dao
@Suppress("TooManyFunctions") // small focused queries over four tables; splitting would obscure
interface RatchetDao {
    // --- sessions ---

    @Query("SELECT * FROM ratchet_sessions WHERE peerId = :peerId")
    suspend fun session(peerId: String): RatchetSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(row: RatchetSessionEntity)

    @Query("SELECT peerId FROM ratchet_sessions")
    suspend fun sessionPeerIds(): List<String>

    @Query("DELETE FROM ratchet_sessions WHERE peerId = :peerId")
    suspend fun deleteSession(peerId: String)

    // --- our epoch keypairs ---

    @Query("SELECT priv FROM ratchet_local_epochs WHERE peerId = :peerId AND epoch = :epoch")
    suspend fun localEpochPriv(
        peerId: String,
        epoch: Int,
    ): ByteArray?

    // REPLACE, not IGNORE: epoch numbering restarts at 1 when a session reset re-initiates, so a
    // re-minted number can collide with a surviving dead-era row. The live era must win — keeping the
    // dead key makes every peer frame based on the fresh epoch fail its AEAD with nothing to see in the
    // header. The frames the old key could still have served are pre-reset ciphertext, already stranded
    // by the re-root itself.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocalEpoch(row: RatchetLocalEpochEntity)

    // Newest = most recently MINTED, never the highest number. Epoch numbers restart on a session
    // reset, so a long-lived session banks high-numbered dead-era rows that outrank every live-era row
    // forever under numeric order — the sweep then keeps 16 dead keys and deletes each fresh epoch
    // within one cycle of minting it, wedging the pair in a reset-relapse loop (ADR 027). The epoch
    // tiebreak only orders rows minted in the same millisecond.
    @Query("SELECT * FROM ratchet_local_epochs WHERE peerId = :peerId ORDER BY createdAt DESC, epoch DESC")
    suspend fun localEpochsNewestFirst(peerId: String): List<RatchetLocalEpochEntity>

    // The lowest NUMBER held, deliberately not the oldest by mint time: a dead era's surviving rows (ADR 027)
    // only pull it down, which keeps the retired-base gate on the side of counting (ADR 2026-10.9wvr).
    @Query("SELECT MIN(epoch) FROM ratchet_local_epochs WHERE peerId = :peerId")
    suspend fun lowestLocalEpoch(peerId: String): Int?

    @Query("DELETE FROM ratchet_local_epochs WHERE peerId = :peerId AND epoch IN (:epochs)")
    suspend fun deleteLocalEpochs(
        peerId: String,
        epochs: List<Int>,
    )

    @Query("DELETE FROM ratchet_local_epochs WHERE peerId = :peerId")
    suspend fun deleteLocalEpochsFor(peerId: String)

    // --- inbound epoch chains ---

    // Every era's chain for the number: a reset restarts the peer's numbering, and each root reads only its own
    // (`rootTag`, ADR 2026-10.gcrd). At most a live root, a draining one and a late race loser's.
    @Query("SELECT * FROM ratchet_recv_epochs WHERE peerId = :peerId AND epoch = :epoch")
    suspend fun recvEpochs(
        peerId: String,
        epoch: Int,
    ): List<RatchetRecvEpochEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecvEpoch(row: RatchetRecvEpochEntity)

    @Query("DELETE FROM ratchet_recv_epochs WHERE lastUsedAt < :cutoff")
    suspend fun deleteRecvEpochsBefore(cutoff: Long): Int

    @Query("DELETE FROM ratchet_recv_epochs WHERE peerId = :peerId")
    suspend fun deleteRecvEpochsFor(peerId: String)

    /** [deleteRecvEpochsFor] but the chains of the one root [keepTag] names — a reclaim's purge (ADR 2026-10.4mjm). */
    @Query("DELETE FROM ratchet_recv_epochs WHERE peerId = :peerId AND rootTag != :keepTag")
    suspend fun deleteRecvEpochsForExcept(
        peerId: String,
        keepTag: Long,
    )

    // --- skipped message keys ---

    /** Every era's stored key for `(epoch, idx)`, as [recvEpochs]. */
    @Query("SELECT * FROM ratchet_skipped_keys WHERE peerId = :peerId AND epoch = :epoch AND idx = :idx")
    suspend fun skippedKeys(
        peerId: String,
        epoch: Int,
        idx: Int,
    ): List<RatchetSkippedKeyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkippedKeys(rows: List<RatchetSkippedKeyEntity>)

    // One root's key only: another tag's key at the same (epoch, idx) belongs to another era's frame.
    @Query("DELETE FROM ratchet_skipped_keys WHERE peerId = :peerId AND epoch = :epoch AND idx = :idx AND rootTag = :rootTag")
    suspend fun deleteSkippedKey(
        peerId: String,
        epoch: Int,
        idx: Int,
        rootTag: Long,
    )

    @Query("DELETE FROM ratchet_skipped_keys WHERE createdAt < :cutoff")
    suspend fun deleteSkippedKeysBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM ratchet_skipped_keys")
    suspend fun countSkippedKeys(): Int

    /** Global-cap pressure valve: drop the [n] oldest skipped keys (they were the least likely to arrive). */
    @Query(
        "DELETE FROM ratchet_skipped_keys WHERE (peerId, epoch, idx, rootTag) IN " +
            "(SELECT peerId, epoch, idx, rootTag FROM ratchet_skipped_keys ORDER BY createdAt ASC LIMIT :n)",
    )
    suspend fun evictOldestSkippedKeys(n: Int)

    @Query("DELETE FROM ratchet_skipped_keys WHERE peerId = :peerId")
    suspend fun deleteSkippedKeysFor(peerId: String)

    /** [deleteSkippedKeysFor] but the keys of the one root [keepTag] names — a reclaim's purge (ADR 2026-10.4mjm). */
    @Query("DELETE FROM ratchet_skipped_keys WHERE peerId = :peerId AND rootTag != :keepTag")
    suspend fun deleteSkippedKeysForExcept(
        peerId: String,
        keepTag: Long,
    )
}
