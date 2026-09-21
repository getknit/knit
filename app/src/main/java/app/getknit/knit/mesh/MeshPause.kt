package app.getknit.knit.mesh

import android.content.Context
import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The rules of a mesh pause, pure so they are one JVM test away. A pause is a wall-clock deadline
 * (`SettingsStore.meshPausedUntil`) the user picks from the ongoing notification; `MeshService` takes the
 * mesh down until it and every surface that says "paused" reads the same key through [activeDeadline], so a
 * deadline in the past is simply not a pause anywhere.
 */
object MeshPause {
    /** The two spans the notification offers; its labels (`mesh_notification_pause_*`) name these numbers. */
    const val SHORT_MINUTES = 15
    const val LONG_MINUTES = 60

    private val offered = setOf(SHORT_MINUTES, LONG_MINUTES)

    /**
     * The deadline a pause of [minutes] from [now] ends at, or null for a span we never offered — the value
     * rides a `PendingIntent` extra, so an unknown one is dropped rather than honoured.
     */
    fun deadline(
        now: Long,
        minutes: Int,
    ): Long? = if (minutes in offered) now + TimeUnit.MINUTES.toMillis(minutes.toLong()) else null

    /** [until] if it is still ahead of [now], else null: the one reading of the key every surface shares. */
    fun activeDeadline(
        until: Long?,
        now: Long,
    ): Long? = until?.takeIf { it > now }

    /** Whether [until] falls on a later calendar day than [now] in [zone] — the label then names the day. */
    fun crossesDay(
        until: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = Instant.ofEpochMilli(until).atZone(zone).toLocalDate() > Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}

/**
 * "3:15 PM", or "Tue 3:15 PM" once the deadline is past midnight — the phone's own time format (12/24 h), which
 * is why this is `DateUtils` and not `java.time`. Shared by the notification and the chat list's banner.
 */
fun pausedUntilLabel(
    context: Context,
    until: Long,
    now: Long,
): String {
    var flags = DateUtils.FORMAT_SHOW_TIME
    if (MeshPause.crossesDay(until, now)) flags = flags or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return DateUtils.formatDateTime(context, until, flags)
}
