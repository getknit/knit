package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.protocol.Protocol
import java.nio.ByteBuffer

/**
 * The fixed 24-byte BLE **advertisement service-data** payload the Bluetooth transport broadcasts and scans
 * for — the coordination-plane advert, the L2CAP analogue of Wi-Fi Aware's `serviceSpecificInfo`. It fills a
 * legacy 31-byte advertisement exactly (Flags 3 + service-data AD header 4 + 24 = 31) — which is why the advert
 * carries **only** service data (no separate service-UUID list AD, see [BleAdvertiser]) and the nodeId rides
 * as its raw 16 bytes rather than 26 ASCII chars:
 *
 * ```
 * byte 0       capabilities, low 8 bits (Protocol.CAP_E2E … CAP_FRAME_TRANSCODE — every bit is spent)
 * bytes 1..16  nodeId, 16 raw bytes (NodeId.BYTES = 128 bits), decoded to the 26-char id via NodeId.fromBytes
 * bytes 17..20 digest cue = low 32 bits of StoreDigest.version (BE) — both BLE peers truncate identically
 * bytes 21..22 L2CAP PSM (unsigned 16-bit, BE) so an initiator knows which channel to connect to
 * byte 23      BLE-local flags: bit 0 [FLAG_SIDE_CHANNEL], bit 1 [FLAG_DIALS_GATT_PEERS], bit 2 [FLAG_AWARE],
 *              bit 3 [FLAG_BLINDED], bit 4 [FLAG_LINK_SEAL]; bits 5..7 reserved (0). Optional on the wire: a build before it advertised 23
 *              bytes, and this parser reads a missing byte as 0. Every shipped parser tests only the bits it knows, so a
 *              new bit is additive.
 * ```
 *
 * **Blinded adverts** (ADR 2026-11.xp6d, knit-next #285): with [FLAG_BLINDED] set, bytes 1–16 are a rotating
 * [BlindTag], never a node id, and a blinding writer zeroes the digest cue (unchanged it would tie one tag to the next).
 * [parse] reads such an advert as [Parsed.blinded], its bytes as the tag's 26-char form in [Parsed.nodeId]'s place, and
 * answers null for one whose bytes lack [BlindTag.hasPrefix] (malformed, dropped before anything is computed; the
 * caller counts it through [isMalformedBlinded]). A shipped build ignores the bit and reads the tag as an id, which
 * sorts `zzzz…` above all but about 2⁻²⁰ of real ids, so its ordinary dial leaves the pair to the blinding side.
 *
 * Wi-Fi Aware is said **positively** (GitHub #44, ADR 2026-10.2t49's 2026-10-27 amendment): bit 2 set means "count on
 * my Aware now", and a peer is an Aware candidate only on it. So an advert that never had the byte, and every released
 * build (2.10.0 and older, none of which sets bit 2), reads "don't count on it", and a false clear bit costs only a
 * delayed wake. Main builds from 2026-10-24 until this change wrote bit 2 with the opposite meaning ("don't count on
 * me"); that form never shipped in a release. knit-ios reads the byte without validating it and should reserve bit 2
 * (a follow-up paired change).
 *
 * Byte 23 is the **last** byte the legacy advert can carry, and it is deliberately not a second capabilities
 * byte: `Protocol` reserves the bits from 0x100 up for the authenticated profile and the Wi-Fi Aware advert,
 * and what rides here is mostly a fact about *this controller* (it can advertise and scan extended pages), which the
 * build-wide `Protocol.LOCAL_CAPABILITIES` could never vouch for. [FLAG_LINK_SEAL] is the exception: a fact about the
 * *build* (its HELLO carries `Protocol.CAP_LINK_SEAL`), which rides here because a dialer must pick the link's opening
 * record before any record has crossed, and the low capabilities byte has no bit to spare (#286).
 *
 * There is **no** format-version byte: the layout/`protoVersion` is implied by the versioned service UUID (a
 * peer matching our UUID is our version; a breaking change bumps the UUID and hard-partitions at discovery,
 * like NAN's `SERVICE_NAME` `.vN`), and the full id is confirmed authoritatively over the socket in the HELLO.
 * Growing 23 → 24 bytes was additive under docs/WIRE_COMPAT.md: the older parser ignores trailing bytes (a
 * pinned test) and the older scan filter matches service data of any length.
 * Pure (no Android), so it is JVM-unit-testable ([app.getknit.knit.BleAdvertPayloadTest]).
 */
internal object BleAdvertPayload {
    /** The size every shipped build parses: 1 (caps) + 16 (nodeId) + 4 (digest cue) + 2 (psm). */
    const val SIZE_V1 = 1 + NodeId.BYTES + 4 + 2

