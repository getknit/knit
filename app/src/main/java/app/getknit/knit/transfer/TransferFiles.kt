package app.getknit.knit.transfer

import java.io.InputStream
import java.io.OutputStream

/** The file a sender picked: what the offer names, and how to read it when the bytes are due. */
class TransferSource(
    val name: String,
    val size: Long,
    val mime: String?,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

/** Where a received file is being written — a pending entry the receiver publishes or throws away. */
interface TransferSink {
    /** How the saved file will be addressed once committed (a `content://` Uri on Android). */
    val uri: String

    /** The one stream the bytes go to; opened lazily, closed by [commit]/[discard]. */
    fun stream(): OutputStream

    /**
     * Whether what was written may be kept, asked once the stream's own trailer verified and before the verdict byte
     * goes back, so a refusal here reads as a corrupt transfer on both cards. A plain file has nothing more to check;
     * a model bundle checks every file against this phone's pins (ADR 2026-11.ejn8). Throws only [java.io.IOException].
     */
    suspend fun check(): Boolean = true

    /** Flushes, closes and makes the file visible. */
    suspend fun commit()

    /** Closes and removes whatever was written. Safe to call after a failed [commit]. */
    suspend fun discard()
}

/** The storage seam of a direct transfer: the sender's source and the receiver's destination. */
interface TransferFiles {
    /** Names and sizes [uri], or null when it cannot be read. Cheap: nothing is opened until [TransferSource.open]. */
    suspend fun openSource(uri: String): TransferSource?

    /** A pending destination for [size] bytes named [name], or null when none could be created. */
    suspend fun createSink(
        name: String,
        mime: String?,
        size: Long,
    ): TransferSink?

    /** Bytes this device can still take for a received file. */
    fun freeBytes(): Long
}
