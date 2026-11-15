package app.getknit.knit.mesh.lora.meshcore

import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/**
 * The physical channel a [FakeMeshcoreAir] runs when one is given (ADR 2026-11.gsqu's Stage 5c-2a amendment): opt-in, so
 * every Stage 3b behaviour [MeshcoreFakeVsSimTest] pins stays as it was without it. With it, an airing takes its time on
 * the air, a receiver decodes one packet at a time, a repeater queues and draws its delay as `companion-v1.18.0` does, and
 * every node keeps the firmware's 160-entry seen ring.
 *
 * The airtime is the Semtech formula written here from the datasheet, never `LoraAirtime.formulaMs`: an explicit header,
 * CRC on, the firmware's preamble (`RadioLibWrapper::preambleLengthForSF`, `RadioLibWrappers.h:56`: 32 symbols at SF ≤ 8,
 * else 16) plus 4.25, low-data-rate optimisation once a symbol lasts 16 ms. At SF7 / 62.5 kHz / CR 4/5 a 181-B packet is
 * 633 ms, Q5's measured 633 ± 17 ms ([MeshcoreAirPhysicsTest]). The simulator's SX1262 takes 16 symbols at every SF
 * (`SimRadio.h:50`), so its Q15 parts sit 606 ms apart where these sit 633 (a divergence named in [MeshcoreFakeVsSimTest]).
 */
