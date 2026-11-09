package app.getknit.knit.mesh.link

import kotlinx.serialization.EncodeDefault
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

        /**
         * A link proof (ADR 2026-11.264w): the 128-byte `sigPub ‖ hpkePub ‖ signature` payload of
         * [app.getknit.knit.mesh.crypto.LinkProof], read once right after the HELLOs by [LinkHandshake] and ignored on
         * a live link, as a stray HELLO is. Written only to a peer whose HELLO carries
         * [app.getknit.knit.mesh.protocol.Protocol.CAP_LINK_PROOF]: every build before it throws on an unknown tag
         * ([read]) and drops the link.
         */
        PROOF(8),

        /**
         * The sender's next advertised tag (ADR 2026-11.xp6d, knit-next #285): exactly [TAG_NEXT_BYTES], the Bluetooth
         * advert bytes 1–16 it will air after its next rotation, so a linked reader recognises it before any link. Any
         * other length is a framing error ([read] throws, and the link drops, as for a desynced length). Written only to
         * a proven link whose HELLO carries `Protocol.CAP_BLIND_ADVERT`: every build before it throws on an unknown tag
         * and drops the link. Read by the Bluetooth plane only; a plane that reads no tags ignores it.
         */
        TAG_NEXT(9),

        /**
         * A sealed link's opening record (#286): exactly [KEY_SHARE_BYTES], the sender's ephemeral X25519 public key
         * ([app.getknit.knit.mesh.crypto.LinkSeal]), fresh for every connection attempt. Each side writes one, as its first
         * record, and only on a link the dialer opened in the sealed form — chosen from the peer's advert flag or Wi-Fi
         * Aware SSI caps, which only a build carrying `Protocol.CAP_LINK_SEAL` airs: every build before it throws on an
         * unknown tag and drops the link. Any other length throws [SealFramingException] from the header, before the
         * payload is read. Never valid inside a seal.
         */
        KEY_SHARE(10),

        /**
         * One AES-256-GCM frame of a sealed link (#286): `ciphertext ‖ tag(16)` of 1 to 65,536 plaintext bytes, so its
         * length is [SEALED_MIN_BYTES]..[SEALED_MAX_BYTES]; anything else throws [SealFramingException] from the header.
         * After the two [KEY_SHARE]s every record either side writes is one of these, and the plaintexts, concatenated in
         * counter order, are the ordinary record stream ([app.getknit.knit.mesh.link.SealedLinkSocket]). Written only on a
         * link opened in the sealed form, as [KEY_SHARE] is. Never valid inside a seal.
         */
        SEALED(11),
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

    /** A [Type.TAG_NEXT] record's payload: one advertised form, the 16 bytes of a Bluetooth advert's bytes 1–16. */
    const val TAG_NEXT_BYTES = 16

    /** A [Type.KEY_SHARE] record's payload: one raw X25519 public key. */
    const val KEY_SHARE_BYTES = 32

    /** The shortest [Type.SEALED] payload: one plaintext byte and the 16-byte GCM tag. */
    const val SEALED_MIN_BYTES = 1 + 16

    /** The longest [Type.SEALED] payload: 65,536 plaintext bytes and the 16-byte GCM tag. */
    const val SEALED_MAX_BYTES = 65_536 + 16

    /** One [Type.TAG_NEXT] record for [tag] (exactly [TAG_NEXT_BYTES]; a caller's bug otherwise). */
    fun encodeTagNext(tag: ByteArray): ByteArray {
        require(tag.size == TAG_NEXT_BYTES) { "a tag-next payload is $TAG_NEXT_BYTES bytes" }
        return encode(Type.TAG_NEXT, tag)
    }

    /** One [Type.KEY_SHARE] record for [publicKey] (exactly [KEY_SHARE_BYTES]; a caller's bug otherwise). */
    fun encodeKeyShare(publicKey: ByteArray): ByteArray {
        require(publicKey.size == KEY_SHARE_BYTES) { "a key share is $KEY_SHARE_BYTES bytes" }
        return encode(Type.KEY_SHARE, publicKey)
    }

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
        return readAfterTag(tagByte, input)
    }

    /**
     * The rest of the record whose leading byte [tagByte] the caller already read from [input]: the sealed handshake
     * reads the tag alone first, so it can tell "another record where a key share belongs" (a failed seal) from a stream
     * that ended or timed out (no reply) without reading what follows. Throws as [read] does.
     */
    fun readAfterTag(
        tagByte: Int,
        input: InputStream,
    ): Message {
        val type = Type.fromTag(tagByte.toByte()) ?: throw IOException("unknown record type $tagByte")
        val len = ByteBuffer.wrap(readFully(input, LENGTH_BYTES)).int
        checkSealLength(type, len)
        // A fixed-size record of any other length is a desynced or hostile stream, as an out-of-range length is.
        val fixed = type == Type.TAG_NEXT && len != TAG_NEXT_BYTES
        if (len !in 0..MAX_PAYLOAD_BYTES || fixed) throw IOException("record type $tagByte length $len out of range")
        val payload = if (len == 0) EMPTY else readFully(input, len)
        return Message(type, payload)
    }

    /** A [Type.KEY_SHARE] or [Type.SEALED] header whose length is out of its range: a failed seal, read before the payload. */
    fun checkSealLength(
        type: Type,
        len: Int,
    ) {
        when (type) {
            Type.KEY_SHARE -> if (len != KEY_SHARE_BYTES) throw SealFramingException("key share length $len")
            Type.SEALED -> if (len !in SEALED_MIN_BYTES..SEALED_MAX_BYTES) throw SealFramingException("sealed frame length $len")
            else -> Unit
        }
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

    /**
     * The header, or null when it is not one. A header whose [FileHeaderWire.size] will not decode (a string, a
     * fraction, out of range) is read without it: the size is only a label, and a failed decode aborts the file
     * that follows, so only the fields the receiver routes on may cost the transfer (#115). [FileHeaderWire.offset]
     * is one of those: it says where the bytes go, so one that will not decode refuses the file (#116).
     */
    fun decodeFileHeader(payload: ByteArray): FileHeaderWire? {
        val text = payload.decodeToString()
        return runCatching { json.decodeFromString<FileHeaderWire>(text) }.getOrNull()
            ?: runCatching {
                json
                    .decodeFromString<FileHeaderCore>(
                        text,
                    ).let { FileHeaderWire(it.kind, it.key, it.mime, offset = it.offset) }
            }.getOrNull()
    }

    fun encodeDigest(digest: DigestWire): ByteArray = json.encodeToString(digest).encodeToByteArray()

    fun decodeDigest(payload: ByteArray): DigestWire? =
        runCatching { json.decodeFromString<DigestWire>(payload.decodeToString()) }.getOrNull()
}

