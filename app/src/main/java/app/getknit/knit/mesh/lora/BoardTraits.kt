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
@Suppress("TooManyFunctions") // one board kind's pure facts, each its own question (ADR 2026-11.gsqu)
internal interface BoardTraits {
    /** Which kind these are (Stage 2): what the plane reports as its `boardKind`. */
    val kind: BoardKind

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

    /**
     * The most direct sends ended with no answer whose handles are remembered for a late one (ADR 2026-11.gsqu's Stage 5c-2b
     * amendment): as many as the link can still answer for after its own give-up.
     */
    val maxEnded: Int

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

    /**
     * The header and framing around a payload on the air, for the time-on-air formula. Meshtastic's [phyBytes] adds it to
     * every packet; a kind whose framing depends on the packet's purpose ([MeshcoreTraits]) states its room flood's here
     * and prices each purpose in [phyBytes].
     */
    val packetOverheadBytes: Int

    /**
     * The preamble and sync word, in symbols, for the time-on-air formula ([airMs]). A kind whose preamble depends on the
     * spreading factor states its value at the shortest spreading factors and reads its own rule in [airMs].
     */
    val preambleSymbols: Double

    /**
     * The kind's price hook (ADR 2026-11.gsqu's 2026-11-13 Stage 5a-1 amendment): what a packet for [purpose] costs past
     * its payload on this board, as the ledger prices it. Meshtastic reads the board's signing snapshot — [facts] and
     * whether slot 0 ([primaryAead]) and the bound Knit slot ([knitAead]) are AEAD — through `LoraAirtime.price`, exactly
     * as `OutboundFrame.price` always did; a kind that never signs ignores them and carries [purpose] on the price, which
     * its own [phyBytes] reads.
     */
    fun price(
        purpose: AirPurpose,
        facts: SigningFacts,
        primaryAead: Boolean,
        knitAead: Boolean,
    ): PacketPrice

    /** The bytes a packet with a [payloadBytes] payload at [price] puts on the air, every header, pad and signature in. */
    fun phyBytes(
        payloadBytes: Int,
        price: PacketPrice,
    ): Int

    /** The kind's airtime term: milliseconds [phyBytes] occupy the medium at [radio] (never under 1). */
    fun airMs(
        phyBytes: Int,
        radio: RadioParams,
    ): Long

    /**
     * The least time a room packet carrying [payloadBytes] can take on the air at [radio], whoever airs it: the floor a
     * receiver's decode rate is sized from (`LoraFragCandidates`' per-side cap, ADR 2026-11.gsqu's Stage 5c-2a amendment).
     * Priced under the kind's stated assumption about the shortest form a receiver decodes, never at [phyBytes]'
     * conservative bytes (a scope's transport codes) or [airMs]'s booked margin: a forger's radio airs the shortest form.
     */
    fun leastRoomAirMs(
        payloadBytes: Int,
        radio: RadioParams,
    ): Long

    /** The modulation the ledger prices at before the board reports one: the kind's own shipping default. */
    val fallbackRadio: RadioParams

    /**
     * The duty cycle the allowance reads before the board reports its band. Meshtastic: `LoraAirtime.FALLBACK_PERCENT`,
     * below every region's limit; a kind whose law Knit alone enforces takes the strictest duty of any band it could be
     * on ([LoraBand.STRICTEST_PERCENT]), never a fallback that could be over the law.
     */
    val fallbackDutyPercent: Double

    /**
     * The [Ceiling] an own room post is admitted under: Meshtastic's [Ceiling.FULL], as the core always queued it; a kind
     * whose room floods every repeater takes [Ceiling.FLOOD] (ADR 2026-11.gsqu §4).
     */
    val roomCeiling: Ceiling

    /**
     * The [Ceiling] the user's post on the board's public channel is admitted under: Meshtastic's [Ceiling.HALF] (ADR
     * 2026-10.ug4a); MeshCore's Public post floods every repeater, so [Ceiling.FLOOD] beside Knit's own room posts (ADR
     * 2026-11.gsqu §4, its Stage 6 amendment). What `AirtimeSnapshot.publicPostBytes` reads the most a post can ever carry
     * under.
     */
    val publicCeiling: Ceiling get() = Ceiling.HALF

    /** The pacer's shortest gap between two packets — the factory default of `LoraPacePolicy`, never an injected one. */
    val minGapMs: Long

    /** The most packets an own room post may take (ADR 2026-10.rp3s: one on Meshtastic; two on MeshCore, gsqu §4). */
    val roomPartLimit: Int

