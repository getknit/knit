package app.getknit.knit.mesh.lora

import java.io.ByteArrayOutputStream

/**
 * Raised by [ProtoReader] on malformed input. Stackless: it is thrown on the receive hot path for
 * garbage a board could emit at any time, and only ever caught at the [MeshtasticProto] boundary, which
 * turns it into a `null` decode — no caller sees it.
 */
internal class ProtoException(
    message: String,
) : RuntimeException(message, null, false, false)

/** Protobuf wire types (the low 3 bits of a tag). Groups (3/4) are refused: nothing we speak uses them. */
internal object WireType {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LEN = 2
    const val START_GROUP = 3
    const val END_GROUP = 4
    const val FIXED32 = 5
    const val MASK = 0x07
    const val FIELD_SHIFT = 3
}

/**
 * A minimal proto3 writer: just enough of the encoding for the handful of Meshtastic messages this
 * app sends (`ToRadio` and its `MeshPacket`/`Data`), written by hand so the feature adds no protobuf
 * runtime or Gradle codegen to the toolchain (`.agents/context/toolchain.md`).
 *
 * proto3 rules honoured: scalar defaults are omitted (a zero varint / empty bytes is never written),
 * fields are emitted in the order the caller writes them, and a `oneof` member that is itself a message
 * can be forced out even when empty via [message]'s `emitEmpty` (Meshtastic's `heartbeat {}` is exactly
 * that — an empty message whose *presence* is the signal).
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** A varint field; `int32`/`int64` negatives are sign-extended to ten bytes, as the spec requires. */
    fun varint(
        field: Int,
        value: Long,
    ): ProtoWriter {
        if (value != 0L) {
            tag(field, WireType.VARINT)
            writeVarint(value)
        }
        return this
    }

    fun varint(
        field: Int,
        value: Int,
    ): ProtoWriter = varint(field, value.toLong())

    /** An unsigned 32-bit varint field (`uint32`). */
    fun uint32(
        field: Int,
        value: UInt,
    ): ProtoWriter = varint(field, value.toLong())

    /**
     * A varint field emitted **even when zero** — the encoding a `oneof` member needs, since it is its
     * presence on the wire that selects the case (`AdminMessage.get_config_request = DEVICE_CONFIG(0)`
     * would otherwise vanish and read as "no request at all").
     */
    fun oneofVarint(
        field: Int,
        value: Int,
    ): ProtoWriter {
        tag(field, WireType.VARINT)
        writeVarint(value.toLong())
        return this
    }

    fun bool(
        field: Int,
        value: Boolean,
    ): ProtoWriter = if (value) varint(field, 1L) else this

    /** A little-endian `fixed32` field (Meshtastic node numbers and packet ids). */
    fun fixed32(
        field: Int,
        value: UInt,
    ): ProtoWriter {
        if (value != 0u) {
            tag(field, WireType.FIXED32)
            val v = value.toInt()
            for (b in 0 until FIXED32_BYTES) out.write((v ushr (b * Byte.SIZE_BITS)) and BYTE_MASK)
        }
        return this
    }

    /** A length-delimited field; empty payloads are omitted unless [emitEmpty]. */
    fun bytes(
        field: Int,
        value: ByteArray,
        emitEmpty: Boolean = false,
    ): ProtoWriter {
        if (value.isNotEmpty() || emitEmpty) {
            tag(field, WireType.LEN)
            writeVarint(value.size.toLong())
            out.write(value)
        }
        return this
    }

    fun string(
        field: Int,
        value: String,
    ): ProtoWriter = bytes(field, value.encodeToByteArray())

    /** A nested message field, built by [block] into its own writer and length-prefixed here. */
    fun message(
        field: Int,
        emitEmpty: Boolean = false,
        block: ProtoWriter.() -> Unit,
    ): ProtoWriter = bytes(field, ProtoWriter().apply(block).toByteArray(), emitEmpty)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(
        field: Int,
        wireType: Int,
    ) {
        writeVarint(((field.toLong()) shl WireType.FIELD_SHIFT) or wireType.toLong())
    }

    private fun writeVarint(value: Long) {
        var v = value
        while (v and VARINT_PAYLOAD_MASK.inv() != 0L) {
            out.write(((v and VARINT_PAYLOAD_MASK).toInt()) or VARINT_CONTINUE)
            v = v ushr VARINT_BITS
        }
        out.write(v.toInt())
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val VARINT_BITS = 7
        const val VARINT_PAYLOAD_MASK = 0x7FL
        const val VARINT_CONTINUE = 0x80
        const val FIXED32_BYTES = 4
    }
}

