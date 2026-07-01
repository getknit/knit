package app.getknit.knit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the Wi-Fi Aware mesh needs. Unlike the old Nearby transport, Wi-Fi Aware on
 * API 33+ needs **no location** — `NEARBY_WIFI_DEVICES` carries the `neverForLocation` flag (see the
 * manifest) — and no Bluetooth. Just nearby-Wi-Fi discovery and notifications.
 */
fun requiredMeshPermissions(): Array<String> = arrayOf(
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.POST_NOTIFICATIONS,
)

fun hasAllMeshPermissions(context: Context): Boolean =
    requiredMeshPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Whether this device has the Wi-Fi Aware radio the mesh runs on. False on hardware lacking
 * [PackageManager.FEATURE_WIFI_AWARE] (some budget/older and certain Samsung models); since there is no
 * fallback transport, the UI surfaces a clear "can't mesh on this device" state rather than an empty
 * neighbor list.
 */
fun hasWifiAwareHardware(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
