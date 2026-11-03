package app.getknit.knit.transfer

import app.getknit.knit.mesh.protocol.TransferPayload

/** The mesh seam of a direct transfer: one sealed `CTL_TRANSFER` DM to [peerId]. False when nothing could be sealed. */
fun interface TransferSignals {
    suspend fun sendTransferSignal(
        peerId: String,
        payload: TransferPayload,
    ): Boolean
}

/**
 * An admitted OFFER worth a notification, in the receiving phone's own words: what [TransferManager.onSignal] hands
 * the inbound path. A models offer's [name] is this phone's label for it, never the sender's text.
 */
data class TransferNotice(
    val name: String,
    val size: Long,
)
