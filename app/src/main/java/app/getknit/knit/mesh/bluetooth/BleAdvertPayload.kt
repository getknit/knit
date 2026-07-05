package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import java.nio.ByteBuffer

/**
 * The fixed 23-byte BLE **advertisement service-data** payload the Bluetooth transport broadcasts and scans
 * for — the coordination-plane advert, the L2CAP analogue of Wi-Fi Aware's `serviceSpecificInfo`. It fits a
 * legacy 31-byte advertisement (Flags 3 + service-data AD header 4 + 23 = 30) — which is why the advert
 * carries **only** service data (no separate service-UUID list AD, see [BleAdvertiser]) and the nodeId rides
 * as its raw 16 bytes rather than 26 ASCII chars:
 *
 * ```
 * byte 0       capabilities, low 8 bits (today only 4 bits used: E2E|GROUPS|REACTIONS|STORE_FWD)
 * bytes 1..16  nodeId, 16 raw bytes (NodeId.BYTES = 128 bits), decoded to the 26-char id via NodeId.fromBytes
 * bytes 17..20 digest cue = low 32 bits of StoreDigest.version (BE) — both BLE peers truncate identically
 * bytes 21..22 L2CAP PSM (unsigned 16-bit, BE) so an initiator knows which channel to connect to
 * ```
 *
 * There is **no** format-version byte: the layout/`protoVersion` is implied by the versioned service UUID (a
 * peer matching our UUID is our version; a breaking change bumps the UUID and hard-partitions at discovery,
 * like NAN's `SERVICE_NAME` `.vN`), and the full id is confirmed authoritatively over the socket in the HELLO.
 * Pure (no Android), so it is JVM-unit-testable ([app.getknit.knit.BleAdvertPayloadTest]).
 */
internal object BleAdvertPayload {
    /** Fixed payload size: 1 (caps) + 16 (nodeId) + 4 (digest cue) + 2 (psm). */
    const val SIZE = 1 + NodeId.BYTES + 4 + 2

    private const val CAP_MASK = 0xFFL
    private const val PSM_MASK = 0xFFFF

    /** The decoded advert fields actually carried in the bytes (protoVersion is implied by the UUID). */
    data class Parsed(
        val nodeId: String,
        val capabilities: Long,
        val digestCue: Int,
        val psm: Int,
    )

    fun encode(
        nodeId: String,
        capabilities: Long,
        digestVersion: Long,
        psm: Int,
    ): ByteArray {
        require(nodeId.length == NodeId.LENGTH) { "nodeId must be ${NodeId.LENGTH} chars, was ${nodeId.length}" }
        return ByteBuffer
            .allocate(SIZE)
            .put((capabilities and CAP_MASK).toByte())
            .put(NodeId.toBytes(nodeId)) // 16 raw bytes (128-bit id)
            .putInt(digestVersion.toInt()) // low 32 bits, big-endian
            .putShort((psm and PSM_MASK).toShort())
            .array()
    }

    /**
     * Decodes the fixed 23-byte prefix, or null if the data is absent/too short. Any trailing bytes from a
     * (future) longer advert are ignored — the fields live at fixed offsets.
     */
    fun parse(serviceData: ByteArray?): Parsed? {
        if (serviceData == null || serviceData.size < SIZE) return null
        val buf = ByteBuffer.wrap(serviceData)
        val capabilities = buf.get().toLong() and CAP_MASK
        val idBytes = ByteArray(NodeId.BYTES)
        buf.get(idBytes)
        val nodeId = NodeId.fromBytes(idBytes)
        val digestCue = buf.int
        val psm = buf.short.toInt() and PSM_MASK
        return Parsed(nodeId, capabilities, digestCue, psm)
    }
}
