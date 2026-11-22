package app.getknit.knit.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** [monthSections]: the month a row was sent in, at the reader's zone, each month once, newest first as given. */
class MonthSectionTest {
    private fun at(
        zone: ZoneId,
        y: Int,
        m: Int,
        d: Int,
        h: Int = 12,
        min: Int = 0,
    ): Long =
        LocalDateTime
            .of(y, m, d, h, min)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun rowsGroupByMonthInTheirOwnOrder() {
        val zone = ZoneId.of("Europe/Lisbon")
        val rows = listOf("c" to at(zone, 2026, 11, 2), "b" to at(zone, 2026, 11, 1), "a" to at(zone, 2026, 10, 31))

        val sections = monthSections(rows, zone) { it.second }

        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10)), sections.map { it.month })
        assertEquals(listOf(listOf("c", "b"), listOf("a")), sections.map { s -> s.items.map { it.first } })
        assertEquals(listOf("month_2026-11", "month_2026-10"), sections.map { it.key })
    }

    @Test
    fun theMonthIsTheReadersNotUtcs() {
        // 23:30 on 31 October in New York is already November in UTC: the reader's month is October.
        val newYork = ZoneId.of("America/New_York")
        val lateOctober = at(newYork, 2026, 10, 31, h = 23, min = 30)

        assertEquals(YearMonth.of(2026, 10), monthSections(listOf(lateOctober), newYork) { it }.single().month)
        assertEquals(YearMonth.of(2026, 11), monthSections(listOf(lateOctober), ZoneId.of("UTC")) { it }.single().month)
    }

    @Test
    fun aMonthMetTwiceIsOneSection() {
        // Rows are newest first, but a sender's clock can put one out of order: its month still has one heading,
        // so the list never repeats a key.
        val zone = ZoneId.of("UTC")
        val rows = listOf(at(zone, 2026, 11, 5), at(zone, 2026, 10, 5), at(zone, 2026, 11, 1))

        val sections = monthSections(rows, zone) { it }

        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10)), sections.map { it.month })
        assertEquals(2, sections.first().items.size)
    }
}
