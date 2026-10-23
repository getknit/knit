package app.getknit.knit.ui.addcontact

import android.content.ClipData
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.ButtonRow
import app.getknit.knit.ui.components.leftToRight
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.relay.RelayInviteSheet
import app.getknit.knit.ui.scan.QrScanner
import app.getknit.knit.ui.shareText
import app.getknit.knit.ui.verify.EncryptionSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * "Add contact": the one screen that turns someone into a contact, whichever way they are reachable
 * (docs/CONTACT_CARD.md). In person, it shows this device's identity QR and scans theirs — the old
 * standalone Verify-contact screen, which pins *and* verifies the key. At a distance, it hands out this
 * device's contact link and imports someone else's: pasted here, arriving from a tapped
 * `getknit.app/c` / `knit://c` link, or shared into Knit. A link import pins + accepts but never verifies,
 * so the safety number is shown before the user commits.
 *
 * Reached from the chat-list overflow, the New-message picker, and any tapped card link. An import lands
 * on the new contact's profile, where the intro's progress is shown; a scan stays put with a snackbar.
 */
@Composable
fun AddContactScreen(
    onBack: () -> Unit,
    onImported: (nodeId: String) -> Unit,
    viewModel: AddContactViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val myQrPayload by viewModel.myQrPayload.collectAsStateWithLifecycle()
    val relayInvite by viewModel.relayInvite.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val selfMessage = stringResource(R.string.verify_self)
    val mismatchMessage = stringResource(R.string.add_contact_mismatch)
    val invalidMessage = stringResource(R.string.add_contact_invalid)
    val addedMessage = stringResource(R.string.verify_added)
    val scanMismatchMessage = stringResource(R.string.verify_mismatch)
    val scanInvalidMessage = stringResource(R.string.verify_invalid)
    // Share / copy of the contact link. Android 13+ shows its own copy confirmation, so the snackbar only
    // fires below it (the CrashLogScreen idiom).
    val shareMessage = stringResource(R.string.contact_link_share_text)
    val shareTitle = stringResource(R.string.contact_link_chooser_title)
    val copiedMessage = stringResource(R.string.contact_link_copied)
    val clipLabel = stringResource(R.string.add_contact_clip_label)
    val relayAddedMessage = stringResource(R.string.relays_invite_applied)
    val relayRefusedMessage = stringResource(R.string.relays_invite_refused_url)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddContactEvent.Imported -> {
                    onImported(event.nodeId)
                }

                AddContactEvent.Self -> {
                    snackbarHostState.showSnackbar(selfMessage)
                }

                is AddContactEvent.Mismatch -> {
                    snackbarHostState.showSnackbar(mismatchMessage.format(event.displayName))
                }

                AddContactEvent.Invalid -> {
                    snackbarHostState.showSnackbar(invalidMessage)
                }

                is AddContactEvent.Scanned -> {
                    snackbarHostState.showSnackbar(
                        when (event.result) {
                            VerifyResult.VERIFIED -> addedMessage
                            VerifyResult.MISMATCH -> scanMismatchMessage
                            VerifyResult.SELF -> selfMessage
                            VerifyResult.INVALID -> scanInvalidMessage
                        },
                    )
                }

                is AddContactEvent.ShareLink -> {
                    shareText(context, shareMessage.format(event.url), shareTitle)
                }

                is AddContactEvent.CopyLink -> {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, event.url)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbarHostState.showSnackbar(copiedMessage)
                }

                is AddContactEvent.RelayAdded -> {
                    snackbarHostState.showSnackbar(relayAddedMessage.format(event.host))
                }

                AddContactEvent.RelayRefused -> {
                    snackbarHostState.showSnackbar(relayRefusedMessage)
                }
            }
        }
    }

    // The scanner takes over the whole screen rather than launching an Activity — see [QrScanner].
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScanner(
            onResult = {
                scanning = false
                viewModel.onScanned(it)
            },
            onCancel = { scanning = false },
        )
    } else {
        AddContactScreenContent(
            state = state,
            input = input,
            myQrPayload = myQrPayload,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onScan = { scanning = true },
            onShareLink = viewModel::shareLink,
            onCopyLink = viewModel::copyLink,
            onInputChange = viewModel::setInput,
            onPaste = {
                scope.launch {
                    val clip = clipboard.getClipEntry()?.clipData
                    val text = clip?.let { withContext(Dispatchers.IO) { clipText(it, context) } }
                    if (!text.isNullOrBlank()) {
                        viewModel.setInput(text)
                        viewModel.lookup()
                    }
                }
            },
            onLookup = viewModel::lookup,
            onConfirm = { unblock -> viewModel.confirm(unblock) },
            onAddRelay = viewModel::previewRelay,
            relayInvite = relayInvite,
            onConfirmRelay = viewModel::confirmRelay,
            onDismissRelay = viewModel::dismissRelay,
        )
    }
}

