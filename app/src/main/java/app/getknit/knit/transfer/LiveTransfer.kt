package app.getknit.knit.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.net.Socket

/** One transfer in [TransferManager]'s hands: its state and everything only this run of it holds. Never persisted. */
internal class LiveTransfer(
    @Volatile var state: TransferState,
    val offeredAt: Long,
    val sourceUri: String? = null,
) {
    var credentials: GroupCredentials? = null
    var key: ByteArray? = null
    var port: Int = 0
    val ready = CompletableDeferred<Unit>()
    var job: Job? = null

    @Volatile var bytes: Long = 0L

    @Volatile var lastPublishAt: Long = 0L

    @Volatile var listener: TransferListener? = null

    @Volatile var socket: Socket? = null

    // The receiver's destination, kept so every end short of a commit throws it away (finish).
    @Volatile var sink: TransferSink? = null

    @Volatile var committed = false

    // The sender has sent every byte, so the receiver's verdict now decides how this side ends (ADR 2026-11.ejn8).
    @Volatile var awaitingVerdict = false

    // A peer's CANCEL that arrived while [awaitingVerdict]: used only if no OK verdict comes.
    @Volatile var peerCancel: Int? = null
}
