package app.getknit.knit.data.meshcore

import app.getknit.knit.mesh.lora.meshcore.MeshcoreSetupEntry
import app.getknit.knit.mesh.lora.meshcore.MeshcoreSetupJournal
import app.getknit.knit.mesh.lora.meshcore.MeshcoreSetupRecord

/** [MeshcoreSetupJournal] over the SQLCipher `meshcore_setup` table ([MeshcoreSetupDao]). */
internal class MeshcoreSetupStore(
    private val dao: MeshcoreSetupDao,
) : MeshcoreSetupJournal {
    override suspend fun entryOf(address: String): MeshcoreSetupEntry? =
        dao.get(address)?.let { row ->
            MeshcoreSetupEntry(
                MeshcoreSetupRecord(row.slot, row.slotWritten, row.priorName, row.priorManualAdd, row.priorAutoadd),
                digest = row.boardDigest,
                pending = row.pendingCleanup,
            )
        }

    override suspend fun put(
        address: String,
        digest: String,
        record: MeshcoreSetupRecord,
        pending: Boolean,
    ) = dao.put(
        MeshcoreSetupEntity(
            address = address,
            boardDigest = digest,
            slot = record.slot,
            slotWritten = record.slotWritten,
            priorName = record.priorName,
            priorManualAdd = record.priorManualAdd,
            priorAutoadd = record.priorAutoadd,
            pendingCleanup = pending,
        ),
    )

    override suspend fun markPending(address: String): Boolean = dao.markPending(address) > 0

    override suspend fun clear(address: String) = dao.clear(address)
}
