package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.meshcore.MeshcorePacket
import app.getknit.knit.mesh.meshNodeLabel
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Where the LoRa plane tells the developer view's message inspector what it did with one message row (ADR 2026-10.s7wg):
 * a write-only seam, in memory only. The plane calls it at points it already passes — a frame queued, a packet the board
 * took, an answer, a refusal, a packet heard — and reads nothing back: no air, pacing, gate, dedup or reading moves with
 * it, and the plane keeps no state for it. [NONE] in every build and test that does not ask for one, the lab included.
 *
 * The plane builds an event only while [on] is true, and only after it knows the id: every call goes through [emit] or
 * [emitByHandle], which check first, build second and swallow whatever the trace throws (logging its class name and
 * nothing else), so a failing trace never ends the pacer, the packet or outcome collector or the deadline loop.
 *
 * What an event never holds: payload or text bytes, a name, a key, a channel PSK or hash, a full public key, signature or
 * seal bytes, a nonce, position, `NodeInfo` or telemetry fields, the MQTT gateway's id (`via_mqtt` is a flag), a fragment's
 * own length. Nothing here reaches the field log, the debug bridge, logcat or a crash report.
 */
interface LoraTrace {
    /** A volatile read. While false the plane builds no event. */
    val on: Boolean

    /** One fact about the message row [id]. O(1), never blocks, never suspends. */
    fun note(
        id: String,
        event: LoraTraceEvent,
    )

    /**
     * A fact about one of our airings that names only the board's packet [handle] (a broadcast's late NAK, an implicit
     * ack): the reader maps it to a row through the handles it saw on [LoraTraceEvent.Aired]. O(1), never blocks.
     */
    fun noteByHandle(
        handle: Long,
        event: LoraTraceEvent,
    )

    companion object {
        /** Off, always: what the plane runs with unless the developer view's book is handed in. */
        val NONE: LoraTrace =
            object : LoraTrace {
                override val on: Boolean get() = false

                override fun note(
                    id: String,
                    event: LoraTraceEvent,
                ) = Unit

                override fun noteByHandle(
                    handle: Long,
                    event: LoraTraceEvent,
                ) = Unit
            }
    }
}

/** Which of the plane's paths a message took. */
enum class TraceRoute {
    /** A Knit frame on the bound slot: our own room post fanned out, or one heard. */
    FRAME,

    /** A compact pair-sealed DM to or from a board (ADR 2026-10.x9fa). */
    DIRECT,

    /** A post on the board's public slot 0: the Meshtastic room or MeshCore's Public channel. */
    ROOM,
}

/** A verdict the plane reached about a heard packet, at the point it already reaches it. */
enum class HeardNote {
    /** Counted as its author's presence (the plane's `reachable`, `FramePresence`'s rule). */
    PRESENCE,

    /** Too old to be presence: a frame's place in the mesh, never where its author is. */
    NOT_FRESH,

    /** Aired by our own board and heard back through a repeater: nobody's reach. */
    OWN_BOARD,

    /** Its signature was checked against its author's pinned key on the way in (a kind that vouches for no sender). */
    AUTHOR_VERIFIED,

    /**
     * Nothing on this plane checked its signature (every Meshtastic frame, a whole `0x03`/`0x05` on MeshCore that is not
     * presence): its id and author are the sender's claim, which the router verifies later on its own path.
     */
    AUTHOR_UNVERIFIED,

    /** A live compact DM that lifted its sender's relay cover (#294). */
    COVER_LIFTED,

    /** A compact DM that counts as a hearing of its sender's board, by the kind's route rule. */
    BOARD_HEARD,
}

/** Why a frame did not go on the air when it was asked to, or went back on the queue. The plane's own reasons, by name. */
enum class TraceReason {
    // The fan-out's admission (our own room post).

    /** A room post past the kind's packet limit (`lora room too long`). */
    ROOM_TOO_LONG,

    /** Past three packets, or not encodable (`lora too-big`). */
    TOO_BIG,

    /** Past `LoraFramePolicy.FRESH_MS` when fanned: custody's business. */
    NOT_FRESH,

