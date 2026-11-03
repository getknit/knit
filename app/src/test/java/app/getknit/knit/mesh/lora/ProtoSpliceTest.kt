package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The read-modify-write helper behind ADR 045's config writes. Its whole reason to exist is the last test
 * here: Meshtastic's `set_config` assigns the entire sub-config, so anything this drops is something the
 * board silently loses.
 */
class ProtoSpliceTest {
    private fun hex(s: String): ByteArray =
        s
            .split(" ")
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun replacesAnExistingFieldInPlace() {
        val raw = ProtoWriter().varint(1, 2).varint(7, 900).toByteArray()
        val out = spliceVarintFields(raw, mapOf(7 to 21_600L))!!
        assertEquals(21_600L, readVarintField(out, 7))
        assertEquals(2L, readVarintField(out, 1))
    }

    @Test
    fun appendsAFieldTheBoardNeverSent() {
        val raw = ProtoWriter().varint(1, 2).toByteArray()
        val out = spliceVarintFields(raw, mapOf(7 to 21_600L))!!
        assertEquals(21_600L, readVarintField(out, 7))
    }

    @Test
    fun aZeroClearsTheFieldRatherThanWritingIt() {
        // proto3: absent *is* the default, so clearing a bool means emitting nothing for it.
        val raw = ProtoWriter().varint(1, 900).varint(2, 1).toByteArray()
        val out = spliceVarintFields(raw, mapOf(2 to 0L))!!
        assertNull(readVarintField(out, 2))
        assertEquals(900L, readVarintField(out, 1))
    }

    @Test
    fun malformedInputSplicesToNullRatherThanToGarbage() {
        assertNull(spliceVarintFields(hex("0A 7F"), mapOf(1 to 1L))) // a length past the end
        assertNull(readVarintField(hex("0A 7F"), 1))
    }

    @Test
    fun readsTheLastOccurrenceAndIgnoresAWrongWireType() {
        assertEquals(2L, readVarintField(ProtoWriter().varint(1, 1).varint(1, 2).toByteArray(), 1))
        assertNull("a length-delimited field 1 is not a varint", readVarintField(ProtoWriter().string(1, "x").toByteArray(), 1))
    }

    @Test
    fun everyFieldThisCodecDoesNotModelSurvivesByteForByte() {
        val raw =
            ProtoWriter()
                .varint(1, 2) // role
                .varint(6, 3) // rebroadcast_mode
                .varint(7, 900) // node_info_broadcast_secs — the one field we mean to change
                .string(11, "Europe/Berlin") // tzdef: a string this codec has no idea about
                .fixed32(99, 0xDEADBEEFu) // and a wire type it never writes
                .toByteArray()
        val out = spliceVarintFields(raw, mapOf(7 to 21_600L))!!
        assertEquals(2L, readVarintField(out, 1))
        assertEquals(3L, readVarintField(out, 6))
        assertEquals(21_600L, readVarintField(out, 7))
        val reader = ProtoReader(out)
        val kept = mutableListOf<String>()
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                11 -> kept += reader.readString()
                99 -> kept += reader.readFixed32().toString(16).uppercase()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        assertEquals(listOf("Europe/Berlin", "DEADBEEF"), kept)
    }

    @Test
    fun spliceStringFieldsReplacesTheNamesAndCopiesEverythingElseVerbatim() {
        // A `User` as a board sends it: id, long_name, short_name, hw_model, is_licensed.
        val user =
            ProtoWriter()
                .string(1, "!0000abcd")
                .string(2, "Meshtastic abcd")
                .string(3, "abcd")
                .varint(5, 9)
                .varint(6, 1)
                .toByteArray()
        val spliced = spliceStringFields(user, mapOf(2 to "Knit abcd", 3 to "Knit"))!!
        assertEquals("Knit abcd", readStringField(spliced, 2))
        assertEquals("Knit", readStringField(spliced, 3))
        assertEquals("!0000abcd", readStringField(spliced, 1))
        assertEquals(9L, readVarintField(spliced, 5))
        // The whole point: a presence-less bool a from-scratch message would have silently cleared.
        assertEquals(1L, readVarintField(spliced, 6))
    }

    @Test
    fun spliceStringFieldsAddsAFieldTheMessageNeverCarried() {
        val spliced = spliceStringFields(ProtoWriter().string(1, "id").toByteArray(), mapOf(2 to "Knit abcd"))!!
        assertEquals("Knit abcd", readStringField(spliced, 2))
        assertEquals("id", readStringField(spliced, 1))
    }

    @Test
    fun spliceStringFieldsIsNullOnGarbage() {
        assertNull(spliceStringFields(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), mapOf(2 to "Knit")))
    }

    @Test
    fun readStringFieldIsNullWhenAbsentOrTheWrongWireType() {
        val raw = ProtoWriter().string(2, "Knit").varint(5, 9).toByteArray()
        assertEquals("Knit", readStringField(raw, 2))
        assertNull(readStringField(raw, 3))
        assertNull("a varint is not a string", readStringField(raw, 5))
    }
}
