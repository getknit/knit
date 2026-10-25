package app.getknit.knit.ui.devview

import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/**
 * One fact in the developer view (ADR 2026-10.s7wg): the code's own name for it ([key]) and its value in log notation.
 * Both are deliberately English, like the call diagnostics card's values (`context/translations.md`): they are built
 * here, never handed to `Text` as literals, and only the view's chrome is a string resource.
 */
data class InspectRow(
    val key: String,
    val value: String,
)

/** A titled run of [rows]. The [title] is a code word too (`custody`, `planes`). */
data class InspectGroup(
    val title: String,
    val rows: List<InspectRow>,
)

/** The developer view's value notation, shared by the message inspector and the HUD. Pure, so its tests pin it. */
object InspectFormat {
    /** A fact this phone does not hold. Never a guess in its place. */
    const val ABSENT = "—"

    private const val KIB = 1_024L
    private const val TENTH_MS = 100L
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val PERCENT = 100

    /** A wall-clock stamp as UTC ISO-8601, the raw millis beside it so a log line can be matched by either. */
    fun stamp(ms: Long): String = "${Instant.ofEpochMilli(ms)} ($ms)"

    /** [stamp], or [ABSENT] for a stamp the row never got. */
    fun stamp(ms: Long?): String = ms?.let { stamp(it) } ?: ABSENT

    /** A duration: milliseconds under a second, then seconds, minutes and hours. */
    fun span(ms: Long): String {
        val size = abs(ms)
        val sign = if (ms < 0) "-" else ""
        return sign +
            when {
                size < SECOND_MS -> "$size ms"

                // Truncated, not rounded, so 59 999 ms never reads as a minute.
                size < MINUTE_MS -> "${size / SECOND_MS}.${size % SECOND_MS / TENTH_MS} s"

                size < HOUR_MS -> String.format(Locale.ROOT, "%dm %02ds", size / MINUTE_MS, size % MINUTE_MS / SECOND_MS)

                else -> String.format(Locale.ROOT, "%dh %02dm", size / HOUR_MS, size % HOUR_MS / MINUTE_MS)
            }
    }

    /** A signed difference, `+` shown, so a clock behind ours reads as plainly as one ahead. */
    fun delta(ms: Long): String = if (ms >= 0) "+${span(ms)}" else span(ms)

    /** A byte count, exact, with KiB beside it once it is large enough to need it. */
    fun bytes(count: Long): String =
        if (count < KIB) "$count B" else String.format(Locale.ROOT, "%d B (%.1f KiB)", count, count / KIB.toDouble())

    fun bytes(count: Int): String = bytes(count.toLong())

    /** [bytes] of [done] against [total], with the share when the total is a real bound. */
    fun progress(
        done: Long,
        total: Long?,
    ): String =
        if (total == null || total <= 0 || done > total) {
            bytes(done)
        } else {
            "$done / $total B (${done * PERCENT / total} %)"
        }

    fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
