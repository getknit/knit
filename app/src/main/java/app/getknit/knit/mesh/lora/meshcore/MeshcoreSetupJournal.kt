package app.getknit.knit.mesh.lora.meshcore

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
