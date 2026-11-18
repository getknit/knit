package app.getknit.knit.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The thread's date headers (threadItems), the words they say (dayLabelKind) and the bubble's time line
 * (bubbleTimeKind). Every zone is explicit: unit tests run in the host's default zone, which only the screenshot
 * tasks pin.
 */
class ThreadItemsTest {
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(
        text: String,
        zone: ZoneId,
    ): Long =
        LocalDateTime
            .parse(text)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun row(
        id: String,
        sentAt: Long,
    ) = ChatRow(
        id = id,
        body = id,
        mine = false,
        senderName = "Bob",
        senderNodeId = "bob",
        avatarHash = null,
        sentAt = sentAt,
        received = false,
    )

    /** The items as `m:<id>` and `d:<date>`, newest first, as the reversed list holds them. */
    private fun shape(items: List<ThreadItem>): List<String> =
        items.map {
            when (it) {
                is MessageItem -> "m:${it.row.id}"
                is DaySeparator -> "d:${it.day}"
            }
        }

    @Test
    fun anEmptyThreadHasNoItems() {
        assertEquals(emptyList<ThreadItem>(), threadItems(emptyList(), utc))
    }

    @Test
    fun eachDaysHeaderFollowsItsOldestRowSoItIsDrawnAboveTheDaysFirstMessage() {
        val rows =
            listOf(
                row("a", at("2026-11-15T09:00", utc)),
                row("b", at("2026-11-15T18:30", utc)),
                row("c", at("2026-11-16T08:00", utc)),
                row("d", at("2026-11-17T07:15", utc)),
                row("e", at("2026-11-17T07:16", utc)),
            )

        assertEquals(
            listOf("m:e", "m:d", "d:2026-11-17", "m:c", "d:2026-11-16", "m:b", "m:a", "d:2026-11-15"),
            shape(threadItems(rows, utc)),
        )
    }

    /** 23:30 UTC is already the next day in Berlin: the reader's zone decides the day, not UTC's. */
    @Test
    fun daysAreTheReadersLocalDays() {
        val rows =
            listOf(
                row("late", at("2026-11-16T23:30", utc)),
                row("early", at("2026-11-17T00:30", utc)),
            )

        assertEquals(listOf("m:early", "d:2026-11-17", "m:late", "d:2026-11-16"), shape(threadItems(rows, utc)))
        assertEquals(listOf("m:early", "m:late", "d:2026-11-17"), shape(threadItems(rows, berlin)))
    }

    /** Berlin's clocks went back an hour at 03:00 on 2026-10-25, a 25-hour day: one header, not two. */
    @Test
    fun aDaylightSavingChangeIsStillOneDay() {
        val rows =
            listOf(
                row("before", at("2026-10-25T00:30", berlin)),
                row("after", at("2026-10-25T23:30", berlin)),
            )

        assertEquals(listOf("m:after", "m:before", "d:2026-10-25"), shape(threadItems(rows, berlin)))
    }

    /** Rows out of sentAt order split a day in two; a second header with the same key would crash the list. */
    @Test
    fun aDaySplitByOutOfOrderRowsGetsOneHeader() {
        val rows =
            listOf(
                row("a", at("2026-11-15T09:00", utc)),
                row("b", at("2026-11-16T09:00", utc)),
                row("c", at("2026-11-15T10:00", utc)),
            )

        val items = threadItems(rows, utc)

        assertEquals(items.size, items.map { it.key }.toSet().size)
        assertEquals(listOf("m:c", "d:2026-11-15", "m:b", "d:2026-11-16", "m:a"), shape(items))
    }

    /** A header's key is its day alone, so a page of older history from the same day keeps it in place. */
    @Test
    fun aHeadersKeyIsItsDayAndNeverAMessageId() {
        val early = row("early", at("2026-11-17T06:00", utc))
        val later = row("later", at("2026-11-17T09:00", utc))

        val before = threadItems(listOf(later), utc).filterIsInstance<DaySeparator>().single()
        val after = threadItems(listOf(early, later), utc).filterIsInstance<DaySeparator>().single()

        assertEquals(before.key, after.key)
        assertEquals("day_${LocalDate.parse("2026-11-17").toEpochDay()}", after.key)
    }

    @Test
    fun dayLabelsNameTheRecentDaysThenTheWeekdayThenTheDate() {
        val today = LocalDate.parse("2026-11-17")

        assertEquals(DayLabelKind.Today, dayLabelKind(today, today))
        assertEquals(DayLabelKind.Yesterday, dayLabelKind(today.minusDays(1), today))
        assertEquals(DayLabelKind.Weekday, dayLabelKind(today.minusDays(2), today))
        assertEquals(DayLabelKind.Weekday, dayLabelKind(today.minusDays(6), today))
        // A week ago is the same weekday as today: a bare "Tuesday" would read as this one.
        assertEquals(DayLabelKind.ThisYear, dayLabelKind(today.minusDays(7), today))
        assertEquals(DayLabelKind.ThisYear, dayLabelKind(LocalDate.parse("2026-01-01"), today))
        assertEquals(DayLabelKind.Older, dayLabelKind(LocalDate.parse("2025-12-31"), today))
    }

    /** Yesterday across New Year is still "Yesterday", and the week before it is last year's date. */
    @Test
    fun dayLabelsAcrossNewYear() {
        val today = LocalDate.parse("2027-01-01")

        assertEquals(DayLabelKind.Yesterday, dayLabelKind(LocalDate.parse("2026-12-31"), today))
        assertEquals(DayLabelKind.Weekday, dayLabelKind(LocalDate.parse("2026-12-27"), today))
        assertEquals(DayLabelKind.Older, dayLabelKind(LocalDate.parse("2026-12-20"), today))
    }

    /** A sender whose clock runs ahead dates a message tomorrow: that is a date, never "Today". */
    @Test
    fun aDayAfterTodayIsADate() {
        val today = LocalDate.parse("2026-11-17")

        assertEquals(DayLabelKind.ThisYear, dayLabelKind(today.plusDays(1), today))
    }

    @Test
    fun bubbleTimeIsJustNowThenMinutesThenTheClock() {
        val now = at("2026-11-17T12:00", utc)
        val minute = 60_000L

        assertEquals(BubbleTimeKind.JustNow, bubbleTimeKind(now, now))
        assertEquals(BubbleTimeKind.JustNow, bubbleTimeKind(now - minute + 1, now))
        assertEquals(BubbleTimeKind.MinutesAgo, bubbleTimeKind(now - minute, now))
        assertEquals(BubbleTimeKind.MinutesAgo, bubbleTimeKind(now - 60 * minute + 1, now))
        assertEquals(BubbleTimeKind.ClockTime, bubbleTimeKind(now - 60 * minute, now))
        assertEquals(BubbleTimeKind.ClockTime, bubbleTimeKind(now - 3 * 24 * 60 * minute, now))
    }

    /** A sender's clock running ahead must not read as a time in the future. */
    @Test
    fun aMessageFromTheFutureIsJustNow() {
        val now = at("2026-11-17T12:00", utc)

        assertEquals(BubbleTimeKind.JustNow, bubbleTimeKind(now + 10 * 60_000L, now))
    }
}
