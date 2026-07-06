package app.getknit.knit.ui.chatlist

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.ConnectionStatusRow
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.invite.ShareKnitDialog
import app.getknit.knit.ui.invite.ShareStorageException
import app.getknit.knit.ui.invite.launchApkShareChooser
import app.getknit.knit.ui.invite.prepareKnitApk
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.util.compactTimeAgo
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatListScreen(
    onOpenConversation: (conversationId: String) -> Unit,
    onNewMessage: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenDonate: () -> Unit,
    viewModel: ChatListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showShareApp by remember { mutableStateOf(false) }
    // A Play (App Bundle) install is merged into one shareable APK on the fly — several seconds — so we
    // gate the share sheet behind a spinner. Flashes instantly for a single-APK install (fast copy path).
    var preparingShare by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A ticking clock so each row's relative timestamp recomposes as time passes; a bare
    // System.currentTimeMillis() read would freeze at first composition (see rememberCurrentTimeMillis).
    val now by rememberCurrentTimeMillis()

    ChatListScreenContent(
        state = state,
        now = now,
        onOpenConversation = onOpenConversation,
        onNewMessage = onNewMessage,
        onOpenProfile = onOpenProfile,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenBlockedUsers = onOpenBlockedUsers,
        onOpenDonate = onOpenDonate,
        onShareApp = { showShareApp = true },
        onOpenRadioSettings = { warning -> openRadioSettings(context, warning) },
        onDismissRadioWarning = viewModel::dismissRadioWarning,
        onDeleteConversation = viewModel::deleteConversation,
    )

    if (showShareApp) {
        ShareKnitDialog(
            onConfirm = {
                showShareApp = false
                preparingShare = true
                scope.launch {
                    try {
                        runCatching {
                            launchApkShareChooser(context, prepareKnitApk(context))
                        }.onFailure { e ->
                            val msg =
                                if (e is ShareStorageException) {
                                    R.string.share_app_error_storage
                                } else {
                                    R.string.share_app_error
                                }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        preparingShare = false
                    }
                }
            },
            onDismiss = { showShareApp = false },
        )
    }

    if (preparingShare) {
        // Non-dismissible: the merge/sign runs on a background coroutine; block interaction until the
        // share sheet opens (or an error toast fires). onDismissRequest is a no-op so taps don't cancel it.
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(20.dp))
                    Text(stringResource(R.string.share_app_preparing))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListScreenContent(
    state: ChatListUiState,
    now: Long,
    onOpenConversation: (conversationId: String) -> Unit,
    onNewMessage: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenDonate: () -> Unit,
    onShareApp: () -> Unit,
    onOpenRadioSettings: (RadioWarning) -> Unit,
    onDismissRadioWarning: () -> Unit,
    onDeleteConversation: (conversationId: String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        ConnectionStatusRow(state.neighborCount, state.transportHealth)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_title)) },
                                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenProfile()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_title)) },
                                leadingIcon = { Icon(Icons.Filled.NetworkCheck, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenDiagnostics()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.blocked_users_title)) },
                                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenBlockedUsers()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.donate_title)) },
                                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenDonate()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_app_menu)) },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onShareApp()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewMessage,
                modifier = Modifier.semantics { testTag = "chatlist_fab" },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = stringResource(R.string.contacts_new_message),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Pinned above the list (not a scrolling item) so a connectivity warning never scrolls away.
            state.radioWarning?.let { warning ->
                RadioWarningBanner(
                    warning = warning,
                    onOpenSettings = { onOpenRadioSettings(warning) },
                    // The critical "all radios off" banner is not dismissible.
                    onDismiss = if (warning == RadioWarning.AllRadiosOff) null else onDismissRadioWarning,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.conversations, key = { it.id }) { row ->
                    ConversationListItem(
                        row = row,
                        now = now,
                        onClick = { onOpenConversation(row.id) },
                        onDelete = onDeleteConversation,
                    )
                }
            }
        }
    }
}

/**
 * Deep-links to the system panel that fixes [warning]: Bluetooth settings for a Bluetooth-off warning, Wi-Fi
 * settings for Wi-Fi-off, and (for all-radios-off) the airplane-mode panel when airplane mode is on else the
 * top-level wireless panel. Wrapped in [runCatching] since a device may lack the settings activity.
 */
