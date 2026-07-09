package app.getknit.knit.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.FullscreenImageViewer
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.verify.EncryptionSection
import app.getknit.knit.ui.verify.PeerVerification
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Read-only "contact details" view of another peer (keyed by [nodeId]): avatar, display name, live
 * online/offline state, free-text status, node id, and end-to-end key verification (safety number + QR
 * scan). Offers a Message action (accepts any pending request from this peer, then opens/starts a DM via
 * [onMessage]) and Block/Unblock in the overflow menu. Reached by tapping a peer's avatar in a chat, or
 * a sender's avatar in the Message Requests inbox.
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

    val scanLauncher =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { viewModel.onScanned(it) }
        }

    ProfileDetailsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        // Tapping Message accepts any pending request from this peer (idempotent) before opening the DM,
        // so answering a request from the sender's profile behaves like accepting it in the inbox.
        onMessage = { id ->
            viewModel.accept()
            onMessage(id)
        },
        onScan = {
            scanLauncher.launch(
                ScanOptions().setBeepEnabled(false).setOrientationLocked(false),
            )
        },
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        onMarkVerified = viewModel::markVerified,
        onClearVerification = viewModel::clearVerification,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileDetailsScreenContent(
    state: ProfileDetailsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onMessage: (nodeId: String) -> Unit,
    onScan: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onMarkVerified: () -> Unit,
    onClearVerification: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showAvatarFullscreen by remember { mutableStateOf(false) }

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
                                            if (state.isBlocked) {
                                                R.string.chat_action_unblock
                                            } else {
                                                R.string.chat_action_block
                                            },
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    if (state.isBlocked) onUnblock() else onBlock()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
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
                // Tappable only when a photo is set: a default (initials) avatar has nothing to enlarge,
                // so onClick stays null and Avatar renders non-interactive (no ripple / no touch target).
                contentDescription = if (state.avatarHash != null) state.displayName else null,
                onClickLabel = stringResource(R.string.profile_details_view_photo),
                onClick = if (state.avatarHash != null) ({ showAvatarFullscreen = true }) else null,
            )

            Text(
                text = state.displayName,
                style = MaterialTheme.typography.titleLarge,
            )

            // Live presence: a filled dot + label, matching the contact-list online indicator.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.online) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text =
                        stringResource(
                            if (state.online) {
                                R.string.profile_details_online
                            } else {
                                R.string.profile_details_offline
                            },
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
            EncryptionSection(
                myQrPayload = state.myQrPayload,
                peer =
                    PeerVerification(
                        displayName = state.displayName,
                        hasKey = state.hasKey,
                        verified = state.verified,
                        safetyNumber = state.safetyNumber,
                    ),
                onScan = onScan,
                onMarkVerified = onMarkVerified,
                onClearVerification = onClearVerification,
            )
        }
    }

    if (showAvatarFullscreen && state.avatarHash != null) {
        FullscreenImageViewer(
            model = BlobImage(state.avatarHash!!),
            contentDescription = stringResource(R.string.chat_image_viewer_desc),
            title = state.displayName,
            onDismiss = { showAvatarFullscreen = false },
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenOnlineVerifiedPreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "8f3a2b1c9d4e",
                    displayName = "Ada Lovelace",
                    status = "Hiking this weekend",
                    avatarHash = null,
                    online = true,
                    isBlocked = false,
                    hasKey = true,
                    verified = true,
                    safetyNumber = "12345 67890 12345 67890 12345 67890",
                    myQrPayload = "knit:verify:ada",
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenOfflinePreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "a1b2c3d4e5f6",
                    displayName = "Grace Hopper",
                    status = "",
                    avatarHash = null,
                    online = false,
                    isBlocked = false,
                    hasKey = true,
                    verified = false,
                    safetyNumber = "98765 43210 98765 43210 98765 43210",
                    myQrPayload = "knit:verify:grace",
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }

// Exercises the hasKey = false branch: no safety number / QR, just the "no key yet" notice.
@Preview(showBackground = true)
@Composable
fun ProfileDetailsScreenNoKeyPreview() =
    KnitPreview {
        ProfileDetailsScreenContent(
            state =
                ProfileDetailsUiState(
                    nodeId = "b2c3d4e5f6a1",
                    displayName = "Edsger Dijkstra",
                    status = "",
                    avatarHash = null,
                    online = false,
                    isBlocked = false,
                    hasKey = false,
                    verified = false,
                    safetyNumber = null,
                    myQrPayload = null,
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onMessage = {},
            onScan = {},
            onBlock = {},
            onUnblock = {},
            onMarkVerified = {},
            onClearVerification = {},
        )
    }
