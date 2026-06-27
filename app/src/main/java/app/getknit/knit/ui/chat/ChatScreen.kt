package app.getknit.knit.ui.chat

import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.ConnectionStatusRow
import app.getknit.knit.ui.image.BlobImage
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    viewModel: ChatViewModel = koinViewModel { parametersOf(conversationId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val confirmAttachment by viewModel.confirmAttachment.collectAsStateWithLifecycle()
    val inputState = rememberTextFieldState()
    // Mentions the user inserted via autocomplete, draft-local alongside inputState (per the AGENTS.md
    // gotcha, draft state stays in the screen, not the ViewModel/DataStore). Filtered against the final
    // text on send so a mention whose "@name" was deleted doesn't ship.
    val pendingMentions = remember { mutableStateListOf<Mention>() }
    var fullscreenImage by remember { mutableStateOf<BlobImage?>(null) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Modern Android Photo Picker — needs no runtime permission. ImageOnly still includes GIFs.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::attach)
    }

    // Suppress message notifications while the chat is on screen, and clear any active one. The NavHost
    // back-stack entry is this composable's LifecycleOwner, so navigating away pauses (and popping
    // disposes) the screen — both paths re-enable notifications so messages arriving elsewhere notify.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onChatForeground()
                Lifecycle.Event.ON_PAUSE -> viewModel.onChatBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.onChatBackground()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(state.rows.size) {
        if (state.rows.isNotEmpty()) listState.animateScrollToItem(state.rows.lastIndex)
    }

    // Surface one-shot results (e.g. image saved) as toasts; a toast shows over the fullscreen Dialog,
    // unlike a Scaffold snackbar which the viewer would cover.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Clear the input only once a message is accepted and sent (not when it's blocked for abuse).
    LaunchedEffect(Unit) {
        viewModel.clearInput.collect {
            inputState.clearText()
            pendingMentions.clear()
        }
    }
    // Blocking the peer of a DM hides this whole thread, so leave the now-empty screen.
    LaunchedEffect(Unit) {
        viewModel.closeChat.collect { onBack() }
    }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = {
                    when {
                        state.isRoom -> {
                            Column {
                                Text(
                                    text = stringResource(R.string.nearby_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                ConnectionStatusRow(state.neighborCount)
                            }
                        }
                        state.isGroup -> {
                            // Group: a people glyph + name + member count.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GroupGlyph(size = 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = state.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.chat_group_member_count,
                                            state.memberCount,
                                            state.memberCount,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        else -> {
                            // 1:1 DM: peer avatar + name, Signal-style.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(
                                    avatarHash = state.avatarHash,
                                    name = state.title,
                                    size = 36.dp,
                                    onClick = { onOpenProfile(conversationId) },
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = state.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    // The overflow lives on DM and group threads — the broadcast room has no actions.
                    if (!state.isRoom) {
                        Box {
                            IconButton(onClick = { headerMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.chat_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = headerMenuOpen,
                                onDismissRequest = { headerMenuOpen = false },
                            ) {
                                if (state.isGroup) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_group_rename)) },
                                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                        onClick = {
                                            headerMenuOpen = false
                                            showRenameDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_group_leave)) },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                                        },
                                        onClick = {
                                            headerMenuOpen = false
                                            showLeaveConfirm = true
                                        },
                                    )
                                } else {
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
                                            headerMenuOpen = false
                                            if (state.isBlocked) viewModel.unblock(conversationId)
                                            else viewModel.block(conversationId)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            MessageInput(
                state = inputState,
                pendingAttachment = pendingAttachment,
                candidates = state.mentionCandidates,
                onMentionAdded = { m -> if (pendingMentions.none { it == m }) pendingMentions.add(m) },
                onAttachClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onClearAttachment = viewModel::clearAttachment,
                onReceiveImage = viewModel::attach,
                onSend = {
                    val text = inputState.text.toString()
                    val applied = pendingMentions.filter { text.contains("@${it.name}") }
                    // Don't clear here: a message blocked for abuse must keep the draft. The ViewModel
                    // emits clearInput only once a message is actually accepted (see the collector above).
                    viewModel.send(text, applied)
                },
            )
        },
    ) { padding ->
        if (state.rows.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    MessageBubble(
                        row,
                        onImageClick = { fullscreenImage = it },
                        onOpenProfile = onOpenProfile,
                        onReact = viewModel::react,
                        onDelete = viewModel::deleteMessage,
                        onBlock = viewModel::block,
                        onCopy = { text ->
                            copyScope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText("message", text).toClipEntry(),
                                )
                                // Android 13+ shows its own copy confirmation; skip the toast there
                                // so the user doesn't see a duplicate.
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    viewModel.onMessageCopied()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    fullscreenImage?.let { image ->
        FullscreenImageViewer(
            image = image,
            onDismiss = { fullscreenImage = null },
            onSave = { viewModel.saveAttachment(image.hash) },
        )
    }

    // Sending an explicit image is allowed but discouraged: confirm before staging a flagged one.
    if (confirmAttachment != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFlaggedAttachment,
            title = { Text(stringResource(R.string.moderation_image_confirm_title)) },
            text = { Text(stringResource(R.string.moderation_image_confirm_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFlaggedAttachment) {
                    Text(stringResource(R.string.moderation_image_confirm_send))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFlaggedAttachment) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
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

/** Circular people glyph used as a group's leading visual, mirroring the chat-list room logo style. */
@Composable
private fun GroupGlyph(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.6f),
        )
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
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.chat_group_name_label)) },
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

/** The short set of quick reactions offered when long-pressing a message. */
private val REACTION_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatRow,
    onImageClick: (BlobImage) -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    onReact: (messageId: String, emoji: String) -> Unit,
    onDelete: (messageId: String) -> Unit,
    onBlock: (nodeId: String) -> Unit,
    onCopy: (text: String) -> Unit,
) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    val bubbleShape = if (row.mine) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    }
    var showPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // A message flagged by the on-device text moderator is collapsed until the user taps to reveal it.
    var revealed by remember(row.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (row.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!row.mine) {
            Avatar(
                avatarHash = row.avatarHash,
                name = row.senderName,
                size = 40.dp,
                onClick = { onOpenProfile(row.senderNodeId) },
            )
            Spacer(Modifier.width(8.dp))
        }
        // Bubble + its reaction chips stack vertically, aligned to the message's side. The Box anchors
        // the long-press picker popup directly above the bubble.
        Column(horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start) {
            Box {
                Surface(
                    color = if (row.mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (row.mine) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = bubbleShape,
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .combinedClickable(
                            // Tap only reveals a moderation-collapsed message; otherwise no tap action
                            // (and no ripple). Long-press opens the reaction picker.
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (row.moderationFlagged && !revealed) revealed = true },
                            onLongClick = { showPicker = true },
                        ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (!row.mine) {
                            Text(
                                text = row.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (row.attachmentHash != null) {
                            AttachmentImage(
                                row.attachmentHash,
                                row.attachmentMime,
                                row.attachmentReady,
                                row.attachmentFlagged,
                                onImageClick,
                            )
                            if (row.body.isNotBlank()) Spacer(Modifier.height(4.dp))
                        }
                        if (row.body.isNotBlank()) {
                            if (row.moderationFlagged && !revealed) {
                                Text(
                                    text = stringResource(R.string.moderation_text_hidden),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                val mentionStyle = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = highlightMentions(row.body, row.mentions, mentionStyle),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = timeLabel(row),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Our own messages show a delivery tick: one check sent, two checks acked.
                            if (row.mine) {
                                Icon(
                                    imageVector = if (row.received) Icons.Filled.DoneAll else Icons.Filled.Done,
                                    contentDescription = stringResource(
                                        if (row.received) R.string.chat_status_delivered
                                        else R.string.chat_status_sent,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                if (showPicker) {
                    ReactionPicker(
                        onPick = { emoji ->
                            onReact(row.id, emoji)
                            showPicker = false
                        },
                        // Image-only messages have no text to copy, so omit the action.
                        onCopy = if (row.body.isNotBlank()) {
                            {
                                onCopy(row.body)
                                showPicker = false
                            }
                        } else {
                            null
                        },
                        onDelete = {
                            showPicker = false
                            showDeleteConfirm = true
                        },
                        // You can only block other people, not yourself.
                        onBlock = if (!row.mine) {
                            {
                                onBlock(row.senderNodeId)
                                showPicker = false
                            }
                        } else {
                            null
                        },
                        onDismiss = { showPicker = false },
                    )
                }
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(stringResource(R.string.chat_delete_confirm_title)) },
                        text = { Text(stringResource(R.string.chat_delete_confirm_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                onDelete(row.id)
                                showDeleteConfirm = false
                            }) {
                                Text(
                                    text = stringResource(R.string.chat_delete_confirm_action),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
            }
            if (row.reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ReactionRow(reactions = row.reactions, onToggle = { emoji -> onReact(row.id, emoji) })
            }
        }
    }
}

/**
 * Floating menu shown just above a long-pressed bubble: a row of quick-reaction emoji, an optional
 * "Copy text" action ([onCopy] is null for messages with no copyable text), an optional "Block user"
 * action ([onBlock] is null for your own messages), and an always-present "Delete message" action that
 * removes the message from this device only.
 */
@Composable
private fun ReactionPicker(
    onPick: (String) -> Unit,
    onCopy: (() -> Unit)?,
    onDelete: () -> Unit,
    onBlock: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    // Center the bar horizontally over the bubble and place it above; drop below only if it would clip
    // the top of the window.
    val positionProvider = remember(spacingPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                val clampedX = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val above = anchorBounds.top - popupContentSize.height - spacingPx
                val y = if (above >= 0) above else anchorBounds.bottom + spacingPx
                return IntOffset(clampedX, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            // Size the menu to the emoji row's width so the divider/copy row span it, not the screen.
            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    REACTION_EMOJI.forEach { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onPick(emoji) }
                                .padding(8.dp),
                        )
                    }
                }
                // Delete is always offered, so the divider below the emoji row always shows.
                HorizontalDivider()
                if (onCopy != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCopy() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.chat_action_copy),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (onBlock != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBlock() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.chat_action_block),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDelete() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_action_delete),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Aggregated reaction chips shown below a bubble; tapping a chip toggles the local user's reaction. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(reactions: List<ReactionSummary>, onToggle: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            ReactionChip(reaction, onClick = { onToggle(reaction.emoji) })
        }
    }
}

@Composable
private fun ReactionChip(reaction: ReactionSummary, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        shape = shape,
        color = if (reaction.mine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (reaction.mine) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (reaction.mine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.clip(shape).clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(text = reaction.emoji, style = MaterialTheme.typography.labelLarge)
            // A lone reaction shows just the emoji (Signal-style); the count appears once it's shared.
            if (reaction.count > 1) {
                Text(
                    text = reaction.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** The image inside a bubble: the photo/GIF once fetched (tap to open fullscreen), or a loading placeholder. */
@Composable
private fun AttachmentImage(
    hash: String?,
    mime: String?,
    ready: Boolean,
    flagged: Boolean,
    onImageClick: (BlobImage) -> Unit,
) {
    // A flagged image stays hidden behind a placeholder until the user taps to view it.
    var revealed by remember(hash) { mutableStateOf(false) }
    val hidden = flagged && !revealed
    val image = if (ready && hash != null) BlobImage(hash, mime) else null
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                when {
                    hidden -> Modifier.clickable { revealed = true }
                    image != null -> Modifier.clickable { onImageClick(image) }
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            hidden -> Column(
                modifier = Modifier
                    .size(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.moderation_image_hidden),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            image != null -> AsyncImage(
                model = image,
                contentDescription = stringResource(R.string.chat_attachment_image_desc),
                contentScale = ContentScale.Fit,
                modifier = Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 260.dp),
            )
            else -> Column(
                modifier = Modifier
                    .size(160.dp)
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.chat_loading_photo), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Fullscreen, pinch-to-zoom/pan viewer for a tapped image. A back arrow (top-left) dismisses it and
 * an overflow menu (top-right) offers Save; back press / outside tap also dismiss. Controls float
 * white over the black backdrop, Signal-style.
 */
@Composable
private fun FullscreenImageViewer(image: BlobImage, onDismiss: () -> Unit, onSave: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var menuOpen by remember { mutableStateOf(false) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset += panChange
        }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = image,
                contentDescription = stringResource(R.string.chat_image_viewer_desc),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformState)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White,
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.chat_more_options),
                        tint = Color.White,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_save)) },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onSave()
                        },
                    )
                }
            }
        }
    }
}

private fun timeLabel(row: ChatRow): String =
    DateUtils.getRelativeTimeSpanString(
        row.sentAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.chat_empty),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageInput(
    state: TextFieldState,
    pendingAttachment: AttachmentStore.Ingested?,
    candidates: List<MentionCandidate>,
    onMentionAdded: (Mention) -> Unit,
    onAttachClick: () -> Unit,
    onClearAttachment: () -> Unit,
    onReceiveImage: (Uri) -> Unit,
    onSend: () -> Unit,
) {
    // Capture images committed by the keyboard (Gboard GIFs), drag-and-drop, or paste. The state-based
    // BasicTextField is required here: it advertises the accepted content MIME types to the IME, so the
    // keyboard offers GIFs instead of "images not supported here".
    val receiveContentListener = remember(onReceiveImage) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                return transferableContent.consume { item ->
                    val uri = item.uri
                    if (uri != null) {
                        onReceiveImage(uri)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
    // The trailing button doubles as Attach when there's nothing to send, and Send once there is.
    val canSend = state.text.isNotBlank() || pendingAttachment != null

    // Track the "@token" the cursor is inside to drive the autocomplete dropdown. snapshotFlow observes
    // the field reactively; the LaunchedEffect ties the collector to composition (cancels on dispose).
    var activeQuery by remember { mutableStateOf<MentionQuery?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() to state.selection }
            .collect { (text, sel) ->
                activeQuery = if (sel.collapsed) activeMentionQuery(text, sel.end) else null
            }
    }
    val filtered = remember(activeQuery, candidates) {
        activeQuery?.let { filterCandidates(candidates, it.query) }.orEmpty()
    }
    val showSuggestions = activeQuery != null && filtered.isNotEmpty()

    fun insertMention(query: MentionQuery, candidate: MentionCandidate) {
        val replacement = "@${candidate.displayName} "
        state.edit {
            replace(query.start, query.end, replacement)
            placeCursorBeforeCharAt(query.start + replacement.length)
        }
        onMentionAdded(Mention(nodeId = candidate.nodeId, name = candidate.displayName))
        activeQuery = null
    }

    Surface(
        tonalElevation = 2.dp,
        // Edge-to-edge: lift the input bar above the IME and the navigation bar.
        modifier = Modifier.imePadding().navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (showSuggestions) {
                // Sits above the input row (and so above the keyboard, since the whole Surface is
                // imePadding-lifted). A plain Column grows upward; a DropdownMenu would open downward.
                Surface(
                    tonalElevation = 3.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column {
                        filtered.take(5).forEach { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activeQuery?.let { insertMention(it, candidate) } }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(avatarHash = candidate.avatarHash, name = candidate.displayName, size = 32.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = candidate.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            if (pendingAttachment != null) {
                AttachmentPreview(
                    image = BlobImage(pendingAttachment.hash, pendingAttachment.mime),
                    onClear = onClearAttachment,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.text.isEmpty()) {
                        Text(stringResource(R.string.chat_message_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        state = state,
                        modifier = Modifier.fillMaxWidth().contentReceiver(receiveContentListener),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        onKeyboardAction = { onSend() },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (canSend) onSend() else onAttachClick() },
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp).align(Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.AddPhotoAlternate,
                        contentDescription = if (canSend) {
                            stringResource(R.string.action_send)
                        } else {
                            stringResource(R.string.action_attach_photo)
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** Thumbnail of the staged attachment with a button to remove it before sending. */
@Composable
private fun AttachmentPreview(image: BlobImage, onClear: () -> Unit) {
    Box {
        AsyncImage(
            model = image,
            contentDescription = stringResource(R.string.chat_attachment_preview_desc),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
        ) {
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_remove_attachment),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
