package app.getknit.knit.data.ratchet

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * Row-level operations for the group sender-key ratchet state (crypto scheme v2, group form). Thin by rule
 * (`.agents/rules/coding.md`): no `@Transaction` methods — atomicity lives at the repository callers'
 * `db.withWriteTransaction`.
 */
@Suppress("TooManyFunctions") // small focused queries over four tables; splitting would obscure
@Dao
interface GroupRatchetDao {
    // --- our send chains ---

    @Query("SELECT * FROM group_send_chains WHERE groupId = :groupId ORDER BY epoch DESC LIMIT 1")
    suspend fun newestSendChain(groupId: String): GroupSendChainEntity?

    @Query("SELECT * FROM group_send_chains WHERE groupId = :groupId ORDER BY epoch DESC")
    suspend fun sendChains(groupId: String): List<GroupSendChainEntity>

    @Query("SELECT DISTINCT groupId FROM group_send_chains")
    suspend fun sendChainGroupIds(): List<String>

    /**
     * Writes [row], replacing every non-key column in place when (groupId, epoch) is already there. Callers keep
     * passing one [GroupSendChainEntity]; this plain function (nothing Room processes) unpacks it onto
     * [upsertSendChainRow].
     *
     * Spelled out rather than `@Upsert` on purpose, the same fix as [app.getknit.knit.data.draft.DraftDao.upsert]
     * (work item 60), [app.getknit.knit.data.peer.PeerDao.upsert] and [app.getknit.knit.data.group.GroupDao.upsert]:
     * Room's `@Upsert` is an INSERT it expects to fail on an existing key, then an UPDATE, and SQLCipher logs every
     * failed statement at `E` before Room's catch runs — lab phones logged a "UNIQUE constraint failed: group_send_chains.groupId" line
     * per sealed frame. `ON CONFLICT DO UPDATE` never issues a failing statement, and overwrites every non-key column
     * exactly as `@Upsert` did. The parameters are flattened because Room's `:entity.column` bind syntax is refused
     * by this processor at build time.
     */
    suspend fun upsertSendChain(row: GroupSendChainEntity) =
        upsertSendChainRow(
            groupId = row.groupId,
            epoch = row.epoch,
            seed = row.seed,
            chainKey = row.chainKey,
            count = row.count,
            mintedAt = row.mintedAt,
            export = row.export,
            updatedAt = row.updatedAt,
        )

    /** The statement [upsertSendChain] actually runs, flattened — see its doc comment for why. */
    @Suppress("LongParameterList") // one column per field of GroupSendChainEntity; see upsertSendChain
    @Query(
        "INSERT INTO group_send_chains (groupId, epoch, seed, chainKey, count, mintedAt, export, updatedAt) " +
            "VALUES (:groupId, :epoch, :seed, :chainKey, :count, :mintedAt, :export, :updatedAt) " +
            "ON CONFLICT(groupId, epoch) DO UPDATE SET " +
            "seed = excluded.seed, chainKey = excluded.chainKey, count = excluded.count, " +
            "mintedAt = excluded.mintedAt, export = excluded.export, updatedAt = excluded.updatedAt",
    )
    suspend fun upsertSendChainRow(
        groupId: String,
        epoch: Int,
        seed: ByteArray,
        chainKey: ByteArray,
        count: Int,
        mintedAt: Long,
        export: ByteArray,
        updatedAt: Long,
    )

    @Query("DELETE FROM group_send_chains WHERE groupId = :groupId")
    suspend fun deleteSendChainsFor(groupId: String)

    @Query("DELETE FROM group_send_chains WHERE groupId = :groupId AND epoch < :minEpoch")
    suspend fun deleteSendChainsBelow(
        groupId: String,
        minEpoch: Int,
    )

    // --- inbound per-sender chains ---

    @Query(
        "SELECT * FROM group_recv_chains WHERE groupId = :groupId AND senderId = :senderId AND epoch = :epoch " +
            "ORDER BY mintedAt DESC",
    )
    suspend fun recvChains(
        groupId: String,
        senderId: String,
        epoch: Int,
    ): List<GroupRecvChainEntity>

    /**
     * Writes [row], replacing every non-key column in place when (groupId, senderId, epoch, mintedAt) is already
     * there. Callers keep passing one [GroupRecvChainEntity]; this plain function (nothing Room processes) unpacks
     * it onto [upsertRecvChainRow].
     *
     * Spelled out rather than `@Upsert` on purpose, the same fix as [app.getknit.knit.data.draft.DraftDao.upsert]
     * (work item 60), [app.getknit.knit.data.peer.PeerDao.upsert] and [app.getknit.knit.data.group.GroupDao.upsert]:
     * Room's `@Upsert` is an INSERT it expects to fail on an existing key, then an UPDATE, and SQLCipher logs every
     * failed statement at `E` before Room's catch runs — lab phones logged a "UNIQUE constraint failed: group_recv_chains.groupId" line
     * per opened frame. `ON CONFLICT DO UPDATE` never issues a failing statement, and overwrites every non-key column
     * exactly as `@Upsert` did. The parameters are flattened because Room's `:entity.column` bind syntax is refused
     * by this processor at build time.
     */
    suspend fun upsertRecvChain(row: GroupRecvChainEntity) =
        upsertRecvChainRow(
            groupId = row.groupId,
            senderId = row.senderId,
            epoch = row.epoch,
            mintedAt = row.mintedAt,
            chainKey = row.chainKey,
            next = row.next,
            lastUsedAt = row.lastUsedAt,
        )

