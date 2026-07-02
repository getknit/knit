package app.getknit.knit.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.hasAllMeshPermissions
import app.getknit.knit.ui.hasBleHardware
import app.getknit.knit.ui.hasWifiAwareHardware
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import app.getknit.knit.ui.requiredMeshPermissions

/**
 * First-run gate: explains why the mesh needs its nearby-Wi-Fi + Bluetooth + notification permissions
 * and (optionally) battery exemption, requests them, then hands off to the chat once granted. The mesh
 * can start even if a permission was denied — it just degrades. Only on hardware with **neither** Wi-Fi
 * Aware nor Bluetooth LE does it show a clear "unsupported" notice (but still lets the user in).
 */
@Composable
fun OnboardingScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    // The mesh runs on either radio plane, so a device with Wi-Fi Aware OR Bluetooth LE can participate.
    val meshSupported = remember { hasWifiAwareHardware(context) || hasBleHardware(context) }
    var granted by remember { mutableStateOf(hasAllMeshPermissions(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasAllMeshPermissions(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_blurb),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            if (!meshSupported) {
                Text(
                    text = stringResource(R.string.onboarding_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Button(
                onClick = { launcher.launch(requiredMeshPermissions()) },
                modifier = Modifier.fillMaxWidth().testTag("onboarding_grant"),
            ) {
                Text(
                    if (granted) {
                        stringResource(R.string.onboarding_permissions_granted)
                    } else {
                        stringResource(R.string.onboarding_grant_permissions)
                    },
                )
            }

            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimizations(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.battery_allow_button))
            }

            Button(
                onClick = onReady,
                enabled = granted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .testTag("onboarding_start"),
            ) {
                Text(stringResource(R.string.onboarding_start))
            }
        }
    }
}
