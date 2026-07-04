package app.getknit.knit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the mesh needs across both transport planes. Wi-Fi Aware on API 33+ needs **no
 * location** — `NEARBY_WIFI_DEVICES` carries `neverForLocation` — and neither does the Bluetooth LE plane,
 * whose `BLUETOOTH_SCAN` also carries `neverForLocation` (identity is the nodeId, RSSI is proximity-only). A
 * device missing either radio is handled at runtime (the transport self-degrades, and the composite runs the
 * plane that is present), so requesting all four here is safe — an unsupported permission is simply a grant
 * the app never exercises.
 */
fun requiredMeshPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

fun hasAllMeshPermissions(context: Context): Boolean =
    requiredMeshPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Whether this device has the Wi-Fi Aware radio the primary mesh plane runs on. False on hardware lacking
 * [PackageManager.FEATURE_WIFI_AWARE] (some budget/older and certain Samsung models).
 */
fun hasWifiAwareHardware(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

/**
 * Whether this device has the Bluetooth LE radio the secondary mesh plane runs on. Together with
 * [hasWifiAwareHardware] this gates the "can this device mesh at all?" onboarding state: a device with
 * **either** radio can participate (the composite runs whichever plane is present).
 */
fun hasBleHardware(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
