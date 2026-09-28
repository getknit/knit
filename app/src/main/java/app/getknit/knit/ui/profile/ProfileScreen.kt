// The file's single top-level class (ProfileFormState, the content composable's state holder) rides
// along with the screen composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.profile

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.CharCounter
import app.getknit.knit.ui.components.DetailCard
import app.getknit.knit.ui.components.DetailRow
import app.getknit.knit.ui.components.DisplayNameField
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.preview.KnitPreview
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** UI-local projection of [ProfileViewModel]'s per-field flows for the stateless content. */
internal data class ProfileFormState(
    val name: String,
    val status: String,
    val nodeId: String,
    val alias: String,
    val aliasMore: String = "",
    val avatarHash: String?,
    val openToChat: Boolean = false,
    val isDirty: Boolean,
)

/**
 * Your own profile: the photo, name, status and availability that peers see. Reached from the header row
 * at the top of Settings; app settings themselves live there, not here.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val nodeId by viewModel.nodeId.collectAsStateWithLifecycle()
    val alias by viewModel.alias.collectAsStateWithLifecycle()
    val aliasMore by viewModel.aliasMore.collectAsStateWithLifecycle()
    val avatarHash by viewModel.avatarHash.collectAsStateWithLifecycle()
    val cropTarget by viewModel.cropTarget.collectAsStateWithLifecycle()
    val openToChat by viewModel.openToChat.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()

    // Navigate back only once Save has finished persisting (the write outlives this composition because
    // it runs in viewModelScope, but we wait so the user lands back on the previous screen on success).
    LaunchedEffect(Unit) {
        viewModel.saved.collect { onBack() }
    }

    // The photo persists the moment the crop is confirmed, while Save stays greyed out (it batches only the
    // name and status) — so the change says so itself, or it reads as unsaved (#26).
    val snackbarHostState = remember { SnackbarHostState() }
    val photoUpdated = stringResource(R.string.profile_photo_updated)
    val photoRemoved = stringResource(R.string.profile_photo_removed)
    LaunchedEffect(Unit) {
        viewModel.photoChanged.collect { change ->
            val message =
                when (change) {
                    PhotoChange.UPDATED -> photoUpdated
                    PhotoChange.REMOVED -> photoRemoved
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri -> uri?.let(viewModel::pickAvatar) }

    cropTarget?.let { bmp ->
        val image = remember(bmp) { bmp.asImageBitmap() }
        AvatarCropDialog(
            bitmap = image,
            onCancel = viewModel::cancelCrop,
            onConfirm = viewModel::confirmCrop,
        )
    }

    ProfileScreenContent(
        form =
            ProfileFormState(
                name = name,
                status = status,
                nodeId = nodeId,
                alias = alias,
                aliasMore = aliasMore,
                avatarHash = avatarHash,
                openToChat = openToChat,
                isDirty = isDirty,
            ),
        onBack = onBack,
        onNameChange = viewModel::setDisplayName,
        onNameCommit = viewModel::commitDisplayName,
        onStatusChange = viewModel::setStatus,
        onStatusCommit = viewModel::commitStatus,
        onToggleOpenToChat = viewModel::setOpenToChat,
        onPickPhoto = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onClearPhoto = viewModel::clearAvatar,
        onSave = viewModel::save,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreenContent(
    form: ProfileFormState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onStatusChange: (String) -> Unit,
    onStatusCommit: () -> Unit,
    onToggleOpenToChat: (Boolean) -> Unit = {},
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onSave: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Android 13+ shows its own copy confirmation, so the snackbar only fires below it (the CrashLogScreen
    // idiom, also followed by Add contact).
    val copiedMessage = stringResource(R.string.action_copied)
    val nodeIdLabel = stringResource(R.string.profile_node_id_label)

    Scaffold(
        modifier = Modifier.testTag("screen_profile"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Save belongs here, not at the foot of the column. It batches the name/status write
                    // that republishes the profile, while everything below it on this screen persists on
                    // toggle — and a button sitting under a switch reads as though it saves that too
                    // (ADR 2026-09.m7vn left this as the open item). Nothing follows the switch now, so
                    // nothing can imply it. The explicit height is the accessibility touch target:
                    // Material's text button is 40dp, which the ATF suite flags.
                    TextButton(
                        onClick = onSave,
                        enabled = form.isDirty,
                        modifier = Modifier.height(48.dp).testTag("profile_save"),
                    ) {
                        Text(stringResource(R.string.action_save))
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
                    .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The photo is the one thing here that isn't a field or a row, so it keeps the centred hero
            // position. Everything under it is left-aligned like every other list in the app, and the
            // horizontal inset moves onto the children so the section headings line up with their rows.
            Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Avatar(
                    avatarHash = form.avatarHash,
                    name = displayNameFor(form.name, form.nodeId),
                    nodeId = form.nodeId,
                    size = 96.dp,
                    textStyle = MaterialTheme.typography.displaySmall,
                    contentDescription = stringResource(R.string.profile_change_photo_desc),
                    onClick = onPickPhoto,
                )
                // Only offer "remove" when a photo is set. This also covers a dangling hash whose blob is
                // gone (the avatar shows the initial fallback, but the hash is still non-null), giving the
                // user a way to drop it.
                if (form.avatarHash != null) {
                    RemovePhotoButton(onClick = onClearPhoto)
                }
            }

            SectionHeader(stringResource(R.string.profile_section_public))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Shared with onboarding's name page (ui/components/DisplayNameField) so the two fields
                // can't drift. It renders the alias as supporting text, which is why no alias row repeats
                // it below — two copies would make TalkBack say the same word twice.
                DisplayNameField(
                    value = form.name,
                    alias = form.alias,
                    aliasMore = form.aliasMore,
                    onValueChange = onNameChange,
                    onCommit = onNameCommit,
                    modifier = Modifier.fillMaxWidth().testTag("profile_name"),
                    aliasLineModifier = Modifier.testTag("profile_alias"),
                )
                OutlinedTextField(
                    value = form.status,
                    onValueChange = onStatusChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("profile_status")
                            .noAutofillMenu()
                            .onFocusChanged { if (!it.isFocused) onStatusCommit() },
                    label = { Text(stringResource(R.string.profile_status_label)) },
                    singleLine = true,
                    supportingText = { CharCounter(form.status.length, TextLimits.STATUS) },
                )

                // A profile field like the name and status above it (peers see it on your profile), but a
                // switch: persisted on toggle, not on Save, since that write is what republishes the
                // profile. It sits inside this section for exactly that reason.
                OpenToChatRow(
                    enabled = form.openToChat,
                    onToggle = onToggleOpenToChat,
                )
            }

            // The node id names the device, not the person, so it gets its own heading rather than
            // trailing the fields peers read.
            SectionHeader(stringResource(R.string.profile_section_device))
            DetailCard {
                DetailRow(
                    label = nodeIdLabel,
                    value = form.nodeId,
                    modifier = Modifier.testTag("profile_node_id"),
                    copyLabel = stringResource(R.string.action_copy),
                    onCopy = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(nodeIdLabel, form.nodeId)))
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                snackbarHostState.showSnackbar(copiedMessage)
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Small circular "X" badge that clears the photo, straddling the avatar's top-end edge. The visible
 * circle is intentionally small (28dp), but [minimumInteractiveComponentSize] keeps the *touch target* at
 * the 48dp accessibility minimum, and the badge carries its own spoken label + [Role.Button] so TalkBack
 * announces it as a distinct, named action separate from the avatar's "change photo" tap.
 *
 * The [offset] nudges the badge up-and-out along the circle's 45° so its center lands on the avatar's
 * rim — roughly half the button hangs outside the circle — so it reads as an attached control rather
 * than an overlay covering the photo.
 */
