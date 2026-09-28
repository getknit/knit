package app.getknit.knit.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/**
 * Knit's own read cadence while a Knit complication is on the face (ADR 2026-09.wetm, fourth amendment). Wear OS
 * runs a data source's periodic asks as jobs that wait for the watch to leave Doze, and raising the wrist lights
 * the screen without leaving it (display policy DOZE), so a face could go all day without asking. An
 * allow-while-idle alarm fires in Doze, and the read it kicks is an expedited job, which may run there too; a
 * good read then asks the face for fresh data.
 *
 * At most [INTERVAL_MS] apart, or [AWAY_INTERVAL_MS] while the last read found no phone. The alarm is inexact
 * (the price of not needing the exact-alarm grant): the system may hold it up to three quarters of its lead time
 * to batch it, and in Doze it did so every time — asked for five minutes, it fired every 8 min 45 s. So it is
 * asked for [lead] of the interval, which puts the latest delivery at the interval itself. The chain runs only while
 * a complication is active — one that has asked within [ASKED_WITHIN_MS] and not been deactivated — so removing
 * every Knit complication stops the wakes. It is re-armed by the next ask, a reboot or an update.
 */
object StatusAlarm {
    const val INTERVAL_MS = 5 * 60_000L
    const val AWAY_INTERVAL_MS = 15 * 60_000L

    /** How long a complication that stopped asking still counts as on the face. */
    const val ASKED_WITHIN_MS = 24 * 60 * 60_000L

    /** The system holds an inexact alarm up to HOLD_PARTS / LEAD_PARTS of its lead time: three quarters. */
    private const val LEAD_PARTS = 4L
    private const val HOLD_PARTS = 3L

    /** An alarm this far past its time has been lost (a reboot, an update) and is armed again. */
    private const val LOST_AFTER_MS = 20 * 60_000L

    /** A complication asked: it is on the face, so the chain should be running. */
    fun asked(
        context: Context,
        complicationId: Int,
    ) {
        val prefs = prefs(context)
        val ids = prefs.getStringSet(KEY_ACTIVE, emptySet()).orEmpty()
        prefs.edit {
            if (complicationId.toString() !in ids) putStringSet(KEY_ACTIVE, ids + complicationId.toString())
            putLong(KEY_ASKED_AT, System.currentTimeMillis())
        }
        ensure(context)
    }

    /** A complication left the face; the last one to leave stops the chain. */
    fun deactivated(
        context: Context,
        complicationId: Int,
    ) {
        val prefs = prefs(context)
        val ids = prefs.getStringSet(KEY_ACTIVE, emptySet()).orEmpty() - complicationId.toString()
        prefs.edit { putStringSet(KEY_ACTIVE, ids) }
        if (ids.isEmpty()) cancel(context)
    }

    /** Arms the chain if a complication is active and no alarm is pending. */
    fun ensure(context: Context) {
        if (!active(context)) return
        val next = prefs(context).getLong(KEY_NEXT_AT, 0L)
        val pending = pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null
        if (pending && System.currentTimeMillis() - next < LOST_AFTER_MS) return
        arm(context)
    }

    /** A reboot or an update may have dropped the alarm without a trace in the pending intents: arm it again. */
    fun restart(context: Context) {
        if (active(context)) arm(context)
    }

    /** The alarm fired: read, and arm the next one while a complication is still active. */
    internal fun fired(context: Context) {
        prefs(context).edit { remove(KEY_NEXT_AT) }
        if (active(context)) {
            // The read kicked here has not landed yet; the back-off follows the one before it.
            val away = PhoneStatusReader.failedAt(context) != 0L
            StatusRefresh.kick(context, force = false)
            arm(context, lead(if (away) AWAY_INTERVAL_MS else INTERVAL_MS))
        } else {
            cancel(context)
        }
    }

    /** The lead time to ask for so that lead + ¾ lead — the latest the system delivers — is [intervalMs]. */
    internal fun lead(intervalMs: Long): Long = intervalMs * LEAD_PARTS / (LEAD_PARTS + HOLD_PARTS)

    private fun arm(
        context: Context,
        leadMs: Long = lead(INTERVAL_MS),
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + leadMs
        val intent = pendingIntent(context, 0) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        prefs(context).edit { putLong(KEY_NEXT_AT, at) }
    }

    private fun cancel(context: Context) {
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pending)
            pending.cancel()
        }
        prefs(context).edit { remove(KEY_NEXT_AT) }
    }

    private fun active(context: Context): Boolean {
        val prefs = prefs(context)
        val askedAt = prefs.getLong(KEY_ASKED_AT, 0L)
        return prefs.getStringSet(KEY_ACTIVE, emptySet()).orEmpty().isNotEmpty() &&
            System.currentTimeMillis() - askedAt in 0..ASKED_WITHIN_MS
    }

    private fun pendingIntent(
        context: Context,
        flags: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StatusAlarmReceiver::class.java).setAction(ACTION_READ),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun prefs(context: Context) = context.getSharedPreferences("status_alarm", Context.MODE_PRIVATE)

    const val ACTION_READ = "app.getknit.knit.wear.READ"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ASKED_AT = "asked_at"
    private const val KEY_NEXT_AT = "next_at"
}

/** The alarm's tick, and the re-arm after a reboot or an update (both clear a pending alarm). */
class StatusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            StatusAlarm.ACTION_READ -> StatusAlarm.fired(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> StatusAlarm.restart(context)
        }
    }
}
