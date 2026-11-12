package app.getknit.knit.usbcontrol

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.getknit.knit.R

/**
 * The notice a pair exchange posts (debug builds only): "a computer calling itself … wants to pair", and a tap that
 * opens [UsbPairActivity], which shows the code. The code is never in the notice, and its visibility is private, so a
 * locked phone shows neither the name nor the code until it is unlocked. Follows [PendingPairs]: posted when one is
 * held, gone when it ends.
 */
internal object UsbPairNotice {
    /** Clear of MeshService's and the notifiers' 1-11. */
    private const val ID = 7_701
    private const val CHANNEL = "knit_usb_pair"

    fun update(
        context: Context,
        pair: PendingPair?,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (pair == null) {
            manager.cancel(ID)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.usbctl_notice_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, UsbPairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val public =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(context.getString(R.string.usbctl_notice_public))
                .build()
        val notice =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle(context.getString(R.string.usbctl_notice_title))
                .setContentText(context.getString(R.string.usbctl_notice_text, pair.name))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setTimeoutAfter((pair.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                .setOnlyAlertOnce(true)
                .build()
        runCatching { manager.notify(ID, notice) }
    }
}