private fun openRadioSettings(
    context: Context,
    warning: RadioWarning,
) {
    val action =
        when (warning) {
            RadioWarning.BluetoothOff -> {
                Settings.ACTION_BLUETOOTH_SETTINGS
            }

            RadioWarning.WifiOff -> {
                Settings.ACTION_WIFI_SETTINGS
            }

            RadioWarning.AllRadiosOff -> {
                if (isAirplaneModeOn(context)) {
                    Settings.ACTION_AIRPLANE_MODE_SETTINGS
                } else {
                    Settings.ACTION_WIRELESS_SETTINGS
                }
            }
        }
    runCatching { context.startActivity(Intent(action)) }
}

private fun isAirplaneModeOn(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationListItem(
    row: ConversationRow,
    now: Long,
    onClick: () -> Unit,
    onDelete: (conversationId: String) -> Unit = {},
) {
    // The Nearby broadcast room can't be deleted, so it gets a plain tap with no long-press menu.
    val deletable = !row.isRoom
    var menuOpen by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val clickModifier =
        if (deletable) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
        } else {
            Modifier.clickable(onClick = onClick)
        }

    // The row is a single accessible target: collapse its children (avatar, title, preview, time,
    // unread badge) into one labelled Button node so a screen reader reads the whole conversation as
    // one summary with a spoken timestamp, and surface the long-press delete as a custom action.
    val preview = row.lastPreview ?: stringResource(R.string.chat_list_empty_preview)
    val spokenTime =
        row.lastMessageAt?.let {
            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
        }
    val spokenUnread =
        if (row.unreadCount > 0) {
            pluralStringResource(R.plurals.chat_list_unread_count, row.unreadCount, row.unreadCount)
        } else {
            null
        }
    val rowDescription = listOfNotNull(row.title, preview, spokenTime, spokenUnread).joinToString(", ")
    val deleteLabel = stringResource(R.string.chat_list_delete_action)

    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(clickModifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clearAndSetSemantics {
                        // Stable id for automation (surfaces as a uiautomator resource-id); "nearby" for the
                        // broadcast room, a peer node id for a DM, or a "g-…" group id.
                        testTag = "chat_row_${row.id}"
                        contentDescription = rowDescription
                        role = Role.Button
                        onClick {
                            onClick()
                            true
                        }
                        if (deletable) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(deleteLabel) {
                                        showConfirm = true
                                        true
                                    },
                                )
                        }
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingVisual(row)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                row.lastMessageAt?.let { sentAt ->
                    Text(
                        text = compactTimeAgo(sentAt, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(row.unreadCount.toString())
                    }
                }
            }
        }
        if (deletable) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.chat_list_delete_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        showConfirm = true
                    },
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.chat_list_delete_confirm_title)) },
            text = { Text(stringResource(R.string.chat_list_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row.id)
                    showConfirm = false
                }) {
                    Text(
                        text = stringResource(R.string.chat_list_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * The circular leading glyph: the knit logo for the room, a group's photo (or a people glyph when unset)
 * for a group, an [Avatar] for a DM.
 */
@Composable
private fun LeadingVisual(row: ConversationRow) {
    val size = 52.dp
    val groupPhoto = row.avatarHash
    when {
        row.isRoom -> {
            CircleGlyph(size) {
                Icon(
                    painter = painterResource(R.drawable.ic_knit_room),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        row.isGroup && groupPhoto != null -> {
            AsyncImage(
                model = BlobImage(groupPhoto),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }

        row.isGroup -> {
            CircleGlyph(size) {
                Icon(
                    Icons.Filled.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        else -> {
            Avatar(avatarHash = row.avatarHash, name = row.title, size = size)
        }
    }
}

/** A circular tinted container for a leading glyph (room logo / group icon). */
@Composable
private fun CircleGlyph(
    size: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
fun ConversationListItemDmPreview() =
    KnitPreview {
        ConversationListItem(
            row =
                ConversationRow(
                    id = "dm-1",
                    title = "Ada Lovelace",
                    avatarHash = null,
                    isRoom = false,
                    isGroup = false,
                    lastPreview = "See you at the meetup tonight!",
                    lastMessageAt = PREVIEW_NOW - 5 * 60_000L,
                    unreadCount = 2,
                ),
            now = PREVIEW_NOW,
            onClick = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ConversationListItemGroupPreview() =
    KnitPreview {
        ConversationListItem(
            row =
                ConversationRow(
                    id = "group-1",
                    title = "Hiking Crew",
                    avatarHash = null,
                    isRoom = false,
                    isGroup = true,
                    lastPreview = "Lena: bringing the trail map",
                    lastMessageAt = PREVIEW_NOW - 60 * 60_000L,
                    unreadCount = 0,
                ),
            now = PREVIEW_NOW,
            onClick = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ConversationListItemRoomPreview() =
    KnitPreview {
        ConversationListItem(
            row =
                ConversationRow(
                    id = "room",
                    title = "Nearby",
                    avatarHash = null,
                    isRoom = true,
                    isGroup = false,
                    lastPreview = null,
                    lastMessageAt = null,
                    unreadCount = 0,
                ),
            now = PREVIEW_NOW,
            onClick = {},
        )
    }

// Shared fixture rows for the full-screen previews.
private fun previewConversations(): List<ConversationRow> =
    listOf(
        ConversationRow(
            id = "room",
            title = "Nearby",
            avatarHash = null,
            isRoom = true,
            isGroup = false,
            lastPreview = "Anyone at the north gate?",
            lastMessageAt = PREVIEW_NOW - 3 * 60_000L,
            unreadCount = 0,
        ),
        ConversationRow(
            id = "group-1",
            title = "Hiking Crew",
            avatarHash = null,
            isRoom = false,
            isGroup = true,
            lastPreview = "Lena: bringing the trail map",
            lastMessageAt = PREVIEW_NOW - 60 * 60_000L,
            unreadCount = 0,
        ),
        ConversationRow(
            id = "dm-1",
            title = "Ada Lovelace",
            avatarHash = null,
            isRoom = false,
            isGroup = false,
            lastPreview = "See you at the meetup tonight!",
            lastMessageAt = PREVIEW_NOW - 5 * 60_000L,
            unreadCount = 2,
        ),
    )

@Preview(showBackground = true)
@Composable
fun ChatListScreenPopulatedPreview() =
    KnitPreview {
        ChatListScreenContent(
            state =
                ChatListUiState(
                    conversations = previewConversations(),
                    neighborCount = 3,
                    transportHealth = TransportHealth.Healthy,
                ),
            now = PREVIEW_NOW,
            onOpenConversation = {},
            onNewMessage = {},
            onOpenProfile = {},
            onOpenDiagnostics = {},
            onOpenBlockedUsers = {},
            onOpenDonate = {},
            onShareApp = {},
            onOpenRadioSettings = {},
            onDismissRadioWarning = {},
            onDeleteConversation = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ChatListScreenRadioWarningPreview() =
    KnitPreview {
        ChatListScreenContent(
            state =
                ChatListUiState(
                    conversations = previewConversations(),
                    neighborCount = 1,
                    transportHealth = TransportHealth.Degraded,
                    radioWarning = RadioWarning.BluetoothOff,
                ),
            now = PREVIEW_NOW,
            onOpenConversation = {},
            onNewMessage = {},
            onOpenProfile = {},
            onOpenDiagnostics = {},
            onOpenBlockedUsers = {},
            onOpenDonate = {},
            onShareApp = {},
            onOpenRadioSettings = {},
            onDismissRadioWarning = {},
            onDeleteConversation = {},
        )
    }

// Exercises the non-dismissible AllRadiosOff banner branch (no close affordance).
@Preview(showBackground = true)
@Composable
fun ChatListScreenQuietPreview() =
    KnitPreview {
        ChatListScreenContent(
            state =
                ChatListUiState(
                    conversations = previewConversations().take(1),
                    neighborCount = 0,
                    transportHealth = TransportHealth.Unavailable,
                    radioWarning = RadioWarning.AllRadiosOff,
                ),
            now = PREVIEW_NOW,
            onOpenConversation = {},
            onNewMessage = {},
            onOpenProfile = {},
            onOpenDiagnostics = {},
            onOpenBlockedUsers = {},
            onOpenDonate = {},
            onShareApp = {},
            onOpenRadioSettings = {},
            onDismissRadioWarning = {},
            onDeleteConversation = {},
        )
    }
