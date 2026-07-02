package app.getknit.knit.ui.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.FullscreenImageViewer
import app.getknit.knit.ui.components.GroupAvatar
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.profile.AvatarCropDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The group details / settings screen (the group analogue of [app.getknit.knit.ui.profile.ProfileDetailsScreen]):
 * the group photo + name, a member roster, and the group-management actions (rename, set photo, leave)
 * in the overflow menu. Reached by tapping the group avatar in a group chat. Tapping a member opens
 * their profile via [onOpenMemberProfile]; the local user's own row is labeled "You" and isn't tappable.
 * Leaving the group deletes the thread, so [onLeft] pops back past the (now-gone) chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailsScreen(
    groupId: String,
    onBack: () -> Unit,
    onOpenMemberProfile: (nodeId: String) -> Unit,
    onLeft: () -> Unit,
    viewModel: GroupDetailsViewModel = koinViewModel { parametersOf(groupId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showPhotoFullscreen by remember { mutableStateOf(false) }

    // Leaving tombstones the group and deletes its messages; pop back past the chat once it persists.
    LaunchedEffect(Unit) {
        viewModel.left.collect { onLeft() }
    }

    // Group-photo pick → crop → save, mirroring the profile-avatar flow (and the old chat header path).
    val groupPhotoCropTarget by viewModel.groupPhotoCropTarget.collectAsStateWithLifecycle()
    val groupPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::pickGroupPhoto)
    }
    groupPhotoCropTarget?.let { bmp ->
        val image = remember(bmp) { bmp.asImageBitmap() }
        AvatarCropDialog(
            bitmap = image,
            onCancel = viewModel::cancelGroupPhotoCrop,
            onConfirm = viewModel::confirmGroupPhoto,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_details_title)) },
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
                                modifier = Modifier.testTag("group_set_photo"),
                                text = { Text(stringResource(R.string.chat_group_set_photo)) },
                                leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    groupPhotoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                modifier = Modifier.testTag("group_rename"),
                                text = { Text(stringResource(R.string.chat_group_rename)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                modifier = Modifier.testTag("group_leave"),
                                text = { Text(stringResource(R.string.chat_group_leave)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showLeaveConfirm = true
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
            // Tappable only when a custom photo is set: the default people glyph has nothing to enlarge,
            // so onClick stays null and GroupAvatar renders non-interactive (no ripple / no touch target).
            GroupAvatar(
                photoHash = state.photoHash,
                size = 96.dp,
                contentDescription = if (state.photoHash != null) state.title else null,
                onClickLabel = stringResource(R.string.group_details_view_photo),
                onClick = if (state.photoHash != null) ({ showPhotoFullscreen = true }) else null,
            )

            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.chat_group_member_count,
                    state.members.size,
                    state.members.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.group_details_members),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.members.forEach { member ->
                    MemberRow(member = member, onOpen = onOpenMemberProfile)
                }
            }
        }
    }

    if (showPhotoFullscreen && state.photoHash != null) {
        FullscreenImageViewer(
            model = BlobImage(state.photoHash!!),
            contentDescription = stringResource(R.string.chat_image_viewer_desc),
            title = state.title,
            onDismiss = { showPhotoFullscreen = false },
        )
    }

    if (showRenameDialog) {
        RenameGroupDialog(
            currentName = state.title,
            onDismiss = { showRenameDialog = false },
            onRename = { name ->
                viewModel.renameGroup(name)
                showRenameDialog = false
            },
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.chat_group_leave_confirm_title)) },
            text = { Text(stringResource(R.string.chat_group_leave_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    viewModel.leaveGroup()
                }) {
                    Text(
                        text = stringResource(R.string.chat_group_leave_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * One member row: avatar + display name, tappable to open that member's profile. The local user's own
 * row shows a "You" label instead of an online dot and is not tappable; other members show a live
 * online dot when reachable (matching the contact list / profile-details presence indicator).
 */
@Composable
private fun MemberRow(member: GroupMemberRow, onOpen: (nodeId: String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!member.isSelf) Modifier.clickable { onOpen(member.nodeId) } else Modifier,
            )
            .testTag("group_member_${member.nodeId}")
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarHash = member.avatarHash, name = member.displayName, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (member.isSelf) {
            Text(
                text = stringResource(R.string.chat_self_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (member.online) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}

/** Dialog to rename a group; pre-fills the current name and disables Rename when the field is blank. */
@Composable
private fun RenameGroupDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_group_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(TextLimits.GROUP_NAME) },
                singleLine = true,
                label = { Text(stringResource(R.string.chat_group_name_label)) },
                supportingText = {
                    Text(
                        text = "${name.length} / ${TextLimits.GROUP_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_group_rename_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
