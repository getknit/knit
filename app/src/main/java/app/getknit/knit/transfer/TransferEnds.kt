package app.getknit.knit.transfer

import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.AppPin
import app.getknit.knit.mesh.protocol.TransferPayload
import java.io.IOException

/**
 * What a transfer reads from and writes to: a picked file and a Downloads entry, a bundle of translation models and
 * its staging (ADR 2026-11.ejn8), or this app's own APK and its update staging (ADR 2026-11.29m7). Kept apart from
 * [TransferManager] for detekt's LargeClass, as [TransferListener] is. No kind of transfer ever falls back to another's
 * end.
 */
internal class TransferEnds(
    private val files: TransferFiles,
    val models: TransferModels?,
    val app: TransferAppUpdate? = null,
) {
    suspend fun file(uri: String): TransferSource? = files.openSource(uri)

    fun bundle(pins: List<Long>): TransferSource? = models?.bundle(pins)

    fun wanted(pins: List<Long>): ModelsWanted? = models?.wanted(pins)

    fun freeBytes(): Long = files.freeBytes()

    /**
     * [payload] from [senderId] as an offer to admit, or null: an app update read against this phone's own ask (ADR
     * 2026-11.29m7), anything else as a file or a models offer ([OfferReading.read]).
     */
    fun readOffer(
        senderId: String,
        payload: TransferPayload,
        nearby: () -> Boolean,
        refused: (why: String) -> Unit,
    ): Offered? {
        val pin = payload.ap ?: return OfferReading.read(payload, models, nearby, refused)
        val expects = app?.let { a -> { p: AppPin, size: Long -> a.expects(senderId, p, size) } }
        return OfferReading.readApp(payload, pin, nearby, refused, expects)
    }

    /**
     * [sourceUri] when [state] is an outgoing transfer no Retry will read again (it arrived, or its receiver declined
     * it), for [release]; null otherwise. A failed or cancelled one keeps its source for the Retry.
     */
    fun unretryableSource(
        state: TransferState,
        sourceUri: String?,
    ): String? {
        val final =
            state.phase == TransferPhase.Done ||
                (state.phase == TransferPhase.Declined && state.reason == TransferPayload.REASON_USER)
        return sourceUri?.takeIf { state.outgoing && final }
    }

    /** Lets [uri] go: a covered photo's copy is deleted (ADR 2026-10.whkk), a picked document left alone. */
    suspend fun release(uri: String) = files.release(uri)

    /**
     * What [state] sends: its bundle for a models transfer, this phone's APK for an update (read again, and refused if it
     * is no longer the version the offer pinned), else the file at [sourceUri].
     */
    suspend fun source(
        state: TransferState,
        sourceUri: String?,
    ): TransferSource? {
        val pins = state.models
        val pin = state.app
        return when {
            pins != null -> bundle(pins)
            pin != null -> app?.source()?.takeIf { it.versionCode == pin.versionCode && it.sha256.contentEquals(pin.sha256) }?.source
            else -> sourceUri?.let { file(it) }
        }
    }

    /** Where [state]'s bytes go: a bundle's or an update's staging, never a Downloads entry, for either. */
    suspend fun sink(state: TransferState): TransferSink? {
        val pins = state.models
        val pin = state.app
        return when {
            pins != null -> models?.sink(pins)
            pin != null -> app?.sink(state.id, pin, state.size)
            else -> files.createSink(state.name, state.mime, state.size)
        }
    }

    /** Whether a Retry of [record] would find what it sends: its file, or every model still installed. */
    suspend fun retryReadable(record: TransferRecord): Boolean {
        val pins = record.models
        return if (pins != null) bundle(pins) != null else record.sourceUri?.let { file(it) } != null
    }
}

/** A commit that failed after an OK verdict: the bytes were good, and this phone could not keep them. */
internal class NotSaved(
    cause: IOException,
) : IOException(cause)

/** [TransferSink.commit], its failure marked as this phone's own ([NotSaved]) rather than the link's. */
internal suspend fun TransferSink.commitOrNotSaved() {
    try {
        commit()
    } catch (e: IOException) {
        throw NotSaved(e)
    }
}
