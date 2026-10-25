package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.protocol.BodyStyle.BOLD
import app.getknit.knit.mesh.protocol.BodyStyle.ITALIC
import app.getknit.knit.mesh.protocol.BodyStyle.MONOSPACE
import app.getknit.knit.mesh.protocol.BodyStyle.STRIKETHROUGH
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StyleSpans], the codec and normalisation of ADR 2026-10.p95v's two tables: every rule pinned on the bytes both
 * ports read, and the range algebra the composer calls.
 */
class StyleSpansTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun span(
        start: Int,
        end: Int,
        style: BodyStyle = BOLD,
    ) = StyleSpan(start, end, style)

    @Test
    fun noSpansPackToNothingAndCostNothing() {
        assertNull(StyleSpans.pack(emptyList()))
        assertEquals(0, StyleSpans.wireCost(emptyList(), StyleKey.LABEL))
        assertEquals(0, StyleSpans.wireCost(emptyList(), StyleKey.NAMED))
        assertEquals(emptyList<StyleSpan>(), StyleSpans.unpack(null))
        assertEquals(emptyList<StyleSpan>(), StyleSpans.unpack(ByteArray(0)))
    }

    @Test
    fun aTypicalSpanIsTwoBytes() {
        // delta 0, (length 5 << 3) | bold = 40.
        assertArrayEquals(bytes(0x00, 0x28), StyleSpans.pack(listOf(span(0, 5))))
        // delta 6, (6 << 3) | italic = 49.
        assertArrayEquals(bytes(0x00, 0x28, 0x06, 0x31), StyleSpans.pack(listOf(span(0, 5), span(6, 12, ITALIC))))
        assertEquals(listOf(span(0, 5), span(6, 12, ITALIC)), StyleSpans.unpack(bytes(0x00, 0x28, 0x06, 0x31)))
    }

    @Test
    fun uvarintsTakeOneTwoOrThreeBytes() {
        // A start of 127 is one byte; 128 is two (0x80 0x01); 16384 is three (0x80 0x80 0x01).
        assertArrayEquals(bytes(0x7F, 0x08), StyleSpans.pack(listOf(span(127, 128))))
        assertArrayEquals(bytes(0x80, 0x01, 0x08), StyleSpans.pack(listOf(span(128, 129))))
        assertArrayEquals(bytes(0x80, 0x80, 0x01, 0x08), StyleSpans.pack(listOf(span(16_384, 16_385))))
        // A length of 16 no longer fits the low byte beside its code: (16 << 3) = 128, two bytes.
        assertArrayEquals(bytes(0x00, 0x80, 0x01), StyleSpans.pack(listOf(span(0, 16))))
        for (spans in listOf(listOf(span(127, 128)), listOf(span(128, 129)), listOf(span(16_384, 16_385)), listOf(span(0, 16)))) {
            assertEquals(spans, StyleSpans.unpack(StyleSpans.pack(spans)))
        }
    }

    @Test
    fun overlappingStylesKeepANonNegativeDelta() {
        // Bold 0..10 and italic 2..4: the second entry's delta is from the first entry's start, not its end.
        val spans = listOf(span(0, 10), span(2, 4, ITALIC))
        assertArrayEquals(bytes(0x00, 0x50, 0x02, 0x11), StyleSpans.pack(spans))
        assertEquals(spans, StyleSpans.unpack(StyleSpans.pack(spans)))
    }

    @Test
    fun theEscapeReadsItsCodeAndAnUnknownOneIsDroppedButMovesTheStart() {
        // An escaped code 7 + 5 = 12 at delta 3, then bold at delta 2 from it: the bold lands at 5.
        val escaped = bytes(0x03, (4 shl 3) or BodyStyle.ESCAPE, 0x05, 0x02, 0x10)
        assertEquals(listOf(span(5, 7)), StyleSpans.unpack(escaped))
        // The reserved spoiler, and 5 and 6, are unknown to v1: dropped, the next span still placed.
        for (code in listOf(BodyStyle.SPOILER_RESERVED, 5, 6)) {
            assertEquals("code $code", listOf(span(4, 5, ITALIC)), StyleSpans.unpack(bytes(0x01, (2 shl 3) or code, 0x03, 0x09)))
        }
        // An escape whose own uvarint never comes ends the read.
        assertEquals(listOf(span(0, 1)), StyleSpans.unpack(bytes(0x00, 0x08, 0x00, (1 shl 3) or BodyStyle.ESCAPE)))
    }

    @Test
    fun aZeroLengthEntryIsDroppedButMovesTheStart() {
        assertEquals(listOf(span(3, 4)), StyleSpans.unpack(bytes(0x02, 0x00, 0x01, 0x08)))
    }

    @Test
    fun aTruncatedOrFourByteUvarintEndsTheReadKeepingWhatCameBefore() {
        // A continuation byte with nothing after it.
        assertEquals(listOf(span(0, 1)), StyleSpans.unpack(bytes(0x00, 0x08, 0x80)))
        // A delta with no length entry.
        assertEquals(listOf(span(0, 1)), StyleSpans.unpack(bytes(0x00, 0x08, 0x01)))
        // A third byte that asks for a fourth.
        assertEquals(listOf(span(0, 1)), StyleSpans.unpack(bytes(0x00, 0x08, 0x80, 0x80, 0x80, 0x01, 0x08)))
        // Garbage first: nothing, and no throw.
        assertEquals(emptyList<StyleSpan>(), StyleSpans.unpack(bytes(0xFF, 0xFF, 0xFF, 0xFF)))
    }

    @Test
    fun atMostSixtyFourEntriesAreRead() {
        val entries = ByteArray(2 * 70) { if (it % 2 == 0) 0x02 else 0x08 }
        val spans = StyleSpans.unpack(entries)
        assertEquals(StyleSpans.MAX_SPANS, spans.size)
        assertEquals(span(128, 129), spans.last())
        // An encoder writes no more than it reads.
        val many = List(70) { span(it * 2, it * 2 + 1) }
        assertEquals(StyleSpans.MAX_SPANS, StyleSpans.unpack(StyleSpans.pack(many)).size)
    }

    @Test
    fun normalizeDropsWhatIsOutOfRangeAndClipsTheEnd() {
        val body = "hello world"
        assertEquals(emptyList<StyleSpan>(), StyleSpans.normalize(body, listOf(span(11, 12), span(20, 30), span(3, 3), span(-1, 2))))
        assertEquals(listOf(span(6, 11)), StyleSpans.normalize(body, listOf(span(6, 400))))
        assertEquals(listOf(span(0, 11)), StyleSpans.normalize(body, listOf(span(0, Int.MAX_VALUE))))
        assertEquals(emptyList<StyleSpan>(), StyleSpans.normalize("", listOf(span(0, 1))))
    }

    @Test
    fun anOverflowingEntryIsClippedNotWrapped() {
        // The largest start and length three-byte uvarints can say.
        val far = bytes(0xFF, 0xFF, 0x7F, 0xF8, 0xFF, 0x7F)
        val spans = StyleSpans.unpack(far)
        assertEquals(listOf(span((1 shl 21) - 1, (1 shl 21) - 1 + ((1 shl 18) - 1))), spans)
        assertEquals(emptyList<StyleSpan>(), StyleSpans.normalize("short", spans))
    }

    @Test
    fun aSplitSurrogatePairSnapsOutwardAtBothEnds() {
        val body = "a👍b" // a 👍 b: the pair is units 1..3
        assertEquals(listOf(span(1, 3)), StyleSpans.normalize(body, listOf(span(2, 3))))
        assertEquals(listOf(span(0, 3)), StyleSpans.normalize(body, listOf(span(0, 2))))
        assertEquals(listOf(span(1, 3)), StyleSpans.normalize(body, listOf(span(1, 3))))
    }

    @Test
    fun aLoneSurrogateAtEitherEndIsNoPairAndNeverThrows() {
        val trailingHigh = "ab\uD83D"
        assertEquals(listOf(span(2, 3)), StyleSpans.normalize(trailingHigh, listOf(span(2, 3))))
        assertEquals(listOf(span(0, 3)), StyleSpans.normalize(trailingHigh, listOf(span(0, 9))))
        val leadingLow = "\uDC4Dab"
        assertEquals(listOf(span(0, 1)), StyleSpans.normalize(leadingLow, listOf(span(0, 1))))
        assertEquals(listOf(span(1, 3)), StyleSpans.normalize(leadingLow, listOf(span(1, 3))))
        // An end exactly at the body's length reads nothing past it.
        assertEquals(listOf(span(1, 3)), StyleSpans.normalize("a👍", listOf(span(1, 3))))
    }

    @Test
    fun oneStyleMergesWhereItTouchesOrOverlapsAndStylesMayOverlap() {
        val body = "x".repeat(40)
        assertEquals(listOf(span(0, 10)), StyleSpans.normalize(body, listOf(span(0, 5), span(5, 10))))
        assertEquals(listOf(span(0, 12)), StyleSpans.normalize(body, listOf(span(4, 12), span(0, 6))))
        assertEquals(listOf(span(0, 5), span(6, 9)), StyleSpans.normalize(body, listOf(span(6, 9), span(0, 5))))
        assertEquals(
            listOf(span(0, 10), span(0, 4, ITALIC), span(2, 30, MONOSPACE)),
            StyleSpans.normalize(body, listOf(span(2, 30, MONOSPACE), span(0, 4, ITALIC), span(0, 10))),
        )
    }

    @Test
    fun normalizeSortsByStartThenCode() {
        val body = "x".repeat(20)
        assertEquals(
            listOf(span(0, 2, STRIKETHROUGH), span(3, 5), span(3, 4, ITALIC)),
            StyleSpans.normalize(body, listOf(span(3, 4, ITALIC), span(3, 5), span(0, 2, STRIKETHROUGH))),
        )
    }

    @Test
    fun toggleAddsWhereUncoveredAndRemovesWhereCovered() {
        val on = StyleSpans.toggle(emptyList(), BOLD, 2, 6)
        assertEquals(listOf(span(2, 6)), on)
        assertEquals(emptyList<StyleSpan>(), StyleSpans.toggle(on, BOLD, 2, 6))
        // Partly covered: the whole range takes the style.
        assertEquals(listOf(span(0, 8)), StyleSpans.toggle(listOf(span(0, 4)), BOLD, 2, 8))
        // Inside a span: the span splits around the range.
        assertEquals(listOf(span(0, 3), span(5, 10)), StyleSpans.toggle(listOf(span(0, 10)), BOLD, 3, 5))
        // Another style is left alone.
        assertEquals(listOf(span(0, 10, ITALIC), span(3, 5)), StyleSpans.toggle(listOf(span(0, 10, ITALIC)), BOLD, 3, 5))
        assertEquals(listOf(span(0, 1)), StyleSpans.toggle(listOf(span(0, 1)), BOLD, 4, 4))
    }

    @Test
    fun clearRemovesEveryStyleInTheRangeAndKeepsTheOutside() {
        val spans = listOf(span(0, 10), span(4, 12, ITALIC), span(20, 22, MONOSPACE))
        assertEquals(listOf(span(0, 3), span(8, 10), span(8, 12, ITALIC), span(20, 22, MONOSPACE)), StyleSpans.clear(spans, 3, 8))
        assertEquals(emptyList<StyleSpan>(), StyleSpans.clear(spans, 0, 30))
    }

    @Test
    fun coversNeedsEveryUnit() {
        val spans = listOf(span(0, 4), span(4, 8), span(10, 12))
        assertTrue(StyleSpans.covers(spans, BOLD, 1, 8))
        assertFalse(StyleSpans.covers(spans, BOLD, 6, 11))
        assertFalse(StyleSpans.covers(spans, ITALIC, 0, 2))
        assertFalse("an empty range is never covered", StyleSpans.covers(spans, BOLD, 2, 2))
    }

    @Test
    fun rebaseShiftsAcrossTheTrimAndClipsToTheClamp() {
        // "  hello world  " trimmed: two leading units dropped, eleven kept.
        val spans = listOf(span(0, 7), span(8, 15, ITALIC))
        assertEquals(listOf(span(0, 5), span(6, 11, ITALIC)), StyleSpans.rebase(spans, dropLeading = 2, length = 11))
        // A span wholly inside the dropped whitespace, or past the clamp, is gone.
        assertEquals(emptyList<StyleSpan>(), StyleSpans.rebase(listOf(span(0, 2), span(20, 25)), dropLeading = 2, length = 11))
    }

    @Test
    fun wireCostIsTheEncodedSize() {
        val one = listOf(span(0, 4))
        // ADR 2026-10.p95v: one bold word costs a v3 message 4 bytes and a room post 5.
        assertEquals(4, StyleSpans.wireCost(one, StyleKey.LABEL))
        assertEquals(5, StyleSpans.wireCost(one, StyleKey.NAMED))
        for (n in listOf(1, 11, 12, 30, 64)) {
            val spans = List(n) { span(it * 300, it * 300 + 20, BodyStyle.entries[it % 4]) }
            val packed = checkNotNull(StyleSpans.pack(spans))
            val header = if (packed.size < 24) 1 else 2
            assertEquals("$n spans", StyleKey.NAMED.bytes + header + packed.size, StyleSpans.wireCost(spans, StyleKey.NAMED))
        }
    }

    @Test
    fun wireCostStepsTheByteStringHeaderAt24And256Bytes() {
        // Packed sizes built by hand: a span with a start delta under 128 and a length under 16 is 2 bytes; a delta of
        // 128 and a length of 16 make each uvarint 2 bytes, so 4. The header is 1 byte below 24, 2 below 256, else 3.
        val small = { n: Int -> List(n) { span(it * 2, it * 2 + 1) } }
        val ten = small(10)
        val twentyThree = ten + span(20 + 128, 20 + 128 + 1)
        assertEquals(23, checkNotNull(StyleSpans.pack(twentyThree)).size)
        assertEquals(2 + 1 + 23, StyleSpans.wireCost(twentyThree, StyleKey.NAMED))
        val twentyFour = small(12)
        assertEquals(24, checkNotNull(StyleSpans.pack(twentyFour)).size)
        assertEquals(2 + 2 + 24, StyleSpans.wireCost(twentyFour, StyleKey.NAMED))

        val wide = { first: Int -> List(64) { span(first + it * 128, first + it * 128 + 16) } }
        val twoFiftyFive = wide(0) // the first entry's delta is 0, so 1 byte short of 64 four-byte entries
        assertEquals(255, checkNotNull(StyleSpans.pack(twoFiftyFive)).size)
        assertEquals(1 + 2 + 255, StyleSpans.wireCost(twoFiftyFive, StyleKey.LABEL))
        val twoFiftySix = wide(128)
        assertEquals(256, checkNotNull(StyleSpans.pack(twoFiftySix)).size)
        assertEquals(1 + 3 + 256, StyleSpans.wireCost(twoFiftySix, StyleKey.LABEL))
    }
}