    /** Written before this process started (ADR 2026-10.ug4a). */
    PREDATES_PROCESS,

    /** Fanned before our own id was read, past the few slots kept for that. */
    PRE_ID_FULL,

    /** Already fanned over LoRa while still fresh (the signature dedup). */
    ALREADY_QUEUED,

    /** The queue refused it. */
    QUEUE_FULL,

    // A compact DM's offer.

    /** DMs over LoRa are switched off. */
    DMS_OFF,

    /** The recipient's pinned profile names no board with the capability, or the board map has not reported. */
    NO_BOARD,

    /** A copy of it is queued or in flight already. */
    COPY_WAITING,

    /** Parked behind a send to the same peer still waiting on its answer. */
    PARKED,

    /** No pair key to seal it under. */
    UNSEALED,

    /** The seal is past one packet. */
    TOO_LONG,

    // The offer's gates and the send-time re-ask (`StaleAtSend`).

    /** A better plane holds a live link to the addressee. */
    LINKED,

    /** A connected spool carries the addressee (ADR 2026-09.y5f3). */
    INTERNET,

    /** The addressee is no longer a direct board holder at that board, or DMs went off. */
    NOT_DIRECT,

    /** The peer's board is marked refused, keyless or unreachable. */
    HELD,

    /** A resend whose ✓✓ landed while it waited. */
    ACKED,

    /** The bound slot is 0, which carries no compact DM. */
    SLOT_ZERO,

    /** Aged past its freshness deadline in the queue. */
    STALE,

    /** Not ours once our own id was read (ADR 2026-10.rp3s). */
    NOT_OWN,

    // The write.

    /** No bound slot to write it to. */
    NO_SLOT,

    /** The bound slot is not one Knit's frames may reach (`lora send skipped`). */
    SLOT_REFUSED,

    /** Shed from a full queue for a newer frame. */
    SHED,

    /** Over its ceiling of an empty window: no window ever admits it (`lora air never`). */
    AIR_NEVER,

    // Back on the queue.

    /** The board had no room for it (`Busy`). */
    BUSY,

    /** The board was between sessions. */
    NOT_READY,

    /** Our board lacks the destination's key: held while the kind teaches it (ADR 2026-10.aajj). */
    KEY_TEACHING,

    /** The destination's board lacks our key: re-offered once, a retransmit gap on (ADR 2026-10.aajj). */
    PEER_LACKS_KEY,
}

/** How one airing ended, as the plane read the board's answer. */
enum class TraceOutcome {
    /** The destination's own board acked it: its radio, never its phone. */
    ACKED,

    /** An ack whose proof our board found forged (`AckProof.INVALID`): never shown as reached. */
    PROOF_INVALID,

    /** A relay or our own board heard it repeated: counted, never delivery, and it ends no wait. */
    IMPLICIT_ACK,

    /** Our own board refused it ([LoraTraceEvent.Ended.nak] names the routing error). */
    NAK,

    /** The destination's board refused it. */
    DEST_REFUSED,

    /** The firmware gave up after every retry. */
    UNREACHABLE,

    /** No answer by its deadline. */
    NO_OUTCOME,

    /** Our board lacks the destination's key and none could be taught. */
    NO_KEY,

    /** The destination's board still lacked our key after the one re-offer. */
    PEER_LACKS_KEY,

    /** The write went out and no `queueStatus` came back: a broadcast is given up. */
    WRITE_TIMEOUT,

    /** The board refused the write itself (too large, rejected, the kind's own refusal). */
    REJECTED,

    /** A public post past its freshness at the write: never written. */
    STALE_AT_WRITE,
}

/**
 * How a heard packet reached our board, as the board reported it: facts about the last hop and the packet's header, never
 * about its author. A plain class with no `toString`, as [LoraTraceEvent] is.
 */
