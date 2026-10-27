package app.getknit.knit.data.crypto

import app.getknit.knit.mesh.crypto.cryptoCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [CborShape] describes a document's structure, never its values: what an identity that opens but does not parse
 * carries into the crash log. The documents are [cryptoCbor]'s, the codec the identity file is written with.
 */
@OptIn(ExperimentalSerializationApi::class)
class CborShapeTest {
    @Serializable
    private class Sample(
        val a: ByteArray,
        val b: ByteArray,
        val inner: List<Inner>? = null,
    )

    @Serializable
    private class Inner(
        val id: Int,
        val text: String,
    )

    private val names = setOf("a", "b", "inner", "id", "text")

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun aWellFormedDocumentReadsAsItsFieldsWithByteArraysAsRunsOfIntegers() {
        val blob = cryptoCbor.encodeToByteArray(Sample(bytes(1, 2, 3, 4), bytes(5)))
        assertEquals("12 B {a:[i×4],b:[i]}", CborShape.describe(blob, names))
    }

    @Test
    fun aSpanLostMidArrayShowsWhereTheRunBreaksAndWhereTheWalkStops() {
        val blob = cryptoCbor.encodeToByteArray(Sample(bytes(1, 2, 3, 4), bytes(5)))
        val lost = blob.copyOfRange(0, 6) + blob.copyOfRange(8, blob.size)

        // The 2.9.0 crash: the decoder reads the next key's text where the array promised an integer.
        assertThrows(SerializationException::class.java) { cryptoCbor.decodeFromByteArray<Sample>(lost) }
        assertEquals("10 B {a:[i×2 t1@6 [i]@8],!@10", CborShape.describe(lost, names))
    }

    @Test
    fun noValueReachesTheShapeAndOnlyGivenNamesDo() {
        val blob = cryptoCbor.encodeToByteArray(Sample(bytes(), bytes(), listOf(Inner(1, "hunter2"), Inner(2, "hunter3"))))

        assertEquals("50 B {a:[],b:[],inner:[{id:i,text:t7}×2]}", CborShape.describe(blob, names))
        val unnamed = CborShape.describe(blob)
        assertEquals("50 B {t1:[],t1:[],t5:[{t2:i,t4:t7}×2]}", unnamed)
        assertFalse(unnamed.contains("hunter"))
    }

    @Test
    fun malformedAndTrailingBytesAreMarkedWhereTheyStart() {
        assertEquals("3 B i +2 B", CborShape.describe(bytes(1, 2, 3)))
        assertEquals("1 B !@0", CborShape.describe(bytes(0x1C))) // reserved additional information
        assertEquals("3 B !@0", CborShape.describe(bytes(0x45, 1, 2))) // a 5-byte string with 2 bytes left
        assertEquals("2 B [i !@2", CborShape.describe(bytes(0x82, 1))) // an array cut short
        assertEquals("1 B !@0", CborShape.describe(bytes(0xFF))) // a break outside an indefinite item
        assertEquals("0 B !@0", CborShape.describe(bytes()))
    }

    @Test
    fun indefiniteLengthsTagsAndSimpleValuesHaveTheirOwnMarks() {
        assertEquals("4 B [i×2]*", CborShape.describe(bytes(0x9F, 1, 2, 0xFF)))
        assertEquals("7 B t3*", CborShape.describe(bytes(0x7F, 0x61, 0x61, 0x62, 0x61, 0x62, 0xFF)))
        assertEquals("4 B {i:s}*", CborShape.describe(bytes(0xBF, 1, 0xF6, 0xFF)))
        assertEquals("2 B #1(i)", CborShape.describe(bytes(0xC1, 1)))
        assertEquals("5 B f", CborShape.describe(bytes(0xFA, 0, 0, 0, 0)))
    }
}
