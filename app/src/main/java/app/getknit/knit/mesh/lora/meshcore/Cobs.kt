package app.getknit.knit.mesh.lora.meshcore

/**
 * Consistent Overhead Byte Stuffing, without the trailing delimiter: the framing a compact DM rides in on a MeshCore board
 * (ADR 2026-11.gsqu §2). A MeshCore contact text DM is `strlen`-terminated at both ends (`BaseChatMesh.cpp:237, 452`), so
 * a `0x00` inside the sealed `0x11` frame cuts it silently — and the sender's board still acks the cut text (bench Q11).
 * [encode] therefore never emits a zero, and costs at most one byte per 254: a 156-B seal is at most 157 B of text, the
 * fleet cap the receiver's ESP32 notify sets (173 B less the 16-B `CONTACT_MSG_RECV_V3` header).
 *
 * Below the transport, so not a wire change: the seal and the frame are the ones the Meshtastic plane sends.
 * Pure and total: [decode] answers null for anything [encode] could not have produced, and never throws.
 */
internal object Cobs {
    /** The longest run a code byte can describe: 254 data bytes and the code itself. */
    private const val MAX_CODE = 0xFF

    /** The most bytes [encode] adds to [size] bytes of input. */
    fun overhead(size: Int): Int = size / (MAX_CODE - 1) + 1

    /** [data] stuffed so that no byte of the result is `0x00`; empty in, one byte (`0x01`) out. */
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArray(data.size + overhead(data.size))
        var codeAt = 0
        var write = 1
        var code = 1
        for (b in data) {
            if (b.toInt() == 0) {
                out[codeAt] = code.toByte()
                codeAt = write++
                code = 1
            } else {
                out[write++] = b
                code++
                if (code == MAX_CODE) {
                    out[codeAt] = code.toByte()
                    codeAt = write++
                    code = 1
                }
            }
        }
        out[codeAt] = code.toByte()
        return out.copyOf(write)
    }

    /**
     * The bytes [encoded] stuffs, or null when it is not the encoding [encode] writes: empty, a zero byte anywhere, a code
     * that runs past the end, or any non-canonical form (checked by encoding the result again). A block of 254 data bytes
     * closes without an implied zero, as [encode] wrote it.
     */
    fun decode(encoded: ByteArray): ByteArray? {
        if (encoded.isEmpty()) return null
        val out = ByteArray(encoded.size)
        var read = 0
        var write = 0
        while (read < encoded.size) {
            val code = encoded[read].toInt() and MAX_CODE
            if (code == 0 || read + code > encoded.size) return null
            read++
            repeat(code - 1) {
                val b = encoded[read++]
                if (b.toInt() == 0) return null
                out[write++] = b
            }
            if (code != MAX_CODE && read < encoded.size) out[write++] = 0
        }
        val decoded = out.copyOf(write)
        // Canonical only: a run of 254 left open, or any other form encode never writes, is refused — one text, one frame.
        return decoded.takeIf { encode(it).contentEquals(encoded) }
    }
}
