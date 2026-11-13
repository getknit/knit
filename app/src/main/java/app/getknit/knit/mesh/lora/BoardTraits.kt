package app.getknit.knit.mesh.lora

import kotlin.math.pow

/**
 * What one board kind's numbers are, for the board-neutral plane core (ADR 2026-11.gsqu §1): the payload caps, a compact
 * DM's airings, deadline and refund model, the airtime model's framing, the pacer's floor, the room part limit and which
 * hearings count. One value per kind — [MESHTASTIC] is the only one in Stage 1 — and stateless, so it is read from any
 * plane coroutine and from the entry points outside it without a lock.
 *
 * **Called under the plane's monitors.** The ledger's (`LoraAirtime.timeOnAirMs` / `slotTimeMs` read the framing and the
 * slot) and the pacer's (`LoraPacePolicy.onNak` asks [refusalOf]). So an implementation never locks, never suspends and
 * never reads mutable state: constants and pure functions of its arguments only. The rule that a kind object is never
 * entered holding either monitor is about the stateful ones — the hooks and the kind's policies — not these traits.
 *
 * The Meshtastic values are not moved here: each stays the companion constant it was ([LoraDirectPolicy.AIRINGS],
 * [LoraMeshTransport.PRE_READY_PAYLOAD], [LoraAirtime.PACKET_OVERHEAD_BYTES] …), measured on a board or decided in an ADR
 * and pinned by the tests that name it, and [MeshtasticTraits] reads them. A trait sets a factory default only: it never
 * overrides a value a caller injected (`LoraMeshTransport`'s `pace`).
 */
internal interface BoardTraits {
    /** The payload cap before the board has said what it takes (Meshtastic: [LoraMeshTransport.PRE_READY_PAYLOAD]). */
    val floorPayload: Int

    /** The largest payload the board takes at any link size (Meshtastic: [MeshtasticProto.MAX_PAYLOAD]). */
    val maxPayload: Int

    /**
     * The largest sealed compact DM one packet carries on a link whose payload cap is [payload]: the seal's own bound,
     * with the link's framing for a direct send (Meshtastic: `want_ack`, the PKC overhead) already subtracted. What the
     * plane checks a seal against and, at [floorPayload], what the plaintext budget is cut from ([directBudget]).
     */
    fun directPayloadCap(payload: Int): Int

    /** Every airing one compact DM may cost — the send and each retry: what it is admitted and booked against. */
    val airings: Int

    /** The most direct sends waiting on an answer at once. */
    val maxFlights: Int

    /** The firmware's contention windows, as powers of two: the shortest and longest gap between two airings. */
    val cwMin: Int
    val cwMax: Int

    /** The gap between two airings of a direct send of [airMs], at contention window [cw], in slots of [slotMs]. */
    fun retransmitMs(
        airMs: Long,
        slotMs: Double,
        cw: Int,
    ): Long

    /** How long the plane waits on a direct send of [airMs] before ending it with no outcome. */
    fun deadlineMs(
        airMs: Long,
        slotMs: Double,
    ): Long

    /** How many retries had flown [elapsedMs] after a send whose shortest gap is [retransmitMs]: what an ack keeps booked. */
    fun retriesBefore(
        elapsedMs: Long,
        retransmitMs: Long,
    ): Int

    /** The contention slot at [radio], what [retransmitMs] counts in. */
    fun slotTimeMs(radio: RadioParams): Double

    /** The payload of our board's ACK of a compact DM to us, booked when the board sent one. */
    val ackPayloadBytes: Int

    /** The header and framing around a payload on the air, for the time-on-air formula. */
    val packetOverheadBytes: Int

    /** The preamble and sync word, in symbols, for the time-on-air formula. */
    val preambleSymbols: Double

    /** The pacer's shortest gap between two packets — the factory default of `LoraPacePolicy`, never an injected one. */
    val minGapMs: Long

    /** The most packets an own room post may take (ADR 2026-10.rp3s: one on Meshtastic). */
    val roomPartLimit: Int

    /**
     * Whether [packet] was heard on the air just now, as the hearing hook and the cover's lift read it — never a replay
     * of the board's queue or a packet that did not cross the air. [nowWallMs] is the wall clock.
     */
    fun heardLive(
        packet: BoardPacket,
        nowWallMs: Long,
    ): Boolean

    /** What a refusal by our board or the destination's means to the plane ([RefusalClass]). */
    fun refusalOf(outcome: PacketOutcome.Refused): RefusalClass