    /** Payload size this build emits: [SIZE_V1] plus the flags byte. */
    const val SIZE = SIZE_V1 + 1

    /**
     * This controller advertises **and** scans the BLE side channel — the non-connectable extended-advertising
     * pages [BleSideChannel] carries small floodable frames on. A sender offers a frame to its own pages only
     * while a flagged peer is nearby (or linked), so a mesh of older builds never spends the airtime.
     */
    const val FLAG_SIDE_CHANNEL = 0x01

    /**
     * This node reads the GATT payload of a peer that advertises only the `0xFE30` UUID (an iPhone, which cannot
     * advertise service data) and dials it, so such a peer whose id sorts lower waits to be dialed rather than
     * dialing (companion change A3, knit-ios ADR 2026-09.xzpt). Set only while [GattPayloads]' reader runs: a flag
     * without a reader strands the pair.
     */
    const val FLAG_DIALS_GATT_PEERS = 0x02

    /**
     * Count on this phone's Wi-Fi Aware now (GitHub #44, ADR 2026-10.2t49's 2026-10-27 amendment): set only once "will
     * run Aware" has held a while, cleared at once when it will not (no hardware, a switch off, the radio off by
     * evidence, deep idle, attaching given up); never cleared for mere dormancy or a Wi-Fi Direct hand-over. A peer is an
     * Aware candidate only on a service-data sighting with it set ([AwareCandidateTracker]), whose sighting near wakes a
     * dormant Aware plane, so an older build's advert, without it, never does. The debounce is [AwareFlag]'s, so a Doze
     * maintenance window does not wake dormant neighbours.
     */
    const val FLAG_AWARE = 0x04

    /**
     * Bytes 1–16 are a rotating tag ([BlindTag]), not the node id (ADR 2026-11.xp6d). A reader resolves the tag through
     * `AdvertAliases`; nothing keys a tag as an id. The writer sets it ([advert]'s `blinded`, through `AdvertWriter`) only
     * while it airs a tag: `BuildConfig.BLIND_ADVERT`, the runtime switch and extended advertising (step 5b).
     */
    const val FLAG_BLINDED = 0x08

    /**
     * This build seals its links (#286, ADR 2026-11.8mhs): a dialer opens with a `KEY_SHARE` rather than a HELLO exactly
     * when the advert heard at the address it dials carries this bit. Set in every advert this build writes exactly when
     * its HELLO carries `Protocol.CAP_LINK_SEAL` ([SEALS_LINKS]), clear, blinded and Coded alike, since they all come
     * from [flags]. Unauthenticated: a relay that strips it gets no link, since both HELLOs then carry the bit and §7
     * refuses the plain form (`LinkHandshake.refusesPlain`). A shipped parser tests only the bits it knows.
     */
    const val FLAG_LINK_SEAL = 0x10

    /** This build's HELLO carries `Protocol.CAP_LINK_SEAL`: what [FLAG_LINK_SEAL] says, and the only thing that sets it. */
    val SEALS_LINKS: Boolean = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL != 0L

    /**
     * The flags byte this node advertises: [FLAG_SIDE_CHANNEL] while its side channel is [sideLive],
     * [FLAG_DIALS_GATT_PEERS] while its GATT reader runs ([gattPeers]), so it never claims a read it cannot make, and
     * [FLAG_AWARE] while a peer may count on this phone's Wi-Fi Aware ([aware]), and [FLAG_LINK_SEAL] whenever this
     * build seals ([SEALS_LINKS]; a parameter only so a test can write an older build's byte).
     */
    fun flags(
        sideLive: Boolean,
        gattPeers: Boolean,
        aware: Boolean,
        blinded: Boolean = false,
        linkSeal: Boolean = SEALS_LINKS,
    ): Int =
        (if (sideLive) FLAG_SIDE_CHANNEL else 0) or
            (if (gattPeers) FLAG_DIALS_GATT_PEERS else 0) or
            (if (aware) FLAG_AWARE else 0) or
            (if (blinded) FLAG_BLINDED else 0) or
            (if (linkSeal) FLAG_LINK_SEAL else 0)

    /**
     * This node's presence advert, [encode] over [flags]: the bytes `BluetoothMeshTransport.readvertise` puts on air.
     * [blinded] says [nodeId] is a tag's 26-char form and zeroes the digest cue (unchanged it would tie one tag to the
     * next): the blinding writer's form.
     */
    @Suppress("LongParameterList") // the advert's fields and its flags, each a different source in the transport
    fun advert(
        nodeId: String,
        capabilities: Long,
        digestVersion: Long,
        psm: Int,
        sideLive: Boolean,
        gattPeers: Boolean,
        aware: Boolean,
        blinded: Boolean = false,
        linkSeal: Boolean = SEALS_LINKS,
    ): ByteArray =
        encode(nodeId, capabilities, if (blinded) 0L else digestVersion, psm, flags(sideLive, gattPeers, aware, blinded, linkSeal))

