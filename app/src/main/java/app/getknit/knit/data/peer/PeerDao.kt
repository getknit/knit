package app.getknit.knit.data.peer

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // the pin and its proof of key: two tables behind one DAO, so they are swept together
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY name ASC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun findByNodeId(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    fun observeByNodeId(nodeId: String): Flow<PeerEntity?>

    /**
     * The peer whose latest profile claims LoRa board [node] — the newest claim wins, since a board that
     * changed hands is named by two profiles until the old holder's next one drops it.
     */
    @Query("SELECT * FROM peers WHERE loraNode = :node ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findByLoraNode(node: Long): PeerEntity?

    /** Every peer's [PeerName] — the light projection the name-collision index is built from. */
    @Query("SELECT nodeId, name, verified, lastSeenAt FROM peers")
    suspend fun namesAll(): List<PeerName>

    /**
     * Moves [nodeId]'s `lastSeenAt` up to [at] when the stored stamp is older than [olderThan]; returns the rows
     * written. A stamp already inside that gate matches no row, and a row-level trigger fires for none, so the
     * peers flows stay quiet (ADR 2026-10.k8ge).
     */
    @Query("UPDATE peers SET lastSeenAt = :at WHERE nodeId = :nodeId AND lastSeenAt < :olderThan")
    suspend fun markSeen(
        nodeId: String,
        at: Long,
        olderThan: Long,
    ): Int

    /** The faces of [ids] alone — primary-key seeks, however many peers the table holds. */
    @Query("SELECT nodeId, name, avatarHash FROM peers WHERE nodeId IN (:ids)")
    fun observeFaces(ids: Collection<String>): Flow<List<PeerFace>>

    @Query("UPDATE peers SET verified = :verified WHERE nodeId = :nodeId")
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    )

    /**
     * The faces peers show whose held blob is longer than [max] bytes (#234): what a build before the photo ceiling
     * adopted. One join on the hash; the length is read without the bytes.
     */
    @Query(
        "SELECT DISTINCT p.avatarHash FROM peers p JOIN blobs b ON b.hash = p.avatarHash WHERE length(b.bytes) > :max",
    )
    suspend fun avatarsLongerThan(max: Long): List<String>

    /** Clears the face of every peer showing one of [hashes]; returns how many rows changed. */
    @Query("UPDATE peers SET avatarHash = NULL WHERE avatarHash IN (:hashes)")
    suspend fun clearAvatars(hashes: List<String>): Int

    /** How many peers reference avatar blob [hash] — part of the orphaned-blob garbage-collection check. */
    @Query("SELECT COUNT(*) FROM peers WHERE avatarHash = :hash")
    suspend fun countByAvatarHash(hash: String): Int

    /** Node ids the user has out-of-band verified — exempt from the cap and the message-request queue. */
    @Query("SELECT nodeId FROM peers WHERE verified = 1")
    suspend fun verifiedNodeIds(): List<String>

    /** Count of unverified peers not in [protected] — the pool [evictOldestCappable] may trim. */
    @Query("SELECT COUNT(*) FROM peers WHERE verified = 0 AND nodeId NOT IN (:protected)")
    suspend fun countCappable(protected: Collection<String>): Int

    /** The [over] oldest-by-`updatedAt` unverified peers not in [protected]: what [deleteCappable] evicts. */
    @Query("SELECT nodeId FROM peers WHERE verified = 0 AND nodeId NOT IN (:protected) ORDER BY updatedAt ASC LIMIT :over")
    suspend fun oldestCappable(
        protected: Collection<String>,
        over: Int,
    ): List<String>

    /** Evicts the unverified peers among [nodeIds] (a peer verified since they were chosen stays); returns how many went. */
    @Query("DELETE FROM peers WHERE verified = 0 AND nodeId IN (:nodeIds)")
    suspend fun deleteCappable(nodeIds: Collection<String>): Int

    @Query("DELETE FROM peers WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)

    /**
     * Keeps [signed]/[sig] as [nodeId]'s proof of key ([PeerProfileEntity]) unless the row already holds a copy
     * stamped [sentAt] or later — so the newest publish wins whatever order the copies arrive in, and a copy we
     * already hold is never rewritten. `ON CONFLICT DO UPDATE … WHERE`, not `@Upsert`, for [upsert]'s reason.
     */
    @Query(
        "INSERT INTO peer_profiles (nodeId, signed, sig, sentAt) VALUES (:nodeId, :signed, :sig, :sentAt) " +
            "ON CONFLICT(nodeId) DO UPDATE SET signed = excluded.signed, sig = excluded.sig, sentAt = excluded.sentAt " +
            "WHERE excluded.sentAt > peer_profiles.sentAt",
    )
    suspend fun recordProfile(
        nodeId: String,
        signed: ByteArray,
        sig: ByteArray,
        sentAt: Long,
    )

    @Query("SELECT * FROM peer_profiles WHERE nodeId = :nodeId")
    suspend fun profile(nodeId: String): PeerProfileEntity?

    /** Drops every proof of key whose pin is gone — run after each deletion from `peers`. */
    @Query("DELETE FROM peer_profiles WHERE nodeId NOT IN (SELECT nodeId FROM peers)")
    suspend fun deleteOrphanProfiles()

    /** Drops every proof of key whose `signed` is longer than [max] bytes: what a build before the profile cap kept (#234). */
    @Query("DELETE FROM peer_profiles WHERE length(signed) > :max")
    suspend fun deleteProfilesLongerThan(max: Int): Int

    /** Drops every badge whose pin is gone — run beside [deleteOrphanProfiles]. */
    @Query("DELETE FROM peer_badges WHERE nodeId NOT IN (SELECT nodeId FROM peers)")
    suspend fun deleteOrphanBadges()

    @Query("DELETE FROM peer_badges WHERE nodeId = :nodeId")
    suspend fun deleteBadges(nodeId: String)

    @Insert
    suspend fun insertBadges(badges: List<PeerBadgeEntity>)

    /** Every badge held, in each sender's order: the input the display-time verifier reads. */
    @Query("SELECT * FROM peer_badges ORDER BY nodeId, position")
    fun observeBadges(): Flow<List<PeerBadgeEntity>>

    @Query("SELECT * FROM peer_badges ORDER BY nodeId, position")
    suspend fun badges(): List<PeerBadgeEntity>

    /**
     * Writes [peer], replacing every column in place when the row is already there. Every caller keeps passing
     * one [PeerEntity]; this plain function (nothing Room processes) unpacks it onto [upsertRow].
     *
     * Spelled out rather than `@Upsert` on purpose, the same fix as [app.getknit.knit.data.draft.DraftDao.upsert]
     * (work item 60): Room's `@Upsert` is an INSERT it expects to fail on an existing key, then an UPDATE, and
     * SQLCipher logs every failed statement at `E` before Room's catch runs — and every inbound profile frame
     * upserts a peer, so this one logged far more often than the draft save that fix was written for.
     * `ON CONFLICT DO UPDATE` never issues a failing statement. The parameters are flattened because Room's
     * `:entity.column` bind syntax is refused by this processor at build time. `lastSeenAt` is the one column
     * merged rather than replaced: it takes the larger stamp, so a caller that read the row before a stamp landed
     * never writes it back older (ADR 2026-10.k8ge).
     */
    suspend fun upsert(peer: PeerEntity) =
        upsertRow(
            nodeId = peer.nodeId,
            name = peer.name,
            status = peer.status,
            avatarHash = peer.avatarHash,
            pubKey = peer.pubKey,
            verified = peer.verified,
            deviceTag = peer.deviceTag,
            protoVersion = peer.protoVersion,
            capabilities = peer.capabilities,
            updatedAt = peer.updatedAt,
            prekeyId = peer.prekeyId,
            prekeyPub = peer.prekeyPub,
            prekeySig = peer.prekeySig,
            prekeyProfileAt = peer.prekeyProfileAt,
            openToChat = peer.openToChat,
            loraNode = peer.loraNode,
            loraKey = peer.loraKey,
            lastSeenAt = peer.lastSeenAt,
        )

    /** The statement [upsert] actually runs, flattened — see its doc comment for why. */
    @Suppress("LongParameterList") // one column per field of PeerEntity; see the doc comment above
    @Query(
        "INSERT INTO peers (nodeId, name, status, avatarHash, pubKey, verified, deviceTag, protoVersion, " +
            "capabilities, updatedAt, prekeyId, prekeyPub, prekeySig, prekeyProfileAt, openToChat, loraNode, loraKey, " +
            "lastSeenAt) " +
            "VALUES (:nodeId, :name, :status, :avatarHash, :pubKey, :verified, :deviceTag, :protoVersion, " +
            ":capabilities, :updatedAt, :prekeyId, :prekeyPub, :prekeySig, :prekeyProfileAt, :openToChat, " +
            ":loraNode, :loraKey, :lastSeenAt) " +
            "ON CONFLICT(nodeId) DO UPDATE SET " +
            "name = excluded.name, status = excluded.status, avatarHash = excluded.avatarHash, " +
            "pubKey = excluded.pubKey, verified = excluded.verified, deviceTag = excluded.deviceTag, " +
            "protoVersion = excluded.protoVersion, capabilities = excluded.capabilities, " +
            "updatedAt = excluded.updatedAt, prekeyId = excluded.prekeyId, prekeyPub = excluded.prekeyPub, " +
            "prekeySig = excluded.prekeySig, prekeyProfileAt = excluded.prekeyProfileAt, " +
            "openToChat = excluded.openToChat, loraNode = excluded.loraNode, loraKey = excluded.loraKey, " +
            "lastSeenAt = MAX(peers.lastSeenAt, excluded.lastSeenAt)",
    )
    suspend fun upsertRow(
        nodeId: String,
        name: String,
        status: String,
        avatarHash: String?,
        pubKey: String?,
        verified: Boolean,
        deviceTag: String?,
        protoVersion: Int?,
        capabilities: Long?,
        updatedAt: Long,
        prekeyId: Int?,
        prekeyPub: String?,
        prekeySig: String?,
        prekeyProfileAt: Long?,
        openToChat: Boolean,
        loraNode: Long?,
        loraKey: String?,
        lastSeenAt: Long,
    )
}
