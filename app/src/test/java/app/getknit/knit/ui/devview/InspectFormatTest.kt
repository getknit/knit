package app.getknit.knit.ui.devview

import org.junit.Assert.assertEquals
import org.junit.Test

/** The developer view's value notation (ADR 2026-10.s7wg), at each unit's edge and the value just inside it. */
class InspectFormatTest {
    @Test
    fun aSpanSwitchesUnitAtEachEdgeAndNeverRoundsUpIntoTheNext() {
        assertEquals("999 ms", InspectFormat.span(999))
        assertEquals("1.0 s", InspectFormat.span(1_000))
        // Truncated: 59 999 ms is still under a minute, and says so.
        assertEquals("59.9 s", InspectFormat.span(59_999))
        assertEquals("1m 00s", InspectFormat.span(60_000))
        assertEquals("59m 59s", InspectFormat.span(3_599_999))
        assertEquals("1h 00m", InspectFormat.span(3_600_000))
        assertEquals("-1.5 s", InspectFormat.span(-1_500))
    }

    @Test
    fun aDeltaCarriesItsSignEitherWay() {
        assertEquals("+0 ms", InspectFormat.delta(0))
        assertEquals("+4m 00s", InspectFormat.delta(240_000))
        assertEquals("-850 ms", InspectFormat.delta(-850))
    }

    @Test
    fun bytesStayExactAndGainKibOnlyFromOneKib() {
        assertEquals("1023 B", InspectFormat.bytes(1_023L))
        assertEquals("1024 B (1.0 KiB)", InspectFormat.bytes(1_024L))
        assertEquals("48213 B (47.1 KiB)", InspectFormat.bytes(48_213))
    }

    @Test
    fun progressShowsAShareOnlyAgainstARealTotal() {
        assertEquals("512 / 2048 B (25 %)", InspectFormat.progress(512, 2_048))
        assertEquals("512 B", InspectFormat.progress(512, null))
        // Bytes past the declared total make the total no bound at all (ArrivingFile's rule).
        assertEquals("3000 B (2.9 KiB)", InspectFormat.progress(3_000, 2_048))
    }

    @Test
    fun aStampIsUtcIsoBesideItsRawMillisAndAMissingOneIsAbsent() {
        assertEquals("2023-11-14T22:13:20Z (1700000000000)", InspectFormat.stamp(1_700_000_000_000L))
        assertEquals(InspectFormat.ABSENT, InspectFormat.stamp(null as Long?))
    }
}
