package app.getknit.knit.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * The Internet relays screen (`docs/SPOOL_PROTOCOL.md`): one global switch, the relay list, and each
 * relay's live health.
 *
 * This screen is what makes the switch shippable in a release build. The app seeds a default relay
 * (`res/values/spools.xml`), so without an editor a user could turn the plane on but never point it
 * somewhere else or remove it — which is why the switch was `BuildConfig.DEBUG`-gated until this
 * existed.
 */
@Composable
fun InternetRelayScreen(
    onBack: () -> Unit,
    onOpenRelay: (String) -> Unit,
    viewModel: InternetRelayViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showConsent by viewModel.showConsent.collectAsStateWithLifecycle()
    val invitePreview by viewModel.invitePreview.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Share / copy of a relay invite, and the sheet's outcomes.
    RelayEventsEffect(viewModel.events, snackbarHostState)

    InternetRelayScreenContent(
        state = state,
        showConsent = showConsent,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onToggle = viewModel::onToggle,
        onAcceptConsent = viewModel::acceptConsent,
        onDismissConsent = viewModel::dismissConsent,
        onAddRelay = viewModel::addRelay,
        onOpenRelay = onOpenRelay,
        onSetRelayEnabled = viewModel::setRelayEnabled,
        isValidUrl = viewModel::isValidUrl,
        invitePreview = invitePreview,
        onConfirmInvite = viewModel::confirmInvite,
        onDismissInvite = viewModel::dismissInvite,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod") // one screen's wiring: the list, its dialog and two sheets, mirrored in one place
@Composable
internal fun InternetRelayScreenContent(
    state: InternetRelayUiState,
    showConsent: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAcceptConsent: () -> Unit = {},
    onDismissConsent: () -> Unit = {},
    onAddRelay: (String) -> Unit = {},
    onOpenRelay: (String) -> Unit = {},
    onSetRelayEnabled: (String, Boolean) -> Unit = { _, _ -> },
    isValidUrl: (String) -> Boolean = { it.startsWith("wss://") },
    invitePreview: RelayInviteApplier.Preview? = null,
    onConfirmInvite: () -> Unit = {},
    onDismissInvite: () -> Unit = {},
) {
    var addDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.testTag("screen_internet_relays"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { BarTitle(stringResource(R.string.relays_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MasterSwitchRow(enabled = state.enabled, onToggle = onToggle)

            Text(
                text = stringResource(R.string.relays_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.relays_section_list),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (state.relays.isEmpty()) {
                Text(
                    text = stringResource(R.string.relays_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("relays_empty"),
                )
            } else {
                state.relays.forEach { relay ->
                    RelayListRow(
                        relay = relay,
                        planeEnabled = state.enabled,
                        offline = state.offline,
                        onOpen = { onOpenRelay(relay.url) },
                        onSetEnabled = { onSetRelayEnabled(relay.url, it) },
                    )
                }
            }

            TextButton(
                onClick = { addDialogOpen = true },
                modifier = Modifier.testTag("relays_add"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.relays_add))
            }
        }
    }

    if (addDialogOpen) {
        AddRelayDialog(
            existing = state.relays.map { it.url }.toSet(),
            isValidUrl = isValidUrl,
            onAdd = {
                onAddRelay(it)
                addDialogOpen = false
            },
            onDismiss = { addDialogOpen = false },
        )
    }

    if (showConsent) {
        ModalBottomSheet(
            onDismissRequest = onDismissConsent,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            RelayConsentBody(onAccept = onAcceptConsent, onDecline = onDismissConsent)
        }
    }

    invitePreview?.let { preview ->
        RelayInviteSheet(preview = preview, onConfirm = onConfirmInvite, onDismiss = onDismissInvite)
    }
}

/**
 * The master switch. Same shape as the Profile screen's toggle rows: the row owns the `toggleable` and
 * the `Switch` takes a null handler, so a screen reader announces one labelled switch rather than an
 * unlabelled control next to some text.
 */
@Composable
private fun MasterSwitchRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .testTag("relays_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_internet_relays_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_internet_relays_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * One relay: a status dot, its host, a second line saying what it is doing for us, its own switch, and a
 * chevron. Everything else the relay offers — calls, its ticket and key, free tickets, its room, the invite
 * and the way to remove it — is on its own screen ([RelayDetailScreen]), which a tap on the row opens.
 *
 * The host is [app.getknit.knit.mesh.spool.SpoolUrl.host]-derived, never the raw URL — a private relay
 * carries its bearer token in the query string, and that token is the whole access control, so it must
 * not survive into a screenshot or a bug report.
 *
 * The row is `clickable` and the [Switch] inside it carries its own `toggleable` and label. A toggleable node
 * merges its own semantics, so it stays apart from the row's: a screen reader gets one button that opens the
 * relay and one labelled switch per relay, never one control that does two things.
 */
@Composable
private fun RelayListRow(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
    onOpen: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    val switchLabel = stringResource(R.string.relays_switch_desc, relay.host)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.relays_open_details), role = Role.Button, onClick = onOpen)
                .testTag("relay_row_${relay.host}")
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier.size(10.dp).clip(CircleShape).background(relayDotColor(relay, planeEnabled, offline)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relay.host,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relayStatusLine(relay, planeEnabled, offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A relay that carries frames but no photos is worth calling out here rather than leaving
            // the user to discover it one un-relayed photo at a time.
            if (relay.carriesPhotos == false) {
                Text(
                    text = stringResource(R.string.relays_no_photos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = relay.enabled,
            onCheckedChange = onSetEnabled,
            enabled = planeEnabled,
            modifier = Modifier.testTag("relay_switch_${relay.host}").semantics { contentDescription = switchLabel },
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun AddRelayDialog(
    existing: Set<String>,
    isValidUrl: (String) -> Boolean,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local state, never bound to the DataStore flow: a field fed by an async write races the user's
    // keystrokes (see the rule in .agents/rules/coding.md).
    var url by remember { mutableStateOf("") }
    val trimmed = url.trim()
    val duplicate = trimmed in existing
    val malformed = trimmed.isNotEmpty() && !isValidUrl(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relays_add_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                isError = malformed || duplicate,
                label = { Text(stringResource(R.string.relays_add_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    when {
                        duplicate -> Text(stringResource(R.string.relays_add_duplicate))
                        malformed -> Text(stringResource(R.string.relays_add_invalid))
                        else -> Text(stringResource(R.string.relays_add_hint))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("relays_add_field").noAutofillMenu(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(trimmed) },
                enabled = trimmed.isNotEmpty() && !malformed && !duplicate,
            ) {
                Text(stringResource(R.string.relays_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
fun InternetRelayScreenOnPreview() =
    KnitPreview {
        InternetRelayScreenContent(
            state =
                InternetRelayUiState(
                    enabled = true,
                    relays =
                        listOf(
                            RelayRow(
                                url = "wss://public-1.spool.getknit.app/spool/v1",
                                host = "public-1.spool.getknit.app",
                                enabled = true,
                                connected = true,
                                scopeCount = 3,
                                carriesPhotos = true,
                            ),
                            RelayRow(
                                url = "wss://frames.example.org/spool/v1",
                                host = "frames.example.org",
                                enabled = true,
                                connected = true,
                                scopeCount = 3,
                                carriesPhotos = false,
                            ),
                            RelayRow(
                                url = "wss://parked.example.org/spool/v1",
                                host = "parked.example.org",
                                enabled = false,
                                connected = false,
                            ),
                            RelayRow(
                                url = "wss://down.example.org/spool/v1",
                                host = "down.example.org",
                                enabled = true,
                                connected = false,
                                lastError = "unreachable",
                            ),
                        ),
                ),
            onBack = {},
            onToggle = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun InternetRelayScreenEmptyPreview() =
    KnitPreview {
        InternetRelayScreenContent(
            state = InternetRelayUiState(enabled = false),
            onBack = {},
            onToggle = {},
        )
    }