    private const val CAP_MASK = 0xFFL
    private const val PSM_MASK = 0xFFFF
    private const val FLAGS_MASK = 0xFF
    private const val FLAGS_OFFSET = SIZE_V1

    /** The decoded advert fields actually carried in the bytes (protoVersion is implied by the UUID). */
    data class Parsed(
        val nodeId: String,
        val capabilities: Long,
        val digestCue: Int,
        val psm: Int,
        val flags: Int = 0,
    ) {
        val sideChannel: Boolean get() = flags and FLAG_SIDE_CHANNEL != 0
        val dialsGattPeers: Boolean get() = flags and FLAG_DIALS_GATT_PEERS != 0

        /**
         * Whether the advert says a peer may count on its phone's Wi-Fi Aware now ([FLAG_AWARE]); false for every
         * released build's (2.10.0 and older).
         */
        val aware: Boolean get() = flags and FLAG_AWARE != 0

        /** [nodeId] is a tag's form, not an id ([FLAG_BLINDED]): [form] is what a reader keys it by until it resolves. */
        val blinded: Boolean get() = flags and FLAG_BLINDED != 0

        /**
         * The advert says its build seals links ([FLAG_LINK_SEAL]); false for a 23-byte advert and every build before
         * #286. Read per address: a dial decides from the advert heard at the address it dials, never from another PHY's.
         */
        val linkSeal: Boolean get() = flags and FLAG_LINK_SEAL != 0

        /** The advertised form: the id itself for a clear advert, the tag for a blinded one (ADR 2026-11.xp6d). */
        val form: AdvertOrder.Form get() = AdvertOrder.Form(nodeId, blinded)
    }

    fun encode(
        nodeId: String,
        capabilities: Long,
        digestVersion: Long,
        psm: Int,
        flags: Int = 0,
    ): ByteArray {
        require(nodeId.length == NodeId.LENGTH) { "nodeId must be ${NodeId.LENGTH} chars, was ${nodeId.length}" }
        return ByteBuffer
            .allocate(SIZE)
            .put((capabilities and CAP_MASK).toByte())
            .put(NodeId.toBytes(nodeId)) // 16 raw bytes (128-bit id)
            .putInt(digestVersion.toInt()) // low 32 bits, big-endian
            .putShort((psm and PSM_MASK).toShort())
            .put((flags and FLAGS_MASK).toByte())
            .array()
    }

    /**
     * Decodes the fixed 23-byte prefix, or null if the data is absent/too short, or a [FLAG_BLINDED] advert whose bytes
     * 1–16 lack the tag prefix ([isMalformedBlinded]); the flags byte is read when present and 0 otherwise. Any further
     * trailing bytes from a (future) longer advert are ignored — the fields live at fixed offsets. Total: never throws.
     */
    fun parse(serviceData: ByteArray?): Parsed? {
        if (serviceData == null || serviceData.size < SIZE_V1) return null
        if (isMalformedBlinded(serviceData)) return null
        val buf = ByteBuffer.wrap(serviceData)
        val capabilities = buf.get().toLong() and CAP_MASK
        val idBytes = ByteArray(NodeId.BYTES)
        buf.get(idBytes)
        val nodeId = NodeId.fromBytes(idBytes)
        val digestCue = buf.int
        val psm = buf.short.toInt() and PSM_MASK
        val flags = if (serviceData.size > FLAGS_OFFSET) serviceData[FLAGS_OFFSET].toInt() and FLAGS_MASK else 0
        return Parsed(nodeId, capabilities, digestCue, psm, flags)
    }

    /**
     * Whether [serviceData] is long enough to parse, says [FLAG_BLINDED], and its bytes 1–16 lack [BlindTag.hasPrefix]:
     * a malformed blinded advert, which [parse] drops before anything is computed. Total; the scan path counts it.
     */
    fun isMalformedBlinded(serviceData: ByteArray?): Boolean {
        if (serviceData == null || serviceData.size <= FLAGS_OFFSET) return false
        if (serviceData[FLAGS_OFFSET].toInt() and FLAG_BLINDED == 0) return false
        return !BlindTag.hasPrefix(serviceData.copyOfRange(1, 1 + NodeId.BYTES))
    }
}
