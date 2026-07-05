// The file's single top-level class (ProfileFormState, the content composable's state holder) rides
// along with the screen composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.profile

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.isIgnoringBatteryOptimizations
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import org.koin.androidx.compose.koinViewModel

/** UI-local projection of [ProfileViewModel]'s per-field flows for the stateless content. */
internal data class ProfileFormState(
    val name: String,
    val status: String,
    val nodeId: String,
    val alias: String,
    val avatarHash: String?,
    val contentFilteringEnabled: Boolean,
    val isDirty: Boolean,
)

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val nodeId by viewModel.nodeId.collectAsStateWithLifecycle()
    val alias by viewModel.alias.collectAsStateWithLifecycle()
    val avatarHash by viewModel.avatarHash.collectAsStateWithLifecycle()
    val cropTarget by viewModel.cropTarget.collectAsStateWithLifecycle()
    val contentFilteringEnabled by viewModel.contentFilteringEnabled.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()

    // Navigate back only once Save has finished persisting (the write outlives this composition because
    // it runs in viewModelScope, but we wait so the user lands back on the previous screen on success).
    LaunchedEffect(Unit) {
        viewModel.saved.collect { onBack() }
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

    val context = LocalContext.current
    ProfileScreenContent(
        form =
            ProfileFormState(
                name = name,
                status = status,
                nodeId = nodeId,
                alias = alias,
                avatarHash = avatarHash,
                contentFilteringEnabled = contentFilteringEnabled,
                isDirty = isDirty,
            ),
        batteryExempt = rememberBatteryExempt(),
        onBack = onBack,
        onNameChange = viewModel::setDisplayName,
        onNameCommit = viewModel::commitDisplayName,
        onStatusChange = viewModel::setStatus,
        onStatusCommit = viewModel::commitStatus,
        onToggleContentFiltering = viewModel::setContentFilteringEnabled,
        onPickPhoto = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onClearPhoto = viewModel::clearAvatar,
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
        onSave = viewModel::save,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreenContent(
    form: ProfileFormState,
    batteryExempt: Boolean,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onStatusChange: (String) -> Unit,
    onStatusCommit: () -> Unit,
    onToggleContentFiltering: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onAllowBattery: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box {
                Avatar(
                    avatarHash = form.avatarHash,
                    name = displayNameFor(form.name, form.nodeId),
                    size = 96.dp,
                    background = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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

            OutlinedTextField(
                value = form.name,
                onValueChange = onNameChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("profile_name")
                        .onFocusChanged { if (!it.isFocused) onNameCommit() },
                label = { Text(stringResource(R.string.profile_display_name_label)) },
                placeholder = { if (form.alias.isNotEmpty()) Text(form.alias) },
                singleLine = true,
                supportingText = { CharCounter(form.name.length, TextLimits.DISPLAY_NAME) },
            )
            OutlinedTextField(
                value = form.status,
                onValueChange = onStatusChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("profile_status")
                        .onFocusChanged { if (!it.isFocused) onStatusCommit() },
                label = { Text(stringResource(R.string.profile_status_label)) },
                singleLine = true,
                supportingText = { CharCounter(form.status.length, TextLimits.STATUS) },
            )
            Text(
                text = stringResource(R.string.profile_node_id, form.nodeId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ContentFilteringRow(
                enabled = form.contentFilteringEnabled,
                onToggle = onToggleContentFiltering,
            )

            BatteryOptimizationRow(exempt = batteryExempt, onAllow = onAllowBattery)

            Button(
                onClick = onSave,
                enabled = form.isDirty,
                modifier = Modifier.fillMaxWidth().testTag("profile_save"),
            ) {
                Text(stringResource(R.string.action_save))
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

/** Right-aligned "used / limit" counter shown beneath a capped single-line field. */
@Composable
private fun CharCounter(
    length: Int,
    limit: Int,
) {
    Text(
        text = "$length / $limit",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Toggle for on-device content moderation (abusive-text + explicit-image filtering). */
@Composable
private fun ContentFilteringRow(
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
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_content_filtering_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_content_filtering_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // null handler: the row's toggleable owns the interaction (avoids a duplicate focus stop).
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * Whether the app is currently exempt from battery optimization, refreshed on every screen resume.
 * Lives in the stateful wrapper (not [BatteryOptimizationRow]) because the `PowerManager` read is not
 * available to the preview renderer.
 */
@Composable
private fun rememberBatteryExempt(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    exempt = isIgnoringBatteryOptimizations(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return exempt
}

/** Shows whether the app is exempt from battery optimization, with an "allow" affordance when not. */
@Composable
private fun BatteryOptimizationRow(
    exempt: Boolean,
    onAllow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                if (exempt) {
                    stringResource(R.string.battery_allowed)
                } else {
                    stringResource(R.string.battery_restricted)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!exempt) {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.battery_allow_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CharCounterPreview() =
    KnitPreview {
        Column {
            CharCounter(length = 12, limit = 40)
            CharCounter(length = 40, limit = 40)
        }
    }

@Preview(showBackground = true)
@Composable
fun ContentFilteringRowPreview() =
    KnitPreview {
        Column {
            ContentFilteringRow(enabled = true, onToggle = {})
            ContentFilteringRow(enabled = false, onToggle = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun BatteryOptimizationRowPreview() =
    KnitPreview {
        Column {
            BatteryOptimizationRow(exempt = true, onAllow = {})
            BatteryOptimizationRow(exempt = false, onAllow = {})
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
                    alias = "Rustling Rabbit",
                    avatarHash = null,
                    contentFilteringEnabled = true,
                    isDirty = true,
                ),
            batteryExempt = false,
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onStatusChange = {},
            onStatusCommit = {},
            onToggleContentFiltering = {},
            onPickPhoto = {},
            onClearPhoto = {},
            onAllowBattery = {},
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
                    alias = "Rustling Rabbit",
                    avatarHash = null,
                    contentFilteringEnabled = true,
                    isDirty = false,
                ),
            batteryExempt = true,
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onStatusChange = {},
            onStatusCommit = {},
            onToggleContentFiltering = {},
            onPickPhoto = {},
            onClearPhoto = {},
            onAllowBattery = {},
            onSave = {},
        )
    }
