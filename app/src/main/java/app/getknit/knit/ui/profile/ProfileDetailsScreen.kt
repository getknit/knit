package app.getknit.knit.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.image.QrCode
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Read-only "contact details" view of another peer (keyed by [nodeId]): avatar, display name, live
 * online/offline state, free-text status, node id, and end-to-end key verification (safety number + QR
 * scan). Offers a Message action (open/start a DM via [onMessage]) and Block/Unblock in the overflow
 * menu. Reached by tapping a peer's avatar in a chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailsScreen(
    nodeId: String,
    onBack: () -> Unit,
    onMessage: (nodeId: String) -> Unit,
    viewModel: ProfileDetailsViewModel = koinViewModel { parametersOf(nodeId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val matchMessage = stringResource(R.string.verify_match)
    val mismatchMessage = stringResource(R.string.verify_mismatch)
    LaunchedEffect(scanResult) {
        when (scanResult) {
            VerifyScanResult.MATCH -> snackbarHostState.showSnackbar(matchMessage)
            VerifyScanResult.MISMATCH -> snackbarHostState.showSnackbar(mismatchMessage)
            null -> Unit
        }
        if (scanResult != null) viewModel.consumeScanResult()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onScanned(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.chat_more_options),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.isBlocked) R.string.chat_action_unblock
                                            else R.string.chat_action_block,
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    if (state.isBlocked) viewModel.unblock() else viewModel.block()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                avatarHash = state.avatarHash,
                name = state.displayName,
                size = 96.dp,
                background = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                textStyle = MaterialTheme.typography.displaySmall,
            )

            Text(
                text = state.displayName,
                style = MaterialTheme.typography.titleLarge,
            )

            // Live presence: a filled dot + label, matching the contact-list online indicator.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.online) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (state.online) R.string.profile_details_online
                        else R.string.profile_details_offline,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.status.isNotBlank()) {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = stringResource(R.string.profile_node_id, state.nodeId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledIconButton(onClick = { onMessage(state.nodeId) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Message,
                        contentDescription = stringResource(R.string.profile_details_message),
                    )
                }
                Text(
                    text = stringResource(R.string.profile_details_message),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            HorizontalDivider()
            VerificationSection(
                state = state,
                onScan = {
                    scanLauncher.launch(
                        ScanOptions().setBeepEnabled(false).setOrientationLocked(false),
                    )
                },
                onMarkVerified = viewModel::markVerified,
                onClearVerification = viewModel::clearVerification,
            )
        }
    }
}

/** The end-to-end key-verification block: status badge, safety number, our QR, and verify actions. */
@Composable
private fun VerificationSection(
    state: ProfileDetailsUiState,
    onScan: () -> Unit,
    onMarkVerified: () -> Unit,
    onClearVerification: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.verify_section_title),
            style = MaterialTheme.typography.titleMedium,
        )

        if (!state.hasKey) {
            Text(
                text = stringResource(R.string.verify_no_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        // Verified / not-verified badge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (state.verified) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (state.verified) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (state.verified) R.string.verify_verified else R.string.verify_not_verified,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        state.safetyNumber?.let { number ->
            Card {
                Text(
                    text = number,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.verify_caption, state.displayName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        state.myQrPayload?.let { payload ->
            val qr = remember(payload) { QrCode.render(payload, QR_SIZE_PX) }
            qr?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.size(200.dp),
                )
            }
            Text(
                text = stringResource(R.string.verify_qr_caption, state.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.verify_scan))
        }
        if (state.verified) {
            OutlinedButton(onClick = onClearVerification, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_clear))
            }
        } else {
            OutlinedButton(onClick = onMarkVerified, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_mark_verified))
            }
        }
    }
}

private const val QR_SIZE_PX = 480
