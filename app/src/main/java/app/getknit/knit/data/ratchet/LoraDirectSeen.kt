package app.getknit.knit.data.ratchet

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase

/**
 * One DM whose message row a compact LoRa copy wrote, or that a compact copy could still resurrect (ADR 2026-10.x9fa).
 * Two jobs, both about the copy the ratchet seals beside it:
 *
 * - **The ratchet still sees its frame.** A compact copy usually lands first and writes the row, and the signed copy
 *   then meets the pre-decrypt exists-gate and was never opened: its message key a skipped key for two days, and —
 *   when every frame of a session's opening run came by LoRa — its X3DH init never processed. With a row here and
 *   [ratchetDone] false, `InboundPipeline.decryptAndDeliver` opens the signed copy anyway, commits the ratchet's step
 *   and sets [ratchetDone], and writes no row: the ratchet sees exactly the sequence it would without the LoRa leg.
 * - **A deleted message stays deleted.** The compact path refuses any frame id held here whether or not its row still
 *   exists, and `LocalMessageDelete` never touches this table, so a compact packet replayed inside its 24 h window
 *   cannot bring back a row the person deleted. A signed copy that lands first writes a row here with [ratchetDone]
 *   already true, for the same reason, toward a peer whose board could re-air the compact copy.
 *
 * [arrivedAt] is our own clock; the row is swept 48 h on ([LoraDirectSeenRepository.RETENTION_MS]), past the compact
 * window (24 h of the sender's clock plus the skew) and the spool's 48 h. Never in a backup: it describes ratchet
 * state, which a restore never carries (`BackupTables.TRANSIENT`).
 */
@Entity(tableName = "lora_direct_seen", primaryKeys = ["frameId"])
data class LoraDirectSeenEntity(
    val frameId: String,
    /** The authenticated sender: the pair the compact copy opened under, or the signed copy's verified sender. */
    val peerId: String,
    val arrivedAt: Long,
    val ratchetDone: Boolean,
)

@Dao
interface LoraDirectSeenDao {
    @Query("SELECT * FROM lora_direct_seen WHERE frameId = :frameId")
    suspend fun find(frameId: String): LoraDirectSeenEntity?

    /** Inserts [row] unless its frame id is held; the row id, or -1 when it was. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: LoraDirectSeenEntity): Long

    @Query("UPDATE lora_direct_seen SET ratchetDone = 1 WHERE frameId = :frameId")
    suspend fun markRatchetDone(frameId: String)

    @Query("DELETE FROM lora_direct_seen WHERE arrivedAt < :before")
    suspend fun deleteArrivedBefore(before: Long): Int
}

/** The `lora_direct_seen` table's one door; every write is inside a caller's transaction or its own. */
class LoraDirectSeenRepository(
    private val db: KnitDatabase,
) {
    private val dao: LoraDirectSeenDao get() = db.loraDirectSeenDao()

    suspend fun find(frameId: String): LoraDirectSeenEntity? = dao.find(frameId)

    /** Records [frameId] from [peerId]; false when it was already held (the caller's write is then a duplicate). */
    suspend fun recordIfAbsent(
        frameId: String,
        peerId: String,
        arrivedAt: Long,
        ratchetDone: Boolean,
    ): Boolean = dao.insertIfAbsent(LoraDirectSeenEntity(frameId, peerId, arrivedAt, ratchetDone)) != -1L

    suspend fun markRatchetDone(frameId: String) = dao.markRatchetDone(frameId)

    /** Drops what arrived more than [RETENTION_MS] before [now] on our own clock; returns the rows removed. */
    suspend fun sweep(now: Long): Int = db.withWriteTransaction { dao.deleteArrivedBefore(now - RETENTION_MS) }

    companion object {
        /**
         * How long a row is kept, on our own clock: the compact window is 24 h of the sender's `sentAt`, the spool keeps
         * a signed copy 48 h, so two days covers every copy that can still reach us.
         */
        const val RETENTION_MS = 48 * 60 * 60_000L
    }
}
