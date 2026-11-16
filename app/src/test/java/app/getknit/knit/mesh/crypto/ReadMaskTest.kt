package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.FrameId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * The read mask (ADR 2026-11.z6jw): its encoding, its reading, and the bytes it adds to a receipt. The width pins are the
 * point: a compact ✓✓'s length is public on the band, so the mask's encoded width must depend on the list's length alone,
 * never on which ids were read (review round 1, B1).
 */
class ReadMaskTest {
    private fun ids(n: Int): List<String> = List(n) { FrameId.new() }

    /** The compact receipt's encoded size for [list] with mask [rm]: what a carrier or the band sees, less the seal. */
    private fun compactSize(
        list: List<String>,
        rm: Long,
    ): Int = MessageContentV2.encodeOrNull(MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = list, rm = rm))!!.size

    private fun subset(
        list: List<String>,
        bits: Long,
    ): Set<String> = list.filterIndexedTo(HashSet()) { i, _ -> (bits ushr i) and 1L == 1L }

    @Test
    fun `up to twelve ids, every read subset encodes to one width per length and reads back`() {
        for (n in 1..12) {
            val list = ids(n)
            val widths = HashSet<Int>()
            for (bits in 0L until (1L shl n)) {
                val read = subset(list, bits)
                val rm = ReadMask.encode(list, read)
                widths += compactSize(list, rm)
                assertEquals("n=$n bits=$bits", read, ReadMask.readIn(list, rm))
            }
            assertEquals("n=$n has one width: $widths", 1, widths.size)
        }
    }

    @Test
    fun `thirteen to sixty-two ids, sampled subsets and the boundaries keep one width per length`() {
        val random = Random(seed = 23)
        for (n in 13..62) {
            val list = ids(n)
            val samples =
                buildList {
                    add(emptySet())
                    add(list.toSet())
                    add(setOf(list.first()))
                    add(setOf(list.last()))
                    add(list.drop(1).toSet())
                    repeat(40) { add(list.filter { random.nextBoolean() }.toSet()) }
                }
            val widths =
                samples.mapTo(HashSet()) { read ->
                    val rm = ReadMask.encode(list, read)
                    assertEquals("n=$n", read, ReadMask.readIn(list, rm))
                    compactSize(list, rm)
                }
            assertEquals("n=$n has one width: $widths", 1, widths.size)
        }
    }

    @Test
    fun `none and all read at the edges of each form`() {
        for (n in 1..4) {
            val list = ids(n)
            assertEquals("n=$n all", -1L, ReadMask.encode(list, list.toSet()))
            assertEquals("n=$n none", 0L, ReadMask.encode(list, emptySet()))
        }
        for (n in 5..62) {
            val list = ids(n)
            assertEquals("n=$n none: the first bit past the list", 1L shl n, ReadMask.encode(list, emptySet()))
            assertEquals("n=$n all", (1L shl (n + 1)) - 1, ReadMask.encode(list, list.toSet()))
        }
    }

    @Test
    fun `sixty-three and sixty-four ids take the mask as it is, and the top bits read`() {
        for (n in 63..64) {
            val list = ids(n)
            assertEquals(0L, ReadMask.encode(list, emptySet()))
            val last = setOf(list[n - 1])
            assertEquals(last, ReadMask.readIn(list, ReadMask.encode(list, last)))
            assertEquals(list.toSet(), ReadMask.readIn(list, ReadMask.encode(list, list.toSet())))
        }
        val list = ids(64)
        assertEquals(-1L, ReadMask.encode(list, list.toSet()))
        assertEquals(Long.MIN_VALUE, ReadMask.encode(list, setOf(list[63])))
    }

    @Test
    fun `bits past the list are ignored and an id past the sixty-fourth never reads`() {
        val list = ids(5)
        val read = setOf(list[0], list[3])
        assertEquals(read, ReadMask.readIn(list, 0b1001L or (1L shl 40)))
        assertEquals(read, ReadMask.readIn(list, 0b1001L or (-1L shl 5)))
        val long = ids(70)
        assertEquals(long.take(64).toSet(), ReadMask.readIn(long, -1L))
        assertEquals(0L, ReadMask.encode(long, setOf(long[65])))
    }

    @Test
    fun `no mask reads nothing`() {
        assertEquals(emptySet<String>(), ReadMask.readIn(ids(3), null))
    }

    @Test
    fun `a receipt without a mask is byte-identical to one built before the field`() {
        val id = FrameId.new()
        val plain = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = id)
        val again = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = id, rm = null)
        assertArrayEquals(plain.encode(), again.encode())
        assertArrayEquals(MessageContentV2.encodeOrNull(plain), MessageContentV2.encodeOrNull(again))
    }

    /** The compact cost of the mask per list length: label 20 (one byte) plus the value — the figures the docs quote. */
    @Test
    fun `the compact mask costs two bytes up to four ids, three to seven, four from eight to nine`() {
        val expected = mapOf(1 to 2, 2 to 2, 3 to 2, 4 to 2, 5 to 3, 6 to 3, 7 to 3, 8 to 4, 9 to 4)
        for ((n, cost) in expected) {
            val list = ids(n)
            val base = MessageContentV2.encodeOrNull(MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = list))!!.size
            assertEquals("n=$n", cost, compactSize(list, ReadMask.encode(list, emptySet())) - base)
        }
    }

    @Test
    fun `the mask survives both layouts`() {
        val list = ids(9)
        val rm = ReadMask.encode(list, setOf(list[2], list[8]))
        val content = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = list, rm = rm)
        assertEquals(rm, MessageContent.decode(content.encode())!!.rm)
        assertEquals(rm, MessageContentV2.decode(MessageContentV2.encodeOrNull(content)!!)!!.rm)
        assertNull(MessageContentV2.decode(MessageContentV2.encodeOrNull(content.copy(rm = null))!!)!!.rm)
    }
}
