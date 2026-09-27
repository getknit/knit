package app.getknit.knit.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearStatus
import kotlinx.coroutines.launch

/**
 * Every field of the last snapshot, its age, and a Refresh that reads the phone now and re-queries the
 * complications. Also where the API 31+ `BLUETOOTH_CONNECT` grant is asked for (a complication service cannot
 * ask); on Wear OS 3 the permissions are install-time and nothing is asked.
 */
class StatusActivity : ComponentActivity() {
    private val askConnect =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            askConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        setContent { MaterialTheme { StatusScreen() } }
    }

    @Composable
    private fun StatusScreen() {
        var snapshot by remember { mutableStateOf(PhoneStatusReader.cached(this)) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val refresh: () -> Unit = {
            if (!busy) {
                busy = true
                scope.launch {
                    snapshot = PhoneStatusReader.read(this@StatusActivity, maxAgeMs = 0)
                    StatusComplicationService.requestUpdateAll(this@StatusActivity)
                    busy = false
                }
            }
        }
        LaunchedEffect(Unit) { refresh() }
        val now = System.currentTimeMillis()
        val status = StatusText.fresh(snapshot, now)
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Knit mesh") } }
            for (line in lines(status, snapshot, now)) {
                item { Text(line, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
            item {
                Button(onClick = refresh, enabled = !busy) {
                    Text(if (busy) "Reading…" else "Refresh")
                }
            }
        }
    }

    /** One glyph per plane state, so a two-plane line fits the round screen. */
    private fun mark(plane: Plane): String =
        when (plane) {
            Plane.Live -> "●"
            Plane.Degraded -> "◐"
            Plane.Down -> "○"
            Plane.Absent -> "–"
        }

    private fun lines(
        status: WearStatus?,
        snapshot: Snapshot?,
        now: Long,
    ): List<String> {
        if (status == null) {
            return listOf(
                if (snapshot == null) "No reading yet" else "Last reading too old",
                "Is Knit running on the phone?",
            )
        }
        val age = ((now - snapshot!!.fetchedAtMs) / 1_000).coerceAtLeast(0)
        return listOf(
            StatusText.word(status.state),
            "${status.nearby} nearby",
            "BLE ${mark(status.ble)}  NAN ${mark(status.nan)}",
            "LoRa ${mark(status.lora)}  Spool ${mark(status.spool)}",
            "${StatusText.compact(status.relayed)} relayed",
            "read ${age}s ago",
        )
    }
}
