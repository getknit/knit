package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

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

    /**
     * The user's post on the board's public channel. [timestampSecs] is the sender timestamp a kind whose packet carries one
     * fixed when the post was queued (MeshCore's `SEND_CHANNEL_TXT_MSG`, ADR 2026-11.gsqu's Stage 6 amendment): a retry of
     * the same frame writes the same one, so our own row's id matches what receivers read. Null where the board stamps its
     * own (the Meshtastic room).
     */
    data class PublicPost(
        val timestampSecs: Long? = null,
        /**
         * The board name the post was consented and priced under, where the firmware prefixes one (MeshCore, ADR
         * 2026-11.f4d3): the write refuses it when the board reads another. Null on the Meshtastic room.
         */
        val name: MeshcoreHandle? = null,
    ) : SendPurpose

    /** The fixed answer to a stock user's DM, to board [to], unacknowledged. */
    data class AutoReply(
        val to: BoardAddr,
    ) : SendPurpose

    /** A local admin write to our own board [to], unacknowledged, nothing on the air. */
    data class Admin(
        val to: BoardAddr,
    ) : SendPurpose

    /**
     * A raw payload from the debug bridge (`…debug.LORATX --es kind meshcore`, ADR 2026-11.gsqu's Stage 7 amendment) on the
     * bound Knit [channel], never Public: the same channel data a room post is, through the pacer, the ledger and the
     * session's actor, and refused by a board this phone has no consent for. MeshCore's only; Meshtastic refuses it.
     */
    data class Debug(
        val channel: Int,
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
 * What only a board kind does, called by the plane core (ADR 2026-11.gsqu §1, Stage 1b and 1c). One per plane instance,
 * made by the kind (Meshtastic's is [MeshtasticHooks]) with the kind's own state objects inside it — the core never
 * names them. A hook is called from any plane coroutine and from the entry points outside the plane
 * (`postToPublicChannel` from `MeshManager`, `provisionKnitChannel` from the settings, the limiter snapshot from the
 * save loop and `stop()`), never under the pacer's or the ledger's monitor; so every piece of state behind it has
 * `@Synchronized` entry points or atomics, and a hook takes the pacer's and the ledger's monitors only inside its own
 * call, never the other way round. A hook called on the outcomes collector ([onDelivered]) or the packet collector
 * ([route], [bookDirectAck], [onKindPacket]) returns at once: no suspending, no waiting on a launch, no re-entry.
 */
@Suppress("TooManyFunctions") // one seam per kind: every Meshtastic-only behaviour the core calls, grouped by stage
internal interface BoardKindHooks {
    /** Which kind's behaviour this is (Stage 2): the same kind as the plane's traits, which the plane checks at construction. */
    val kind: BoardKind

    /**
     * [status] with the board's half filled in from [state]: the link state the settings row and the field log render
     * (`LoraFieldLog`), and the board's own number. Called under the core's status lock (ADR 2026-10.a239's 2026-10-18
     * amendment), so it reads nothing that waits.
     */
    fun statusOf(
        state: BoardState,
        status: LoraStatus,
    ): LoraStatus

    // --- the inbound dispatch (Stage 1c.0) ---

    /**
     * What [packet] is to the core ([PacketRoute]), given the slot the plane is bound to ([bound], null with no config):
     * the kind's own portnums, its compact-DM marker and the slots each may arrive on.
     */
    fun route(
        packet: BoardPacket,
        bound: Int?,
    ): PacketRoute

    /**
     * Books the air our own board spends acknowledging [packet], a compact DM addressed to it ([own]), when the board did
     * send an acknowledgement; [onBound] says it arrived on the bound slot. Called before the DM is opened: the board
     * acked a stranger's and a forged one too.
     */
    fun bookDirectAck(
        packet: BoardPacket,
        own: BoardAddr,
        onBound: Boolean,
    )

    /** Acts on a packet [route] called [PacketRoute.KIND], after the core has noted the hearing. */
    fun onKindPacket(packet: BoardPacket)

    /**
     * The slot Knit's own frames go out on and arrive on, given the plane's [configured] index and the Ready board
     * [ready] (null while it is not): Meshtastic binds the slot the user's setting holds (the setup wrote it), so the
     * default is [configured]; a kind whose board reports its own Knit slot (MeshCore's `Ready.channel`) answers that.
     * Read on the packet collector and the pacer; returns at once.
     */
    fun boundSlot(
        configured: Int?,
        ready: BoardState.Ready?,
    ): Int? = configured

    // --- the kind's own public channel (Stage 1c.1) ---

    /**
     * Puts the user's post on the board's public channel, or says why not — the core's `PublicChannelSink`. Called from
     * `MeshManager`'s coroutine.
     */
    suspend fun postToPublicChannel(body: String): PublicPostRefusal?

    /**
     * [postToPublicChannel] with what a queued post is on the air where the kind can say ([PublicPostResult.Queued.key]:
     * MeshCore's timestamp and prefixed text, what a mirrored row's id is derived from). A kind that cannot say answers its
     * refusal, or a queued post with no key. [consented] is the board name the composer's consent decision saw (MeshCore,
     * ADR 2026-11.f4d3: refused unless it is the board's name now); a kind whose board prefixes no name ignores it.
     */
    suspend fun postPublic(
        body: String,
        consented: MeshcoreHandle? = null,
    ): PublicPostResult = postToPublicChannel(body)?.let { PublicPostResult.Refused(it) } ?: PublicPostResult.Queued(key = null)

    /**
     * Queued posts with a [PublicPostResult.Queued.key] that ended without airing ([PublicPostEnded]); none on a kind that
     * keys no post (Meshtastic).
     */
    val publicPostsEnded: Flow<PublicPostEnded> get() = emptyFlow()

    /**
     * Writes one of the kind's own queued frames (any [Destination] but Knit's: the public post, the auto-reply) through
     * the core's [send], which books it, and counts what became of it. Called on the pacer.
     */
    suspend fun sendKindFrame(
        frame: OutboundFrame,
        send: suspend (ByteArray, SendPurpose) -> FragmentOutcome,
    )

    // --- the kind's limiters that outlive the process (Stage 1c.2) ---

    /**
     * Puts back the kind's share of the last process's [snapshot] (the auto-reply's memory, [LoraPlaneSnapshot.autoReplied]),
     * its stamps moved onto this process's clock by [shift]. Called once, before the core's `restored` gate opens.
     */
    fun restoreState(
        snapshot: LoraPlaneSnapshot,
        shift: WallShift,
    )

    /**
     * The kind's share of the plane snapshot — [LoraPlaneSnapshot.autoReplied], on the wall clock by [shift], capped as
     * persisted. Called from the save loop and from `stop()`.
     */
    fun snapshotState(shift: WallShift): List<SeenStamp>

    /**
     * The kind's own share of the plane snapshot beyond [snapshotState]'s, added to [snapshot] (Stage 5a-2: MeshCore's duty
     * law, [LoraPlaneSnapshot.meshcoreLaws]); [restoreState] reads it back. Meshtastic adds nothing, so its blob is the
     * one it always wrote. Called from the save loop and from `stop()`.
     */
    fun extendSnapshot(snapshot: LoraPlaneSnapshot): LoraPlaneSnapshot = snapshot

    // --- the send-time slot guards (Stage 1c.3) ---

    /**
     * Whether a compact DM may not leave from bound slot [bound] at all (Meshtastic: slot 0, the public channel —
     * `loraDirectSlotZero`, `StaleAtSend.SLOT_ZERO`). Asked at the offer, the resend and again at send.
     */
    fun directSlotRefused(bound: Int?): Boolean

    /**
     * Whether bound slot [ch] of the Ready board [ready] may carry the Knit [frame] now — the board's channel table read
     * at send — or null when the kind cannot tell, which the core refuses: silence is the only safe reading of a slot
     * nobody can vouch for.
     */
    fun checkSlot(
        frame: OutboundFrame,
        ready: BoardState.Ready,
        ch: Int,
    ): SlotCheck?

    // --- a session coming up (Stage 1c.4) ---

    /**
     * The kind's half of a session coming up, after the core took [ready]'s payload cap: what it tells the ledger about
     * how the board prices a packet (its radio, how it signs), the `lora ready` line, and the binding the profile
     * advertises. Called on the board-state collector.
     */
    fun onReady(ready: BoardState.Ready)

    // --- our board without the destination's key, and the destination without ours (Stage 1c.5, ADR 2026-10.aajj) ---

    /** Sets the board up for Knit, or puts it back ([ProvisionMode]) — the core's `provisionKnitChannel`. */
    suspend fun provision(
        mode: ProvisionMode,
        previous: BoardSettings?,
    ): ProvisionResult

    /**
     * A setup over another board's record at the address ([ProvisionResult.DifferentBoard], ADR 2026-11.gsqu's Stage 7
     * amendment): MeshCore's alone; a kind with no setup record refuses it.
     */
    suspend fun provisionReplacing(token: ReplaceToken): ProvisionResult = ProvisionResult.Failed(NO_REPLACE)

    /** What a setup would erase, read fresh off the Ready board (MeshCore's, Stage 7); null on a kind that erases nothing. */
    suspend fun setupPreview(): SetupPreview? = null

    /** Renames the Ready board (MeshCore's handle, ADR 2026-11.f4d3); a kind with no such rename answers NO_BOARD. */
    suspend fun renameMeshcore(name: String): MeshcoreRenameResult = MeshcoreRenameResult.NO_BOARD

    /** True while [provision] runs: nothing is taught to the board inside its admin transaction. */
    val provisioning: Boolean

    /**
     * What may be done about our own board [own] refusing a compact DM to [peerId]'s board ([holder]) for want of its key
     * ([RefusalClass.OWN_NO_KEY]): a key written to the board ([KeyTeach.Write]), or nothing ([KeyTeach.Refused]).
     * [sentAfter] is the token stamped before the refused send ([BoardSend.preSend]): what the kind learned about that
     * board after it, the refusal cannot cover. Called on the pacer or the outcomes collector; returns at once.
     */
    fun teachKey(
        peerId: String,
        holder: LoraBoardHolder,
        own: BoardAddr,
        sentAfter: Long,
    ): KeyTeach

    /**
     * The destination's board [to] took a compact DM: what the kind keeps about teaching our board its key may be taught
     * again should it lose it. Called on the outcomes collector, between the mark and the radio-reached report.
     */
    fun onDelivered(to: BoardAddr)

    /**
     * The plane's board map changed to [map] (Stage 5b-2): a kind that teaches its board the whole set it may DM (MeshCore's
     * `MeshcoreContacts`) decides it here; Meshtastic teaches per refusal ([teachKey]) and ignores it. Called on the board
     * map's collector after the core took the map; returns at once (a write is launched on the plane's scope).
     */
    fun onBoardMap(map: Map<String, LoraBoardHolder>) = Unit

    /**
     * The holders the core may offer a compact DM to, from the board map [map] (Stage 5b-2 part 2, lora-reviewer N2): a kind
     * that teaches its board a set answers `direct` false for a holder it does not teach (MeshCore: cut at capacity, over the
     * daily cap, contested), so nothing is offered that our board would only refuse. Meshtastic offers the map as it is.
     * Pure; returns at once.
     */
    fun offered(map: Map<String, LoraBoardHolder>): Map<String, LoraBoardHolder> = map

    /**
     * The destination's board lacks our key ([RefusalClass.PEER_LACKS_KEY]), and ours answers it on its own (Meshtastic:
     * our `NodeInfo`): books that air at [now]. Called on the outcomes collector.
     */
    fun bookKeyReply(now: Long)
}

/** What a kind can do about our own board lacking a destination's key ([BoardKindHooks.teachKey]). */
internal sealed interface KeyTeach {
    /**
     * Teach the board: [write] does it, on the plane's scope, and says what came of it. The core holds the frame
     * meanwhile and acts on the answer.
     */
    class Write(
        val write: suspend () -> KeyWrite,
    ) : KeyTeach

    /** Nothing may be written (counted and logged by the kind): the peer is marked keyless and the frame let go. */
    data object Refused : KeyTeach
}

/** What became of a [KeyTeach.Write], for the core's held frame. */
internal enum class KeyWrite {
    /** The board took the key: the frame goes again through the pacer. */
    WRITTEN,

    /** The write went out and nothing confirmed it: the frame goes again once, and a second refusal is terminal. */
    UNCONFIRMED,

    /** Never written (the board busy, the link gone): the frame is owed and goes back on the queue. */
    NOT_WRITTEN,

    /** Our board refused the write: the peer is marked keyless and the frame let go. */
    REFUSED,
}

/** What a kind's [BoardKindHooks.checkSlot] says about a bound slot: clear, or refused in the words `lora send skipped` logs. */
internal sealed interface SlotCheck {
    data object Clear : SlotCheck

    data class Refused(
        val why: String,
    ) : SlotCheck
}

/**
 * What the core's write of one fragment did with its frame. Three ways, not a Boolean, because a frame put back on the
 * queue is not one the board refused: a Meshtastic room post or auto-reply caught by a board drop was counted refused,
 * then sent when it aired after the reconnect (#307).
 */
internal enum class FragmentOutcome {
    /** The board took the fragment (or may have, a compact DM's timeout): booked, the cursor moved. */
    SENT,

    /** Still owed: requeued at its cursor for a busy board or a session that ended, or a compact DM held for a missing key. */
    OWED,

    /** Ended: a NAK, a broadcast's timeout or another give-up. Abandoned unless a fragment already flew. */
    ENDED,

    /**
     * Ended before the board took any of it: its first fragment refused at the write ([SendResult.Rejected],
     * [SendResult.TooLarge] — the session's own gate, the encoder, or the firmware's `ERR`), so nothing of the frame was
     * queued or booked, and it never aired. A refusal after a fragment flew is [ENDED]. Abandoned
     * as [ENDED] is; told apart so a kind can say "not sent" rather than "may have gone out" (MeshCore's Public post, the
     * ADR 2026-11.f4d3 review). A write whose answer never came is [ENDED], never this.
     */
    REFUSED,
}

/**
 * What a packet off the board is to the plane core (ADR 2026-11.gsqu, Stage 1c.0), as the kind's [BoardKindHooks.route]
 * reads it. Every route but [DIRECT] and [DROPPED] is a hearing first, by the packet's sender where the kind vouches for it
 * ([BoardTraits.hearsSender], [BoardTraits.heardLive] deciding whether it counts).
 */
internal enum class PacketRoute {
    /** A compact DM on a slot one may arrive on: the core opens it when it is addressed to our board. */
    DIRECT,

    /** A Knit frame on the bound slot: the radio is counted, and the frame decoded and handed to the router. */
    KNIT,

    /** A Knit packet under a burned tag ([LoraMeshTransport.RETIRED_CTL_TAG]): the radio is counted, the packet dropped. */
    RETIRED,

    /** The kind's own traffic, which its [BoardKindHooks.onKindPacket] acts on. */
    KIND,

    /** A hearing, and nothing more: off the bound slot, empty, or a port nothing here reads. */
    HEARD,

    /**
     * Not even a hearing: a packet the kind could not read, a compact DM on a slot none arrives on, or — on MeshCore, whose
     * board is Knit's alone — traffic that is not Knit's, which the kind counts and discards (never stored, logged, shown,
     * relayed or put in custody).
     */
    DROPPED,
}

/**
 * The plane core as a board kind's hooks reach it (ADR 2026-11.gsqu, Stage 1c): the ledger, the config and the payload
 * cap as they stand, the queue, and the core's own neutral handlers. Implemented by the core; read on the hook's caller's
 * coroutine.
 */
internal interface PlaneCore {
    /** The one ledger: what a kind's own transmission is priced, admitted and booked against. */
    val airtime: LoraAirtime

    /** The plane's config, null with no board configured. */
    val config: LoraConfig?

    /** The largest payload one packet carries now. */
    val maxPayload: Int

    /** Whether the last process's limiters are back ([LoraPlaneState]): nothing may claim one before. */
    val restored: Boolean

    /**
     * The developer view's write-only trace ([LoraTrace], ADR 2026-10.s7wg): a kind notes what it heard or ended on its own
     * paths through [emit], never reading it back. [LoraTrace.NONE] unless the plane was handed one.
     */
    val trace: LoraTrace get() = LoraTrace.NONE

    /** This phone's wall clock as the plane reads it: what a [trace] event is stamped with. */
    val wallNow: Long get() = System.currentTimeMillis()

    /** Queues a kind's own one-packet frame, as a room-class frame at [ceiling]; true when it is in the queue now. */
    fun enqueue(
        parts: List<ByteArray>,
        label: String,
        ceiling: Ceiling,
        destination: Destination,
        to: BoardAddr? = null,
    ): Boolean

    /**
     * Queues the user's post on the board's public channel as one room-class packet at [ceiling], carrying the sender
     * timestamp [timestampSecs] fixed now (MeshCore, ADR 2026-11.gsqu's Stage 6 amendment): every write of the frame, a
     * `Busy` retry included, airs that one stamp — and fresh until `LoraFramePolicy.FRESH_MS` past it (`RideGate.freshUntil`,
     * which the kind asks at the write). The board's [name], read at enqueue, rides the frame too (ADR 2026-11.f4d3): its
     * price and the write's name check read it. True when it is in the queue now. A core that cannot carry the stamp queues
     * nothing. [traceId] is the room row the developer view's trace notes the frame's fate under (`OutboundFrame.traceId`),
     * null when the trace is off: read by nothing else.
     */
    fun enqueuePublic(
        text: ByteArray,
        label: String,
        ceiling: Ceiling,
        timestampSecs: Long,
        name: MeshcoreHandle,
        traceId: String? = null,
    ): Boolean = false

    /** A persisted limiter moved: the core saves the plane state behind its debounce. */
    fun limitersMoved()

    /**
     * Queues the debug bridge's raw [payload] for the kind to write on its bound Knit slot ([Destination.Debug], ADR
     * 2026-11.gsqu's Stage 7 amendment): one room-class frame under [Ceiling.FLOOD], priced as a room post, through the one
     * pacer and ledger. [freshUntilWall] is the wall-clock instant past which the kind never writes it. False when the queue
     * refused it. A kind that writes no such frame never calls it.
     */
    fun enqueueDebug(
        payload: ByteArray,
        label: String,
        freshUntilWall: Long,
    ): Boolean = false

    /**
     * Something the kind renders into the plane's face ([BoardKindHooks.statusOf]) moved without a link transition (MeshCore:
     * this phone's consent for the board, which decides whether a Public post may leave): the core republishes its status.
     * Never called under the pacer's or the ledger's monitor.
     */
    fun faceMoved() = Unit

    /**
     * [from], a board on the air, was heard announcing its key — our board stores a key it lacks off that — so a
     * missing-key mark on its peer lifts and the newest DM still without its ✓✓ goes again (ADR 2026-10.aajj).
     */
    fun onBoardKeyHeard(from: BoardAddr)

    /**
     * Holds every frame until [untilMs] on the plane's clock, or lifts the hold with null: the band's law, where it is the
     * kind's to keep (MeshCore, `MeshcoreDutyLaw`; ADR 2026-11.gsqu's Stage 5a-1 amendment). The core wakes its pacer.
     */
    fun holdForLaw(untilMs: Long?) = Unit

    /**
     * Whether the compact DM the direct send [handle] carries is still wanted, asked by a kind whose link retries a DM on
     * routes of its own right before one that floods (MeshCore's ladder, ADR 2026-11.gsqu's 2026-11-19 amendment): the gates
     * the frame's own path asked, asked again — no link to its addressee and no spool cover over it, DMs still switched on,
     * and for a DM a person wrote, still without its ✓✓ (a database read). True for a send the core no longer waits on.
     */
    suspend fun directStillWanted(handle: UInt): Boolean = true

    /**
     * The kind's answer to [BoardKindHooks.offered] moved without a new board map (MeshCore: a board's capacity read at Ready
     * cut or grew the taught set): the core asks it again over the last map. Never called from inside a core callback.
     */
    fun holdersMoved() = Unit
}

/**
 * One board kind's plane, as the core is built over it (ADR 2026-11.gsqu, Stage 5a-2; Stage 2's report, item 3): its
 * numbers ([traits]), its link seen through the neutral seam ([board]) and its hooks, made by [hooks] once the core they
 * reach back into exists. The core reads no kind's link but through these, and checks the three agree on the kind.
 */
internal class BoardPlane(
    val traits: BoardTraits,
    val board: BoardLink,
    val hooks: (PlaneCore) -> BoardKindHooks,
)

/** A replace asked of a board kind that keeps no setup record ([BoardKindHooks.provisionReplacing]). */
internal const val NO_REPLACE = "this board kind has no setup record to replace"