@Composable
private fun BoxScope.RemovePhotoButton(onClick: () -> Unit) {
    val description = stringResource(R.string.profile_remove_photo_desc)
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-10).dp)
                .minimumInteractiveComponentSize()
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            // Decorative: the enclosing Box carries the accessible name.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The open-to-chat flag as a titled switch row. The one switch on this screen: it is a profile field
 * peers read off your card (ADR 2026-09.74fq), not an app setting, so it stays beside the name and
 * status it travels with rather than moving to Settings.
 */
@Composable
private fun OpenToChatRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // One toggle target: the row owns the switch so a screen reader announces the title +
                // subtitle as the label with an on/off state, instead of an unlabelled switch node.
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .padding(top = 8.dp)
                .testTag("profile_open_to_chat"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.profile_open_to_chat_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.profile_open_to_chat_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // null handler: the row's toggleable owns the interaction (avoids a duplicate focus stop).
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Preview(showBackground = true)
@Composable
fun OpenToChatRowPreview() =
    KnitPreview {
        Column {
            OpenToChatRow(enabled = true, onToggle = {})
            OpenToChatRow(enabled = false, onToggle = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun ProfileScreenPreview() =
    KnitPreview {
        ProfileScreenContent(
            form =
                ProfileFormState(
                    name = "Ada Lovelace",
                    status = "Hiking this weekend",
                    nodeId = "8f3a2b1c9d4e",
                    alias = "GentlyRustlingRabbit",
                    aliasMore = "QuietlyBoldCedar",
                    avatarHash = null,
                    openToChat = true,
                    isDirty = true,
                ),
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onStatusChange = {},
            onStatusCommit = {},
            onPickPhoto = {},
            onClearPhoto = {},
            onSave = {},
        )
    }

// A fresh install: no name yet (the generated alias shows as the placeholder), nothing to save.
@Preview(showBackground = true)
@Composable
fun ProfileScreenNewUserPreview() =
    KnitPreview {
        ProfileScreenContent(
            form =
                ProfileFormState(
                    name = "",
                    status = "",
                    nodeId = "8f3a2b1c9d4e",
                    alias = "GentlyRustlingRabbit",
                    aliasMore = "QuietlyBoldCedar",
                    avatarHash = null,
                    isDirty = false,
                ),
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onStatusChange = {},
            onStatusCommit = {},
            onPickPhoto = {},
            onClearPhoto = {},
            onSave = {},
        )
    }
