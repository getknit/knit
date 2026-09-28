package app.getknit.knit.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The one grant a read of the phone needs: `BLUETOOTH_CONNECT` on API 31+ (runtime, asked for only by
 * [StatusActivity] — a complication or tile service cannot ask), install-time on Wear OS 3.
 *
 * Without it no surface starts a read, and each says so in its own way — the tile and the complications as
 * "Allow" with a tap into the app, the app as its Allow card — rather than "Phone out of reach", which would send
 * the wearer to the wrong device (the quality guidelines' "signed out" state, WO-V9).
 */
object BluetoothGrant {
    fun held(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Redraws every surface after the grant changed, whether or not a read follows. */
    fun changed(context: Context) {
        StatusComplicationService.requestUpdateAll(context)
        StatusRefresh.requestTileUpdate(context)
    }
}