internal class MeshcoreChannel(
    val spreadFactor: Int = 8,
    val bandwidthHz: Int = 62_500,
    /** The coding rate's denominator: 5 for 4/5. */
    val codingRate: Int = 5,
    /** The seed every repeater draw mixes in (`LabChaos.seed` in the lab, a fixed constant otherwise). */
    val runSeed: Long = 1L,
) {
    /** `tx_delay_factor` (`simple_repeater/MyMesh.cpp:893`, 0.5 by default, up to 2.0 at `:986`). */
    var txDelayFactor = 0.5

    /** `direct_tx_delay_factor` (`MyMesh.cpp:894`, 0.3). */
    var directTxDelayFactor = 0.3

    /** `rx_delay_base` (`MyMesh.cpp:892`, 0: off). Above 0 a flood waits `calcRxDelay` before it is processed. */
    var rxDelayBase = 0.0

    /** The packet score `calcRxDelay` reads (`packetScore`), per link: 1.0 by default, a clean link. */
    var score: (from: String, to: String) -> Double = { _, _ -> 1.0 }

    /**
     * Pins every repeater draw ([FakeMeshcoreAir.Draw]): given the draw's inclusive maximum `5·t`, the delay to use. Null
     * draws as the firmware does, from the draw's own seeded source. An overtake case pins it at its maximum (the
     * forger's best case).
     */
    var pinDraw: ((maxMs: Long) -> Long)? = null

    /** Radios that ignore the 50 % tx budget and listen before nothing: a forger's own hardware, not a firmware board. */
    val rogue = HashSet<String>()

    /**
     * The preamble a radio airs behind, in symbols before the 4.25 of sync, or null for the firmware's own: a forger's
     * hardware may air the shortest one a receiver still locks on.
     */
    var preambleFor: (from: String) -> Double? = { null }

    /**
     * Capture, per link: whether [receiver], locked on [locked]'s packet, keeps it through an overlap by [newcomer]'s
     * (a few dB of advantage). Never by default: an equal-power, same-SF overlap corrupts both packets — radio behaviour
     * the firmware source says nothing about, so a case that needs capture states it here.
     */
    var capture: (receiver: String, locked: String, newcomer: String) -> Boolean = { _, _, _ -> false }

    /** Milliseconds a symbol lasts at this preset. */
    val symbolMs: Double get() = (1 shl spreadFactor) * 1_000.0 / bandwidthHz

    /**
     * The time on air of a [bytes]-byte LoRa packet (Semtech AN1200.13), truncated to the millisecond as RadioLib's
     * `getTimeOnAir` / 1000 is: what the firmware's `getEstAirtimeFor` answers, and how long the fake's radio is busy.
     */
    fun airtimeMs(
        bytes: Int,
        preambleSymbols: Double? = null,
    ): Long {
        val tSym = symbolMs
        val lowRate = if (tSym >= LOW_RATE_SYMBOL_MS) 1 else 0
        val preamble = (preambleSymbols ?: if (spreadFactor <= SHORT_PREAMBLE_MAX_SF) SHORT_PREAMBLE else LONG_PREAMBLE) + SYNC_SYMBOLS
        val numerator = 8.0 * bytes - 4 * spreadFactor + CRC_AND_HEADER_BITS
        val denominator = 4.0 * (spreadFactor - 2 * lowRate)
        val payloadSymbols = 8 + max(ceil(numerator / denominator) * codingRate, 0.0)
        return ((preamble + payloadSymbols) * tSym).toLong()
    }

    /** `calcRxDelay` (`simple_repeater/MyMesh.cpp:542-545`): 0 when off. */
    fun rxDelayMs(
        score: Double,
        airMs: Long,
    ): Long = if (rxDelayBase <= 0.0) 0 else ((rxDelayBase.pow(RX_DELAY_SCORE_BASE - score) - 1.0) * airMs).toLong()

    companion object {
        private const val LOW_RATE_SYMBOL_MS = 16.0
        private const val SHORT_PREAMBLE_MAX_SF = 8
        private const val SHORT_PREAMBLE = 32.0
        private const val LONG_PREAMBLE = 16.0
        private const val SYNC_SYMBOLS = 4.25

        /** `28` of the formula's numerator, and 16 for the CRC; an explicit header adds nothing. */
        private const val CRC_AND_HEADER_BITS = 28 + 16
        private const val RX_DELAY_SCORE_BASE = 0.85

        /** `MAX_TRANS_UNIT` (`MeshCore.h`): the budget check prices a full packet. */
        const val MAX_TRANS_UNIT = 255

        /** A rx delay under this is processed at once (`Dispatcher.cpp:243`). */
        const val RX_DELAY_THRESHOLD_MS = 50L

        /** `MAX_RX_DELAY_MILLIS` (`Dispatcher.cpp:11`). */
        const val MAX_RX_DELAY_MS = 32_000L

        /** `getCADFailRetryDelay` and `getCADFailMaxDuration` (`Dispatcher.cpp:57-62`). */
        const val CAD_RETRY_MS = 200L
        const val CAD_MAX_MS = 4_000L

        /** `getDutyCycleWindowMs` (`Dispatcher.h:173`) and the duty `1 / (1 + airtime_factor 1.0)`. */
        const val DUTY_WINDOW_MS = 3_600_000L
        const val DUTY = 0.5

        /** `MAX_PACKET_HASHES` (`SimpleMeshTables.h:9`). */
        const val RING_SIZE = 160

        /** `MAX_PATH_SIZE` (`MeshCore.h`): a flood whose path would grow past it is not re-aired. */
        const val MAX_PATH_SIZE = 64

        /** `TXT_ACK_DELAY` (`BaseChatMesh.cpp:9`), the reciprocal PATH's 500 (`Mesh.cpp:183`), the Q13 PATH's 3 s (`:389`). */
        const val ACK_DELAY_MS = 200L
        const val RECIPROCAL_PATH_DELAY_MS = 500L
        const val RETURN_PATH_RETRY_DELAY_MS = 3_000L

        /** `PAYLOAD_TYPE_*` (`Packet.h`). */
        const val TYPE_TXT_MSG = 0x02
        const val TYPE_ACK = 0x03
        const val TYPE_GRP_TXT = 0x05
        const val TYPE_GRP_DATA = 0x06
        const val TYPE_PATH = 0x08

        /** Zero-padding to the AES block, as `Utils::encrypt` does. */
        fun aes(plaintext: Int): Int = (plaintext + 15) / 16 * 16

        /** A seed mixed from [parts], stable across runs and interleavings (never a packet's random bytes). */
        fun mix(vararg parts: Any): Long {
            var h = -0x61c8864680b583ebL
            for (p in parts) {
                h = (h xor p.toString().hashCode().toLong()) * -0x4b6d499041670d8dL
                h = h xor (h ushr 31)
            }
            return h
        }
    }
}

/** What one packet carries, in the terms its receiver acts on: the fake's stand-in for the decrypted payload. */
internal sealed interface AirBody {
    /** A contact text (`TXT_MSG`). */
    class Msg(
        val from: FakeMeshcoreBoard,
        val to: ByteArray,
        val ts: Long,
        val attempt: Int,
        val text: ByteArray,
    ) : AirBody

    /** An ack of [code]. */
    class Ack(
        val code: Int,
    ) : AirBody

    /** A PATH return: [outPath] (with its [outPathLen] byte) is the route [to] should use to reach [from]. */
    class Path(
        val from: FakeMeshcoreBoard,
        val to: ByteArray,
        val outPath: ByteArray,
        val outPathLen: Int,
        val extraAck: Int?,
    ) : AirBody

    /** Channel data on [secret]. */
    class Group(
        val secret: ByteArray,
        val dataType: Int,
        val data: ByteArray,
    ) : AirBody

    /** Channel text on [secret]. */
    class GroupText(
        val secret: ByteArray,
        val ts: Long,
        val text: ByteArray,
    ) : AirBody
}