    /**
     * Whether an own room post rides in the elided form (`0x06`, `FastFrameCodec.encodeElided`: the sender a 4-byte prefix)
     * and an inbound one is read as such: resolved against the authors whose key we hold, and every fragment set joined by
     * the combination whose signature verifies ([LoraFragCandidates]), since the kind's channel packets name no sender on a
     * key anyone holds. False on Meshtastic, whose plane never reads `0x06`; true on MeshCore (ADR 2026-11.gsqu §4, Stage
     * 5c-1).
     */
    val elidesRoom: Boolean

    /**
     * Whether [packet] came off the air just now — never a replay of the board's queue, an uplink, or a packet that did not
     * cross the air — at any hop count. What the cover's lift reads (#294: a live compact frame, any path). [nowWallMs] is
     * the wall clock. (ADR 2026-11.gsqu's Stage 5a-2 amendment split the old `heardLive` into this and [heardZeroHop].)
     */
    fun live(
        packet: BoardPacket,
        nowWallMs: Long,
    ): Boolean

    /**
     * Whether [packet] came straight from the radio that aired it, by the kind's own rule — what a hearing of a board needs
     * beside [live] (ADR 2026-11.gsqu §5). Meshtastic reads no hop count here (ADR 2026-10.x9fa counts every live
     * hearing), so it answers true; MeshCore answers its zero-hop test, live drains only.
     */
    fun heardZeroHop(packet: BoardPacket): Boolean

    /**
     * Whether a packet's sender ([BoardPacket.from]) is the board's own word, which the core may key a hearing on. True on
     * Meshtastic (the node number in the packet's header, ADR 2026-10.x9fa). False on MeshCore: a contact DM's 6-byte
     * sender prefix is a claim behind a 2-byte MAC and channel data names no sender, so a hearing there comes only from a
     * verified Knit frame — an opened seal, or a channel post's verified author (ADR 2026-11.gsqu's Stage 5 decisions).
     */
    val hearsSender: Boolean

    /** A hearing as the sender-keyed hook reads it: [live] and [heardZeroHop] together. */
    fun heardLive(
        packet: BoardPacket,
        nowWallMs: Long,
    ): Boolean = live(packet, nowWallMs) && heardZeroHop(packet)

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
 * What a packet is on the air, for a kind's price hook ([BoardTraits.price], [BoardTraits.phyBytes]): its framing turns on
 * this on MeshCore (a flood carries transport codes, a zero-hop DM and an ack never do), and on Meshtastic it names the
 * slot, the packet class and the port `LoraAirtime.price` reads.
 */
internal enum class AirPurpose {
    /** An own Knit room post: a broadcast on the bound slot (Meshtastic), a scoped `GRP_DATA` flood (MeshCore). */
    ROOM_FLOOD,

    /** A compact DM, its ✓✓ or a reaction: a PKI data unicast (Meshtastic), a zero-hop contact text DM (MeshCore). */
    DIRECT_DM,

    /** Our board's ACK of a compact DM to us. */
    ACK,

    /** Our board's flooded PATH return answering a flooded DM to us (MeshCore only; never on Meshtastic). */
    PATH_RETURN,

    /** A post on the board's public channel, slot 0: a broadcast (Meshtastic), a scoped `GRP_TXT` flood (MeshCore). */
    PUBLIC_POST,

    /** The DM auto-reply (Meshtastic only): a data unicast on slot 0. */
    REPLY,
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
@Suppress("TooManyFunctions") // implements BoardTraits, one function a question
internal object MeshtasticTraits : BoardTraits {
    override val kind: BoardKind get() = BoardKind.MESHTASTIC

    override val floorPayload: Int get() = LoraMeshTransport.PRE_READY_PAYLOAD
    override val maxPayload: Int get() = MeshtasticProto.MAX_PAYLOAD

    override fun directPayloadCap(payload: Int): Int = LoraDirectPolicy.payloadCap(payload)

    override val airings: Int get() = LoraDirectPolicy.AIRINGS
    override val maxFlights: Int get() = LoraDirectPolicy.MAX_FLIGHTS
    override val maxEnded: Int get() = LoraDirectPolicy.MAX_FLIGHTS
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
    override val elidesRoom: Boolean get() = false
    override val fallbackRadio: RadioParams get() = FALLBACK_RADIO
    override val fallbackDutyPercent: Double get() = LoraAirtime.FALLBACK_PERCENT
    override val roomCeiling: Ceiling get() = Ceiling.FULL