    /** What a synchronous send result's refusal means to the plane, or null for a result that refused nothing. */
    fun refusalOf(result: SendResult): RefusalClass?

    /**
     * A compact DM's plaintext budget on this kind: [directPayloadCap] at [floorPayload], less the seal's header and tag
     * ([LORA_DIRECT_OVERHEAD]), and the tick acks it holds. Never the live payload cap: a copy sized here must fit
     * whichever board of this kind sends it.
     */
    fun directBudget(): LoraDirectBudget = LoraDirectBudget.forPlaintext(directPayloadCap(floorPayload) - LORA_DIRECT_OVERHEAD)

    companion object {
        val MESHTASTIC: BoardTraits get() = MeshtasticTraits
    }
}

/**
 * What a refusal means to the plane core (ADR 2026-11.gsqu, Stage 1a.3), as the kind's classifier reads it — so the core
 * branches on these, never on a firmware code.
 */
internal enum class RefusalClass {
    /** Our own board holds no key for the destination's board and refused before anything aired (Meshtastic `39`). */
    OWN_NO_KEY,

    /** The destination's board holds no key for ours (Meshtastic `PKI_UNKNOWN_PUBKEY`, 35). */
    PEER_LACKS_KEY,

    /** Our board's duty-cycle or rate refusal: the pacer backs off (Meshtastic 9 or 38 only). */
    PACE,

    /** Any other refusal by the destination's board: the peer is held until their profile changes. */
    DEST_REFUSED,

    /** Anything else: neither paced nor held. */
    OTHER,
}

/**
 * A radio's modulation, kind-neutral: what the time-on-air formula and the contention slot read (ADR 2026-11.gsqu
 * Stage 1a.2). A Meshtastic board's comes from its modem preset ([MeshtasticTraits.radioParams]).
 */
internal data class RadioParams(
    val spreadFactor: Int,
    val bandwidthHz: Int,
    /** Coding-rate denominator: 5 means 4/5. */
    val codingRate: Int,
) {
    /** One symbol's duration in milliseconds. */
    val symbolMs: Double get() = 2.0.pow(spreadFactor) * MS_PER_SECOND / bandwidthHz

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}

/**
 * The law a board transmits under, kind-neutral: the regional duty cycle that applies to us ([dutyCyclePercent], 100
 * where the board's own override lifted it) and whether the board sits on Knit's dedicated slot, which can lift the
 * politeness ceiling (ADR 067). A Meshtastic board's is its region's `dutyCyclePercent`, never one derived from a
 * frequency ([MeshtasticTraits.dutyPolicy]).
 */
internal data class DutyPolicy(
    val dutyCyclePercent: Double,
    val dedicatedSlot: Boolean,
)

/**
 * The Meshtastic board's traits: each the constant it always was, read where it lives. The one place a Meshtastic
 * modem preset and region become [RadioParams] and [DutyPolicy], and the one refusal classifier.
 */
internal object MeshtasticTraits : BoardTraits {
    override val floorPayload: Int get() = LoraMeshTransport.PRE_READY_PAYLOAD
    override val maxPayload: Int get() = MeshtasticProto.MAX_PAYLOAD

    override fun directPayloadCap(payload: Int): Int = LoraDirectPolicy.payloadCap(payload)

    override val airings: Int get() = LoraDirectPolicy.AIRINGS
    override val maxFlights: Int get() = LoraDirectPolicy.MAX_FLIGHTS
    override val cwMin: Int get() = LoraDirectPolicy.CW_MIN
    override val cwMax: Int get() = LoraDirectPolicy.CW_MAX

    override fun retransmitMs(
        airMs: Long,
        slotMs: Double,
        cw: Int,
    ): Long = LoraDirectPolicy.retransmitMs(airMs, slotMs, cw)

    override fun deadlineMs(
        airMs: Long,
        slotMs: Double,
    ): Long = LoraDirectPolicy.deadlineMs(airMs, slotMs)

    override fun retriesBefore(
        elapsedMs: Long,
        retransmitMs: Long,
    ): Int = LoraDirectPolicy.retriesBefore(elapsedMs, retransmitMs)