/**
 * A bounds-checked proto3 reader over `buf[start, end)`. Every method throws [ProtoException] rather
 * than an `IndexOutOfBounds`/`NegativeArraySize` on truncated or hostile input, so the codec boundary
 * can turn *any* malformed FromRadio into a null with one catch. Unknown fields are skipped by wire type
 * ([skip]); groups and reserved wire types are refused.
 */
internal class ProtoReader(
    private val buf: ByteArray,
    private var pos: Int = 0,
    private val end: Int = buf.size,
) {
    init {
        if (pos < 0 || end > buf.size || pos > end) throw ProtoException("bad window $pos..$end of ${buf.size}")
    }

    val hasMore: Boolean get() = pos < end

    /** The absolute read cursor into the backing array — how [spliceVarintFields] copies a field verbatim. */
    val position: Int get() = pos

    /** The next tag (`field << 3 | wireType`); field 0 and oversized tags are malformed. */
    fun readTag(): Int {
        val raw = readVarint64()
        if (raw <= 0L || raw > Int.MAX_VALUE) throw ProtoException("bad tag $raw")
        val tag = raw.toInt()
        if (tag ushr WireType.FIELD_SHIFT == 0) throw ProtoException("field 0")
        return tag
    }

    fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (shift >= MAX_VARINT_BITS) throw ProtoException("varint too long")
            val b = readByte()
            result = result or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
            if (b and VARINT_CONTINUE == 0) return result
            shift += VARINT_BITS
        }
    }

    /** A varint truncated to 32 bits — the right reading for `int32`/`uint32`/enum fields. */
    fun readVarint32(): Int = readVarint64().toInt()

    fun readFixed32(): UInt {
        require(FIXED32_BYTES)
        var v = 0
        for (i in 0 until FIXED32_BYTES) {
            v = v or ((buf[pos + i].toInt() and BYTE_MASK) shl (i * Byte.SIZE_BITS))
        }
        pos += FIXED32_BYTES
        return v.toUInt()
    }

    fun readFloat(): Float = Float.fromBits(readFixed32().toInt())

    fun readBytes(): ByteArray {
        val len = readLength()
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readString(): String = readBytes().decodeToString()

    /** A reader over the next length-delimited value, without copying; this reader advances past it. */
    fun sub(): ProtoReader {
        val len = readLength()
        val reader = ProtoReader(buf, pos, pos + len)
        pos += len
        return reader
    }

    /** Skips one value of [wireType] (an unknown field). Groups and reserved types are malformed. */
    fun skip(wireType: Int) {
        when (wireType) {
            WireType.VARINT -> readVarint64()
            WireType.FIXED64 -> advance(FIXED64_BYTES)
            WireType.LEN -> advance(readLength())
            WireType.FIXED32 -> advance(FIXED32_BYTES)
            else -> throw ProtoException("unsupported wire type $wireType")
        }
    }

    private fun readLength(): Int {
        val len = readVarint64()
        if (len < 0L || len > end - pos) throw ProtoException("length $len past end")
        return len.toInt()
    }

    private fun readByte(): Int {
        if (pos >= end) throw ProtoException("truncated")
        return buf[pos++].toInt() and BYTE_MASK
    }

    private fun require(n: Int) {
        if (end - pos < n) throw ProtoException("truncated: need $n")
    }

    private fun advance(n: Int) {
        require(n)
        pos += n
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val VARINT_BITS = 7
        const val VARINT_PAYLOAD_MASK = 0x7F
        const val VARINT_CONTINUE = 0x80
        const val MAX_VARINT_BITS = 70 // ten 7-bit groups: the widest legal (sign-extended int64) varint
        const val FIXED32_BYTES = 4
        const val FIXED64_BYTES = 8
    }
}