    /**
     * Each purpose as the slot, the packet class and the port `OutboundFrame.price` always read (#243): a room post a
     * broadcast on the bound slot's private port, a compact DM a data unicast there (which 2.8 PKI-encrypts), a public
     * post a text broadcast on slot 0, the auto-reply a text unicast there, our board's ACK a routing unicast. A PATH
     * return is no Meshtastic packet; it reads as the room post's broadcast, the expensive answer, and nothing asks it.
     * Pinned against the code it replaced by `LoraAirtimeKindParityTest`.
     */
    override fun price(
        purpose: AirPurpose,
        facts: SigningFacts,
        primaryAead: Boolean,
        knitAead: Boolean,
    ): PacketPrice {
        val (slot, kind, port) =
            when (purpose) {
                AirPurpose.ROOM_FLOOD, AirPurpose.PATH_RETURN -> {
                    Triple(
                        AirSlot.KNIT,
                        PacketKind.BROADCAST,
                        MeshtasticProto.PORT_PRIVATE_APP,
                    )
                }

                AirPurpose.DIRECT_DM -> {
                    Triple(AirSlot.KNIT, PacketKind.DATA_UNICAST, MeshtasticProto.PORT_PRIVATE_APP)
                }

                AirPurpose.ACK -> {
                    Triple(AirSlot.KNIT, PacketKind.ROUTING_UNICAST, MeshtasticProto.PORT_ROUTING)
                }

                AirPurpose.PUBLIC_POST -> {
                    Triple(AirSlot.PRIMARY, PacketKind.BROADCAST, MeshtasticProto.PORT_TEXT_MESSAGE)
                }

                AirPurpose.REPLY -> {
                    Triple(AirSlot.PRIMARY, PacketKind.DATA_UNICAST, MeshtasticProto.PORT_TEXT_MESSAGE)
                }
            }
        return LoraAirtime.price(facts, aead = if (slot == AirSlot.PRIMARY) primaryAead else knitAead, kind, port)
    }

    /**
     * The payload, the bytes its encryption adds, [packetOverheadBytes] of header and framing, and the 2.8 signature when
     * the payload is at or under the price's cliff — the firmware's own `signedDataFits`, a cliff rather than a ramp: a
     * 165-byte payload costs 66 bytes more than a 166-byte one (ADR 2026-09.mhs5).
     */
    override fun phyBytes(
        payloadBytes: Int,
        price: PacketPrice,
    ): Int {
        val signature = if (payloadBytes <= price.signedUpTo) MeshtasticProto.XEDDSA_SIGNATURE_FIELD else 0
        return payloadBytes + price.extraBytes + packetOverheadBytes + signature
    }

    /** The LoRa formula (Semtech AN1200.13) with the firmware's 16-symbol preamble and its sync word ([preambleSymbols]). */
    override fun airMs(
        phyBytes: Int,
        radio: RadioParams,
    ): Long = LoraAirtime.formulaMs(phyBytes, radio, preambleSymbols)

    /** An unsigned broadcast's bytes at the formula: Meshtastic's room never fragments, so nothing reads this yet. */
    override fun leastRoomAirMs(
        payloadBytes: Int,
        radio: RadioParams,
    ): Long = airMs(payloadBytes + packetOverheadBytes, radio)

    /**
     * An RSSI (the NodeDB replay at every handshake has none), not an MQTT uplink, and an `rx_time`, when present, within
     * [LoraMeshTransport.HEARING_MAX_AGE_MS] (the to-phone queue replayed at reconnect) — ADR 2026-10.x9fa.
     */
    override fun live(
        packet: BoardPacket,
        nowWallMs: Long,
    ): Boolean {
        val rssi = packet.rxRssi
        if (packet.meshtastic?.viaMqtt == true || rssi == null || rssi == 0) return false
        val heardAt = packet.rxTimeMs ?: return true
        return nowWallMs - heardAt <= LoraMeshTransport.HEARING_MAX_AGE_MS
    }

    /** No hop count is read on Meshtastic: every live hearing counts, as it always did (ADR 2026-10.x9fa). */
    override fun heardZeroHop(packet: BoardPacket): Boolean = true

    /** The packet's `from` is the board's node number, what the hearing hook has always keyed on. */
    override val hearsSender: Boolean get() = true

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

    /** What the formula prices before a board has reported its modulation: LongFast, the shipping default. */
    private val FALLBACK_RADIO: RadioParams = radioParams(ModemPreset.LONG_FAST)

    private const val FULL_PERCENT = 100.0

    // The contention slot (`RadioInterface::computeSlotTimeMsec`): an SX126x CAD of four symbols, half a symbol of
    // processing, and 0.2 + 0.4 + 7 ms of propagation, turnaround and MAC time.
    private const val CAD_SYMBOLS = 4
    private const val CAD_TAIL_SYMBOLS = 0.5
    private const val SLOT_TURNAROUND_MS = 7.6
}
