package app.getknit.knit.mesh.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * The length-prefixed framing that multiplexes mesh frames and out-of-band file transfers over a
 * single connected byte stream — the transport's own little record protocol, since a raw socket is just
 * a byte stream with no message boundaries. **Transport-neutral**: it runs over any
 * [InputStream]/[OutputStream], so both the Wi-Fi Aware NDP TCP socket and the Bluetooth L2CAP socket
 * ([app.getknit.knit.mesh.link.LinkSocket]) share it unchanged.
 *
 * Each record is `[type:1][len:4 big-endian][payload:len]`. [Type.FRAME] carries one CBOR
 * [app.getknit.knit.mesh.protocol.WireEnvelope]; a file is streamed as [Type.FILE_HEADER] (a
 * [FileHeaderWire] JSON) then a run of [Type.FILE_CHUNK]s then [Type.FILE_END]. Only one file streams
 * at a time per socket (the writer serializes them), so chunks need no file id — but FRAME records may
 * be interleaved *between* a file's chunks so a large blob never stalls live frames.
 *
 * Pure (no Android), so the codec is JVM-unit-testable ([app.getknit.knit.LinkFramingTest]).
 */
internal object LinkFraming {
    /** Record discriminator (the leading byte of every record); the byte values are the on-wire tags. */
    @Suppress("MagicNumber")
    enum class Type(
        val tag: Byte,
    ) {
        FRAME(1),
        FILE_HEADER(2),
        FILE_CHUNK(3),
        FILE_END(4),

        /** Transport-internal idle heartbeat: keeps an idle link's socket alive. Reader ignores it. */
        KEEPALIVE(5),

        /**
         * First record an initiator sends after connecting: its advert
         * ([app.getknit.knit.mesh.protocol.Protocol.advertise] bytes), so an accept-any responder — which
         * accepts a connection without knowing the peer up front — learns which node it is. Consumed once at
         * accept (see [LinkHandshake]); ignored thereafter.
         */
        HELLO(6),

        /**
         * Store-and-forward digest: a [DigestWire] JSON listing the message ids the sender currently holds in
         * custody. Exchanged on link-up so each side pushes only the frames the other lacks — the id-diff that
         * replaces push-all (see `docs/DIGEST_PULL_REATTACH.md`).
         */
        DIGEST(7),
        ;

        companion object {
            fun fromTag(tag: Byte): Type? = entries.firstOrNull { it.tag == tag }
        }
    }

    /** A decoded record: its [type] and raw [payload] bytes (empty for [Type.FILE_END]). */
    data class Message(
        val type: Type,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Message && type == other.type && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
    }

    /**
     * Hard ceiling on a single record's payload so a malformed/malicious length prefix can't drive an
     * unbounded allocation. Comfortably above a max mesh frame and our [FILE_CHUNK_BYTES]; a whole file
     * is bounded separately during reassembly (see the transport's incoming-file ceiling).
     */
    const val MAX_PAYLOAD_BYTES = 512 * 1024

    /**
     * Bytes read from disk per [Type.FILE_CHUNK] record when streaming a file. Kept small so the writer's
     * between-chunk interleave/pace points (see [app.getknit.knit.mesh.link.FramedLink]) are frequent — the
     * finer the granularity, the sooner a live frame jumps ahead of a slow BLE blob. No L2CAP throughput cost
     * (the controller fragments to MPS K-frames regardless — see [BluetoothSocketLink]) and negligible on NAN.
     */
    const val FILE_CHUNK_BYTES = 16 * 1024

    /** Encodes one record: `[type][big-endian length][payload]`. */
    fun encode(
        type: Type,
        payload: ByteArray = EMPTY,
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "record payload ${payload.size} exceeds $MAX_PAYLOAD_BYTES" }
        return ByteBuffer
            .allocate(HEADER_BYTES + payload.size)
            .put(type.tag)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    /** Writes one record to [output] (does not flush). */
    fun write(
        output: OutputStream,
        type: Type,
        payload: ByteArray = EMPTY,
    ) {
        output.write(encode(type, payload))
    }

    /**
     * Reads exactly one record from [input], blocking until it arrives. Returns null on a clean EOF
     * (the peer closed the socket at a record boundary). Throws [IOException] on a truncated record or
     * an out-of-range length prefix (a desynced/hostile stream — the caller drops the connection).
     */
    fun read(input: InputStream): Message? {
        val tagByte = input.read()
        if (tagByte == -1) return null // clean EOF at a record boundary
        val type = Type.fromTag(tagByte.toByte()) ?: throw IOException("unknown record type $tagByte")
        val len = ByteBuffer.wrap(readFully(input, LENGTH_BYTES)).int
        if (len < 0 || len > MAX_PAYLOAD_BYTES) throw IOException("record length $len out of range")
        val payload = if (len == 0) EMPTY else readFully(input, len)
        return Message(type, payload)
    }

    /** Reads exactly [n] bytes, or throws [EOFException] if the stream ends mid-record. */
    private fun readFully(
        input: InputStream,
        n: Int,
    ): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(buf, off, n - off)
            if (read == -1) throw EOFException("truncated record: $off/$n")
            off += read
        }
        return buf
    }

    private const val LENGTH_BYTES = 4
    private const val HEADER_BYTES = 1 + LENGTH_BYTES
    private val EMPTY = ByteArray(0)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encodeFileHeader(header: FileHeaderWire): ByteArray = json.encodeToString(header).encodeToByteArray()

    fun decodeFileHeader(payload: ByteArray): FileHeaderWire? =
        runCatching { json.decodeFromString<FileHeaderWire>(payload.decodeToString()) }.getOrNull()

    fun encodeDigest(digest: DigestWire): ByteArray = json.encodeToString(digest).encodeToByteArray()

    fun decodeDigest(payload: ByteArray): DigestWire? =
        runCatching { json.decodeFromString<DigestWire>(payload.decodeToString()) }.getOrNull()
}

/**
 * Sent as the [LinkFraming.Type.FILE_HEADER] record ahead of a file's chunks so the receiver can route
 * it: [kind] (avatar vs attachment), [key] (avatar's node id or attachment content hash), and [mime].
 */
@Serializable
internal data class FileHeaderWire(
    val kind: String,
    val key: String,
    val mime: String,
)

/**
 * Sent as the [LinkFraming.Type.DIGEST] record on link-up: the message [ids] this node currently holds in
 * store-and-forward custody. The peer diffs it against its own set and pushes back only the frames this node
 * lacks (and vice versa), so a sync transfers the set difference rather than the whole store.
 */
@Serializable
internal data class DigestWire(
    val ids: List<String>,
)
