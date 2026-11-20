package app.getknit.knit.transfer

import app.getknit.knit.mesh.protocol.AppPin

/**
 * The app-update seam of a direct transfer (ADR 2026-11.29m7): this phone's own installed APK as a source, and a
 * staging sink that checks an arriving one before the verdict. The manager learns nothing about packages; the
 * implementation (`update/AndroidAppUpdateFiles`) holds every Android read.
 */
interface TransferAppUpdate {
    /** This phone's installed APK, named and sized, with the version and SHA-256 an offer pins; null when it cannot serve. */
    suspend fun source(): AppSource?

    /**
     * Whether an offer from [peerId] pinning [pin], of [size] bytes, answers an ask this phone sent. True spends that
     * ask. Asked once per offer, before it is admitted.
     */
    fun expects(
        peerId: String,
        pin: AppPin,
        size: Long,
    ): Boolean

    /**
     * A staging sink for transfer [transferId] (a canonical `FrameId`, which names its directory), whose
     * [TransferSink.check] confirms the bytes against [pin] and the archive against this phone's install. Null when
     * none could be made.
     */
    suspend fun sink(
        transferId: String,
        pin: AppPin,
        size: Long,
    ): TransferSink?
}

/** [TransferAppUpdate.source]'s answer: the file to send and what its offer pins. */
class AppSource(
    val source: TransferSource,
    val versionCode: Long,
    val sha256: ByteArray,
)