/**
 * Rewrites [raw] — the bytes of one protobuf message — with each field in [values] replaced by the given
 * value, leaving every other field **byte-for-byte** where it was.
 *
 * This exists because Meshtastic's `AdminModule::handleSetConfig` assigns the whole sub-config
 * (`config.device = c.payload_variant.device`), so sending a `Config { device { node_info_broadcast_secs } }`
 * built from scratch would reset `role`, `rebroadcast_mode` and everything else this codec does not model.
 * The board's own `get_config` reply is therefore the base, and only the fields we mean to change are
 * spliced — no field has to be understood to survive the round trip.
 *
 * proto3 scalars: a zero is the default and is written by *omission*, which is exactly what [ProtoWriter]
 * does, so `field to 0L` clears a field. Returns null on malformed input, like every decode here — the
 * caller must abort rather than write a mangled config to a radio.
 */
internal fun spliceVarintFields(
    raw: ByteArray,
    values: Map<Int, Long>,
): ByteArray? = spliceFields(raw, values.keys) { values.forEach { (field, value) -> varint(field, value) } }

/**
 * [spliceVarintFields] for `string` fields — how the board's own `User` keeps its key, its hardware model
 * and its licensed flag while only the two names Knit renames ([BoardName]) are replaced. An empty string
 * is a proto3 default and is written by omission, which clears the field.
 */
internal fun spliceStringFields(
    raw: ByteArray,
    values: Map<Int, String>,
): ByteArray? = spliceFields(raw, values.keys) { values.forEach { (field, value) -> string(field, value) } }

/** Copies [raw] verbatim minus every field in [replaced], then appends what [append] writes in their place. */
private inline fun spliceFields(
    raw: ByteArray,
    replaced: Set<Int>,
    append: ProtoWriter.() -> Unit,
): ByteArray? =
    runCatching {
        val out = ByteArrayOutputStream()
        val reader = ProtoReader(raw)
        while (reader.hasMore) {
            val start = reader.position
            val tag = reader.readTag()
            reader.skip(tag and WireType.MASK)
            if ((tag ushr WireType.FIELD_SHIFT) !in replaced) out.write(raw, start, reader.position - start)
        }
        out.write(ProtoWriter().apply(append).toByteArray())
        out.toByteArray()
    }.getOrNull()

/** The varint value of [field] in [raw], or null when absent or malformed. The last occurrence wins, as proto3 says. */
internal fun readVarintField(
    raw: ByteArray,
    field: Int,
): Long? =
    runCatching {
        val reader = ProtoReader(raw)
        var found: Long? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            val wire = tag and WireType.MASK
            if (tag ushr WireType.FIELD_SHIFT == field && wire == WireType.VARINT) {
                found = reader.readVarint64()
            } else {
                reader.skip(wire)
            }
        }
        found
    }.getOrNull()

/** The bytes of [field] in [raw], or null when absent or malformed. The last occurrence wins. */
internal fun readBytesField(
    raw: ByteArray,
    field: Int,
): ByteArray? =
    runCatching {
        val reader = ProtoReader(raw)
        var found: ByteArray? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            val wire = tag and WireType.MASK
            if (tag ushr WireType.FIELD_SHIFT == field && wire == WireType.LEN) {
                found = reader.readBytes()
            } else {
                reader.skip(wire)
            }
        }
        found
    }.getOrNull()

/** The string value of [field] in [raw], or null when absent or malformed. The last occurrence wins. */
internal fun readStringField(
    raw: ByteArray,
    field: Int,
): String? =
    runCatching {
        val reader = ProtoReader(raw)
        var found: String? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            val wire = tag and WireType.MASK
            if (tag ushr WireType.FIELD_SHIFT == field && wire == WireType.LEN) {
                found = reader.readString()
            } else {
                reader.skip(wire)
            }
        }
        found
    }.getOrNull()
