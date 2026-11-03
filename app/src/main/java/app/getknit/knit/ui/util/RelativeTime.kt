package app.getknit.knit.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * The words [compactTimeAgo] says for the recent end of its range. [minutes] and [hours] take the count as their one
 * argument.
 */
data class RelativeTimeLabels(
    val now: String,
    val minutes: String,
    val hours: String,
    val yesterday: String,
) {
    companion object {
        /** The English labels — the tests' default; screens pass the reader's own ([relativeTimeLabels]). */
        val ENGLISH = RelativeTimeLabels(now = "now", minutes = $$"%1$dm", hours = $$"%1$dh", yesterday = "Yesterday")
    }
}

/** [RelativeTimeLabels] in the reader's language. */
@Composable
fun relativeTimeLabels(): RelativeTimeLabels =
    RelativeTimeLabels(
        now = stringResource(R.string.time_ago_now),
        minutes = stringResource(R.string.time_ago_minutes),
        hours = stringResource(R.string.time_ago_hours),
        yesterday = stringResource(R.string.time_ago_yesterday),
    )

/**
 * Compact, Signal-style relative timestamp for chat-list rows: "now", "5m", "2h", "Yesterday",
 * a short weekday ("Mon") within the last week, then a localized short date.
 *
 * Pure and clock-free: [now], [zone] and [labels] are parameters so it is deterministically unit-testable.
 * The day-of-week and date are localized via `java.time`, the rest through [labels].
 */
fun compactTimeAgo(
    epochMillis: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    labels: RelativeTimeLabels = RelativeTimeLabels.ENGLISH,
): String {
    val diff = now - epochMillis
    return when {
        diff < MINUTE_MS -> {
            labels.now
        }

        diff < HOUR_MS -> {
            labels.minutes.format(Locale.getDefault(), diff / MINUTE_MS)
        }

        diff < DAY_MS -> {
            labels.hours.format(Locale.getDefault(), diff / HOUR_MS)
        }

        else -> {
            val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            when (ChronoUnit.DAYS.between(date, today)) {
                1L -> labels.yesterday
                in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
            }
        }
    }
}
