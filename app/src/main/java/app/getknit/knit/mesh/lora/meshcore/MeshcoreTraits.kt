package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardTraits
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.DutyPolicy
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraBand
import app.getknit.knit.mesh.lora.LoraDirectPolicy
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.PacketPrice
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.RefusalClass
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.lora.SigningFacts

/**
 * A MeshCore companion's numbers for the board-neutral core (ADR 2026-11.gsqu §1, built in its Stage 5a-1 amendment): the
 * price hook by purpose, the airtime term, the band's duty, the compact DM's airings, flights and backstop, and the
 * refusal classifier. Stateless, like [BoardTraits.MESHTASTIC]: constants and pure functions of their arguments, read under
 * the pacer's and the ledger's monitors. Nothing constructs a plane with it until Stage 5a-2.
 *
 * **Price.** What the firmware puts on the air for each purpose, at `companion-v1.18.0` (`Packet::writeTo`,
 * `Mesh::createGroupDatagram` / `createDatagram` / `createAck` / `createPathReturn`, `Utils::encryptThenMAC`): a header
 * byte, the 4 B of transport codes on a **scoped flood only** (`sendFloodScoped`; never on a direct packet, so never on a
 * zero-hop DM or an ack — priced on every flood, since whether the board's default scope is set is not ours to assume),
 * the path-length byte and the path (empty as our board first airs it), then the payload: a channel hash or the two
 * one-byte hashes, the 2-B MAC, and the plaintext zero-padded to the 16-B AES block (ECB, no extra block on a multiple of
 * 16). Never Meshtastic's signing facts: a MeshCore board signs nothing it floods for us, so [price] ignores them.
 *
 * **Airtime.** The LoRa formula with the firmware's own preamble: 32 symbols at SF ≤ 8 and 16 above
 * (`RadioLibWrapper::preambleLengthForSF`, `RadioLibWrappers.h:56`), plus the 4.25-symbol sync word. That reading closes
 * Q5's whole gap: 181 B at SF7 / BW 62.5 / CR 4/5 is 600.6 ms with a 16-symbol preamble and **633.3 ms** with 32, against
 * 633 ± 17 ms measured. So the excess was the preamble, not a ratio, and a fixed per-packet term stands in only for the
 * measurement's own uncertainty ([FIXED_MS]). A small-packet bench point is still owed.
 */
internal object MeshcoreTraits : BoardTraits {
    override val kind: BoardKind get() = BoardKind.MESHCORE

    /** Channel data the board takes and one ESP32 notify delivers whole ([MeshcoreProto.CHANNEL_DATA_CAP], Q8). */
    override val floorPayload: Int get() = MeshcoreProto.CHANNEL_DATA_CAP
    override val maxPayload: Int get() = MeshcoreProto.CHANNEL_DATA_CAP

    /** A fleet constant, whatever our own MTU: the receiver's notify sets it (§6), the seal under its COBS byte. */
    override fun directPayloadCap(payload: Int): Int = MeshcoreProto.DIRECT_SEAL_CAP

    /** The session's attempts 0..2 (§2, [MeshcoreSession.ATTEMPTS]). */
    override val airings: Int get() = MeshcoreSession.ATTEMPTS

    /**
     * `EXPECTED_ACK_TABLE_SIZE` over [airings]: the firmware's circular table holds eight **attempts**, each its own
     * `expected_ack`, so eight attempts are two whole flights of three. Built once; other clients' sends filling the table
     * are the session's `Busy` to answer, not this number's.
     */
    override val maxFlights: Int get() = MeshcoreAckTable.EXPECTED_ACK_TABLE_SIZE / airings

    /** MeshCore has no contention window of Meshtastic's kind: its retry gap is the session's ([retransmitMs]). */
    override val cwMin: Int get() = 0
    override val cwMax: Int get() = 0