/**
 * One packet on the air. [identity] stands for the firmware's payload: two packets' identities are equal exactly when
 * their payloads would be (ECB with no nonce, Q10), so the ring keys on SHA-256(type + identity), path excluded
 * (`Packet::calculatePacketHash`, `Packet.cpp:41-50`). [payloadLen] is the payload's size on the air.
 */
internal class AirPacket(
    val type: Int,
    val label: String,
    val identity: ByteArray,
    val payloadLen: Int,
    val flood: Boolean,
    /** A flood's hashes so far, or a direct packet's route still ahead. */
    val path: ByteArray,
    val hashSize: Int,
    val origin: String,
    /** The origin's nth packet of this route kind: what a repeater's draw keys on. */
    val seq: Int,
    val body: AirBody,
    /** Aired, heard by nobody (the Stage 3b `loseDm` / `loseAck` knobs, in air). */
    val lost: Boolean = false,
    /** A scoped flood's 4 B of transport codes (`ROUTE_TYPE_TRANSPORT_FLOOD`): the sender's scope override or default. */
    val transportCodes: Boolean = false,
) {
    val hops: Int get() = path.size / hashSize

    /** The `path_len` byte: the hash size in the top two bits, the count below (`Packet::setPathHashSizeAndCount`). */
    val pathLenByte: Int get() = ((hashSize - 1) shl 6) or hops

    val rawLen: Int get() = 2 + (if (transportCodes) TRANSPORT_CODES_BYTES else 0) + path.size + payloadLen

    val hash: String by lazy {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(type.toByte())
        d.update(identity)
        d.digest().joinToString("") { "%02x".format(it) }
    }

    fun withPath(p: ByteArray): AirPacket =
        AirPacket(type, label, identity, payloadLen, flood, p, hashSize, origin, seq, body, lost, transportCodes)

    private companion object {
        const val TRANSPORT_CODES_BYTES = 4
    }
}

/** One node's radio: a firmware board's or a repeater's (`Dispatcher`, `StaticPoolPacketManager`, `SimpleMeshTables`). */
internal class FakeRadio(
    val name: String,
    val key: ByteArray,
    val repeater: Boolean,
    val pool: Int,
) {
    var powered = true

    /** Airings parked before anyone hears them ([FakeMeshcoreAir.hold]). */
    var held = false

    class Queued(
        val packet: AirPacket,
        var at: Long,
        val priority: Int,
    )

    /** The outbound queue (`PacketQueue`): soonest eligible, lowest priority, first in. */
    val queue = mutableListOf<Queued>()

    /** Released by [FakeMeshcoreAir.release]: aired first, in this order, whatever [held] says. */
    val forced = ArrayDeque<Queued>()

    var txUntil = -1L

    /** What the radio is demodulating until [endsAt]; [corrupted] once another packet overlapped it (lost, still busy). */
    class Reception(
        val packet: AirPacket,
        var endsAt: Long,
        val from: String,
        val airing: FakeMeshcoreAir.Airing,
    ) {
        var corrupted = false
    }

    var rx: Reception? = null

    /** Air from an overlap still on the channel after what was decoded ended (a captured packet's newcomer's tail). */
    var busyUntil = -1L
    var cadBusySince = 0L
    var budgetMs = MeshcoreChannel.DUTY_WINDOW_MS * MeshcoreChannel.DUTY
    var budgetAt = 0L

    /** Every airing's time on air, in ms, acks and PATHs included (`Dispatcher::loop`'s `total_air_time`). */
    var txAirMs = 0L
    var rxAirMs = 0L

    /** Packets lost here: one that began while another was being decoded, or while this radio was transmitting. */
    var collisions = 0
    var deaf = 0

    /** Byte-identical copies dropped by the ring. */
    var dups = 0
    var floods = 0
    var directs = 0

    private val ring = arrayOfNulls<String>(MeshcoreChannel.RING_SIZE)
    private var ringNext = 0

    fun wasSeen(p: AirPacket): Boolean = ring.any { it == p.hash }

    fun markSeen(p: AirPacket) {
        ring[ringNext] = p.hash
        ringNext = (ringNext + 1) % ring.size
    }

    /** Forgets every hash (a reboot: the ring is RAM, and neither role restores it). */
    fun clearRing() {
        ring.fill(null)
        ringNext = 0
    }

    fun transmitting(now: Long): Boolean = txUntil > now

    fun receiving(now: Long): Boolean = rx?.let { it.endsAt > now } == true || busyUntil > now

    /** Whether this radio's hash is the first of [path] (`isHashMatch`). */
    fun isNext(
        path: ByteArray,
        hashSize: Int,
    ): Boolean = path.size >= hashSize && (0 until hashSize).all { path[it] == key[it] }
}
