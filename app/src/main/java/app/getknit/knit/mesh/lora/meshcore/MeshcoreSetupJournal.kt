package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.ReplaceToken
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Where the MeshCore setup record lives (ADR 2026-11.gsqu's Stage 5b-2 amendment, part 2; `data/meshcore/MeshcoreSetupStore`
 * under SQLCipher): one record per board address, for the board whose key digest it names ([MeshcoreSetupEntry.digest]),
 * and whether an unbind or a failed setup left it as a pending cleanup. **The single source of consent**, read and written by
 * the session's actor alone (the gate, Restore, the cleanup); the offers and the profile's claim observe the consent the
 * actor publishes (`MeshcoreBoardOps.consent`). A record counts only for the board its digest names, not pending, on a
 * bonded link whose board signed the consent challenge. Never logged.
 */
internal interface MeshcoreSetupJournal {
    /** The record for [address], or null when this phone never set a board up there (or its Restore finished). */
    suspend fun entryOf(address: String): MeshcoreSetupEntry?

    /** Writes the record for the board at [address] whose key digest is [digest], in one write. */
    suspend fun put(
        address: String,
        digest: String,
        record: MeshcoreSetupRecord,
        pending: Boolean,
    )

    /** An unbind that could not run its Restore: it runs at that board's next connection. False when there was no row. */
    suspend fun markPending(address: String): Boolean

    suspend fun clear(address: String)
}

/** One board's setup record, the digest of the board it is for, and whether it waits for a cleanup. Names no field. */
internal class MeshcoreSetupEntry(
    val record: MeshcoreSetupRecord,
    val digest: String,
    val pending: Boolean,
) {
    /** The consent for the board whose key digest is [digest]: the record is its, and not pending. */
    fun consents(digest: String): Boolean = !pending && this.digest == digest

    override fun toString(): String = "MeshcoreSetupEntry(pending=$pending)"

    /**
     * A fingerprint of everything this entry holds (Stage 7's [ReplaceToken]): the digest, the pending mark and every field of
     * the record, so a replace refuses a record that moved in any way since its refusal. SHA-256, never shown.
     */
    fun fingerprint(): ByteArray {
        val name = record.priorName ?: ByteArray(0)
        val buf =
            ByteBuffer
                .allocate(FINGERPRINT_FIXED + digest.length * 2 + name.size)
                .putInt(digest.length)
        digest.forEach { buf.putChar(it) }
        buf
            .put(if (pending) 1 else 0)
            .putInt(record.slot)
            .put(if (record.slotWritten) 1 else 0)
            .putInt(if (record.priorName == null) -1 else name.size)
            .put(name)
            .putInt(record.priorManualAdd ?: ABSENT)
            .putInt(record.priorAutoadd ?: ABSENT)
        return MessageDigest.getInstance("SHA-256").digest(buf.array().copyOf(buf.position()))
    }

    /** The token a different-board refusal hands out for this entry at [address]. */
    fun tokenAt(address: String): ReplaceToken = ReplaceToken(address, fingerprint())

    private companion object {
        const val FINGERPRINT_FIXED = 4 + 1 + 4 + 1 + 4 + 4 + 4
        const val ABSENT = Int.MIN_VALUE
    }
}

/**
 * The setup state the radio screen shows (ADR 2026-11.gsqu's Stage 7 amendment), from a read of the record ([read]: null when
 * every try failed) and, with the board up ([away] false), the connected board's [digest] and whether this connection
 * consents. Pure. Never [BoardSetupState.NONE] for what is not known: a failed read, a board up whose digest is not read
 * yet, and a record of this board that this connection did not prove (no answer, a refused proof, an unbonded board) are
 * all [BoardSetupState.UNKNOWN]. A record for another key at the address is [BoardSetupState.NONE]: this board is not set
 * up, and a setup is refused as a different board.
 */
internal object MeshcoreSetupStates {
    fun of(
        read: Result<MeshcoreSetupEntry?>?,
        digest: String?,
        consent: Boolean,
        away: Boolean,
    ): BoardSetupState {
        if (read == null || read.isFailure) return BoardSetupState.UNKNOWN
        val entry = read.getOrNull() ?: return BoardSetupState.NONE
        return when {
            away -> if (entry.pending) BoardSetupState.PENDING else BoardSetupState.SET_UP
            digest == null -> BoardSetupState.UNKNOWN
            entry.digest != digest -> BoardSetupState.NONE
            entry.pending -> BoardSetupState.PENDING
            consent -> BoardSetupState.SET_UP
            else -> BoardSetupState.UNKNOWN
        }
    }
}

/** The journal in memory: the JVM tests', and a plane built with no store. */
internal class MemoryMeshcoreSetupJournal : MeshcoreSetupJournal {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, MeshcoreSetupEntry>()

    override suspend fun entryOf(address: String): MeshcoreSetupEntry? = entries[address]

    override suspend fun put(
        address: String,
        digest: String,
        record: MeshcoreSetupRecord,
        pending: Boolean,
    ) {
        entries[address] = MeshcoreSetupEntry(record, digest, pending)
    }

    override suspend fun markPending(address: String): Boolean =
        entries.computeIfPresent(address) { _, entry -> MeshcoreSetupEntry(entry.record, entry.digest, pending = true) } != null

    override suspend fun clear(address: String) {
        entries.remove(address)
    }
}
