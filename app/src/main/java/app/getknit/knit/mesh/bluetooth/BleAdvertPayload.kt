package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import java.nio.ByteBuffer

/**
 * The fixed 16-byte BLE **advertisement service-data** payload the Bluetooth transport broadcasts and scans
 * for — the coordination-plane advert, the L2CAP analogue of Wi-Fi Aware's `serviceSpecificInfo`. It fits a
 * legacy 31-byte advertisement with margin (the 8-char [NodeId] is what makes this work — no GATT round-trip,
 * no hashing needed):
 *
 * ```
 * byte 0      format version (this schema; tolerated forward — fields are append-only at fixed offsets)
 * byte 1      capabilities, low 8 bits (today only 4 bits used: E2E|GROUPS|REACTIONS|STORE_FWD)
 * bytes 2..9  nodeId, 8 ASCII bytes (NodeId.LENGTH)
 * bytes 10..13 digest cue = low 32 bits of StoreDigest.version (BE) — both BLE peers truncate identically
 * bytes 14..15 L2CAP PSM (unsigned 16-bit, BE) so an initiator knows which channel to connect to
 * ```
 *
 * `protoVersion` is **not** a payload byte — it is implied by the versioned service UUID (a peer matching our
 * UUID is our version; a breaking change bumps the UUID and hard-partitions at discovery, like NAN's
 * `SERVICE_NAME` `.vN`), and is confirmed authoritatively over the socket in the HELLO. Pure (no Android), so
 * it is JVM-unit-testable ([app.getknit.knit.BleAdvertPayloadTest]).
 */
internal object BleAdvertPayload {

    /** This build's advert schema version (the first byte). Append-only at fixed offsets, so newer is tolerated. */
    const val FORMAT_VERSION = 1

    /** Fixed payload size: 1 (format) + 1 (caps) + 8 (nodeId) + 4 (digest cue) + 2 (psm). */
    const val SIZE = 1 + 1 + NodeId.LENGTH + 4 + 2

    private const val CAP_MASK = 0xFFL
    private const val PSM_MASK = 0xFFFF

    /** The decoded advert fields actually carried in the bytes (protoVersion is implied by the UUID). */
    data class Parsed(val nodeId: String, val capabilities: Long, val digestCue: Int, val psm: Int)

    fun encode(nodeId: String, capabilities: Long, digestVersion: Long, psm: Int): ByteArray {
        require(nodeId.length == NodeId.LENGTH) { "nodeId must be ${NodeId.LENGTH} chars, was ${nodeId.length}" }
        return ByteBuffer.allocate(SIZE)
            .put(FORMAT_VERSION.toByte())
            .put((capabilities and CAP_MASK).toByte())
            .put(nodeId.encodeToByteArray()) // 8 ASCII bytes ([a-z0-9])
            .putInt(digestVersion.toInt()) // low 32 bits, big-endian
            .putShort((psm and PSM_MASK).toShort())
            .array()
    }

    /**
     * Decodes the fixed 16-byte prefix, or null if the data is absent/too short. The format byte is read but
     * not rejected: fields are append-only at fixed offsets, so a newer peer's longer advert still parses.
     */
    fun parse(serviceData: ByteArray?): Parsed? {
        if (serviceData == null || serviceData.size < SIZE) return null
        val buf = ByteBuffer.wrap(serviceData)
        buf.get() // format version (tolerated; fields are at fixed offsets)
        val capabilities = buf.get().toLong() and CAP_MASK
        val idBytes = ByteArray(NodeId.LENGTH)
        buf.get(idBytes)
        val nodeId = idBytes.decodeToString()
        val digestCue = buf.int
        val psm = buf.short.toInt() and PSM_MASK
        return Parsed(nodeId, capabilities, digestCue, psm)
    }
}
