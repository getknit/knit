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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [CborShape] describes a document's structure, never its values: what an identity that opens but does not parse
 * carries to Play. The documents are [cryptoCbor]'s, the codec the identity file is written with. Past a lost span the
 * walk meets key bytes where heads should be, so the faulted cases here are the ones that matter.
 */
@OptIn(ExperimentalSerializationApi::class)
class CborShapeTest {
    @Serializable
    private class Sample(
        val first: ByteArray,
        val second: ByteArray,
        val items: List<Item>? = null,
    )

    @Serializable
    private class Item(
        val id: Int,
        val secret: ByteArray,
        val made: Long,
    )

    private val names = setOf("first", "second", "items", "id", "secret", "made")

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun shape(blob: ByteArray) = CborShape.describe(blob, names)

    /** About the size of an identity file: two keysets' worth of random bytes and a few prekeys. */
    private fun document(random: Random): ByteArray =
        cryptoCbor.encodeToByteArray(
            Sample(
                random.nextBytes(120),
                random.nextBytes(110),
                List(3) { Item(it + 1, random.nextBytes(32), 1_800_000_000_000L + it) },
            ),
        )

    /** [blob] with a span lost, repeated or overwritten, chosen by [random]. */
    private fun faulted(
        blob: ByteArray,
        random: Random,
    ): ByteArray {
        val from = random.nextInt(1, blob.size - 41)
        val to = from + random.nextInt(1, 41)
        return when (random.nextInt(3)) {
            0 -> blob.copyOfRange(0, from) + blob.copyOfRange(to, blob.size)
            1 -> blob.copyOfRange(0, to) + blob.copyOfRange(from, blob.size)
            else -> blob.copyOf().also { random.nextBytes(it, from, to) }
        }
    }

    @Test
    fun aWellFormedDocumentReadsAsItsFieldsWithByteArraysAsRunsOfIntegers() {
        val blob = cryptoCbor.encodeToByteArray(Sample(bytes(1, 2, 3, 4), bytes(5)))
        assertEquals("21 B {first:[i×4],second:[i]}", shape(blob))
    }

    @Test
    fun aSpanLostMidArrayShowsWhereTheRunBreaksAndWhereTheWalkStops() {
        val blob = cryptoCbor.encodeToByteArray(Sample(bytes(1, 2, 3, 4), bytes(5)))
        val lost = blob.copyOfRange(0, 10) + blob.copyOfRange(12, blob.size)

        // The 2.9.0 crash: the decoder reads the next key's text where the array promised an integer.
        assertThrows(SerializationException::class.java) { cryptoCbor.decodeFromByteArray<Sample>(lost) }
        assertEquals("19 B {first:[i×2 second:[i]@10 !@19", shape(lost))
    }

    @Test
    fun onlyGivenNamesAndNoValuesReachTheShape() {
        val blob = cryptoCbor.encodeToByteArray(Sample(bytes(), bytes(), listOf(Item(1, bytes(7, 8), 9), Item(2, bytes(7, 8), 9))))

        assertEquals("${blob.size} B {first:[],second:[],items:[{id:i,secret:[i×2],made:i}×2]}", shape(blob))
        assertEquals("${blob.size} B !@0", CborShape.describe(blob, emptySet()))
    }

    @Test
    fun malformedAndTrailingBytesAreMarkedWhereTheyStart() {
        assertEquals("3 B i +2 B", shape(bytes(1, 2, 3)))
        assertEquals("1 B !@0", shape(bytes(0x1C))) // reserved additional information
        assertEquals("2 B [i !@2", shape(bytes(0x82, 1))) // an array cut short
        assertEquals("0 B !@0", shape(bytes()))
    }

    @Test
    fun whatNoBuildWritesStopsTheWalkAndPrintsNothingOfItself() {
        val tagged = bytes(0xDB, 0x28, 0x0C, 0x18, 0x6D, 0x18, 0x23, 0x09, 0x38, 1)
        assertEquals("10 B !@0", shape(tagged)) // a tag's argument is eight bytes printed raw
        assertEquals("12 B [i !@2", shape(bytes(0x83, 1) + tagged))
        assertEquals("3 B !@0", shape(bytes(0x42, 1, 2))) // a byte string: type and length are the whole head
        assertEquals("3 B [i !@2", shape(bytes(0x82, 1, 0x67))) // a text that is no name
        assertEquals("7 B [i !@2", shape(bytes(0x83, 1, 0xA0, 0x62, 0x69, 0x64, 1))) // an empty map, whose `{}` would pin 0xA0
        assertEquals("4 B [i !@2", shape(bytes(0x83, 1, 0xA1, 2))) // a map on no name
        assertEquals("3 B [i !@2", shape(bytes(0x82, 1, 0x81))) // an array where only a field could be one
        assertEquals("4 B !@0", shape(bytes(0x9F, 1, 2, 0xFF))) // an indefinite length
        assertEquals("5 B !@0", shape(bytes(0xFA, 0, 0, 0, 0))) // a float
        assertEquals("1 B !@0", shape(bytes(0xFF))) // a break
        // Wider than any ByteArray element, each width a tell.
        assertEquals("5 B [i !@2", shape(bytes(0x82, 1, 0x19, 2, 3)))
        assertEquals("7 B [i !@2", shape(bytes(0x82, 1, 0x1A, 2, 3, 4, 5)))
        assertEquals("11 B [i !@2", shape(bytes(0x82, 1, 0x1B, 2, 3, 4, 5, 6, 7, 8, 9)))
    }

    @Test
    fun aHeadWhoseArgumentRunsPastTheEndStopsTheWalkAndThrowsNothing() {
        assertEquals("3 B [i !@2", shape(bytes(0x82, 1, 0x18)))
        assertEquals("3 B [i !@2", shape(bytes(0x82, 1, 0x61)))
    }

    @Test
    fun aFaultedDocumentPrintsOnlyStructureHoweverTheWalkLosesStep() {
        repeat(RUNS) { seed ->
            val random = Random(seed)
            val described = shape(faulted(document(random), random))

            val tokens = names.sortedByDescending { it.length }.fold(described) { text, name -> text.replace(name, "") }
            assertTrue("seed $seed: $described", Regex("""\d+ B [i×@!\d\[\]{}:, +B]*""").matches(tokens))
            // A digit counts or locates something, or it is a value printed in decimal.
            assertFalse("seed $seed: $described", Regex("""(?<![×@+\d])\d""").containsMatchIn(tokens.substringAfter(" B ")))
        }
    }

    @Test
    fun nothingPastTheStopChangesTheShape() {
        var stops = 0
        repeat(RUNS) { seed ->
            val random = Random(seed)
            val blob = faulted(document(random), random)
            val described = shape(blob)
            val stop =
                Regex("""!@(\d+)""")
                    .find(described)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: return@repeat
            stops++

            val other = blob.copyOf()
            for (index in stop + 1 until other.size) other[index] = (other[index].toInt() xor random.nextInt(1, 256)).toByte()
            assertEquals("seed $seed", described, shape(other))
        }
        assertTrue("the faults must actually stop the walk: $stops of $RUNS", stops > RUNS / 2)
    }

    @Test
    fun anIntactDocumentHasNoStop() {
        val described = shape(document(Random(1)))
        assertFalse(described, described.contains("!@"))
    }

    private companion object {
        /** Seeded, so a run is the same run everywhere. */
        const val RUNS = 2_000
    }
}
