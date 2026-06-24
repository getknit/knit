package app.getknit.knit.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.isIgnoringBatteryOptimizations
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val nodeId by viewModel.nodeId.collectAsStateWithLifecycle()
    val alias by viewModel.alias.collectAsStateWithLifecycle()
    val avatarPath by viewModel.avatarPath.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::pickAvatar) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                avatarPath = avatarPath,
                name = displayNameFor(name, nodeId),
                size = 96.dp,
                background = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                textStyle = MaterialTheme.typography.displaySmall,
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )

            OutlinedTextField(
                value = name,
                onValueChange = viewModel::setDisplayName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Display name") },
                placeholder = { if (alias.isNotEmpty()) Text(alias) },
                singleLine = true,
            )
            OutlinedTextField(
                value = status,
                onValueChange = viewModel::setStatus,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Status") },
                singleLine = true,
            )
            Text(
                text = "Node ID: $nodeId",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BatteryOptimizationRow()
        }
    }
}

/** Shows whether the app is exempt from battery optimization, refreshing when the screen resumes. */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (exempt) {
                "Background battery use: allowed"
            } else {
                "Background battery use: restricted — the mesh may drop when idle"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!exempt) {
            TextButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                Text("Allow background battery use")
            }
        }
    }
}
