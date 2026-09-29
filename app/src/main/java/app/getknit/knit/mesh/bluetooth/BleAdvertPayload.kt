package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
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
 * byte 23      BLE-local flags: bit 0 [FLAG_SIDE_CHANNEL], bit 1 [FLAG_DIALS_GATT_PEERS]; bits 2..7 reserved
 *              (0). Optional on the wire: a build before it advertised 23 bytes, and this parser reads a
 *              missing byte as 0. Every shipped parser tests only the bits it knows, so a new bit is additive.
 * ```
 *
 * Byte 23 is the **last** byte the legacy advert can carry, and it is deliberately not a second capabilities
 * byte: `Protocol` reserves the bits from 0x100 up for the authenticated profile and the Wi-Fi Aware advert,
 * and what rides here is a fact about *this controller* (it can advertise and scan extended pages), which the
 * build-wide `Protocol.LOCAL_CAPABILITIES` could never vouch for.
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
     * Decodes the fixed 23-byte prefix, or null if the data is absent/too short; the flags byte is read when
     * present and 0 otherwise. Any further trailing bytes from a (future) longer advert are ignored — the fields
     * live at fixed offsets.
     */
    fun parse(serviceData: ByteArray?): Parsed? {
        if (serviceData == null || serviceData.size < SIZE_V1) return null
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
}