/**
 * A [LinkFraming.Type.KEY_SHARE] or [LinkFraming.Type.SEALED] header whose length is out of range (#286): bytes that
 * arrived and were wrong, so a caller counts it as a failed seal rather than a missing reply or a dead socket. Carries the
 * length only, never a byte of payload.
 */
class SealFramingException(
    message: String,
) : IOException(message)

/**
 * Sent as the [LinkFraming.Type.FILE_HEADER] record ahead of a file's chunks so the receiver can route
 * it: [kind] (avatar vs attachment), [key] (the blob's content hash, an avatar's too), and [mime].
 *
 * [size] is the byte count of the stream that follows, so the receiver can show how far it has got (#115). A
 * label, never a bound: the receiver still counts the bytes and holds its own ceiling, and shows a sender that
 * sends more than it declared by bytes alone. Null from a build that predates the field, which an older
 * receiver skips under `ignoreUnknownKeys`. This config encodes defaults, so a null would go out as
 * `"size":null` — the sender always fills it.
 *
 * [offset] is the blob's byte the stream starts at, when it is the rest of a transfer a link drop cut off (#116,
 * ADR 2026-10.wtyc): the receiver asked with that many bytes in hand, and splices the stream onto them. [size]
 * keeps its meaning — the bytes that follow — so the whole blob is `offset + size`. Sent only in answer to a
 * `blobreq` that carried an offset, which only a build that reads this field sends; and never written as a null
 * ([EncodeDefault.Mode.NEVER] overrides the config's `encodeDefaults`), so a whole-file header is byte-identical
 * to the one before the field existed.
 */
@Serializable
internal data class FileHeaderWire(
    val kind: String,
    val key: String,
    val mime: String,
    val size: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val offset: Long? = null,
)

/** The fields a receiver routes on, for a header whose [FileHeaderWire.size] will not decode. */
@Serializable
private data class FileHeaderCore(
    val kind: String,
    val key: String,
    val mime: String,
    val offset: Long? = null,
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
