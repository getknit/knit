package app.getknit.knit.ui.util

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
 * Compact, Signal-style relative timestamp for chat-list rows: "now", "5m", "2h", "Yesterday",
 * a short weekday ("Mon") within the last week, then a localized short date.
 *
 * Pure and clock-free: [now] and [zone] are parameters so it is deterministically unit-testable.
 * The day-of-week and date are localized via `java.time`; "now"/"Yesterday" are intentionally
 * short literals.
 */
fun compactTimeAgo(
    epochMillis: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val diff = now - epochMillis
    return when {
        diff < MINUTE_MS -> {
            "now"
        }

        diff < HOUR_MS -> {
            "${diff / MINUTE_MS}m"
        }

        diff < DAY_MS -> {
            "${diff / HOUR_MS}h"
        }

        else -> {
            val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            when (ChronoUnit.DAYS.between(date, today)) {
                1L -> "Yesterday"
                in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
            }
        }
    }
}
