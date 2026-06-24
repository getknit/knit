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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.hasAllMeshPermissions
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import app.getknit.knit.ui.requiredMeshPermissions

/**
 * First-run gate: explains why the mesh needs nearby/Bluetooth permissions and (optionally) battery
 * exemption, requests them, then hands off to the chat once permissions are granted. Like the legacy
 * app, the mesh can start even if some permissions were denied — it just degrades.
 */
@Composable
fun OnboardingScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllMeshPermissions(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasAllMeshPermissions(context)
    }

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

        Button(
            onClick = { launcher.launch(requiredMeshPermissions()) },
            modifier = Modifier.fillMaxWidth(),
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
                .padding(top = 24.dp),
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}
