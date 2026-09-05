package app.getknit.knit.ui.chatlist

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.chat.deliveryIcon
import app.getknit.knit.ui.chat.deliveryLabel
import app.getknit.knit.ui.chat.resolve
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.ConnectionStatusRow
import app.getknit.knit.ui.components.PeerNameText
import app.getknit.knit.ui.components.RoomAvatar
import app.getknit.knit.ui.components.skeletonBlockColor
import app.getknit.knit.ui.components.skeletonPulseAlpha
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.invite.ShareKnitDialog
import app.getknit.knit.ui.invite.ShareStorageException
import app.getknit.knit.ui.invite.launchApkShareChooser
import app.getknit.knit.ui.invite.prepareKnitApk
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.theme.KnitMotion
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
    onOpenMessageRequests: () -> Unit,
    onOpenDonate: () -> Unit,
    onOpenAddContact: () -> Unit,
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
        onOpenMessageRequests = onOpenMessageRequests,
        onOpenDonate = onOpenDonate,
        onOpenAddContact = onOpenAddContact,
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
    onOpenMessageRequests: () -> Unit,
    onOpenDonate: () -> Unit,
    onShareApp: () -> Unit,
    onOpenAddContact: () -> Unit,
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
                        ConnectionStatusRow(
                            neighborCount = state.neighborCount,
                            health = state.transportHealth,
                            relay = state.relayPlane,
                            lora = state.loraPlane,
                        )
                    }
                },
                actions = {
                    // Signal-style: the requests inbox affordance appears only when something is pending,
                    // so it pops in the moment a request lands rather than materialising between frames.
                    // The horizontal expand is added on top of the shared pop for this one call site: the
                    // button is 48.dp of top-bar width, and without it the overflow button beside it would
                    // jump the whole distance in a single frame while this one was still fading in.
                    AnimatedVisibility(
                        visible = state.requestCount > 0,
                        enter = KnitMotion.enterPop() + expandHorizontally(KnitMotion.spatial()),
                        exit = KnitMotion.exitPop() + shrinkHorizontally(KnitMotion.spatial()),
                    ) {
                        // Anchor the badge to the 24dp icon (not the 48dp button) so it sits at the
                        // glyph's top-right corner per the M3 badge spec, not out at the touch-target edge.
                        IconButton(
                            onClick = onOpenMessageRequests,
                            modifier = Modifier.size(48.dp).semantics { testTag = "chatlist_requests" },
                        ) {
                            BadgedBox(
                                badge = { Badge { Text(state.requestCount.toString()) } },
                            ) {
                                Icon(
                                    Icons.Filled.MarkChatUnread,
                                    contentDescription = stringResource(R.string.message_requests_title),
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenProfile()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_contact_title)) },
                                leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenAddContact()
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
                                leadingIcon = { Icon(Icons.Filled.DownloadForOffline, contentDescription = null) },
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
            // animateContentSize on the band it occupies (rather than AnimatedVisibility on the banner)
            // lets the list below slide down and back instead of jumping the banner's height in one frame,
            // and needs no retained copy of a dismissed warning to draw while it collapses.
            Column(modifier = Modifier.animateContentSize(KnitMotion.spatial())) {
                state.radioWarning?.let { warning ->
                    RadioWarningBanner(
                        warning = warning,
                        onOpenSettings = { onOpenRadioSettings(warning) },
                        // The critical "all radios off" banner is not dismissible.
                        onDismiss = if (warning == RadioWarning.AllRadiosOff) null else onDismissRadioWarning,
                    )
                }
            }
            // Cold start: the state is a combine of Room + DataStore + mesh flows that emits nothing until
            // all have first-emitted (~1s). Show a skeleton so the screen reads as "loading", not as an
            // empty chat list — then cross-fade to the real rows, which land in the same shape and used to
            // replace the placeholders in a single frame.
            val listEnter = KnitMotion.enterFade()
            val listExit = KnitMotion.exitFade()
            AnimatedContent(
                targetState = state.isLoading,
                transitionSpec = { listEnter togetherWith listExit },
                label = "chatListLoading",
                modifier = Modifier.fillMaxSize(),
            ) { loading ->
                if (loading) {
                    ChatListSkeleton(modifier = Modifier.fillMaxSize())
                } else {
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
                                // Unlike the message list, placement IS animated here: the ViewModel sorts
                                // by lastMessageAt, so a thread that receives a message genuinely travels up
                                // the list, and watching it move is the clearest signal in the app that
                                // something just arrived.
                                modifier = Modifier.animateItem(placementSpec = KnitMotion.spatial()),
                            )
                        }
                        // First run only, and directly under the Nearby row it points at: the room's row is
                        // always there, so without this the screen offers a new user nothing to act on.
                        if (state.showGettingStarted) {
                            item(key = "getting_started") {
                                GettingStartedCard(
                                    onSayHello = { onOpenConversation(Conversations.NEARBY) },
                                    onAddContact = onOpenAddContact,
                                    modifier = Modifier.animateItem(placementSpec = KnitMotion.spatial()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Placeholder rows shown while the conversation list is still loading (see [ChatListUiState.isLoading]).
 * A row's shape mirrors [ConversationListItem] — leading circle + title/preview lines — so the real list
 * slides in without a layout jump. A slow alpha pulse signals "loading" rather than empty content.
 */
@Composable
private fun ChatListSkeleton(modifier: Modifier = Modifier) {
    val alpha = skeletonPulseAlpha(label = "chatListSkeleton")
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        repeat(SKELETON_ROW_COUNT) { ConversationSkeletonRow(pulseAlpha = alpha) }
    }
}

private const val SKELETON_ROW_COUNT = 6

@Composable
private fun ConversationSkeletonRow(pulseAlpha: Float) {
    val blockColor = skeletonBlockColor(pulseAlpha)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(blockColor))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth(0.45f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(blockColor),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.75f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(blockColor),
            )
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
    modifier: Modifier = Modifier,
    onDelete: (conversationId: String) -> Unit = {},
) {
    // The Nearby broadcast room can't be deleted, so it gets a plain tap with no long-press menu. The
    // Meshtastic room is a room too but *can* be cleared — it is a radio channel arriving unasked; clearing
    // it drops the history, and the row stays only while a radio is bound.
    val deletable = !row.isRoom || row.isBridged
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
    // The tick is icon-only, and the row's clearAndSetSemantics below would swallow a description hung on
    // the Icon itself, so its words ride here instead — the same label the bubble and details screen use.
    val spokenStatus =
        // No counts here on purpose: the list would have to load every thread's roster and receipt tallies
        // to describe one glyph. Omitting them yields exactly today's wording (see deliveryLabel).
        row.lastStatus?.let { deliveryLabel(it, row.lastDeliveredVia, mine = true).resolve() }
    val rowDescription =
        listOfNotNull(row.title, preview, spokenTime, spokenStatus, spokenUnread).joinToString(", ")
    val deleteLabel = stringResource(R.string.chat_list_delete_action)

    Box(modifier = modifier) {
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
                PeerNameText(
                    text = row.title,
                    discriminator = row.discriminator,
                    style = MaterialTheme.typography.titleMedium,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Signal-style: when the newest message is one of ours, its tick sits beside the
                    // timestamp — one check sent, two acked, a clock while it's still waiting for the
                    // recipient's key. Ahead of the time rather than after it (where the chat bubble puts
                    // it) so every row's timestamp stays flush right whether or not there's a tick.
                    // The tick crosses clock → ✓ → ✓✓ as the newest message progresses. Everything in this
                    // row sits under the clearAndSetSemantics above, so animating it adds no semantics nodes
                    // at all — the spoken description is unchanged and unaffected.
                    //
                    // Kept inside the `let` rather than given a nullable targetState: an AnimatedContent
                    // always emits a child, and a zero-width one still earns its 3.dp from spacedBy above.
                    // A row whose newest message is not ours has no tick and must have no gap either.
                    row.lastStatus?.let { status ->
                        val tickEnter = KnitMotion.enterPop()
                        val tickExit = KnitMotion.exitPop()
                        AnimatedContent(
                            targetState = status,
                            transitionSpec = { tickEnter togetherWith tickExit },
                            label = "chatListTick",
                        ) { current ->
                            Icon(
                                imageVector = deliveryIcon(current),
                                // Decorative here: the row folds the spoken label into its own description.
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    row.lastMessageAt?.let { sentAt ->
                        Text(
                            text = compactTimeAgo(sentAt, now),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = row.unreadCount > 0,
                    enter = KnitMotion.enterPop(),
                    exit = KnitMotion.exitPop(),
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Spacer(Modifier.height(4.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            // A count that ticks up while the thread is on screen should read as the same
                            // badge counting, not as a badge being replaced.
                            Text(
                                text = row.unreadCount.toString(),
                                modifier = Modifier.animateContentSize(KnitMotion.fastSpatial()),
                            )
                        }
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
            RoomAvatar(size = size)
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
fun ConversationListItemDeliveredPreview() =
    KnitPreview {
        ConversationListItem(
            row =
                ConversationRow(
                    id = "dm-2",
                    title = "Grace Hopper",
                    avatarHash = null,
                    isRoom = false,
                    isGroup = false,
                    lastPreview = "You: on my way",
                    lastMessageAt = PREVIEW_NOW - 2 * 60_000L,
                    unreadCount = 0,
                    lastStatus = DeliveryStatus.Delivered,
                    lastDeliveredVia = DeliveryPlane.Nearby,
                ),
            now = PREVIEW_NOW,
            onClick = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ConversationListItemSentPreview() =
    KnitPreview {
        // One check, forever: a group (or Nearby) send is a broadcast and never gets a delivery receipt.
        ConversationListItem(
            row =
                ConversationRow(
                    id = "group-2",
                    title = "Trail Wardens",
                    avatarHash = null,
                    isRoom = false,
                    isGroup = true,
                    lastPreview = "You: heading up the ridge now",
                    lastMessageAt = PREVIEW_NOW - 12 * 60_000L,
                    unreadCount = 0,
                    lastStatus = DeliveryStatus.Sent,
                ),
            now = PREVIEW_NOW,
            onClick = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ConversationListItemPendingPreview() =
    KnitPreview {
        // Clock, not a check: written locally but unsendable — we don't hold their key yet. The unread
        // badge is unrelated to the tick, so both can sit in the same row.
        ConversationListItem(
            row =
                ConversationRow(
                    id = "dm-3",
                    title = "Katherine Johnson",
                    avatarHash = null,
                    isRoom = false,
                    isGroup = false,
                    lastPreview = "You: are you still at the trailhead?",
                    lastMessageAt = PREVIEW_NOW - 40 * 60_000L,
                    unreadCount = 1,
                    lastStatus = DeliveryStatus.Pending,
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
        ConversationRow(
            id = "dm-2",
            title = "Grace Hopper",
            avatarHash = null,
            isRoom = false,
            isGroup = false,
            lastPreview = "You: on my way",
            lastMessageAt = PREVIEW_NOW - 9 * 60_000L,
            unreadCount = 0,
            lastStatus = DeliveryStatus.Delivered,
            lastDeliveredVia = DeliveryPlane.Nearby,
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
                    relayPlane = RelayPlane.Live,
                ),
            now = PREVIEW_NOW,
            onOpenConversation = {},
            onNewMessage = {},
            onOpenProfile = {},
            onOpenDiagnostics = {},
            onOpenBlockedUsers = {},
            onOpenMessageRequests = {},
            onOpenDonate = {},
            onOpenAddContact = {},
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
            onOpenMessageRequests = {},
            onOpenDonate = {},
            onOpenAddContact = {},
            onShareApp = {},
            onOpenRadioSettings = {},
            onDismissRadioWarning = {},
            onDeleteConversation = {},
        )
    }

// Cold-start loading state: the skeleton placeholder rows shown until the state flow first emits.
@Preview(showBackground = true)
@Composable
fun ChatListScreenLoadingPreview() =
    KnitPreview {
        ChatListScreenContent(
            state = ChatListUiState(isLoading = true),
            now = PREVIEW_NOW,
            onOpenConversation = {},
            onNewMessage = {},
            onOpenProfile = {},
            onOpenDiagnostics = {},
            onOpenBlockedUsers = {},
            onOpenMessageRequests = {},
            onOpenDonate = {},
            onOpenAddContact = {},
            onShareApp = {},
            onOpenRadioSettings = {},
            onDismissRadioWarning = {},
            onDeleteConversation = {},
        )
    }

// First run: the Nearby row with no messages behind it, plus the getting-started nudge.
@Preview(showBackground = true)
@Composable
fun ChatListScreenFirstRunPreview() =
    KnitPreview {
        ChatListScreenContent(
            state =
                ChatListUiState(
                    conversations =
                        listOf(
                            ConversationRow(
                                id = "nearby",
                                title = "Nearby",
                                avatarHash = null,
                                isRoom = true,
                                isGroup = false,
                                lastPreview = null,
                                lastMessageAt = null,
                                unreadCount = 0,
                            ),
                        ),
                    neighborCount = 0,
                    transportHealth = TransportHealth.Healthy,
                    showGettingStarted = true,
                ),
            now = PREVIEW_NOW,
            onOpenConversation = {},
            onNewMessage = {},
            onOpenProfile = {},
            onOpenDiagnostics = {},
            onOpenBlockedUsers = {},
            onOpenMessageRequests = {},
            onOpenDonate = {},
            onOpenAddContact = {},
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
            onOpenMessageRequests = {},
            onOpenDonate = {},
            onOpenAddContact = {},
            onShareApp = {},
            onOpenRadioSettings = {},
            onDismissRadioWarning = {},
            onDeleteConversation = {},
        )
    }
