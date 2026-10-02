package app.getknit.knit.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.getknit.knit.MainActivity
import app.getknit.knit.R

/**
 * The one notification for a mesh that could not start because this phone's Keystore refused to unwrap its
 * storage key: `MeshService` posts it as it stands down instead of crashing, and takes it back the next time
 * the mesh starts. A tap opens the app, whose own open retries (`ui/StorageGate`). On the [NotificationChannels.ALERTS]
 * channel, which is low importance on purpose: nothing is lost, and the next open usually just works.
 * ADR 2026-10.47rw.
 *
 * Built without the Koin graph — the graph is exactly what failed to build — so it takes a bare [Context].
 */
object StorageAlert {
    /** Ids 1-10 are MeshService's and [MessageNotifier]'s. */
    const val ID = 11

    fun post(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        // Inlined at the notify call: lint's flow analysis only sees the guard on the direct path (see MessageNotifier).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.ALERTS)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(context.getString(R.string.storage_alert_title))
                .setContentText(context.getString(R.string.storage_alert_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.storage_alert_text)))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