class HeardRadio(
    /** The last hop's SNR to our board, in tenths of a dB. */
    val snrDeci: Int?,
    /** The last hop's RSSI to our board; a reported 0 reads as none. */
    val rssi: Int?,
    /** The hops it took, where the kind reports them. */
    val hops: Int?,
    /** MeshCore's `path_len` `0xFF`: it arrived direct, and the count is not carried. */
    val direct: Boolean,
    /** Meshtastic's `hop_start`, null when the sender wrote none (and on MeshCore). */
    val hopStart: Int?,
    /** Meshtastic's `hop_limit` as received; null on MeshCore. */
    val hopLimit: Int?,
    /** It reached the mesh off somebody's MQTT uplink. */
    val viaMqtt: Boolean,
    /** The plane's live reading (`BoardTraits.live`): false for a to-phone-queue replay or an MQTT copy. */
    val live: Boolean,
    /** When the board heard it, on the board's own clock. */
    val boardRxMs: Long?,
)

/**
 * One fact the plane noted about a message row. Plain classes, never data classes, and no `toString`: no generated string
 * that names a field ships in a release dex, and nothing here is meant for a log. [atMs] is this phone's wall clock when
 * the plane noted it; [board] the kind of the plane that did.
 */
sealed class LoraTraceEvent(
    val atMs: Long,
    val board: BoardKind,
) {
    /**
     * A packet heard that carried this row: for a fragmented frame, the completing packet's reception and [parts], the
     * count it completed. A second Heard for one row is a re-air under a new packet id, never a second path to one packet:
     * both firmwares drop a repeat of a packet id before the phone sees it.
     */
    class Heard(
        atMs: Long,
        board: BoardKind,
        val route: TraceRoute,
        /** The channel slot it arrived on. */
        val slot: Int,
        /**
         * The **radio's** claim, never the author: `!%08x` on Meshtastic, the source prefix as carried (hex, its carried
         * length) on MeshCore; null where the packet names none (every MeshCore channel packet).
         */
        val from: String?,
        /** Our board vouched for the sending radio: it PKI-decrypted it or verified its XEdDSA signature. */
        val fromVouched: Boolean,
        /** The packet's own id, where the kind carries one (Meshtastic). */
        val packetId: Long?,
        /** How the packet reached our board. */
        val radio: HeardRadio,
        val parts: Int,
        /**
         * A room post's first 32 bits of SHA-256 over its payload as heard, for the reader to tell two airings of different
         * bytes apart; never shown. Null on anything but a room post.
         */
        val payloadTag: Int?,
        val notes: Set<HeardNote>,
    ) : LoraTraceEvent(atMs, board)

    /** Queued for the air: [parts] packets of [bytes] in all, addressed to [to] (a destination board, as a claim) or the channel. */
    class Queued(
        atMs: Long,
        board: BoardKind,
        val route: TraceRoute,
        val parts: Int,
        val bytes: Int,
        val to: String?,
    ) : LoraTraceEvent(atMs, board)

    /**
     * The board took one packet of the frame, [part] of [parts] (0-based), under packet [handles] on [slot], and the ledger
     * booked [bookedMs] for it (every airing a compact DM may cost). [heldForAir] and [heldForBand] say the window or the
     * shared-band brake held the frame on its way.
     */
    class Aired(
        atMs: Long,
        board: BoardKind,
        val slot: Int,
        val handles: List<Long>,
        val bookedMs: Long,
        val part: Int,
        val parts: Int,
        val heldForAir: Boolean,
        val heldForBand: Boolean,
    ) : LoraTraceEvent(atMs, board)

    /** Not put on the air for [reason]: at the fan-out, the offer, the send-time re-ask, the write or the pacer. */
    class Refused(
        atMs: Long,
        board: BoardKind,
        val reason: TraceReason,
    ) : LoraTraceEvent(atMs, board)

    /** Back on the queue for [reason]; it goes again from where it stopped. */
    class Requeued(
        atMs: Long,
        board: BoardKind,
        val reason: TraceReason,
    ) : LoraTraceEvent(atMs, board)

    /**
     * What became of an airing: [outcome], [late] when it came after the send had already ended, [nak] the routing error's
     * name for a refusal, [refundedMs] the air given back for airings that never flew.
     */
    class Ended(
        atMs: Long,
        board: BoardKind,
        val outcome: TraceOutcome,
        val late: Boolean = false,
        val nak: String? = null,
        val refundedMs: Long? = null,
    ) : LoraTraceEvent(atMs, board)
}

