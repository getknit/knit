package app.getknit.knit.transfer

import app.getknit.knit.data.message.TransferRecord

/**
 * What a transfer reads from and writes to: a picked file and a Downloads entry, or a bundle of translation models and
 * its staging (ADR 2026-11.ejn8). Kept apart from [TransferManager] for detekt's LargeClass, as [TransferListener] is.
 * A models transfer never falls back to a file end, nor a file transfer to a models one.
 */
internal class TransferEnds(
    private val files: TransferFiles,
    val models: TransferModels?,
) {
    suspend fun file(uri: String): TransferSource? = files.openSource(uri)

    fun bundle(pins: List<Long>): TransferSource? = models?.bundle(pins)

    fun wanted(pins: List<Long>): ModelsWanted? = models?.wanted(pins)

    fun freeBytes(): Long = files.freeBytes()

    /** What [state] sends: its bundle for a models transfer, else the file at [sourceUri]. */
    suspend fun source(
        state: TransferState,
        sourceUri: String?,
    ): TransferSource? {
        val pins = state.models
        return if (pins != null) bundle(pins) else sourceUri?.let { file(it) }
    }

    /** Where [state]'s bytes go: a bundle's staging, never a Downloads entry, for a models transfer. */
    suspend fun sink(state: TransferState): TransferSink? {
        val pins = state.models
        return if (pins != null) models?.sink(pins) else files.createSink(state.name, state.mime, state.size)
    }

    /** Whether a Retry of [record] would find what it sends: its file, or every model still installed. */
    suspend fun retryReadable(record: TransferRecord): Boolean {
        val pins = record.models
        return if (pins != null) bundle(pins) != null else record.sourceUri?.let { file(it) } != null
    }
}
