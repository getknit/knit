package app.getknit.knit.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.getknit.knit.wear.ui.KnitWearTheme
import app.getknit.knit.wear.ui.StatusScreen

/**
 * The watch app: one scrolling status screen ([StatusScreen]) over the phone's snapshot. Also where the API 31+
 * `BLUETOOTH_CONNECT` grant is asked for (a complication or tile service cannot ask); on Wear OS 3 the
 * permissions are install-time and nothing is asked. It opens on the launcher icon over black
 * (`Theme.Knit.Starting`, the compat splash screen on every API level — WO-V15).
 */
class StatusActivity : ComponentActivity() {
    private var granted by mutableStateOf(true)

    private val askConnect =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            noteGrant(ok)
            if (ok) StatusRefresh.kick(this, force = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        granted = BluetoothGrant.held(this)
        if (!granted) askForConnect()
        setContent {
            KnitWearTheme {
                StatusScreen(
                    granted = granted,
                    onAllow = ::askForConnect,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The grant can change in Settings while the app is away.
        noteGrant(BluetoothGrant.held(this))
    }

    private fun noteGrant(now: Boolean) {
        if (now == granted) return
        granted = now
        BluetoothGrant.changed(this)
    }

    private fun askForConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) askConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