    /**
     * `RadioInterface::computeSlotTimeMsec` (firmware `src/mesh/RadioInterface.cpp`, v2.8.1): a channel-activity scan of
     * four symbols plus half a symbol on an SX126x, plus 7.6 ms of propagation, turnaround and MAC time — ≈ 44.5 ms at
     * LongFast.
     */
    override fun slotTimeMs(radio: RadioParams): Double = (CAD_SYMBOLS + CAD_TAIL_SYMBOLS) * radio.symbolMs + SLOT_TURNAROUND_MS

    override val ackPayloadBytes: Int get() = LoraDirectPolicy.ACK_PAYLOAD_BYTES
    override val packetOverheadBytes: Int get() = LoraAirtime.PACKET_OVERHEAD_BYTES
    override val preambleSymbols: Double get() = LoraAirtime.PREAMBLE_SYMBOLS
    override val minGapMs: Long get() = LoraPacePolicy.MIN_GAP_MS
    override val roomPartLimit: Int get() = ROOM_PART_LIMIT

    /**
     * An RSSI (the NodeDB replay at every handshake has none), not an MQTT uplink, and an `rx_time`, when present, within
     * [LoraMeshTransport.HEARING_MAX_AGE_MS] (the to-phone queue replayed at reconnect) — ADR 2026-10.x9fa.
     */
    override fun heardLive(
        packet: BoardPacket,
        nowWallMs: Long,
    ): Boolean {
        val rssi = packet.rxRssi
        if (packet.meshtastic?.viaMqtt == true || rssi == null || rssi == 0) return false
        val heardAt = packet.rxTimeMs ?: return true
        return nowWallMs - heardAt <= LoraMeshTransport.HEARING_MAX_AGE_MS
    }

    /**
     * Our board's `39` is ours to act on (ADR 2026-10.aajj); the destination's 35 is our key missing there, never a
     * refusal; any other refusal by the destination holds the peer; 9 and 38 from our own board pace (60 s); the rest
     * does nothing.
     */
    override fun refusalOf(outcome: PacketOutcome.Refused): RefusalClass = refusalOf(outcome.reason, outcome.byDestination)

    override fun refusalOf(result: SendResult): RefusalClass? =
        when (result) {
            is SendResult.Rejected -> {
                if (result.res == RoutingError.PKI_SEND_FAIL_PUBLIC_KEY.code) RefusalClass.OWN_NO_KEY else RefusalClass.OTHER
            }

            is SendResult.Nak -> {
                refusalOf(result.reason, byDestination = false)
            }

            else -> {
                null
            }
        }

    /** The one rule behind both [refusalOf]s, and behind `LoraPacePolicy.onNak`. */
    fun refusalOf(
        reason: RoutingError,
        byDestination: Boolean,
    ): RefusalClass =
        when {
            reason == RoutingError.PKI_SEND_FAIL_PUBLIC_KEY && !byDestination -> RefusalClass.OWN_NO_KEY
            reason == RoutingError.PKI_UNKNOWN_PUBKEY && byDestination -> RefusalClass.PEER_LACKS_KEY
            byDestination -> RefusalClass.DEST_REFUSED
            reason == RoutingError.RATE_LIMIT_EXCEEDED || reason == RoutingError.DUTY_CYCLE_LIMIT -> RefusalClass.PACE
            else -> RefusalClass.OTHER
        }

    /** A modem preset's modulation: the spreading factor, bandwidth and coding rate the firmware programs for it. */
    fun radioParams(preset: ModemPreset): RadioParams = RadioParams(preset.spreadFactor, preset.bandwidthHz, preset.codingRate)

    /**
     * The duty cycle a board's config puts us under: its region's, or 100 % where the user set the firmware's own
     * `override_duty_cycle` (they took the regulatory call), and whether it sits on Knit's dedicated slot.
     */
    fun dutyPolicy(config: LoraRadioConfig): DutyPolicy =
        DutyPolicy(
            dutyCyclePercent = if (config.overrideDutyCycle) FULL_PERCENT else config.region.dutyCyclePercent,
            dedicatedSlot = config.dedicatedSlot,
        )

    /** An own room post rides in one packet (ADR 2026-10.rp3s). */
    const val ROOM_PART_LIMIT = 1

    private const val FULL_PERCENT = 100.0

    // The contention slot (`RadioInterface::computeSlotTimeMsec`): an SX126x CAD of four symbols, half a symbol of
    // processing, and 0.2 + 0.4 + 7 ms of propagation, turnaround and MAC time.
    private const val CAD_SYMBOLS = 4
    private const val CAD_TAIL_SYMBOLS = 0.5
    private const val SLOT_TURNAROUND_MS = 7.6
}
