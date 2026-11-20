package app.getknit.knit.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.notifications.NotificationChannels

/**
 * What the new build does when it replaced the old one (`MY_PACKAGE_REPLACED`, ADR 2026-11.29m7). Every staged APK is
 * gone, landed or superseded. When this phone's own peer update put it here, named by the [PendingInstall] the install
 * left for this version within [RELAUNCH_WINDOW_MS], the person tapped "Install and restart" moments ago, so Knit comes
 * back: an activity start where the platform allows one, and always a notification, since a receiver's start is
 * refused in the background on most phones. Never a full-screen intent or an overlay. A store's update finds no such
 * record and brings nothing back.
 */
object UpdateLanded {
    const val RELAUNCH_WINDOW_MS = 10 * 60_000L

    private const val ID_UPDATED = 13
    private const val TAG_UPDATED = "updated"

    /** Whether the update that just landed is the peer update [pending] recorded for [versionCode]. Pure. */
    fun ours(
        pending: PendingInstall?,
        versionCode: Long,
        now: Long,
    ): Boolean = pending != null && pending.versionCode == versionCode && now - pending.at in 0..RELAUNCH_WINDOW_MS

    fun onReplaced(
        context: Context,
        staging: UpdateStaging,
        now: Long = System.currentTimeMillis(),
    ) {
        val pending = staging.readPending()
        staging.clearAll()
        if (!ours(pending, BuildConfig.VERSION_CODE.toLong(), now)) return
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        runCatching { context.startActivity(launch) }
        notifyUpdated(context, launch)
    }

    @SuppressLint("MissingPermission") // Guarded: a phone that refused notifications simply gets none.
    private fun notifyUpdated(
        context: Context,
        launch: Intent,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, ID_UPDATED, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.ALERTS)
                .setSmallIcon(R.drawable.ic_direct_transfer)
                .setContentTitle(context.getString(R.string.update_landed_title, BuildConfig.VERSION_NAME))
                .setContentText(context.getString(R.string.update_landed_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(TAG_UPDATED, ID_UPDATED, notification) }
    }
}
