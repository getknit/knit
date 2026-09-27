package app.getknit.knit.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.getknit.knit.wear.ui.KnitWearTheme
import app.getknit.knit.wear.ui.StatusScreen

/**
 * The watch app: one scrolling status screen ([StatusScreen]) over the phone's snapshot. Also where the API 31+
 * `BLUETOOTH_CONNECT` grant is asked for (a complication or tile service cannot ask); on Wear OS 3 the
 * permissions are install-time and nothing is asked.
 */
class StatusActivity : ComponentActivity() {
    private var granted by mutableStateOf(true)

    private val askConnect =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            if (ok) StatusRefresh.kick(this, force = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granted = hasConnect()
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
        granted = hasConnect()
    }

    private fun askForConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) askConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun hasConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