    /**
     * The session's gap between two attempts of a zero-hop DM of [airMs]: the firmware's direct `est_timeout` at zero hops
     * (`calcDirectTimeoutMillisFor`: 500 ms + (6 × air + 250 ms) × 1, `MyMesh.cpp:891`) and the session's retry slack. It
     * sets only the core's guess of what flew when no outcome says ([PacketOutcome.flown] always does on this kind).
     */
    override fun retransmitMs(
        airMs: Long,
        slotMs: Double,
        cw: Int,
    ): Long = SEND_TIMEOUT_BASE_MS + airMs * DIRECT_PERHOP_FACTOR + DIRECT_PERHOP_EXTRA_MS + RETRY_SLACK_MS

    /**
     * A backstop the session always beats (the Stage 3a/3b amendment): never below [MeshcoreSession.MAX_FLIGHT_MS], the
     * session's own cap on a flight's life past which it ends the flight `Unreachable` itself, and the BLE hop's slack
     * beyond it. The session clamps every attempt's `est_timeout` to 60 s, so no airtime moves the session past its cap.
     */
    override fun deadlineMs(
        airMs: Long,
        slotMs: Double,
    ): Long = MeshcoreSession.MAX_FLIGHT_MS + LoraDirectPolicy.ANSWER_SLACK_MS

    override fun retriesBefore(
        elapsedMs: Long,
        retransmitMs: Long,
    ): Int = (elapsedMs / retransmitMs.coerceAtLeast(1)).toInt().coerceIn(0, airings - 1)

    /** No CAD slot enters MeshCore's retry gap; one symbol, for a caller that asks. */
    override fun slotTimeMs(radio: RadioParams): Double = radio.symbolMs

    /** A multi-ack's payload, the larger of the two forms our board acks with (`createMultiAck`: a count byte and the code). */
    override val ackPayloadBytes: Int get() = MULTI_ACK_BYTES

    /** The room flood's framing outside its AES block: header, transport codes, path length, channel hash and MAC. */
    override val packetOverheadBytes: Int get() = HEADER_BYTES + TRANSPORT_CODES_BYTES + PATH_LEN_BYTES + HASH_BYTES + MAC_BYTES

    /** The preamble and sync word at SF ≤ 8, MeshCore's narrow presets; [airMs] reads the firmware's rule per SF. */
    override val preambleSymbols: Double get() = SHORT_SF_PREAMBLE + SYNC_SYMBOLS

    /** The session paces itself off `GET_STATS` (§6): no gap of the pacer's own. */
    override val minGapMs: Long get() = 0L

    /** Two elided packets carry about 200 B of text (§4). */
    override val roomPartLimit: Int get() = ROOM_PART_LIMIT

    /**
     * The companion's build default (`platformio.ini` `[arduino_base]`: 869.618 MHz, BW 62.5 kHz, SF8; CR 4/5, `MyMesh.h`):
     * what a board that has reported no sane radio is priced at, never Meshtastic's LongFast.
     */
    override val fallbackRadio: RadioParams get() = FALLBACK_RADIO

    /** The law is Knit's alone on this kind: before a band is known, the strictest duty of any ([LoraBand]). */
    override val fallbackDutyPercent: Double get() = LoraBand.STRICTEST_PERCENT

    /** An own room post floods every repeater: admitted under the flood ceiling (§4). */
    override val roomCeiling: Ceiling get() = Ceiling.FLOOD

    /**
     * Whether [packet] is a live zero-hop reception: drained after this connection's first `NO_MORE` and channel data at
     * `path_len` 0 (§5). Never a contact text — its sender is a 6-byte prefix, a claim (the Stage 5 decisions) — and never
     * a replay. Stage 5a-2 splits this into `live` and `heardZeroHop` and keys a hearing on a verified Knit frame only.
     */
    override fun heardLive(
        packet: BoardPacket,
        nowWallMs: Long,
    ): Boolean {
        val mc = packet.kind as? MeshcorePacket ?: return false
        return mc.live && mc.type == MeshcorePacket.Type.CHANNEL_DATA && !mc.direct && (mc.pathLen and MeshcoreProto.PATH_HOPS_MASK) == 0
    }

