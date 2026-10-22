package app.getknit.knit.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import app.getknit.knit.ui.camera.openAppSettings

/**
 * Whether an incoming call may ring full screen over the lock screen (Android 14+'s "Allow full screen intents"),
 * or `null` where there is nothing to show: a build without calls declares no `USE_FULL_SCREEN_INTENT` at all, and
 * below Android 14 it is an install-time grant every build that declares it holds. From 14 a sideloaded build starts
 * without it and no prompt of ours can grant it; the person turns it on, on the page [openFullScreenCallSettings]
 * opens. Read on demand, never remembered: the person's answer lives in the platform, and only the platform's word
 * says whether the next call rings full screen.
 */
fun fullScreenCalls(
    context: Context,
    callsWired: Boolean,
): Boolean? {
    if (!callsWired || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
}

/**
 * The system page that holds Knit's full-screen switch, or app info below Android 14 (where there is no such page). For
 * a notification's tap, which cannot fall back once sent; [openFullScreenCallSettings] for a button.
 */
fun fullScreenCallSettingsIntent(context: Context): Intent =
    Intent(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        },
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens the page that holds Knit's full-screen switch, or app info where a vendor dropped that page. */
fun openFullScreenCallSettings(context: Context) {
    runCatching { context.startActivity(fullScreenCallSettingsIntent(context)) }.onFailure { openAppSettings(context) }
}
