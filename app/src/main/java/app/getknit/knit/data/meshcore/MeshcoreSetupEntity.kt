package app.getknit.knit.data.meshcore

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * What a MeshCore setup changed on one board, and that this phone's user consented to it (ADR 2026-11.gsqu's Stage 5b-2
 * amendment, part 2): keyed by the board's BLE [address], the slot Knit's channel went into ([slot], [slotWritten]), the
 * advert name before Knit renamed the board ([priorName] — it may be the user's real name, which is why the record is in
 * SQLCipher and never in DataStore), `manual_add_contacts` ([priorManualAdd]) and `autoadd_config` ([priorAutoadd]). No
 * contacts snapshot and no added-keys list: setup erased the board, and Knit's taught set is its whole table.
 *
 * Its existence is the consent gate, read and written by the session's actor alone: the session sends, pins, teaches and
 * removes only on a bonded board with a row naming its key ([pendingCleanup] false) — a board's own Knit slot and `Knit abcd` name are a claim any client can write. [pendingCleanup]
 * is an unbind that could not reach the board: Restore's clearing runs at that board's next connection, then the row goes.
 *
 * This phone's and this board's alone: never in a backup (`BackupTables.DEVICE_LOCAL`, ADR 2026-09.6mj7), cleared by
 * sign-out with every table, kept across a bind of the other kind (`BoardBindPolicy` edits DataStore only). Its
 * [toString] names no field: a row never reaches a log line.
 */
@Entity(tableName = "meshcore_setup")
data class MeshcoreSetupEntity(
    @PrimaryKey val address: String,
    /**
     * The first bytes of the SHA-256 of the board's own key, hex (`MeshcoreHooks.digest`): the board at [address] the record
     * is for. It stops a replaced or reflashed board (a new key); it cannot stop a spoofer, since a board's key is in its own
     * adverts — the signed challenge does (the board signs a fresh nonce with that key before any consent, Restore or
     * cleanup; the maintainer's decision, 2026-11-13), and the bond is belt and braces.
     */
    val boardDigest: String,
    val slot: Int,
    val slotWritten: Boolean,
    val priorName: ByteArray?,
    val priorManualAdd: Int?,
    val priorAutoadd: Int?,
    val pendingCleanup: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is MeshcoreSetupEntity && address == other.address && boardDigest == other.boardDigest && slot == other.slot &&
            slotWritten == other.slotWritten &&
            priorName.contentEqualsOrNull(other.priorName) && priorManualAdd == other.priorManualAdd &&
            priorAutoadd == other.priorAutoadd && pendingCleanup == other.pendingCleanup

    override fun hashCode(): Int = address.hashCode()

    override fun toString(): String = "MeshcoreSetupEntity(pending=$pendingCleanup)"

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
        if (this == null || other == null) this === other else contentEquals(other)
}

/** The MeshCore setup records ([MeshcoreSetupEntity]). */
@Dao
interface MeshcoreSetupDao {
    @Query("SELECT * FROM meshcore_setup WHERE address = :address")
    suspend fun get(address: String): MeshcoreSetupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MeshcoreSetupEntity)

    @Query("UPDATE meshcore_setup SET pendingCleanup = 1 WHERE address = :address")
    suspend fun markPending(address: String): Int

    @Query("DELETE FROM meshcore_setup WHERE address = :address")
    suspend fun clear(address: String)

    /** Whether any setup record is kept, for any board (the restore dialog's warning, Stage 7). */
    @Query("SELECT EXISTS(SELECT 1 FROM meshcore_setup)")
    suspend fun any(): Boolean
}