    /** MeshCore reports no refusal after a send: a confirmation, a timeout or the session's own give-up. */
    override fun refusalOf(outcome: PacketOutcome.Refused): RefusalClass = RefusalClass.OTHER

    override fun refusalOf(result: SendResult): RefusalClass? = MeshcoreRefusal.classOf(result)

    /** A MeshCore price is its purpose: no signature, no extra bytes; [phyBytes] frames it. */
    override fun price(
        purpose: AirPurpose,
        facts: SigningFacts,
        primaryAead: Boolean,
        knitAead: Boolean,
    ): PacketPrice = PacketPrice(LoraAirtime.UNSIGNED, 0, capOf(purpose), purpose)

    /**
     * The bytes on the air for a [payloadBytes] payload, by the price's purpose (a price with none is a room flood):
     * - a room flood (`GRP_DATA`): header, transport codes, path length, channel hash, MAC, and AES over the data type,
     *   its length byte and the payload;
     * - a zero-hop DM (`TXT_MSG`, direct): header, path length, the two hashes, MAC, and AES over the timestamp, the
     *   flags byte and the COBS form of the payload — no transport codes;
     * - an ack: header, path length and the [payloadBytes]-byte ack (4, or 5 for a multi-ack) — no AES, no transport codes;
     * - a PATH return, flooded: header, transport codes, path length, the two hashes, MAC, and AES over [payloadBytes] of
     *   path plaintext ([pathReturnPlaintext]);
     * - a public post (`GRP_TXT` on slot 0, flooded): as a room flood, over the timestamp and flags before the text.
     */
    override fun phyBytes(
        payloadBytes: Int,
        price: PacketPrice,
    ): Int =
        when (price.purpose ?: AirPurpose.ROOM_FLOOD) {
            AirPurpose.ROOM_FLOOD -> {
                FLOOD_HEAD + HASH_BYTES + MAC_BYTES + aesPadded(GROUP_DATA_HEADER + payloadBytes)
            }

            AirPurpose.DIRECT_DM, AirPurpose.REPLY -> {
                DIRECT_HEAD + 2 * HASH_BYTES + MAC_BYTES + aesPadded(TEXT_HEADER + Cobs.overhead(payloadBytes) + payloadBytes)
            }

            AirPurpose.ACK -> {
                DIRECT_HEAD + payloadBytes
            }

            AirPurpose.PATH_RETURN -> {
                FLOOD_HEAD + 2 * HASH_BYTES + MAC_BYTES + aesPadded(payloadBytes)
            }

            AirPurpose.PUBLIC_POST -> {
                FLOOD_HEAD + HASH_BYTES + MAC_BYTES + aesPadded(TEXT_HEADER + payloadBytes)
            }
        }

    /** The formula with the firmware's preamble for [radio]'s spreading factor, and [FIXED_MS] per packet. */
    override fun airMs(
        phyBytes: Int,
        radio: RadioParams,
    ): Long = LoraAirtime.formulaMs(phyBytes, radio, preambleSymbolsAt(radio.spreadFactor)) + FIXED_MS

    /** `RadioLibWrapper::preambleLengthForSF` (`RadioLibWrappers.h:56`) and the sync word: 32 + 4.25 at SF ≤ 8, else 16 + 4.25. */
    fun preambleSymbolsAt(spreadFactor: Int): Double =
        (if (spreadFactor <= SHORT_PREAMBLE_MAX_SF) SHORT_SF_PREAMBLE else LONG_SF_PREAMBLE) + SYNC_SYMBOLS

