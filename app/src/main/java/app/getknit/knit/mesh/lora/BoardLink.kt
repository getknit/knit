package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.TransportHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The board link the plane core consumes, board-neutral (ADR 2026-11.gsqu §1, Stage 1b). A board kind adapts its own
 * link to it — Meshtastic's is [MeshtasticBoardLink] over the unchanged [MeshtasticLink] — and adds no buffering: the
 * state is a view of the link's, and the inbound flows are mapped on the core's own collectors. Pacing stays the core's:
 * this only reports what the board says and takes one packet at a time.
 *
 * What a kind means by its answers is not read here. [SendResult], [PacketOutcome] and the kind's link state ride
 * through as they are, and the core reads them only through the kind's refusal classifier ([BoardTraits.refusalOf])
 * and its hooks ([BoardKindHooks]).
 */
internal interface BoardLink {
    /** The board's state, a value view of the kind's own: read synchronously by the core, and collected. */
    val state: StateFlow<BoardState>

    /** Inbound packets, mapped from the kind's on the collector. */
    val packets: Flow<BoardPacket>

    /** What became of a packet after [send] returned, by its handle ([SendResult.Queued.id]), as the kind reports it. */
    val outcomes: Flow<PacketOutcome>

    /** The board's transmit-queue headroom (`free`) each time it reports it; the pacer drains while it is not 0. */
    val headroom: Flow<Int>

    /** The latest [headroom], or null until reported: what the status row shows. */
    val headroomNow: Int?

    /** The board's own battery, null until reported and once stopped. */
    val battery: StateFlow<BoardBattery?>

    /** The board's own air readings, null until reported and once stopped or re-handshaking. */
    val boardAir: StateFlow<BoardAir?>

    /**
     * Writes one packet for [purpose], which decides where it goes and how ([SendPurpose]), and returns the board's
     * synchronous answer with the token stamped just before the write ([BoardSend.preSend]).
     */
    suspend fun send(
        payload: ByteArray,
        purpose: SendPurpose,
    ): BoardSend

    /** Sets the board up for Knit, or puts it back ([ProvisionSpec]); requires a Ready board. */
    suspend fun provision(spec: ProvisionSpec): ProvisionResult

    /** (Re)connects to [address]; idempotent for the same address. */
    fun start(address: String)

    fun stop()
}

/**
 * The board's state as the core reads it. [detail] is the kind's own state, which only its hooks read — for Meshtastic
 * the [LinkState] itself, which `LoraStatus.state` renders.
 */
internal sealed interface BoardState {
    val detail: Any

    /**
     * A board the core may send through: [own] is the board's own address, [payloadCap] the largest payload one packet
     * carries at this link's size, [radio] its modulation when it reported one, and [channel] the slot its channel table
     * names for Knit, when it names one. The Meshtastic plane still binds the slot the user's setting holds, which the
     * setup wrote from this.
     */
    data class Ready(
        val own: BoardAddr,
        val payloadCap: Int,
        val radio: RadioParams?,
        val channel: Int?,
        override val detail: Any,
    ) : BoardState

    /** Anything short of Ready: [health] is what the composite shows for it. */
    data class Down(
        val health: TransportHealth,
        override val detail: Any,
    ) : BoardState
}

/**
 * One inbound packet, kind-neutral. [from] is null when the board could not say; [toUs] is a unicast addressed to our own
 * board; [hopsAway] the hops it took, when the kind reports it; [rxTimeMs] when the board heard it, on the epoch clock,
 * when it has a clock. [kind] is the kind's own record of the packet, read only by its hooks and traits — for Meshtastic
 * the [ReceivedPacket] itself: its signature and the fields signed with it, the board's verdict, `want_ack`, `hop_limit`
 * and `next_hop`, `pki_encrypted`, the portnum and `via_mqtt`.
 */
internal class BoardPacket(
    val from: BoardAddr?,
    val toUs: Boolean,
    val channel: Int,
    val payload: ByteArray,
    val rxSnr: Float?,
    val rxRssi: Int?,
    val hopsAway: Int?,
    val rxTimeMs: Long?,
    val kind: Any?,
)

/**
 * Why a packet is written, which is all the core says about where and how (ADR 2026-11.gsqu, Stage 1b.1). The kind maps
 * each to its own fields; Meshtastic's are [MeshtasticBoardLink.send]'s.
 */
internal sealed interface SendPurpose {
    /** A Knit broadcast — an own room post — on the bound Knit [channel]. */
    data class KnitFrame(
        val channel: Int,
    ) : SendPurpose

    /** A compact DM to board [to], acknowledged by it, from the bound Knit [channel]. */
    data class DirectDm(
        val channel: Int,
        val to: BoardAddr,
    ) : SendPurpose

    /** The user's post on the board's public channel (the Meshtastic room). */
    data object PublicPost : SendPurpose

    /** The fixed answer to a stock user's DM, to board [to], unacknowledged. */
    data class AutoReply(
        val to: BoardAddr,
    ) : SendPurpose

    /** A local admin write to our own board [to], unacknowledged, nothing on the air. */
    data class Admin(
        val to: BoardAddr,
    ) : SendPurpose
}

/**
 * The board's synchronous answer to one [BoardLink.send], and the token stamped just before the write: Meshtastic's
 * [MeshtasticLink.userSeq], which orders the board's NodeInfo stream against the send — a key streamed after it raced a
 * `39` (ADR 2026-10.aajj, `LoraContactPolicy.judge`'s `sentAfter`).
 */
internal class BoardSend(
    val result: SendResult,
    val preSend: Long,
)

/**
 * What only a board kind does, called by the plane core (ADR 2026-11.gsqu §1). Stateless: called from any plane coroutine
 * and from the entry points outside the plane, never under the pacer's or the ledger's monitor. Stage 1b carries the
 * status; Stage 1c moves the kind's inbound dispatch and features behind it.
 */
internal interface BoardKindHooks {
    /**
     * [status] with the board's half filled in from [state]: the link state the settings row and the field log render
     * (`LoraFieldLog`), and the board's own number. Called under the core's status lock (ADR 2026-10.a239's 2026-10-18
     * amendment), so it reads nothing that waits.
     */
    fun statusOf(
        state: BoardState,
        status: LoraStatus,
    ): LoraStatus
}
