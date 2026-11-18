package app.getknit.knit.ui.chat

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS

/**
 * One entry of the thread's `LazyColumn`: a loaded message, or the date header above the first message of a day.
 * [key] is the list key, so the two kinds must never collide: a message keeps its id, a header is `day_<epochDay>`.
 */
sealed interface ThreadItem {
    val key: String
}

data class MessageItem(
    val row: ChatRow,
) : ThreadItem {
    override val key: String get() = row.id
}

data class DaySeparator(
    val day: LocalDate,
) : ThreadItem {
    override val key: String get() = "day_${day.toEpochDay()}"
}

/**
 * The thread's items, newest first — the order the reversed list draws bottom up — with a [DaySeparator] after each
 * day's oldest row, so it is drawn above that day's first message. [rows] are oldest first, as the ViewModel emits
 * them, sorted by `sentAt`, which makes every local day one contiguous run.
 *
 * A day's header is keyed by the day alone, so a page of older history from the same day moves it up the thread
 * rather than replacing it. Each day is emitted at most once: rows out of `sentAt` order would split a day into two
 * runs, and two items with one key crash the list — a missing header is the better failure. The oldest loaded day
 * gets its header even while older history remains: it is true of every row shown beneath it.
 */
fun threadItems(
    rows: List<ChatRow>,
    zone: ZoneId,
): List<ThreadItem> {
    if (rows.isEmpty()) return emptyList()
    val out = ArrayList<ThreadItem>(rows.size + rows.size / 8 + 1)
    val emitted = HashSet<Long>()
    for (i in rows.indices.reversed()) {
        val row = rows[i]
        out += MessageItem(row)
        val day = localDay(row.sentAt, zone)
        val olderDay = if (i > 0) localDay(rows[i - 1].sentAt, zone) else null
        if (day != olderDay && emitted.add(day.toEpochDay())) out += DaySeparator(day)
    }
    return out
}

private fun localDay(
    epochMillis: Long,
    zone: ZoneId,
): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

/** How a day header names its day, relative to [today]. */
enum class DayLabelKind { Today, Yesterday, Weekday, ThisYear, Older }

/**
 * Today and yesterday by name, the weekday within the last week, then the date — without its year in the current
 * year. A day after [today] (a sender whose clock runs ahead) reads as a date, never as "Today".
 */
fun dayLabelKind(
    day: LocalDate,
    today: LocalDate,
): DayLabelKind =
    when (ChronoUnit.DAYS.between(day, today)) {
        0L -> DayLabelKind.Today
        1L -> DayLabelKind.Yesterday
        in 2L..6L -> DayLabelKind.Weekday
        else -> if (day.year == today.year) DayLabelKind.ThisYear else DayLabelKind.Older
    }

/** The words a [DaySeparator] shows for [day], in the reader's language and date format. */
@Composable
fun dayLabel(
    day: LocalDate,
    today: LocalDate,
    zone: ZoneId,
): String {
    val context = LocalContext.current
    // Noon rather than midnight: a zone whose clocks skip midnight on a DST change has no midnight that day.
    val millis =
        day
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    return when (dayLabelKind(day, today)) {
        DayLabelKind.Today -> {
            stringResource(R.string.chat_day_today)
        }

        DayLabelKind.Yesterday -> {
            stringResource(R.string.chat_day_yesterday)
        }

        DayLabelKind.Weekday -> {
            day.dayOfWeek.getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])
        }

        DayLabelKind.ThisYear -> {
            DateUtils.formatDateTime(
                context,
                millis,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
            )
        }

        DayLabelKind.Older -> {
            DateUtils.formatDateTime(
                context,
                millis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
    }
}

/** How a bubble states when it was sent. The day is the separator's to say, so past the hour it is the clock time. */
enum class BubbleTimeKind { JustNow, MinutesAgo, ClockTime }

/**
 * Under a minute — and any time in the future, a sender's clock running ahead — is "just now"; under an hour, how
 * many minutes ago; after that the clock time, beneath the separator that names its day.
 */
fun bubbleTimeKind(
    sentAt: Long,
    now: Long,
): BubbleTimeKind {
    val age = now - sentAt
    return when {
        age < MINUTE_MS -> BubbleTimeKind.JustNow
        age < HOUR_MS -> BubbleTimeKind.MinutesAgo
        else -> BubbleTimeKind.ClockTime
    }
}

/** The bubble's time line: see [bubbleTimeKind]. The clock time follows the device's 12/24-hour setting. */
@Composable
fun bubbleTime(
    sentAt: Long,
    now: Long,
): String =
    when (bubbleTimeKind(sentAt, now)) {
        BubbleTimeKind.JustNow -> {
            stringResource(R.string.chat_time_just_now)
        }

        BubbleTimeKind.MinutesAgo -> {
            DateUtils
                .getRelativeTimeSpanString(sentAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                .toString()
        }

        BubbleTimeKind.ClockTime -> {
            DateUtils.formatDateTime(LocalContext.current, sentAt, DateUtils.FORMAT_SHOW_TIME)
        }
    }