    /**
     * The plaintext of our board's PATH return answering a flood that came over [hops] one-byte path hashes
     * (`createPathReturn`): the path length byte, the path, the extra type and the 4-B ack it carries.
     */
    fun pathReturnPlaintext(hops: Int): Int = PATH_LEN_BYTES + hops * HASH_BYTES + 1 + ACK_BYTES

    /**
     * The duty a board on [freqKhz] at [bandwidthHz] transmits under: its band's ([LoraBand]), never more, and never a
     * dedicated slot (Knit never moves a MeshCore radio, ADR 045). What the ledger's allowance reads once Stage 5a-2 feeds
     * it: `min(duty, 10 %) × 0.5` of the window, so the allowance never exceeds the band's duty.
     */
    fun dutyPolicy(
        freqKhz: Long,
        bandwidthHz: Int,
    ): DutyPolicy = DutyPolicy(LoraBand.dutyPercent(freqKhz * HZ_PER_KHZ, bandwidthHz.toLong()), dedicatedSlot = false)

    /** Own room posts in up to two elided packets (§4). */
    const val ROOM_PART_LIMIT = 2

    /**
     * The per-packet term on top of the formula: the uncertainty of Q5's 60-send measurement at 181 B (±17 ms), for the
     * board's ramp and loop-poll latency the preamble does not already explain. Not a bound — the measurement's spread at
     * one size, which a small-packet bench point is still owed to replace.
     */
    const val FIXED_MS = 17L

    private val FALLBACK_RADIO = RadioParams(spreadFactor = 8, bandwidthHz = 62_500, codingRate = 5)

    private const val HEADER_BYTES = 1
    private const val TRANSPORT_CODES_BYTES = 4
    private const val PATH_LEN_BYTES = 1
    private const val HASH_BYTES = 1
    private const val MAC_BYTES = 2
    private const val ACK_BYTES = 4
    private const val MULTI_ACK_BYTES = 5

    /** A flood's bytes before its payload: header, transport codes, path length (and no path as our board airs it). */
    private const val FLOOD_HEAD = HEADER_BYTES + TRANSPORT_CODES_BYTES + PATH_LEN_BYTES

    /** A zero-hop direct packet's bytes before its payload: header and path length, nothing else. */
    private const val DIRECT_HEAD = HEADER_BYTES + PATH_LEN_BYTES

    /** `sendGroupData`'s plaintext header: the data type (2) and the data length (1) (`BaseChatMesh.cpp:550-552`). */
    private const val GROUP_DATA_HEADER = 3

    /** A text's plaintext header: the timestamp (4) and the flags byte (`txt_type << 2 | attempt`). */
    private const val TEXT_HEADER = 5

    private const val SHORT_PREAMBLE_MAX_SF = 8
    private const val SHORT_SF_PREAMBLE = 32.0
    private const val LONG_SF_PREAMBLE = 16.0
    private const val SYNC_SYMBOLS = 4.25

    // The companion's timeouts (`MyMesh.cpp:108-111`) and the session's retry slack.
    private const val SEND_TIMEOUT_BASE_MS = 500L
    private const val DIRECT_PERHOP_FACTOR = 6L
    private const val DIRECT_PERHOP_EXTRA_MS = 250L
    private const val RETRY_SLACK_MS = 1_000L

    private const val HZ_PER_KHZ = 1_000L
}

private const val AES_BLOCK = 16

/** Zero-padding to the AES block, as `Utils::encrypt` does: none for an exact multiple, never an extra block. */
private fun aesPadded(plaintextBytes: Int): Int = (plaintextBytes + AES_BLOCK - 1) / AES_BLOCK * AES_BLOCK

/** The largest payload a [purpose] takes: a sealed compact DM under its COBS byte, else channel data. */
private fun capOf(purpose: AirPurpose): Int =
    when (purpose) {
        AirPurpose.DIRECT_DM, AirPurpose.REPLY -> MeshcoreProto.DIRECT_SEAL_CAP
        else -> MeshcoreProto.CHANNEL_DATA_CAP
    }
