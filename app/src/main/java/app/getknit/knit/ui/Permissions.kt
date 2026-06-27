package app.getknit.knit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions Nearby Connections needs, varying by SDK level. */
fun requiredMeshPermissions(): Array<String> = buildList {
    // Nearby Connections discovery requires location on every supported API level (neverForLocation
    // does not exempt it), so always request it — see the note in AndroidManifest.xml.
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

fun hasAllMeshPermissions(context: Context): Boolean =
    requiredMeshPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Whether "Allow all the time" (background) location is granted. Nearby discovery keeps scanning with
 * the screen off only when [MeshService] can reach location while backgrounded, which needs this on
 * top of foreground location — without it a backgrounded device stops discovering (error 8034) and
 * can only be found, never initiate. Request it *separately and after* [requiredMeshPermissions] are
 * granted: the system only offers "all the time" once foreground location is held, and silently
 * denies it if bundled into the same request on API 30+.
 */
fun hasBackgroundLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
