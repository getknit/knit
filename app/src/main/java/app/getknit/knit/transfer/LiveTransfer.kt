package app.getknit.knit.transfer

import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.mesh.protocol.TransferPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.net.Socket
import java.util.Base64

/** One transfer in [TransferManager]'s hands: its state and everything only this run of it holds. Never persisted. */
internal class LiveTransfer(
    @Volatile var state: TransferState,
    val offeredAt: Long,
    val sourceUri: String? = null,
) {
    var credentials: GroupCredentials? = null
    var key: ByteArray? = null
    var port: Int = 0

    // The frequencies the receiver said it can join a group on (ADR 2026-11.8zm5), null when it did not say.
    var peerChannels: Set<Int>? = null
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

    /** The credentials, port and stream key this transfer's receiver joins with. The same payload every time it is sent. */
    fun readyPayload(): TransferPayload {
        val creds = checkNotNull(credentials)
        return TransferPayload(
            id = state.id,
            phase = TransferPayload.PHASE_READY,
            ssid = creds.ssid,
            passphrase = creds.passphrase,
            port = port,
            key = Base64.getEncoder().encodeToString(checkNotNull(key)),
        )
    }

    /** This side's offer to [senderId], still unanswered at [now]. */
    fun isOurOpenOffer(
        senderId: String,
        now: Long,
    ): Boolean = state.outgoing && state.peerId == senderId && state.phase == TransferPhase.Offered && now < state.expiresAt

    /** The peer's offer to this side, still unanswered at [now]. */
    fun isOpenIncomingOffer(now: Long): Boolean = !state.outgoing && state.phase == TransferPhase.Offered && now < state.expiresAt
}
