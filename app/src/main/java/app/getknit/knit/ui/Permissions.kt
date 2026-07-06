package app.getknit.knit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the mesh needs across both transport planes, **tiered by API level** — the permission
 * model changed twice under us, so a single static list would wedge onboarding below API 33 (an ungrantable
 * permission would keep [hasAllMeshPermissions] false forever):
 *
 * - **API 33+** — location-free. Wi-Fi Aware discovery rides `NEARBY_WIFI_DEVICES` (`neverForLocation`) and
 *   BLE rides the split `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` (`BLUETOOTH_SCAN` also `neverForLocation`;
 *   identity is the nodeId, RSSI is proximity-only). `POST_NOTIFICATIONS` is a runtime permission here too.
 * - **API 31-32** — the split BLE permissions exist, but Wi-Fi Aware discovery has no `NEARBY_WIFI_DEVICES`
 *   yet, so it needs `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (Android 12 requires the pair — the
 *   precise/approximate toggle; there is no `neverForLocation` escape before API 31). Neither
 *   `NEARBY_WIFI_DEVICES` nor `POST_NOTIFICATIONS` is a runtime permission pre-33.
 * - **API 29-30** — no split BLE permissions; BLE scan *and* Wi-Fi Aware discovery both need fine location
 *   (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`). Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` are install-time
 *   (normal) permissions auto-granted from the manifest, so they are not requested here.
 *
 * A device missing either radio is handled at runtime (the transport self-degrades and the composite runs
 * whichever plane is present), so an unused permission on a given device is simply a grant never exercised.
 * [sdkInt] is injectable (default [Build.VERSION.SDK_INT]) so the tiering is a pure, unit-testable function.
 */
fun requiredMeshPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        sdkInt >= Build.VERSION_CODES.S -> {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

        else -> {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }

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