/**
 * The one guarded way the plane notes a fact (the design pass's Blocking finding): nothing is built while the trace is off
 * or the row has no [id], the event is built only after both checks, and whatever the build or the trace throws is logged
 * by its class name alone and swallowed — a trace must never end the loop that called it.
 */
internal inline fun LoraTrace.emit(
    id: String?,
    log: (String) -> Unit,
    build: () -> LoraTraceEvent,
) {
    if (id == null || !on) return
    runCatching { note(id, build()) }.onFailure { log("lora trace failed: ${it.javaClass.simpleName}") }
}

/**
 * [emit] for a row whose [id] costs a hash to derive (a room post's): derived only once the trace is known to be on, inside
 * the same guard.
 */
internal inline fun LoraTrace.emitDerived(
    id: () -> String,
    log: (String) -> Unit,
    build: () -> LoraTraceEvent,
) {
    if (!on) return
    runCatching { note(id(), build()) }.onFailure { log("lora trace failed: ${it.javaClass.simpleName}") }
}

/** [emit] for a fact that names only our board's packet [handle]. */
internal inline fun LoraTrace.emitByHandle(
    handle: UInt,
    log: (String) -> Unit,
    build: () -> LoraTraceEvent,
) {
    if (!on) return
    runCatching { noteByHandle(handle.toLong(), build()) }.onFailure { log("lora trace failed: ${it.javaClass.simpleName}") }
}

/**
 * A [LoraTraceEvent.Heard] off [packet] as the board handed it over: its reception and the radio's claim, kind by kind, and
 * nothing of its payload. Called only inside [emit]'s builder.
 */
@Suppress("LongParameterList") // the facts the caller already holds, each passed once
internal fun heardOf(
    atMs: Long,
    board: BoardKind,
    route: TraceRoute,
    packet: BoardPacket,
    live: Boolean,
    parts: Int = 1,
    payloadTag: Int? = null,
    notes: Set<HeardNote> = emptySet(),
): LoraTraceEvent.Heard {
    val raw = packet.meshtastic
    return LoraTraceEvent.Heard(
        atMs = atMs,
        board = board,
        route = route,
        slot = packet.channel,
        from = packet.from?.let(::traceLabel),
        fromVouched = raw != null && (raw.pkiEncrypted || raw.boardVerified),
        packetId = raw?.id?.toLong(),
        radio =
            HeardRadio(
                snrDeci = traceSnrDeci(packet.rxSnr),
                rssi = packet.rxRssi?.takeIf { it != 0 },
                hops = packet.hopsAway,
                direct = (packet.kind as? MeshcorePacket)?.direct == true,
                hopStart = raw?.hopStart?.takeIf { it > 0 },
                hopLimit = raw?.hopLimit,
                viaMqtt = raw?.viaMqtt == true,
                live = live,
                boardRxMs = packet.rxTimeMs,
            ),
        parts = parts,
        payloadTag = payloadTag,
        notes = notes,
    )
}

/** A board address as the inspector shows a radio's claim: `!%08x` on Meshtastic, the carried prefix in hex on MeshCore. */
internal fun traceLabel(addr: BoardAddr): String =
    when (addr) {
        is BoardAddr.Meshtastic -> meshNodeLabel(addr.node.toLong())
        is BoardAddr.MeshCore -> addr.prefix.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and BYTE_MASK) }
    }

/** An SNR in dB as tenths, rounded as the Meshtastic room's row is. */
internal fun traceSnrDeci(snr: Float?): Int? = snr?.let { (it * DECI).roundToInt() }

/** The first 32 bits of SHA-256 over [payload], big-endian: a room post's [LoraTraceEvent.Heard.payloadTag]. */
internal fun tracePayloadTag(payload: ByteArray): Int {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    var tag = 0
    for (i in 0 until Int.SIZE_BYTES) tag = (tag shl Byte.SIZE_BITS) or (digest[i].toInt() and BYTE_MASK)
    return tag
}

private const val BYTE_MASK = 0xFF
private const val DECI = 10f