/**
 * The first non-blank text the clip holds. `coerceToText` needs a real [Context]: an item carrying a URI
 * (a copied image or file) is read through the content resolver, which a null context crashes on — and
 * that read is a stream open, so the caller runs this off the main thread.
 */
internal fun clipText(
    clip: ClipData,
    context: Context,
): String? =
    (0 until clip.itemCount)
        .asSequence()
        .mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
        .firstOrNull { it.isNotBlank() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddContactScreenContent(
    state: AddContactUiState,
    input: String,
    myQrPayload: String?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onShareLink: () -> Unit,
    onCopyLink: () -> Unit,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onLookup: () -> Unit,
    onConfirm: (unblock: Boolean) -> Unit,
    onAddRelay: (String) -> Unit = {},
    relayInvite: RelayInviteApplier.Preview? = null,
    onConfirmRelay: () -> Unit = {},
    onDismissRelay: () -> Unit = {},
) {
    relayInvite?.let { preview ->
        RelayInviteSheet(preview = preview, onConfirm = onConfirmRelay, onDismiss = onDismissRelay)
    }
    Scaffold(
        modifier = Modifier.testTag("screen_add_contact"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.add_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The in-person half: our code to be scanned, a scanner for theirs, and — for when the two of
            // you aren't in the same room — our contact link to send. Shares its layout with a peer's
            // profile, in its no-bound-peer (standalone) mode.
            EncryptionSection(
                myQrPayload = myQrPayload,
                peer = null,
                onScan = onScan,
                onShareLink = onShareLink,
                onCopyLink = onCopyLink,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.add_contact_link_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.add_contact_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.add_contact_input_label)) },
                placeholder = { Text(stringResource(R.string.add_contact_input_hint)) },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("add_contact_input").noAutofillMenu(),
            )
            ButtonRow {
                OutlinedButton(onClick = onPaste, modifier = Modifier.testTag("add_contact_paste")) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_contact_paste))
                }
                Button(
                    onClick = onLookup,
                    enabled = input.isNotBlank() && state !is AddContactUiState.Importing,
                    modifier = Modifier.testTag("add_contact_lookup"),
                ) {
                    Text(stringResource(R.string.add_contact_lookup))
                }
            }

            when (state) {
                is AddContactUiState.Preview -> PreviewCard(state.ready, onConfirm = onConfirm, onAddRelay = onAddRelay)
                AddContactUiState.Importing -> Text(stringResource(R.string.add_contact_importing))
                AddContactUiState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun PreviewCard(
    ready: ContactImporter.Preview.Ready,
    onConfirm: (unblock: Boolean) -> Unit,
    onAddRelay: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("add_contact_preview"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Avatar(avatarHash = null, name = ready.displayName, nodeId = ready.nodeId, size = 72.dp)
        Text(
            text = stringResource(R.string.add_contact_preview_title, ready.displayName),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.profile_alias, ready.alias),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.profile_node_id, ready.nodeId),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.verify_safety_number),
            style = MaterialTheme.typography.titleSmall,
        )
        Card {
            Text(
                text = ready.safetyNumber,
                style = MaterialTheme.typography.bodyLarge.leftToRight(),
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        Text(
            text = stringResource(R.string.add_contact_safety_caption, ready.displayName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val note =
            when {
                ready.blocked -> stringResource(R.string.add_contact_blocked)
                ready.alreadyContact -> stringResource(R.string.add_contact_already, ready.displayName)
                ready.relaysOff -> stringResource(R.string.add_contact_relays_off)
                else -> null
            }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // A relay the card names that this device lacks: one row per relay with an Add that goes through
        // the relay invite sheet — never applied silently (docs/RELAY_INVITE.md, ADR 042 as amended).
        if (ready.unknownRelays.isNotEmpty()) {
            Text(
                text = stringResource(R.string.add_contact_relay_hint, ready.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            for (url in ready.unknownRelays) {
                val host = SpoolUrl.host(url)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onAddRelay(url) },
                        modifier = Modifier.testTag("add_contact_relay_add_$host"),
                    ) {
                        Text(stringResource(R.string.add_contact_relay_add))
                    }
                }
            }
        }
        Button(
            onClick = { onConfirm(ready.blocked) },
            modifier = Modifier.fillMaxWidth().testTag("add_contact_confirm"),
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (ready.blocked) R.string.add_contact_confirm_unblock else R.string.add_contact_confirm))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AddContactScreenIdlePreview() =
    KnitPreview {
        AddContactScreenContent(
            state = AddContactUiState.Idle,
            input = "",
            myQrPayload = "knit-id:v1:me:bundle",
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onScan = {},
            onShareLink = {},
            onCopyLink = {},
            onInputChange = {},
            onPaste = {},
            onLookup = {},
            onConfirm = {},
        )
    }