    /** The statement [upsertRecvChain] actually runs, flattened — see its doc comment for why. */
    @Suppress("LongParameterList") // one column per field of GroupRecvChainEntity; see upsertRecvChain
    @Query(
        "INSERT INTO group_recv_chains (groupId, senderId, epoch, mintedAt, chainKey, next, lastUsedAt) " +
            "VALUES (:groupId, :senderId, :epoch, :mintedAt, :chainKey, :next, :lastUsedAt) " +
            "ON CONFLICT(groupId, senderId, epoch, mintedAt) DO UPDATE SET " +
            "chainKey = excluded.chainKey, next = excluded.next, lastUsedAt = excluded.lastUsedAt",
    )
    suspend fun upsertRecvChainRow(
        groupId: String,
        senderId: String,
        epoch: Int,
        mintedAt: Long,
        chainKey: ByteArray,
        next: Int,
        lastUsedAt: Long,
    )

    @Query("DELETE FROM group_recv_chains WHERE lastUsedAt < :cutoff")
    suspend fun deleteRecvChainsBefore(cutoff: Long)

    @Query("DELETE FROM group_recv_chains WHERE groupId = :groupId")
    suspend fun deleteRecvChainsFor(groupId: String)

    // --- skipped keys ---

    @Query(
        "SELECT * FROM group_skipped_keys WHERE groupId = :groupId AND senderId = :senderId AND epoch = :epoch " +
            "AND idx = :idx ORDER BY mintedAt DESC",
    )
    suspend fun skippedKeys(
        groupId: String,
        senderId: String,
        epoch: Int,
        idx: Int,
    ): List<GroupSkippedKeyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkippedKeys(rows: List<GroupSkippedKeyEntity>)

    // One mint's key only, by the full primary key: another mint's key at the same (epoch, idx) opens another era's
    // frame (#177).
    @Query(
        "DELETE FROM group_skipped_keys WHERE groupId = :groupId AND senderId = :senderId AND epoch = :epoch " +
            "AND mintedAt = :mintedAt AND idx = :idx",
    )
    suspend fun deleteSkippedKey(
        groupId: String,
        senderId: String,
        epoch: Int,
        mintedAt: Long,
        idx: Int,
    )

    @Query("DELETE FROM group_skipped_keys WHERE createdAt < :cutoff")
    suspend fun deleteSkippedKeysBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM group_skipped_keys")
    suspend fun countSkippedKeys(): Int

    @Query(
        "DELETE FROM group_skipped_keys WHERE (groupId, senderId, epoch, mintedAt, idx) IN " +
            "(SELECT groupId, senderId, epoch, mintedAt, idx FROM group_skipped_keys ORDER BY createdAt ASC LIMIT :n)",
    )
    suspend fun evictOldestSkippedKeys(n: Int)

    @Query("DELETE FROM group_skipped_keys WHERE groupId = :groupId")
    suspend fun deleteSkippedKeysFor(groupId: String)

    // --- the seed outbox ---

    @Query("SELECT * FROM group_key_sends WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun keySend(
        groupId: String,
        memberId: String,
    ): GroupKeySendEntity?

    @Query("SELECT * FROM group_key_sends WHERE groupId = :groupId")
    suspend fun keySends(groupId: String): List<GroupKeySendEntity>

    /**
     * Writes [row], replacing every non-key column in place when (groupId, memberId) is already there. Callers keep
     * passing one [GroupKeySendEntity]; this plain function (nothing Room processes) unpacks it onto
     * [upsertKeySendRow].
     *
     * Spelled out rather than `@Upsert` on purpose, the same fix as [app.getknit.knit.data.draft.DraftDao.upsert]
     * (work item 60), [app.getknit.knit.data.peer.PeerDao.upsert] and [app.getknit.knit.data.group.GroupDao.upsert]:
     * Room's `@Upsert` is an INSERT it expects to fail on an existing key, then an UPDATE, and SQLCipher logs every
     * failed statement at `E` before Room's catch runs — lab phones logged a "UNIQUE constraint failed: group_key_sends.groupId" line
     * per seed send or ack. `ON CONFLICT DO UPDATE` never issues a failing statement, and overwrites every non-key column
     * exactly as `@Upsert` did. The parameters are flattened because Room's `:entity.column` bind syntax is refused
     * by this processor at build time.
     */
    suspend fun upsertKeySend(row: GroupKeySendEntity) =
        upsertKeySendRow(
            groupId = row.groupId,
            memberId = row.memberId,
            sentEpoch = row.sentEpoch,
            sentAt = row.sentAt,
            ackedEpoch = row.ackedEpoch,
            ackedAt = row.ackedAt,
        )

    /** The statement [upsertKeySend] actually runs, flattened — see its doc comment for why. */
    @Suppress("LongParameterList") // one column per field of GroupKeySendEntity; see upsertKeySend
    @Query(
        "INSERT INTO group_key_sends (groupId, memberId, sentEpoch, sentAt, ackedEpoch, ackedAt) " +
            "VALUES (:groupId, :memberId, :sentEpoch, :sentAt, :ackedEpoch, :ackedAt) " +
            "ON CONFLICT(groupId, memberId) DO UPDATE SET " +
            "sentEpoch = excluded.sentEpoch, sentAt = excluded.sentAt, ackedEpoch = excluded.ackedEpoch, " +
            "ackedAt = excluded.ackedAt",
    )
    suspend fun upsertKeySendRow(
        groupId: String,
        memberId: String,
        sentEpoch: Int,
        sentAt: Long,
        ackedEpoch: Int,
        ackedAt: Long,
    )

    @Query("DELETE FROM group_key_sends WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun deleteKeySend(
        groupId: String,
        memberId: String,
    )

    @Query("DELETE FROM group_key_sends WHERE groupId = :groupId")
    suspend fun deleteKeySendsFor(groupId: String)
}
