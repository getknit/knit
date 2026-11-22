package app.getknit.knit.ui.media

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One month of a "Media, links and files" list: the [month] its [items] were sent in, in the list's own order. */
data class MonthSection<T>(
    val month: YearMonth,
    val items: List<T>,
) {
    /** The heading's list key: one per month, never a message id. */
    val key: String get() = "month_$month"
}

/**
 * [items] grouped by the month [sentAt] falls in at [zone], each month once and in the order it is first met, each
 * group keeping the items' own order. The lists come newest first, so the months do too. Pure, for the JVM tests.
 */
fun <T> monthSections(
    items: List<T>,
    zone: ZoneId,
    sentAt: (T) -> Long,
): List<MonthSection<T>> {
    val byMonth = LinkedHashMap<YearMonth, MutableList<T>>()
    for (item in items) {
        val month = YearMonth.from(Instant.ofEpochMilli(sentAt(item)).atZone(zone))
        byMonth.getOrPut(month) { mutableListOf() } += item
    }
    return byMonth.map { (month, group) -> MonthSection(month, group) }
}

/** A month heading in the reader's language and date format, always with its year ("November 2026"). */
@Composable
fun monthLabel(
    month: YearMonth,
    zone: ZoneId,
): String {
    // Mid-month at noon: clear of either end's zone offsets and of a DST change's missing midnight.
    val millis =
        month
            .atDay(MID_MONTH)
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    return DateUtils.formatDateTime(
        LocalContext.current,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
    )
}

/** A row's or a tile's date, short and always with its year, so it reads the same whatever year it is read in. */
@Composable
fun shortDate(sentAt: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        sentAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
    )

private const val MID_MONTH = 15
