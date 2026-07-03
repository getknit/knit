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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
import app.getknit.knit.TextLimits
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.ConnectionStatusRow
import app.getknit.knit.ui.components.GroupAvatar
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.openUrl
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    onOpenGroupDetails: (conversationId: String) -> Unit,
    viewModel: ChatViewModel = koinViewModel { parametersOf(conversationId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val confirmAttachment by viewModel.confirmAttachment.collectAsStateWithLifecycle()
    val inputState = rememberTextFieldState()
    val shareInbox = koinInject<ShareInbox>()
    // Mentions the user inserted via autocomplete, draft-local alongside inputState (per the AGENTS.md
    // gotcha, draft state stays in the screen, not the ViewModel/DataStore). Filtered against the final
    // text on send so a mention whose "@name" was deleted doesn't ship.
    val pendingMentions = remember { mutableStateListOf<Mention>() }
    var fullscreenImage by remember { mutableStateOf<FullscreenImage?>(null) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var showEncryptionInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Aspect ratios of already-decoded image attachments, keyed by content hash, kept above the
    // LazyColumn so they survive item disposal. Coil doesn't memory-cache animated GIFs, so each one
    // re-decodes every time it scrolls back into view; without a reserved height the bubble collapses
    // to zero mid-decode and snaps back, which is what made the list "skip" when flinging past several
    // GIFs. Caching the ratio lets a re-entering bubble reserve the right height before it decodes.
    val imageRatios = remember { HashMap<String, Float>() }
    // A ticking clock so each bubble's relative timestamp ("2 min ago") recomposes as time passes;
    // System.currentTimeMillis() alone is not a tracked read and would freeze at first composition.
    val now by rememberCurrentTimeMillis()

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

    // The thread is rendered bottom-anchored (the LazyColumn below uses reverseLayout), so it opens
    // already resting on the newest message — no initial scroll, no visible glide through history — and
    // the newest bubble stays glued to the bottom as the soft keyboard slides in and as late-loading
    // images change earlier bubbles' heights. When a new trailing message arrives, follow it to the
    // bottom if it's our own or the user is already parked there; if they've scrolled up to read
    // history, leave their position untouched. After a prepend, reverseLayout shifts the bottom anchor
    // from index 0 to 1, so treat <= 1 as "was at the bottom".
    val newest = state.rows.lastOrNull()
    LaunchedEffect(newest?.id) {
        val row = newest ?: return@LaunchedEffect
        if (row.mine || listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
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
            // Signal the field is now empty so the ViewModel releases its double-submit guard; releasing
            // only after the text is gone closes the last window where a rapid tap could re-send a draft.
            viewModel.onInputCleared()
        }
    }
    // Drain any payload handed in from the system share sheet (see ShareInbox): prefill the text draft
    // and stage the image through the normal attach() path, so it inherits ingest-time screening and
    // the "send anyway?" / hard-block handling. consume() is single-shot, so only the chat opened right
    // after the share-target picker prefills — normal chat opens see nothing.
    LaunchedEffect(Unit) {
        shareInbox.consume()?.let { shared ->
            shared.text?.let { if (it.isNotEmpty()) inputState.setTextAndPlaceCursorAtEnd(it) }
            shared.imageUri?.let { viewModel.attach(Uri.parse(it)) }
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
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
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
                            // Group: its photo (or a people glyph when unset) + name + member count.
                            // Tapping the avatar opens the group details / settings screen.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GroupAvatar(
                                    photoHash = state.avatarHash,
                                    size = 36.dp,
                                    modifier = Modifier.testTag("chat_group_avatar"),
                                    contentDescription = stringResource(R.string.chat_view_group_info),
                                    onClick = { onOpenGroupDetails(conversationId) },
                                )
                                Spacer(Modifier.width(10.dp))
                                // Weight (fill = false) lets a long group name ellipsize while the
                                // fixed-size badge — measured first as a non-weighted child — always
                                // keeps its room. Short names stay snug against the icon.
                                Column(modifier = Modifier.weight(1f, fill = false)) {
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
                                EncryptionBadge { showEncryptionInfo = true }
                            }
                        }
                        else -> {
                            // 1:1 DM: peer avatar + name, Signal-style.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(
                                    avatarHash = state.avatarHash,
                                    name = state.title,
                                    size = 36.dp,
                                    contentDescription = stringResource(R.string.chat_view_profile, state.title),
                                    onClick = { onOpenProfile(conversationId) },
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = state.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    // Weight (fill = false) lets a long name ellipsize while the
                                    // fixed-size badges — measured first as non-weighted children —
                                    // always keep their room. Short names stay snug against the icons.
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                EncryptionBadge { showEncryptionInfo = true }
                                if (state.verified) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = stringResource(R.string.verify_verified),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    // The overflow lives on DM threads only: the broadcast room has no actions, and a
                    // group's actions moved to the group-details screen (tap the group avatar to open it).
                    if (!state.isRoom && !state.isGroup) {
                        Box {
                            IconButton(onClick = { headerMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.chat_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = headerMenuOpen,
                                onDismissRequest = { headerMenuOpen = false },
                            ) {
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
                // Bottom-anchored so the thread opens on the newest message with no scroll; the data is
                // reversed to match, making index 0 the newest row, drawn at the bottom. Arrangement.Bottom
                // keeps a short thread (fewer rows than fit on screen) resting just above the input rather
                // than floating at the top with a gap beneath the newest bubble.
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                reverseLayout = true,
            ) {
                items(state.rows.asReversed(), key = { it.id }) { row ->
                    if (row.kind == MessageEntity.KIND_MEMBER_LEFT) {
                        SystemNotice(stringResource(R.string.chat_group_member_left, row.senderName))
                    } else {
                        MessageBubble(
                            row,
                            now = now,
                            // In a 1:1 DM the peer's name is in the top bar, so don't repeat it on every
                            // received bubble; show it only where multiple people can speak.
                            showSenderName = state.isRoom || state.isGroup,
                            imageRatios = imageRatios,
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
    }

    fullscreenImage?.let { fs ->
        FullscreenImageViewer(
            fullscreen = fs,
            now = now,
            onDismiss = { fullscreenImage = null },
            onSave = { viewModel.saveAttachment(fs.image.hash) },
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

    if (showEncryptionInfo) {
        AlertDialog(
            onDismissRequest = { showEncryptionInfo = false },
            icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            title = { Text(stringResource(R.string.chat_encryption_info_title)) },
            text = { Text(stringResource(R.string.chat_encryption_info_body)) },
            confirmButton = {
                TextButton(onClick = { showEncryptionInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/**
 * Lock badge shown in the header of encrypted threads (1:1 DMs and groups, never the plaintext Nearby
 * room). Tapping it explains that the conversation is end-to-end encrypted. Neutral-tinted so it reads
 * distinctly from the green verified shield it sits to the left of.
 */
@Composable
private fun EncryptionBadge(onClick: () -> Unit) {
    Spacer(Modifier.width(6.dp))
    Icon(
        imageVector = Icons.Filled.Lock,
        contentDescription = stringResource(R.string.chat_encrypted_desc),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(3.dp)
            .size(18.dp),
    )
}

/** The short set of quick reactions offered when long-pressing a message. */
private val REACTION_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatRow,
    now: Long,
    showSenderName: Boolean,
    // Hash-keyed aspect-ratio cache shared across the list so a re-entering image reserves its height
    // before decode (see [ChatScreen]). Defaulted so the @Preview call sites need no extra wiring.
    imageRatios: MutableMap<String, Float> = HashMap(),
    onImageClick: (FullscreenImage) -> Unit,
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
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (row.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!row.mine && showSenderName) {
            Avatar(
                avatarHash = row.avatarHash,
                name = row.senderName,
                size = 40.dp,
                contentDescription = stringResource(R.string.chat_view_profile, row.senderName),
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
                        if (!row.mine && showSenderName) {
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
                                row.attachmentKey,
                                row.attachmentReady,
                                row.attachmentFlagged,
                                imageRatios = imageRatios,
                                onImageClick = {
                                    onImageClick(
                                        FullscreenImage(it, row.mine, row.senderName, row.sentAt),
                                    )
                                },
                                onLongClick = { showPicker = true },
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
                                val linkStyle = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                )
                                Text(
                                    text = annotateMessageBody(
                                        row.body,
                                        row.mentions,
                                        mentionStyle,
                                        linkStyle,
                                        onLinkClick = { url -> openUrl(context, url) },
                                    ),
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
                                text = timeLabel(row, now, stringResource(R.string.chat_time_just_now)),
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
                // Signal-style: lift the chips so their top third tucks under the bubble's bottom edge
                // instead of floating below it. [overlapTop] both shifts the row up and reclaims that
                // height, so the next message moves up too (no dead gap left behind).
                ReactionRow(
                    reactions = row.reactions,
                    onToggle = { emoji -> onReact(row.id, emoji) },
                    modifier = Modifier.overlapTop(REACTION_OVERLAP),
                )
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
                        val reactWith = stringResource(R.string.chat_react_with, emoji)
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = { onPick(emoji) })
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = reactWith }
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

/**
 * How far the reaction row is pulled up into the bubble's bottom edge. Each chip carries a tall
 * invisible touch-target margin (`minimumInteractiveComponentSize`, ~48.dp box around a ~26.dp pill),
 * so this absorbs that margin plus a little more to leave roughly the chip's top third overlapping the
 * bubble. Tune by a few dp on-device if the overlap looks off.
 */
private val REACTION_OVERLAP = 16.dp

/**
 * Pull a composable up by [amount] *and* shrink the height it reports to its parent by the same amount,
 * so it overlaps whatever sits above it while letting following content move up to meet it (a plain
 * `offset` would leave a dead gap below). Used to tuck reaction chips under the message bubble.
 */
private fun Modifier.overlapTop(amount: Dp) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dy = amount.roundToPx()
    layout(placeable.width, (placeable.height - dy).coerceAtLeast(0)) {
        placeable.place(0, -dy)
    }
}

/** Aggregated reaction chips shown below a bubble; tapping a chip toggles the local user's reaction. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(
    reactions: List<ReactionSummary>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
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
    val chipDescription = pluralStringResource(
        R.plurals.chat_reaction_count, reaction.count, reaction.count, reaction.emoji,
    )
    Surface(
        shape = shape,
        color = if (reaction.mine) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (reaction.mine) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (reaction.mine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = chipDescription },
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

// A picked GIF/sticker can be tiny in pixel terms, so anchor bubble images to a fixed width (not just
// a max) to scale them up; cap the height for tall images. The slot is reserved at the image's true
// aspect ratio once known, so re-decoding never changes its height (see [AttachmentImage]).
private val ATTACHMENT_WIDTH = 220.dp
private val ATTACHMENT_MAX_HEIGHT = 260.dp

/** The image inside a bubble: the photo/GIF once fetched (tap to open fullscreen), or a loading placeholder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentImage(
    hash: String?,
    mime: String?,
    key: String?,
    ready: Boolean,
    flagged: Boolean,
    imageRatios: MutableMap<String, Float>,
    onImageClick: (BlobImage) -> Unit,
    onLongClick: () -> Unit,
) {
    // A flagged image stays hidden behind a placeholder until the user taps to view it.
    var revealed by remember(hash) { mutableStateOf(false) }
    val hidden = flagged && !revealed
    val image = if (ready && hash != null) BlobImage(hash, mime, key) else null
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                // The image consumes taps itself (reveal / open fullscreen), so a long-press would never
                // reach the bubble's combinedClickable — wire it here too so it opens the reaction picker.
                when {
                    hidden -> Modifier.combinedClickable(
                        onClick = { revealed = true },
                        onLongClick = onLongClick,
                    )
                    image != null -> Modifier.combinedClickable(
                        onClickLabel = stringResource(R.string.chat_view_photo),
                        onClick = { onImageClick(image) },
                        onLongClick = onLongClick,
                    )
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
            image != null -> {
                // Reserve the bubble at the image's true aspect ratio once we've measured it (cached by
                // hash above the list). Coil doesn't memory-cache animated GIFs, so each re-decodes as it
                // scrolls back into view; without a reserved height the slot collapses to zero mid-decode
                // and snaps back, making the list "skip" when flinging past several GIFs. First view (ratio
                // unknown) falls back to the width-anchored slot and records the ratio on load for next time.
                val ratio = hash?.let { imageRatios[it] }
                val sizeModifier = if (ratio != null && ratio > 0f) {
                    val h = (ATTACHMENT_WIDTH / ratio).coerceAtMost(ATTACHMENT_MAX_HEIGHT)
                    Modifier.size(width = h * ratio, height = h)
                } else {
                    Modifier.width(ATTACHMENT_WIDTH).heightIn(max = ATTACHMENT_MAX_HEIGHT)
                }
                AsyncImage(
                    model = image,
                    contentDescription = stringResource(R.string.chat_attachment_image_desc),
                    // ContentScale.Fit preserves aspect ratio; the reserved box already matches it.
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val measured = state.result.image
                        if (hash != null && measured.width > 0 && measured.height > 0) {
                            imageRatios[hash] = measured.width.toFloat() / measured.height.toFloat()
                        }
                    },
                    modifier = sizeModifier,
                )
            }
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

/** A tapped attachment plus the bubble metadata the fullscreen viewer shows in its top bar. */
private data class FullscreenImage(
    val image: BlobImage,
    val mine: Boolean,
    val senderName: String,
    val sentAt: Long,
)

/**
 * Fullscreen, pinch-to-zoom/pan viewer for a tapped image. The top bar floats white over the black
 * backdrop, Signal-style: a back arrow (left) dismisses it, the sender ("You"/peer) and relative send
 * time sit in the middle, and an overflow menu (right) offers Save; back press / outside tap also dismiss.
 */
@Composable
private fun FullscreenImageViewer(
    fullscreen: FullscreenImage,
    now: Long,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val image = fullscreen.image
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
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = if (fullscreen.mine) stringResource(R.string.chat_self_name)
                        else fullscreen.senderName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = relativeTime(
                            fullscreen.sentAt, now, stringResource(R.string.chat_time_just_now),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
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
}

private fun timeLabel(row: ChatRow, now: Long, justNow: String): String =
    relativeTime(row.sentAt, now, justNow)

private fun relativeTime(sentAt: Long, now: Long, justNow: String): String {
    // DateUtils renders anything under a minute (and any slight clock skew into the future) as
    // "0 minutes ago"; show a friendlier "Just now" instead.
    if (now - sentAt < DateUtils.MINUTE_IN_MILLIS) return justNow
    return DateUtils.getRelativeTimeSpanString(
        sentAt, now, DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

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

/** A centered, muted status line in the thread (e.g. "Alice left the chat"); not a sender bubble. */
@Composable
private fun SystemNotice(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
                    // The hint is a sibling overlay, so wire it onto the field as its accessibility
                    // label (the field would otherwise be an unnamed edit box); the visible hint is
                    // then marked decorative to avoid TalkBack reading it twice.
                    val messageHint = stringResource(R.string.chat_message_hint)
                    if (state.text.isEmpty()) {
                        Text(
                            messageHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    BasicTextField(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .contentReceiver(receiveContentListener)
                            .testTag("chat_input")
                            .semantics { contentDescription = messageHint },
                        inputTransformation = InputTransformation.maxLength(TextLimits.MESSAGE),
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
                    modifier = Modifier.size(48.dp).align(Alignment.CenterVertically).testTag("chat_send"),
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
        // 48dp touch target (a11y) with the small visible badge kept flush in the corner.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onClear, role = Role.Button),
            contentAlignment = Alignment.TopEnd,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(2.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_remove_attachment),
                    modifier = Modifier.padding(4.dp).size(16.dp),
                )
            }
        }
    }
}

// Previews exercise the text/no-attachment branches; attachment-bearing rows render only a loading
// placeholder in a preview (Coil/BlobImage has no DB-backed bytes), so sample rows leave attachments null.
@Preview(showBackground = true)
@Composable
fun MessageBubbleTheirsPreview() = KnitPreview {
    MessageBubble(
        row = ChatRow(
            id = "m1",
            body = "Hey! Are you coming to the trailhead at 8?",
            mine = false,
            senderName = "Ada Lovelace",
            senderNodeId = "node-ada",
            avatarHash = null,
            sentAt = PREVIEW_NOW - 5 * 60_000L,
            received = false,
            reactions = listOf(ReactionSummary("👍", 2, false), ReactionSummary("❤️", 1, true)),
        ),
        now = PREVIEW_NOW,
        showSenderName = true,
        onImageClick = {},
        onOpenProfile = {},
        onReact = { _, _ -> },
        onDelete = {},
        onBlock = {},
        onCopy = {},
    )
}

@Preview(showBackground = true)
@Composable
fun MessageBubbleMinePreview() = KnitPreview {
    MessageBubble(
        row = ChatRow(
            id = "m2",
            body = "On my way — see you in 10 minutes.",
            mine = true,
            senderName = "You",
            senderNodeId = "node-self",
            avatarHash = null,
            sentAt = PREVIEW_NOW - 2 * 60_000L,
            received = true,
        ),
        now = PREVIEW_NOW,
        showSenderName = false,
        onImageClick = {},
        onOpenProfile = {},
        onReact = { _, _ -> },
        onDelete = {},
        onBlock = {},
        onCopy = {},
    )
}

@Preview(showBackground = true)
@Composable
fun MessageBubbleWithMentionPreview() = KnitPreview {
    MessageBubble(
        row = ChatRow(
            id = "m3",
            body = "Thanks @Grace! See you both there.",
            mine = false,
            senderName = "Ada Lovelace",
            senderNodeId = "node-ada",
            avatarHash = null,
            sentAt = PREVIEW_NOW - 60 * 60_000L,
            received = false,
            mentions = listOf(Mention(nodeId = "node-grace", name = "Grace")),
        ),
        now = PREVIEW_NOW,
        showSenderName = true,
        onImageClick = {},
        onOpenProfile = {},
        onReact = { _, _ -> },
        onDelete = {},
        onBlock = {},
        onCopy = {},
    )
}

@Preview(showBackground = true)
@Composable
fun ReactionRowPreview() = KnitPreview {
    ReactionRow(
        reactions = listOf(
            ReactionSummary("👍", 3, true),
            ReactionSummary("❤️", 1, false),
            ReactionSummary("😂", 5, false),
        ),
        onToggle = {},
    )
}

@Preview(showBackground = true)
@Composable
fun ReactionPickerPreview() = KnitPreview {
    ReactionPicker(
        onPick = {},
        onCopy = {},
        onDelete = {},
        onBlock = {},
        onDismiss = {},
    )
}

@Preview(showBackground = true)
@Composable
fun MessageInputPreview() = KnitPreview {
    MessageInput(
        state = rememberTextFieldState("See you at 8"),
        pendingAttachment = null,
        candidates = emptyList(),
        onMentionAdded = {},
        onAttachClick = {},
        onClearAttachment = {},
        onReceiveImage = {},
        onSend = {},
    )
}

@Preview(showBackground = true)
@Composable
fun EmptyStatePreview() = KnitPreview {
    EmptyState()
}

@Preview(showBackground = true)
@Composable
fun SystemNoticePreview() = KnitPreview {
    SystemNotice(text = "Ada left the chat")
}
