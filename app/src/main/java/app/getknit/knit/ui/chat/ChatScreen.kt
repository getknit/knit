package app.getknit.knit.ui.chat

import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.emoji.EmojiCatalogLoader
import app.getknit.knit.data.emoji.RecentReactions
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.PeerRename
import app.getknit.knit.data.relay.AttachmentRelay
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.data.relay.dismissable
import app.getknit.knit.demo.DemoComposeCommand
import app.getknit.knit.demo.DemoComposer
import app.getknit.knit.identity.PeerLabel
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.camera.PhotoCapture
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.ConnectionStatusRow
import app.getknit.knit.ui.components.GroupAvatar
import app.getknit.knit.ui.components.KnitStitchIndicator
import app.getknit.knit.ui.components.PeerNameText
import app.getknit.knit.ui.components.RoomAvatar
import app.getknit.knit.ui.components.skeletonBlockColor
import app.getknit.knit.ui.components.skeletonPulseAlpha
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.openUrl
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.rememberPressScale
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import app.getknit.knit.ui.voice.VoiceNoteBubble
import app.getknit.knit.ui.voice.VoiceNotePreview
import app.getknit.knit.ui.voice.VoicePlayer
import app.getknit.knit.ui.voice.VoiceRecordingBar
import app.getknit.knit.ui.voice.VoiceStopButton
import app.getknit.knit.ui.voice.rememberMicGate
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

// Grace period before the send button shows a spinner, so a fast send never flashes one; it surfaces
// only for a genuinely slow send (chiefly the first after a cold start — the one-time model load).
private const val SEND_SPINNER_DELAY_MS = 300L

/** What the composer's trailing button currently is — the three states it crossfades between. */
private enum class SendAction { Sending, Send, Attach }

// Hold-to-talk gesture slop, in pixels. Generous on purpose: these are one-handed thumb gestures, and an
// accidental cancel loses a recording the user can't get back, so the thresholds sit well past ordinary
// finger jitter. Up locks hands-free; left cancels.
private const val LOCK_SLOP_PX = 120f
private const val CANCEL_SLOP_PX = 160f

// The long-press menu's quick-reaction row geometry (see ReactionPicker): a 48 dp cell + 4 dp gap, the row's
// 8 dp side padding twice, and the fewest recents worth showing beside the "+".
private const val QUICK_CELL_PITCH_DP = 52
private const val QUICK_ROW_PADDING_DP = 16
private const val MIN_QUICK_REACTIONS = 4

@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    onOpenGroupDetails: (conversationId: String) -> Unit,
    onOpenMessageDetails: (messageId: String) -> Unit,
    viewModel: ChatViewModel = koinViewModel { parametersOf(conversationId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val confirmAttachment by viewModel.confirmAttachment.collectAsStateWithLifecycle()
    val stagedAttachmentRelay by viewModel.stagedAttachmentRelay.collectAsStateWithLifecycle()
    val linkPreviewLoading by viewModel.linkPreviewLoading.collectAsStateWithLifecycle()
    val showPublicConsent by viewModel.showPublicConsent.collectAsStateWithLifecycle()
    val voiceRecording by viewModel.voiceRecording.collectAsStateWithLifecycle()
    val voicePlayback by viewModel.voicePlayback.collectAsStateWithLifecycle()
    val recentReactions by viewModel.recentReactions.collectAsStateWithLifecycle()
    val inputState = rememberTextFieldState()
    val shareInbox = koinInject<ShareInbox>()
    // Warm the emoji catalog (parse + per-glyph font check, once per process, off the main thread) as soon as
    // a chat is open, so the reaction picker's sheet composes its grid on its first frame instead of a skeleton.
    val emojiCatalog = koinInject<EmojiCatalogLoader>()
    LaunchedEffect(emojiCatalog) { emojiCatalog.load() }
    // Mentions the user inserted via autocomplete, draft-local alongside inputState (per the AGENTS.md
    // gotcha, draft state stays in the screen, not the ViewModel/DataStore). Filtered against the final
    // text on send so a mention whose "@name" was deleted doesn't ship.
    val pendingMentions = remember { mutableStateListOf<Mention>() }
    // The message being replied to (draft-local like inputState/pendingMentions, per the AGENTS.md
    // gotcha), rendered as a quote above the input until the reply is sent or cancelled. Null otherwise.
    // Lives here (not in the content) because the clearInput collector below must reset it atomically
    // with the input text and pending mentions.
    var replyingTo by remember { mutableStateOf<ReplyRef?>(null) }
    // A ticking clock so each bubble's relative timestamp ("2 min ago") recomposes as time passes;
    // System.currentTimeMillis() alone is not a tracked read and would freeze at first composition.
    val now by rememberCurrentTimeMillis()

    // Modern Android Photo Picker — needs no runtime permission. ImageOnly still includes GIFs.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(viewModel::attach)
        }

    // The storage picker, for everything that is not a photo. Also permission-free: the grant rides the
    // returned Uri, so Knit reads the bytes without ever holding storage access of its own.
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::attachFile)
        }

    // Where a received file goes: the user names the destination and Knit streams the decrypted bytes into
    // it. There is no "open" counterpart, deliberately — handing another app a readable copy would mean
    // either a plaintext staging file or a provider serving decrypted bytes, and ADR 029's invariant (an
    // attachment's plaintext lives in the encrypted blob store and nowhere else) is worth more than the
    // convenience. Saving keeps the user in charge of the one copy that leaves.
    var savingFile by remember { mutableStateOf<PendingSave?>(null) }
    var riskyFile by remember { mutableStateOf<PendingSave?>(null) }
    val fileSaver =
        rememberLauncherForActivityResult(CreateNamedDocument()) { uri ->
            val pending = savingFile
            savingFile = null
            if (uri != null && pending != null) viewModel.saveAttachmentTo(pending.hash, pending.key, uri)
        }
    val startSave: (PendingSave) -> Unit = { pending ->
        savingFile = pending
        fileSaver.launch(pending)
    }

    // Suppress message notifications while the chat is on screen, and clear any active one. The NavHost
    // back-stack entry is this composable's LifecycleOwner, so navigating away pauses (and popping
    // disposes) the screen — both paths re-enable notifications so messages arriving elsewhere notify.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        viewModel.onChatForeground()
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        viewModel.onChatBackground()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.onChatBackground()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
            replyingTo = null
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
            shared.imageUri?.let { viewModel.attach(it.toUri()) }
            // A shared *file* lands only where one can be sent — and the refusal is the ViewModel's, not
            // ours: this runs on first composition, before the state combine has read a peer row, so a
            // check against `state.canSendFile` here would refuse every capable peer exactly once.
            shared.fileUri?.let { viewModel.attachFile(it.toUri()) }
        }
    }
    // Debug trailer director: on the Nearby room, drive the REAL composer from scripted DemoComposer
    // commands — type char-by-char (which fires the real typing cue via MessageInput's snapshotFlow) then
    // send through the same path as the button. DEMO_DIRECTOR is a compile-time false in release, so R8
    // dead-code-eliminates this whole block; it never ships.
    if (BuildConfig.DEMO_DIRECTOR) {
        val demoComposer = koinInject<DemoComposer>()
        LaunchedEffect(conversationId) {
            if (conversationId != Conversations.NEARBY) return@LaunchedEffect
            demoComposer.commands.collect { cmd ->
                when (cmd) {
                    is DemoComposeCommand.Type -> {
                        val typed = StringBuilder()
                        cmd.text.forEach { ch ->
                            typed.append(ch)
                            inputState.setTextAndPlaceCursorAtEnd(typed.toString())
                            delay(cmd.perCharMs)
                        }
                    }

                    // clearInput (collected above) resets the field once the send is accepted.
                    DemoComposeCommand.Send -> {
                        viewModel.send(inputState.text.toString())
                    }
                }
            }
        }
    }
    // Blocking the peer of a DM hides this whole thread, so leave the now-empty screen.
    LaunchedEffect(Unit) {
        viewModel.closeChat.collect { onBack() }
    }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    // The camera takes over the whole screen rather than launching an Activity — same in-place shape
    // as the QR scanner, see [app.getknit.knit.ui.camera.PhotoCapture] and ADR 015.
    var capturing by remember { mutableStateOf(false) }
    if (capturing) {
        PhotoCapture(
            onCaptured = {
                capturing = false
                viewModel.attachCaptured(it)
            },
            onCancel = { capturing = false },
        )
        return
    }

    ChatScreenContent(
        conversationId = conversationId,
        state = state,
        isSending = isSending,
        inputState = inputState,
        pendingAttachment = pendingAttachment,
        stagedAttachmentRelay = stagedAttachmentRelay,
        replyingTo = replyingTo,
        now = now,
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onOpenGroupDetails = onOpenGroupDetails,
        onOpenMessageDetails = onOpenMessageDetails,
        onSend = {
            val text = inputState.text.toString()
            val applied = pendingMentions.filter { text.contains("@${it.name}") }
            // Don't clear here: a message blocked for abuse must keep the draft. The ViewModel
            // emits clearInput only once a message is actually accepted (see the collector above).
            viewModel.send(text, applied, replyingTo)
        },
        showPublicConsent = showPublicConsent,
        onAcceptPublicConsent = viewModel::acceptPublicConsent,
        onDismissPublicConsent = viewModel::dismissPublicConsent,
        onAttachClick = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onCameraClick = { capturing = true },
        onFileClick = { filePicker.launch(arrayOf(ANY_MIME)) },
        onSaveFile = { hash, key, name, mime ->
            val pending = PendingSave(hash, key, name, mime)
            // Nothing on the device can look inside an archive or an executable, so the recipient is told
            // that before they save one rather than after. Everything else saves straight away.
            if (FileTypes.isRisky(mime, name)) riskyFile = pending else startSave(pending)
        },
        onClearAttachment = viewModel::clearAttachment,
        onReceiveImage = viewModel::attach,
        onTyping = viewModel::onUserTyping,
        onDraftChanged = viewModel::onDraftChanged,
        linkPreviewLoading = linkPreviewLoading,
        onMentionAdded = { m -> if (pendingMentions.none { it == m }) pendingMentions.add(m) },
        onStartReply = { replyingTo = it },
        onCancelReply = { replyingTo = null },
        onReact = viewModel::react,
        quickReactions = recentReactions,
        onDeleteMessage = viewModel::deleteMessage,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
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
        onSaveAttachment = viewModel::saveAttachment,
        onDismissRelayNotice = viewModel::dismissRelayNotice,
        voiceRecording = voiceRecording,
        voicePlayback = voicePlayback,
        onStartVoice = { locked -> viewModel.startVoiceRecording(locked) },
        onLockVoice = viewModel::lockVoiceRecording,
        onStopVoice = viewModel::stopVoiceRecordingAndStage,
        onCancelVoice = viewModel::cancelVoiceRecording,
        onVoicePlay = viewModel::playVoice,
        onVoiceSeek = viewModel::seekVoice,
    )

    // Sending an explicit image is allowed but discouraged: confirm before staging a flagged one.
    // Stays with the wrapper: its trigger is the ViewModel's confirmAttachment flow and its buttons are
    // pure ViewModel calls.
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

    // Nothing on the device can classify an archive or an executable, so a save of one says so first. This
    // is the honest complement to the fact that Knit never offers to *open* a file it cannot screen: the
    // bytes still leave only where the user sends them, and an app package still meets the platform's own
    // unknown-sources gate afterwards. See docs/CONTENT_MODERATION.md §7.
    riskyFile?.let { pending ->
        AlertDialog(
            onDismissRequest = { riskyFile = null },
            title = { Text(stringResource(R.string.chat_file_risky_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_file_risky_body,
                        pending.name ?: stringResource(R.string.chat_file_unnamed),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        riskyFile = null
                        startSave(pending)
                    },
                ) {
                    Text(stringResource(R.string.chat_file_risky_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { riskyFile = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreenContent(
    conversationId: String,
    state: ChatUiState,
    isSending: Boolean = false,
    inputState: TextFieldState,
    pendingAttachment: AttachmentStore.Ingested?,
    // Reach of the staged photo, so the composer can caption it before the send rather than after.
    stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent,
    replyingTo: ReplyRef?,
    now: Long,
    onBack: () -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    onOpenGroupDetails: (conversationId: String) -> Unit,
    // Opens a message's details screen from its long-press menu. Defaulted so previews and the
    // content-level tests can leave it out.
    onOpenMessageDetails: (messageId: String) -> Unit = {},
    onSend: () -> Unit,
    // The bridged room's first-use disclosure. Raised by the ViewModel when a post is attempted before it
    // has been accepted, so the draft the user already wrote is what gets sent once they accept.
    showPublicConsent: Boolean = false,
    onAcceptPublicConsent: () -> Unit = {},
    onDismissPublicConsent: () -> Unit = {},
    onAttachClick: () -> Unit,
    // Long-pressing the attach affordance opens the in-app camera (ADR 029, unchanged); the paperclip in
    // the field opens the file picker. Defaulted so previews and the content tests need no extra wiring.
    onCameraClick: () -> Unit = {},
    onFileClick: () -> Unit = {},
    onSaveFile: (hash: String, key: String?, name: String?, mime: String?) -> Unit = { _, _, _, _ -> },
    onClearAttachment: () -> Unit,
    onReceiveImage: (Uri) -> Unit,
    onTyping: () -> Unit,
    // Every edit of the draft, so a link in it can grow a preview card; defaulted for the @Preview and test call sites.
    onDraftChanged: (String) -> Unit = {},
    // True while that card is being fetched; the input bar shows a transient "Loading preview…" line.
    linkPreviewLoading: Boolean = false,
    onMentionAdded: (Mention) -> Unit,
    onStartReply: (ReplyRef) -> Unit,
    onCancelReply: () -> Unit,
    onReact: (messageId: String, emoji: String) -> Unit,
    // The quick-reaction row's emoji (most recent picks). Defaulted so previews and the content tests keep working.
    quickReactions: List<String> = RecentReactions.DEFAULTS,
    onDeleteMessage: (messageId: String) -> Unit,
    onBlock: (nodeId: String) -> Unit,
    onUnblock: (nodeId: String) -> Unit,
    onCopy: (text: String) -> Unit,
    onSaveAttachment: (hash: String, key: String?, mime: String?) -> Unit,
    // Closes the room's relay notice for good. Defaulted so previews and the content tests that never
    // render a dismissable notice need not name it.
    onDismissRelayNotice: () -> Unit = {},
    // Voice notes. `voiceRecording` is non-null only while the mic is live, and replaces the whole input row
    // while it is; `voicePlayback` is the app-wide "which note is sounding" state each bubble matches its own
    // hash against. All defaulted so the previews and the content-level tests need not name them.
    voiceRecording: ChatViewModel.VoiceRecording? = null,
    voicePlayback: VoicePlayer.Playback? = null,
    onStartVoice: (locked: Boolean) -> Unit = {},
    onLockVoice: () -> Unit = {},
    onStopVoice: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onVoicePlay: (hash: String, key: String?) -> Unit = { _, _ -> },
    onVoiceSeek: (hash: String, positionMs: Int) -> Unit = { _, _ -> },
) {
    var fullscreenImage by remember { mutableStateOf<FullscreenImage?>(null) }
    // The message the full emoji picker is open for (from the long-press menu's "+"), or null. Saveable so a
    // rotation mid-pick keeps the sheet; hosted here, not in the row, so scrolling can't dispose it.
    var emojiSheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The message a tapped quote scrolled to, briefly highlighted then cleared (see the LaunchedEffect
    // below and MessageBubble). Null when nothing is highlighted.
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var showEncryptionInfo by remember { mutableStateOf(false) }
    var showRelayInfo by remember { mutableStateOf(false) }
    var showLoraInfo by remember { mutableStateOf(false) }
    var showMeshInfo by remember { mutableStateOf(false) }
    // The message whose "nearby only" marker was tapped, so the explanation can name its actual cause
    // (too big vs. relays that carry no photos). Null when no explanation is open.
    var relayMarkerExplained by remember { mutableStateOf<AttachmentRelay?>(null) }
    // The contact a tapped heard-author avatar resolved to, held until the caveat is dismissed or followed
    // through to their profile. Null when no explanation is open.
    var heardAuthorExplained by remember { mutableStateOf<HeardAuthor?>(null) }
    val listState = rememberLazyListState()
    // Aspect ratios of already-decoded image attachments, keyed by content hash, kept above the
    // LazyColumn so they survive item disposal. Coil doesn't memory-cache animated GIFs, so each one
    // re-decodes every time it scrolls back into view; without a reserved height the bubble collapses
    // to zero mid-decode and snaps back, which is what made the list "skip" when flinging past several
    // GIFs. Caching the ratio lets a re-entering bubble reserve the right height before it decodes.
    val imageRatios = remember { HashMap<String, Float>() }
    val scrollScope = rememberCoroutineScope()
    // Hoisted because the reply snippet is built inside a plain (non-composable) lambda; see
    // buildReplySnippet for why a voice note's quote label rides the snippet rather than the wire.
    val voiceQuoteLabel = stringResource(R.string.chat_reply_voice)
    val fileQuoteLabel = stringResource(R.string.chat_list_preview_file)

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

    // Reveal the typing indicator when it appears: it's inserted at the visual bottom, where scroll
    // anchoring would otherwise leave it clipped below the viewport (the effect above only fires for a
    // new message). Same parked-at-bottom gate; a user scrolled up into history is never yanked down.
    val typing = state.typingPeers.isNotEmpty()
    LaunchedEffect(typing) {
        if (typing && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    // A tapped quote scrolls to and briefly highlights its original (see MessageBubble); fade it after a
    // beat so the flash is transient.
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1200)
            highlightedMessageId = null
        }
    }

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
                            // Nearby room: the Knit mesh mark (same glyph as the chat-list row) + title +
                            // live connection status. The avatar is decorative — the visible title names it
                            // and the room has nothing to open — so it carries no separate label or tap.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RoomAvatar(size = 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.nearby_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    ConnectionStatusRow(
                                        neighborCount = state.neighborCount,
                                        health = state.transportHealth,
                                        relay = state.relayPlane,
                                        lora = state.loraPlane,
                                    )
                                }
                            }
                        }

                        state.isBridged -> {
                            // The Meshtastic room: the same room glyph, its channel name as the title, and a
                            // subtitle saying whose channel it is. No ConnectionStatusRow — the composer's
                            // gate carries the radio's state, and Knit's own radios say nothing about it.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RoomAvatar(size = 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = state.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_mesh_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text =
                                            pluralStringResource(
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
                                PeerNameText(
                                    text = state.title,
                                    discriminator = state.titleDiscriminator,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
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
                    // The overflow lives on DM and group threads (the broadcast room has no actions).
                    // A DM offers Block/Unblock; a group offers Settings, which opens the same
                    // group-details screen as tapping the group avatar (the avatar tap stays too).
                    // Neither room offers anything: the bridged one has no peer to block — its authors are
                    // not peers, and blocking the gateway would silence a contact over somebody else's post.
                    if (!state.isRoom && !state.isBridged) {
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
                                if (state.isGroup) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_group_settings)) },
                                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                        modifier = Modifier.testTag("chat_group_settings"),
                                        onClick = {
                                            headerMenuOpen = false
                                            onOpenGroupDetails(conversationId)
                                        },
                                    )
                                } else {
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
                                            headerMenuOpen = false
                                            if (state.isBlocked) {
                                                onUnblock(conversationId)
                                            } else {
                                                onBlock(conversationId)
                                            }
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
            // The Meshtastic room posts through this phone's own radio, so with no radio there is nothing to
            // type into: the composer gives way to a line saying so. A radio that is merely down keeps the
            // composer — that state flaps on every Bluetooth reconnect, and unmounting the field would drop
            // the keyboard mid-sentence — and only the hint changes.
            when (state.publicPostGate) {
                PublicPostGate.NoRadio -> {
                    MeshRoomFooter(stringResource(R.string.chat_mesh_no_radio))
                }

                PublicPostGate.ChannelUnusable -> {
                    MeshRoomFooter(stringResource(R.string.chat_mesh_channel_unusable))
                }

                PublicPostGate.Open, PublicPostGate.RadioDown -> {
                    MessageInput(
                        state = inputState,
                        isSending = isSending,
                        pendingAttachment = pendingAttachment,
                        stagedAttachmentRelay = stagedAttachmentRelay,
                        candidates = state.mentionCandidates,
                        replyingTo = replyingTo,
                        myNodeId = state.myNodeId,
                        onCancelReply = onCancelReply,
                        onMentionAdded = onMentionAdded,
                        onAttachClick = onAttachClick,
                        onCameraClick = onCameraClick,
                        // Files are DM/group only, for the reason voice notes are: nothing on the device can screen
                        // one, and the room floods unencrypted to everyone in range. See docs/CONTENT_MODERATION.md §7.
                        fileEnabled = state.canSendFile,
                        // Naming the author in the hint is the whole of the ADR 049 exception's visible surface:
                        // everywhere else on the radio this user is "Knit abcd", and here they are themselves.
                        hint =
                            when {
                                !state.isBridged -> {
                                    null
                                }

                                // A link that flaps on every Bluetooth reconnect: keep the field (and the keyboard)
                                // and let the hint say why a send would be refused.
                                state.publicPostGate == PublicPostGate.RadioDown -> {
                                    stringResource(R.string.chat_mesh_radio_down)
                                }

                                state.publicChannelKeyIsPublic -> {
                                    stringResource(R.string.chat_mesh_hint)
                                }

                                else -> {
                                    stringResource(R.string.chat_mesh_hint_keyed)
                                }
                            },
                        onFileClick = onFileClick,
                        onClearAttachment = onClearAttachment,
                        onReceiveImage = onReceiveImage,
                        onSend = onSend,
                        onTyping = onTyping,
                        onDraftChanged = onDraftChanged,
                        linkPreviewLoading = linkPreviewLoading,
                        loraBudget =
                            loraBudgetFor(state.loraCarry, replying = replyingTo != null, attached = pendingAttachment != null),
                        maxBytes = state.publicPostBudget,
                        // Voice notes are DM/group only: the Nearby room floods unencrypted to everyone in range and
                        // no on-device model can screen speech, so it is the one place unscreenable audio is not
                        // offered. See docs/CONTENT_MODERATION.md.
                        attachEnabled = !state.isBridged,
                        voiceEnabled = !state.isRoom && !state.isBridged,
                        voiceRecording = voiceRecording,
                        voicePlayback = voicePlayback,
                        onStartVoice = onStartVoice,
                        onLockVoice = onLockVoice,
                        onStopVoice = onStopVoice,
                        onCancelVoice = onCancelVoice,
                        onVoicePlay = onVoicePlay,
                        onVoiceSeek = onVoiceSeek,
                    )
                }
            }
        },
    ) { padding ->
        // Column rather than a list item so the relay notice stays pinned: it states a standing fact
        // about the whole thread, and one that scrolled away would be found only by accident.
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The two pinned notices come and go with the radios. animateContentSize on the band they share
            // (rather than AnimatedVisibility on each) means the thread below slides down and back rather
            // than jumping a notice's height in one frame — and it needs no retained copy of a notice's text
            // to draw while it collapses, which is the whole reason each notice can keep its early return.
            Column(modifier = Modifier.animateContentSize(KnitMotion.spatial())) {
                RelayNotice(
                    reach = state.relayReach,
                    onClick = { showRelayInfo = true },
                    // Only the room's notice may be closed; a pending thread's clears itself.
                    onDismiss = onDismissRelayNotice.takeIf { dismissable(state.relayReach) },
                )
                LoraNotice(reach = state.loraReach, onClick = { showLoraInfo = true })
                if (state.isBridged) {
                    MeshRoomNotice(
                        keyIsPublic = state.publicChannelKeyIsPublic,
                        onClick = { showMeshInfo = true },
                    )
                }
            }
            // Cold open: the state is a five-way combine, and its Room arm reads the *whole* thread and folds
            // every row before it first emits. On a conversation with a lot of history that gap is long
            // enough to see — and the seed emission carries no rows, so what would show is the "no messages
            // yet" copy on a thread that has hundreds. Show the shape of a thread instead, then cross-fade
            // to the real rows, which land in the same geometry.
            val threadEnter = KnitMotion.enterFade()
            val threadExit = KnitMotion.exitFade()
            // weight(1f), not fillMaxSize(): the notice above is an unweighted sibling, so the thread must
            // take the space that is left rather than ask for the whole column.
            AnimatedContent(
                targetState = state.isLoading,
                transitionSpec = { threadEnter togetherWith threadExit },
                label = "chatLoading",
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { loading ->
                if (loading) {
                    ChatSkeleton(
                        // A DM draws no avatar column (see `showSenderName` below), so neither may its
                        // skeleton, or every bubble shifts sideways as the real rows replace it.
                        withAvatars = state.isRoom || state.isBridged || state.isGroup,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.rows.isEmpty() && state.typingPeers.isEmpty()) {
                    EmptyState(modifier = Modifier.fillMaxSize(), isBridged = state.isBridged)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        // Bottom-anchored so the thread opens on the newest message with no scroll; the data is
                        // reversed to match, making index 0 the newest row, drawn at the bottom. Arrangement.Bottom
                        // keeps a short thread (fewer rows than fit on screen) resting just above the input rather
                        // than floating at the top with a gap beneath the newest bubble.
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                        reverseLayout = true,
                    ) {
                        // Reverse layout: the first item is drawn at the visual bottom, so the typing indicator sits
                        // directly above the input and below the newest message (Signal-style, scrolls with content).
                        if (state.typingPeers.isNotEmpty()) {
                            item(key = "typing_indicator") {
                                // Fades with the same spec as a message bubble: the indicator arriving and
                                // leaving is a state change like any other, and it used to blink.
                                TypingIndicatorRow(
                                    peers = state.typingPeers,
                                    modifier =
                                        Modifier.animateItem(
                                            placementSpec = null,
                                            fadeInSpec = KnitMotion.fastEffects(),
                                            fadeOutSpec = KnitMotion.fastEffects(),
                                        ),
                                )
                            }
                        }
                        items(state.rows.asReversed(), key = { it.id }) { row ->
                            // Fade only, no placement animation (`placementSpec = null`): the three
                            // LaunchedEffects above already drive animateScrollToItem(0) when a message or a
                            // typing peer arrives, and a placement animation would be sliding rows in one
                            // direction while the scroll slides the viewport in the other. A new bubble
                            // appears; nothing travels.
                            val itemMotion =
                                Modifier.animateItem(
                                    placementSpec = null,
                                    fadeInSpec = KnitMotion.fastEffects(),
                                    fadeOutSpec = KnitMotion.fastEffects(),
                                )
                            val notice = statusNoticeText(row)
                            if (notice != null) {
                                SystemNotice(text = notice, modifier = itemMotion)
                            } else {
                                MessageBubble(
                                    row,
                                    modifier = itemMotion,
                                    now = now,
                                    // In a 1:1 DM the peer's name is in the top bar, so don't repeat it on every
                                    // received bubble; show it only where multiple people can speak.
                                    // Every room and group names its authors; a DM does not (the header already
                                    // does). The bridged room needs it most of all — its posts come from
                                    // strangers, and an unattributed one would read as if Knit knew who sent it.
                                    showSenderName = state.isRoom || state.isBridged || state.isGroup,
                                    myNodeId = state.myNodeId,
                                    imageRatios = imageRatios,
                                    highlighted = row.id == highlightedMessageId,
                                    onImageClick = { fullscreenImage = it },
                                    onSaveFile = onSaveFile,
                                    onOpenProfile = onOpenProfile,
                                    onReact = onReact,
                                    quickReactions = quickReactions,
                                    onMoreReactions = { emojiSheetFor = it },
                                    onReply = { msg ->
                                        onStartReply(
                                            ReplyRef(
                                                messageId = msg.id,
                                                authorId = msg.senderNodeId,
                                                author = msg.senderPlainName,
                                                snippet =
                                                    buildReplySnippet(
                                                        msg.body,
                                                        msg.moderationFlagged,
                                                        attachmentLabel =
                                                            when {
                                                                msg.attachmentName != null -> {
                                                                    fileQuoteLabel.format(msg.attachmentName)
                                                                }

                                                                VoiceAudio.isVoice(msg.attachmentMime) -> {
                                                                    voiceQuoteLabel
                                                                }

                                                                else -> {
                                                                    null
                                                                }
                                                            },
                                                    ),
                                                // A quoted card message shows its link text, not an attachment glyph.
                                                hasAttachment = msg.attachmentHash != null && msg.attachmentMime != LinkPreviewBlob.MIME,
                                            ),
                                        )
                                    },
                                    onQuoteClick = { targetId ->
                                        val idx = state.rows.asReversed().indexOfFirst { it.id == targetId }
                                        if (idx >= 0) {
                                            highlightedMessageId = targetId
                                            scrollScope.launch { listState.animateScrollToItem(idx) }
                                        }
                                    },
                                    onDelete = onDeleteMessage,
                                    onBlock = onBlock,
                                    onCopy = onCopy,
                                    onOpenMessageDetails = onOpenMessageDetails,
                                    onExplainRelay = { relayMarkerExplained = it },
                                    onExplainHeardAuthor = { peerId, name ->
                                        heardAuthorExplained = HeardAuthor(peerId, name)
                                    },
                                    voicePlayback = voicePlayback,
                                    onVoicePlay = onVoicePlay,
                                    onVoiceSeek = onVoiceSeek,
                                )
                            }
                        }
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
            onSave = { onSaveAttachment(fs.image.hash, fs.image.key, fs.image.mime) },
        )
    }

    emojiSheetFor?.let { messageId ->
        EmojiPickerSheet(
            onPick = { emoji ->
                onReact(messageId, emoji)
                emojiSheetFor = null
            },
            onDismiss = { emojiSheetFor = null },
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

    if (showRelayInfo) {
        val isRoom = state.relayReach == RelayReach.Room
        AlertDialog(
            onDismissRequest = { showRelayInfo = false },
            icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            title = {
                Text(stringResource(if (isRoom) R.string.chat_relay_room_title else R.string.chat_relay_pending_title))
            },
            text = {
                Text(stringResource(if (isRoom) R.string.chat_relay_room_body else R.string.chat_relay_pending_body))
            },
            confirmButton = {
                TextButton(onClick = { showRelayInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showLoraInfo) {
        val (loraTitle, loraBody) =
            when (state.loraReach) {
                LoraReach.LoraOnlyDmsOff -> R.string.chat_lora_only_dms_off_title to R.string.chat_lora_only_dms_off_body
                LoraReach.LoraOnlySaturated -> R.string.chat_lora_saturated_title to R.string.chat_lora_saturated_body
                LoraReach.RoomSaturated -> R.string.chat_lora_room_saturated_title to R.string.chat_lora_room_saturated_body
                LoraReach.GroupUnsupported -> R.string.chat_lora_group_title to R.string.chat_lora_group_body
                LoraReach.LoraOnly, LoraReach.Silent -> R.string.chat_lora_only_title to R.string.chat_lora_only_body
            }
        // Every DM body names the peer; the room's and the group's are about a set of people and take no
        // argument, so they are resolved without one rather than formatted against a placeholder they do
        // not carry.
        val loraNamesPeer =
            state.loraReach != LoraReach.RoomSaturated && state.loraReach != LoraReach.GroupUnsupported
        AlertDialog(
            onDismissRequest = { showLoraInfo = false },
            icon = { Icon(Icons.Outlined.Sensors, contentDescription = null) },
            title = { Text(stringResource(loraTitle)) },
            text = { Text(if (loraNamesPeer) stringResource(loraBody, state.title) else stringResource(loraBody)) },
            confirmButton = {
                TextButton(onClick = { showLoraInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showMeshInfo) {
        AlertDialog(
            onDismissRequest = { showMeshInfo = false },
            icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
            title = { Text(stringResource(R.string.chat_mesh_room_title)) },
            text = { Text(stringResource(R.string.chat_mesh_room_body)) },
            confirmButton = {
                TextButton(onClick = { showMeshInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showPublicConsent) {
        ModalBottomSheet(
            onDismissRequest = onDismissPublicConsent,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PublicPostConsentBody(onAccept = onAcceptPublicConsent, onDecline = onDismissPublicConsent)
        }
    }

    relayMarkerExplained?.let { cause ->
        // The peer's name makes the fallback concrete ("arrives when you and Ana are in range") rather
        // than abstract; in the room and in groups the title is already the collective noun.
        val other = state.title
        AlertDialog(
            onDismissRequest = { relayMarkerExplained = null },
            icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            title = {
                Text(
                    stringResource(
                        if (cause == AttachmentRelay.Unsupported) {
                            R.string.chat_relay_unsupported_title
                        } else {
                            R.string.chat_relay_too_large_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (cause == AttachmentRelay.Unsupported) {
                            R.string.chat_relay_unsupported_body
                        } else {
                            R.string.chat_relay_too_large_body
                        },
                        other,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { relayMarkerExplained = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    heardAuthorExplained?.let { author ->
        // Dismiss is the plain button and "Open profile" the confirm, because the point of the dialog is the
        // caveat, not the errand: someone who taps a face on this channel should be able to read why the name
        // is a guess and stop there. Following through opens the *contact's* profile by peer id — the row's
        // sender is this phone on a heard post, so `senderNodeId` would open our own.
        AlertDialog(
            onDismissRequest = { heardAuthorExplained = null },
            icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
            title = { Text(stringResource(R.string.chat_mesh_match_title, author.name)) },
            text = { Text(stringResource(R.string.chat_mesh_match_body, author.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        heardAuthorExplained = null
                        onOpenProfile(author.peerId)
                    },
                ) {
                    Text(stringResource(R.string.chat_mesh_match_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { heardAuthorExplained = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/**
 * A tapped heard-author avatar that resolved to a contact: who to name in the caveat, and whose profile
 * the confirm button opens.
 */
private data class HeardAuthor(
    val peerId: String,
    val name: String,
)

/**
 * The standing statement about a thread's Internet reach, pinned under the header.
 *
 * Renders nothing for [RelayReach.Silent] and [RelayReach.Covered] — coverage is the happy path, and a
 * relay outage is transient and heals itself, so neither earns a permanent line of chrome. Tinted
 * `surfaceVariant` rather than `errorContainer` on purpose: nothing here has failed. The message is
 * delivered by radio exactly as it always was; this only says the Internet shortcut is not available.
 *
 * [onDismiss], when non-null, renders a close button — it is null for every reach but the room's, whose
 * notice is the one that would otherwise never retire itself. See `dismissable`.
 */
@Composable
private fun RelayNotice(
    reach: RelayReach,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    val label =
        when (reach) {
            RelayReach.Room -> R.string.chat_relay_room
            RelayReach.Pending -> R.string.chat_relay_pending
            RelayReach.Silent, RelayReach.Covered -> return
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_relay_notice"),
    ) {
        Row(
            // The close button is a sibling of the clickable, not inside it: the row's tap opens the
            // explanation, and a dismiss that also fired it would flash the dialog it just closed.
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onClick, role = Role.Button)
                        .padding(
                            start = 16.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                            end = if (onDismiss != null) 8.dp else 16.dp,
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(text = stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("chat_relay_notice_dismiss"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_relay_notice_dismiss),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The standing statement that this DM's peer is reachable over the LoRa board alone — or, in the room,
 * that the board's airtime window is spent while somebody is behind it. Pinned under the header beside the
 * relay notice; renders nothing for [LoraReach.Silent]. The same `surfaceVariant` tint as [RelayNotice],
 * for the same reason: nothing has failed — the message still goes, at SMS pace. No dismiss affordance,
 * unlike [RelayNotice]: every state here clears itself once the radios or the window say so.
 */
@Composable
private fun LoraNotice(
    reach: LoraReach,
    onClick: () -> Unit,
) {
    val label =
        when (reach) {
            LoraReach.LoraOnly -> R.string.chat_lora_only
            LoraReach.LoraOnlyDmsOff -> R.string.chat_lora_only_dms_off
            LoraReach.LoraOnlySaturated -> R.string.chat_lora_saturated
            LoraReach.RoomSaturated -> R.string.chat_lora_room_saturated
            LoraReach.GroupUnsupported -> R.string.chat_lora_group
            LoraReach.Silent -> return
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_lora_notice"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick, role = Role.Button)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Sensors,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(text = stringResource(label), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The disclosure shown once, before the first post a user sends to the radio channel.
 *
 * Split can/cannot rather than a paragraph, the way the Internet plane's sheet is (`ui/relay/`), because the
 * two halves are what a person actually has to weigh. The third line is the linkability the first two do not
 * cover: no name rides out with a post any more (ADR 2026-09.9469), but the board's own `Knit abcd` does, and
 * anyone already holding the user's contact card can read that back as them. Burying that mid-sentence would
 * be the kind of technically-true disclosure nobody reads.
 *
 * Decline first, accept second — the destructive-ish choice should not be the one under the thumb.
 */
@Composable
private fun PublicPostConsentBody(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.chat_mesh_consent_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.chat_mesh_consent_can_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.chat_mesh_consent_can_body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.chat_mesh_consent_cannot_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.chat_mesh_consent_cannot_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.chat_mesh_consent_scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.chat_mesh_consent_decline)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("chat_mesh_consent_accept"),
            ) { Text(stringResource(R.string.chat_mesh_consent_accept)) }
        }
    }
}

/**
 * What stands in for the composer when the Meshtastic room has nothing to post through: a line saying so, in
 * the slot the input would occupy. Its own footer rather than a disabled [MessageInput] because a greyed-out
 * text field is a puzzle — the user taps it, nothing happens, and nothing explains why.
 */
@Composable
private fun MeshRoomFooter(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_mesh_footer"),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/**
 * The Meshtastic room's standing notice: this is a radio channel Knit's encryption does not cover, and the
 * names on it are unverified — a contact it lines a post up with included, since that match rests on a
 * self-asserted node number. Both facts belong here rather than only in the dialog this opens, because the
 * room is drawn like every other thread in Knit and a person who never taps the strip would take the padlock
 * elsewhere in the app to apply.
 *
 * [keyIsPublic] picks how strongly to say the first one, off the board's own slot 0
 * ([PublicChannelPolicy.primaryKeyIsPublic]). On the channel ADR 045 leaves a Knit board on, the key is
 * Meshtastic's published default and the AES on the air keeps nobody out, so the honest word is
 * *unencrypted*. On a slot 0 the user keyed themselves it is shared with every radio holding that key and
 * with nobody else — better than open, still not end-to-end, and the notice says exactly that instead of
 * calling their own channel open. Unknown reads as public: the room says the worse of the two rather than
 * risk a person believing an open channel is private.
 *
 * Unlike its two neighbours this one is not a *reach* state — nothing about it comes and goes with the
 * radios, and there is no condition under which it stops being true — so it takes no state and never
 * animates away. It is also not dismissable, for the reason the relay room notice is dismissable and this one
 * cannot be: dismissing that one hides a fact about *Knit*, while this hides a warning about strangers.
 */
@Composable
private fun MeshRoomNotice(
    keyIsPublic: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_mesh_notice"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick, role = Role.Button)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text =
                    stringResource(
                        if (keyIsPublic) R.string.chat_mesh_room_notice else R.string.chat_mesh_room_notice_keyed,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
        modifier =
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(3.dp)
                .size(18.dp),
    )
}

/**
 * The in-bubble "nearby only" marker: this attachment will not cross the Internet plane.
 *
 * Deliberately unalarming — `onSurfaceVariant`, not `error`. Nothing has gone wrong: the message is
 * being delivered by radio exactly as it would be with relays switched off. Only *permanent* causes
 * reach this marker (too large for every relay, or relays that carry no photos at all), so it never
 * appears and then quietly disappears once a retry succeeds; see `attachmentReach`.
 */
@Composable
private fun ColumnScope.NearbyOnlyMarker(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 2.dp, vertical = 1.dp)
                .testTag("chat_relay_marker"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = stringResource(R.string.chat_relay_nearby_only_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.chat_relay_nearby_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatRow,
    now: Long,
    showSenderName: Boolean,
    modifier: Modifier = Modifier,
    // myNodeId drives the quote's viewer-relative "You" swap; defaulted for the @Preview call sites.
    myNodeId: String = "",
    // Hash-keyed aspect-ratio cache shared across the list so a re-entering image reserves its height
    // before decode (see [ChatScreen]). Defaulted so the @Preview call sites need no extra wiring.
    imageRatios: MutableMap<String, Float> = HashMap(),
    // True to briefly highlight this bubble after a quote-tap scrolled to it; defaulted for previews.
    highlighted: Boolean = false,
    onImageClick: (FullscreenImage) -> Unit,
    // Saving a file attachment through the storage picker; defaulted so the @Preview call sites and the
    // content tests need no extra wiring.
    onSaveFile: (hash: String, key: String?, name: String?, mime: String?) -> Unit = { _, _, _, _ -> },
    onOpenProfile: (nodeId: String) -> Unit,
    onReact: (messageId: String, emoji: String) -> Unit,
    // The long-press menu's quick-reaction row and its "+" (opens the full picker); defaulted for previews.
    quickReactions: List<String> = RecentReactions.DEFAULTS,
    onMoreReactions: (messageId: String) -> Unit = {},
    // Reply to this message / tap its quote to jump to the original; defaulted no-ops for previews.
    onReply: (ChatRow) -> Unit = {},
    onQuoteClick: (messageId: String) -> Unit = {},
    onDelete: (messageId: String) -> Unit,
    onBlock: (nodeId: String) -> Unit,
    onCopy: (text: String) -> Unit,
    // Opens this message's details screen ("Message info"); defaulted no-op for previews.
    onOpenMessageDetails: (messageId: String) -> Unit = {},
    // Tapping the "nearby only" marker; defaulted no-op for previews and for bubbles that never show it.
    onExplainRelay: (AttachmentRelay) -> Unit = {},
    // Tapping the avatar of a heard author who resolved to a contact: the caveat, then the way through to
    // that contact's profile. Defaulted no-op for previews and for every bubble outside the bridged room.
    onExplainHeardAuthor: (peerId: String, name: String) -> Unit = { _, _ -> },
    // App-wide voice playback state, and this bubble's play/seek actions. The state is app-wide because only
    // one voice note may sound at a time; a bubble compares its own hash against it to know whether the
    // controls it draws are the live ones. Defaulted for the @Preview call sites.
    voicePlayback: VoicePlayer.Playback? = null,
    onVoicePlay: (hash: String, key: String?) -> Unit = { _, _ -> },
    onVoiceSeek: (hash: String, positionMs: Int) -> Unit = { _, _ -> },
) {
    val maxBubbleWidth =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        } * 0.8f
    val bubbleShape =
        if (row.mine) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        }
    var showPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // A message flagged by the on-device text moderator is collapsed until the user taps to reveal it.
    var revealed by remember(row.id) { mutableStateOf(false) }
    val context = LocalContext.current
    // Fast light-up then slow fade when a tapped quote scrolls to this bubble (see ChatScreen).
    val highlight by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(durationMillis = if (highlighted) 120 else 600),
        label = "quoteHighlight",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (row.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!row.mine && showSenderName) {
            // Four cases, because the name beside a heard Meshtastic author is an unauthenticated claim off
            // an open channel. A Knit author's avatar opens their profile, as everywhere. A heard *stranger*
            // has no profile to open, so the avatar stays inert. A heard author whose node number matched a
            // contact's profile wears that contact's face and does take a tap — but it opens the caveat
            // first, never `profileDetails` directly, because the match is a self-asserted profile field
            // rather than a signature, and a straight-through tap would offer to message somebody who may
            // never have said any of it. The exception is a match the radio's own signature verified
            // (`MeshOrigin.verified`): the words provably came from the radio that contact's profile names,
            // so the avatar opens their profile as a Knit author's does — by peer id, since the row's sender
            // is this phone on a heard post.
            val matchedPeer = row.origin?.peerId
            val verifiedPeer = matchedPeer?.takeIf { row.origin?.verified == true }
            Avatar(
                avatarHash = row.avatarHash,
                name = row.senderName,
                size = 40.dp,
                contentDescription =
                    when {
                        verifiedPeer != null -> stringResource(R.string.chat_mesh_author_verified, row.senderName)
                        matchedPeer != null -> stringResource(R.string.chat_mesh_author_contact, row.senderName)
                        row.origin != null -> stringResource(R.string.chat_mesh_author, row.senderName)
                        else -> stringResource(R.string.chat_view_profile, row.senderName)
                    },
                onClick =
                    when {
                        verifiedPeer != null -> ({ onOpenProfile(verifiedPeer) })
                        matchedPeer != null -> ({ onExplainHeardAuthor(matchedPeer, row.senderPlainName) })
                        row.origin != null -> null
                        else -> ({ onOpenProfile(row.senderNodeId) })
                    },
                onClickLabel = if (matchedPeer != null && verifiedPeer == null) stringResource(R.string.chat_mesh_author_action) else null,
            )
            Spacer(Modifier.width(8.dp))
        }
        // Bubble + its reaction chips stack vertically, aligned to the message's side. The Box anchors
        // the long-press picker popup directly above the bubble.
        Column(horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start) {
            Box {
                Surface(
                    color =
                        lerp(
                            if (row.mine) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            MaterialTheme.colorScheme.primary,
                            0.22f * highlight,
                        ),
                    contentColor =
                        if (row.mine) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    shape = bubbleShape,
                    modifier =
                        Modifier
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PeerNameText(
                                    text = row.senderName,
                                    discriminator = row.senderDiscriminator,
                                    style = MaterialTheme.typography.labelMedium,
                                    // Not the primary colour a Knit author's name takes: that colour is what
                                    // the eye reads as "a person this app knows", and a heard author — a
                                    // resolved contact included — is the one case where it would be a lie.
                                    // Unless the radio's signature verified the match, which is the one
                                    // case where it is true, and the shield beside the name says why.
                                    color =
                                        if (row.origin != null && !row.origin.verified) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                )
                                if (row.origin?.verified == true) {
                                    Spacer(Modifier.width(4.dp))
                                    // The DM header's verified shield at label size: Meshtastic's own apps
                                    // draw a shield on a signed broadcast, so a reader who knows one knows this.
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = stringResource(R.string.chat_mesh_signed_by_radio, row.senderPlainName),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(14.dp).testTag("chat_mesh_shield"),
                                    )
                                }
                            }
                        }
                        row.origin?.let { MeshOriginLine(it, row.senderName) }
                        row.replyTo?.let { reply ->
                            QuotedMessage(
                                replyTo = reply,
                                myNodeId = myNodeId,
                                mine = row.mine,
                                onClick = { onQuoteClick(reply.messageId) },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (row.attachmentHash != null) {
                            if (VoiceAudio.isVoice(row.attachmentMime)) {
                                // Decoded here, under remember, rather than in ChatRow: the row is a data
                                // class, so holding the bars as an array would give it identity equality and
                                // recompose every voice bubble on every emission of the message list.
                                val bars = remember(row.voicePeaks) { VoiceAudio.decodePeaks(row.voicePeaks) }
                                val live = voicePlayback?.takeIf { it.hash == row.attachmentHash }
                                VoiceNoteBubble(
                                    ready = row.attachmentReady,
                                    durationMs = row.voiceDurationMs,
                                    peaks = bars,
                                    positionMs = live?.positionMs,
                                    playing = live?.playing == true,
                                    // Coral on both sides: both bubble fills are warm (primaryContainer /
                                    // surfaceVariant), and `secondary` is Knit's slate, which reads as a
                                    // foreign accent inside one.
                                    accent = MaterialTheme.colorScheme.primary,
                                    onToggle = { onVoicePlay(row.attachmentHash, row.attachmentKey) },
                                    onSeek = { fraction ->
                                        val total = row.voiceDurationMs ?: 0
                                        if (total > 0) onVoiceSeek(row.attachmentHash, (fraction * total).toInt())
                                    },
                                    onLongClick = { showPicker = true },
                                )
                            } else if (row.attachmentName != null) {
                                // A named attachment is a file: nothing here decodes, so the bubble names it
                                // and offers to save it (ADR 2026-09.qq2r). The name is what selects this
                                // arm, not the mime — an image sent under a wrong type still belongs in the
                                // image arm, where it is screened and blurred.
                                FileAttachmentBubble(
                                    name = row.attachmentName,
                                    mime = row.attachmentMime,
                                    declaredSize = row.attachmentSize,
                                    heldBytes = row.attachmentBytes,
                                    ready = row.attachmentReady,
                                    flagged = row.attachmentFlagged,
                                    onSave = {
                                        onSaveFile(
                                            row.attachmentHash,
                                            row.attachmentKey,
                                            row.attachmentName,
                                            row.attachmentMime,
                                        )
                                    },
                                    onLongClick = { showPicker = true },
                                )
                            } else if (row.attachmentMime == LinkPreviewBlob.MIME) {
                                // A link-preview card the sender fetched (ADR: link previews). Keyed on the
                                // MIME, before the image arm, since a card is a nameless non-image by design.
                                // Nothing draws until the container has decoded and its link was found in the
                                // body — never a spinner: the body's own link is tappable meanwhile.
                                row.linkCard?.let { card ->
                                    LinkPreviewCard(
                                        card = card,
                                        hash = row.attachmentHash,
                                        key = row.attachmentKey,
                                        flagged = row.attachmentFlagged,
                                        onOpen = { openUrl(context, card.url) },
                                        onLongClick = { showPicker = true },
                                    )
                                }
                            } else {
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
                            }
                            val drewAttachment = row.attachmentMime != LinkPreviewBlob.MIME || row.linkCard != null
                            if (row.body.isNotBlank() && drewAttachment) Spacer(Modifier.height(4.dp))
                        }
                        if (row.body.isNotBlank()) {
                            if (row.moderationFlagged && !revealed) {
                                Text(
                                    text = stringResource(R.string.moderation_text_hidden),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                val mentionStyle =
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                val linkStyle =
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                    )
                                // A short emoji-only message renders enlarged (Signal-style), scaling
                                // down as the count grows. annotateMessageBody is a no-op on it (an
                                // all-emoji body has no mentions/URLs), so the call stays shared.
                                val bodyStyle =
                                    when (emojiOnlyCount(row.body)) {
                                        0 -> MaterialTheme.typography.bodyLarge
                                        1 -> MaterialTheme.typography.bodyLarge.copy(fontSize = 44.sp, lineHeight = 52.sp)
                                        in 2..3 -> MaterialTheme.typography.bodyLarge.copy(fontSize = 34.sp, lineHeight = 42.sp)
                                        else -> MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 34.sp)
                                    }
                                Text(
                                    text =
                                        annotateMessageBody(
                                            row.body,
                                            row.mentions,
                                            mentionStyle,
                                            linkStyle,
                                            onLinkClick = { url -> openUrl(context, url) },
                                        ),
                                    style = bodyStyle,
                                )
                            }
                        }
                        // Reach, not delivery — kept on its own line above the tick so the two facts stay
                        // visually separate. This says the photo will not take the Internet shortcut; the
                        // tick below still reports whether the message itself got there.
                        if (row.attachmentRelay == AttachmentRelay.TooLarge ||
                            row.attachmentRelay == AttachmentRelay.Unsupported
                        ) {
                            NearbyOnlyMarker(onClick = { onExplainRelay(row.attachmentRelay) })
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
                                val tickDescription =
                                    deliveryLabel(
                                        status =
                                            if (row.received) {
                                                DeliveryStatus.Delivered
                                            } else {
                                                DeliveryStatus.Sent
                                            },
                                        plane = row.deliveredVia,
                                        mine = true,
                                        // On a group send the glyph alone says only "at least one
                                        // member has it"; these make the description say which many.
                                        delivered = row.deliveredCount,
                                        total = row.recipientTotal,
                                    ).resolve()
                                // A receipt landing is the one moment a mesh messenger actually earns its
                                // keep, and until now it passed silently. The glyph and the tick cross
                                // together as one unit, so the pair reads as a single event rather than as
                                // two icons independently changing their minds.
                                //
                                // The description sits on the AnimatedContent rather than on the Icons so
                                // there is exactly one labelled node at every instant: mid-transition both
                                // the outgoing ✓ and the incoming ✓✓ are composed, and two Icons carrying
                                // two different delivery sentences is precisely the sort of thing the ATF
                                // audit is there to catch. Spoken output is unchanged.
                                //
                                // (transitionSpec is not a @Composable lambda, so the transitions are read
                                // from the theme out here and captured.)
                                val tickEnter = KnitMotion.enterPop()
                                val tickExit = KnitMotion.exitPop()
                                AnimatedContent(
                                    targetState = row.received,
                                    transitionSpec = { tickEnter togetherWith tickExit },
                                    label = "deliveryTick",
                                    modifier = Modifier.semantics { contentDescription = tickDescription },
                                ) { received ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        // A plane glyph ahead of the ✓✓ (a globe for the Internet, the
                                        // radio-waves mark for LoRa) says the receipt came back over that
                                        // plane rather than a nearby radio. It sits before the tick so the
                                        // tick keeps the same trailing position on every row, and only once
                                        // acked: an un-acked send has no plane to show.
                                        val glyph = if (received) planeGlyph(row.deliveredVia) else null
                                        if (glyph != null) {
                                            Icon(
                                                imageVector = glyph,
                                                // Decorative: the description above already says
                                                // "Delivered over the Internet"; announcing both repeats it.
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier =
                                                    Modifier
                                                        .size(12.dp)
                                                        .testTag("chat_tick_${planeTag(row.deliveredVia)}"),
                                            )
                                        }
                                        Icon(
                                            imageVector = if (received) Icons.Filled.DoneAll else Icons.Filled.Done,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                            // A received message has no tick — delivery is not ours to report — but the
                            // same glyph still says it reached this phone over the Internet or LoRa rather
                            // than a nearby radio. Here it carries its own description: with no tick beside
                            // it, nothing else would announce it.
                            val arrivalGlyph = if (row.mine) null else planeGlyph(row.deliveredVia)
                            if (arrivalGlyph != null) {
                                Icon(
                                    imageVector = arrivalGlyph,
                                    contentDescription =
                                        deliveryLabel(DeliveryStatus.Delivered, row.deliveredVia, mine = false).resolve(),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp).testTag("chat_arrived_${planeTag(row.deliveredVia)}"),
                                )
                            }
                        }
                    }
                }
                if (showPicker) {
                    ReactionPicker(
                        quickReactions = quickReactions,
                        onPick = { emoji ->
                            onReact(row.id, emoji)
                            showPicker = false
                        },
                        onMore = {
                            showPicker = false
                            onMoreReactions(row.id)
                        },
                        onReply = {
                            onReply(row)
                            showPicker = false
                        },
                        // Image-only messages have no text to copy, so omit the action.
                        onCopy =
                            if (row.body.isNotBlank()) {
                                {
                                    onCopy(row.body)
                                    showPicker = false
                                }
                            } else {
                                null
                            },
                        onDetails = {
                            showPicker = false
                            onOpenMessageDetails(row.id)
                        },
                        onDelete = {
                            showPicker = false
                            showDeleteConfirm = true
                        },
                        // You can only block other people, not yourself.
                        onBlock =
                            if (!row.mine) {
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
 * A quoted-reply block rendered inside a bubble above its body (Signal-style): an accent bar, the quoted
 * author (with the viewer-relative "You" swap via [myNodeId]), and a snippet — or a "photo" placeholder
 * when the quoted original was an attachment with no text. Tapping it jumps to the original ([onClick]).
 */
@Composable
private fun QuotedMessage(
    replyTo: ReplyRef,
    myNodeId: String,
    mine: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    val photoLabel = stringResource(R.string.chat_reply_photo)
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .background(LocalContentColor.current.copy(alpha = 0.10f))
                .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = quoteAuthorLabel(replyTo, myNodeId, stringResource(R.string.chat_self_name)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val snippet = replyTo.snippet.ifBlank { if (replyTo.hasAttachment) photoLabel else "" }
            if (snippet.isNotEmpty()) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Floating menu shown just above a long-pressed bubble: a "Reply" action, a row of quick-reaction emoji,
 * an optional "Copy text" action ([onCopy] is null for messages with no copyable text), a "Message info"
 * action ([onDetails]) opening the per-message details screen, an optional "Block user" action ([onBlock]
 * is null for your own messages), and an always-present "Delete message" action that removes the message
 * from this device only.
 */
@Composable
private fun ReactionPicker(
    // The quick row: the user's most recent picks (RecentReactions.DEFAULTS until the first), newest first.
    quickReactions: List<String>,
    onPick: (String) -> Unit,
    // The trailing "+": opens the full emoji picker for this message.
    onMore: () -> Unit,
    onReply: () -> Unit,
    onCopy: (() -> Unit)?,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onBlock: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    // 48 dp cells with 4 dp gaps inside 8 dp side padding, plus the "+" cell: six recents need 376 dp, past
    // a 360 dp phone, and the popup can only clamp to the window edge, not shrink. Show as many recents as
    // the window fits (never fewer than MIN_QUICK_REACTIONS) so the row never clips.
    val windowWidthDp =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }.value.toInt()
    val shown = ((windowWidthDp - QUICK_ROW_PADDING_DP) / QUICK_CELL_PITCH_DP - 1).coerceIn(MIN_QUICK_REACTIONS, RecentReactions.SHOWN)
    // Center the bar horizontally over the bubble and place it above; drop below only if it would clip
    // the top of the window.
    val positionProvider =
        remember(spacingPx) {
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
                    quickReactions.take(shown).forEach { emoji ->
                        val reactWith = stringResource(R.string.chat_react_with, emoji)
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClick = { onPick(emoji) })
                                    .minimumInteractiveComponentSize()
                                    .semantics { contentDescription = reactWith }
                                    .padding(8.dp),
                        )
                    }
                    IconButton(onClick = onMore, modifier = Modifier.testTag("chat_react_more")) {
                        Icon(Icons.Outlined.AddReaction, contentDescription = stringResource(R.string.chat_react_more))
                    }
                }
                // Delete is always offered, so the divider below the emoji row always shows.
                HorizontalDivider()
                // Reply leads the action list (Signal-style: it's the primary action).
                PickerAction(
                    icon = Icons.AutoMirrored.Filled.Reply,
                    label = stringResource(R.string.chat_action_reply),
                    onClick = onReply,
                )
                if (onCopy != null) {
                    PickerAction(
                        icon = Icons.Filled.ContentCopy,
                        label = stringResource(R.string.chat_action_copy),
                        onClick = onCopy,
                    )
                }
                PickerAction(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.chat_action_details),
                    onClick = onDetails,
                )
                if (onBlock != null) {
                    PickerAction(
                        icon = Icons.Filled.Block,
                        label = stringResource(R.string.chat_action_block),
                        onClick = onBlock,
                    )
                }
                PickerAction(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.chat_action_delete),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * One full-width row of the long-press menu: leading icon + label. Extracted because the menu holds five
 * of these and the only differences are the icon, the label and whether it is destructive ([tint]).
 */
@Composable
private fun PickerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the label beside it already names the action.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}

/**
 * How far the reaction row is pulled up into the bubble's bottom edge so the chips' top edge tucks
 * under it (Signal-style) instead of floating below. The chips are now sized to their visible pill (no
 * 48.dp min-touch-target margin), so this is the real visible overlap — a few dp of the pill's top.
 * Tune by a few dp on-device if the overlap looks off.
 */
private val REACTION_OVERLAP = 6.dp

/**
 * Pull a composable up by [amount] *and* shrink the height it reports to its parent by the same amount,
 * so it overlaps whatever sits above it while letting following content move up to meet it (a plain
 * `offset` would leave a dead gap below). Used to tuck reaction chips under the message bubble.
 */
private fun Modifier.overlapTop(amount: Dp) =
    this.layout { measurable, constraints ->
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
        // Chips arriving and leaving change the bubble's footprint; reflow rather than snap.
        modifier = modifier.animateContentSize(KnitMotion.spatial()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            ReactionChip(reaction, onClick = { onToggle(reaction.emoji) })
        }
    }
}

@Composable
private fun ReactionChip(
    reaction: ReactionSummary,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val chipDescription =
        pluralStringResource(
            R.plurals.chat_reaction_count,
            reaction.count,
            reaction.count,
            reaction.emoji,
        )
    // Toggling my own reaction repaints the pill; ease it so the tap reads as the same chip changing state
    // rather than as one chip being swapped for another.
    val container by animateColorAsState(
        targetValue =
            if (reaction.mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        animationSpec = KnitMotion.effects(),
        label = "reactionContainer",
    )
    val onContainer by animateColorAsState(
        targetValue =
            if (reaction.mine) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = KnitMotion.effects(),
        label = "reactionOnContainer",
    )
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = shape,
        color = container,
        contentColor = onContainer,
        border = if (reaction.mine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier =
            Modifier
                .clip(shape)
                .clickable(
                    role = Role.Button,
                    // A tap gets no haptic for free the way a long-press does, and this chip is small
                    // enough that a fingertip can cover the state change it just caused.
                    onClick = {
                        haptics.performHapticFeedback(
                            if (reaction.mine) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        onClick()
                    },
                )
                // Deliberately no minimumInteractiveComponentSize(): the 48.dp min-touch box padded each
                // pill out with invisible margin, spreading the chips far apart. Sizing to the visible pill
                // packs them tightly like Signal. Toggling an existing reaction is a secondary action (the
                // long-press picker is the primary path), so the smaller target is an acceptable trade.
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

// Floor on the first-view (ratio-unknown) slot so the bubble reserves height and can't collapse to a sliver
// while Coil decodes the just-arrived blob — the loading spinner sits in this reserved area (see AttachmentImage).
private val ATTACHMENT_MIN_HEIGHT = 120.dp

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
    // Bytes Coil could not decode: a wrong key, a corrupt blob, or an attachment kind this build does not
    // know (a newer peer's). Say so instead of spinning forever over the slot.
    var failed by remember(hash) { mutableStateOf(false) }
    val image = if (ready && hash != null && !failed) BlobImage(hash, mime, key) else null
    Box(
        modifier =
            Modifier
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    // The image consumes taps itself (reveal / open fullscreen), so a long-press would never
                    // reach the bubble's combinedClickable — wire it here too so it opens the reaction picker.
                    when {
                        hidden -> {
                            Modifier.combinedClickable(
                                onClick = { revealed = true },
                                onLongClick = onLongClick,
                            )
                        }

                        image != null -> {
                            Modifier.combinedClickable(
                                onClickLabel = stringResource(R.string.chat_view_photo),
                                onClick = { onImageClick(image) },
                                onLongClick = onLongClick,
                            )
                        }

                        else -> {
                            Modifier
                        }
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            hidden -> {
                Column(
                    modifier =
                        Modifier
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
            }

            image != null -> {
                // Reserve the bubble at the image's true aspect ratio once we've measured it (cached by
                // hash above the list). Coil doesn't memory-cache animated GIFs, so each re-decodes as it
                // scrolls back into view; without a reserved height the slot collapses to zero mid-decode
                // and snaps back, making the list "skip" when flinging past several GIFs. First view (ratio
                // unknown) falls back to the width-anchored slot — floored at ATTACHMENT_MIN_HEIGHT so it can't
                // collapse to a sliver during the decode window — and records the ratio on load for next time.
                val ratio = hash?.let { imageRatios[it] }
                val sizeModifier =
                    if (ratio != null && ratio > 0f) {
                        val h = (ATTACHMENT_WIDTH / ratio).coerceAtMost(ATTACHMENT_MAX_HEIGHT)
                        Modifier.size(width = h * ratio, height = h)
                    } else {
                        Modifier
                            .width(ATTACHMENT_WIDTH)
                            .heightIn(min = ATTACHMENT_MIN_HEIGHT, max = ATTACHMENT_MAX_HEIGHT)
                    }
                // `ready` only means the blob bytes are in the DB; Coil still runs an off-thread read → decrypt →
                // (animated) decode before the first frame paints, and repeats it on every scroll-in since
                // animated WebP isn't memory-cached. Hold the spinner over the reserved slot until that decode
                // lands, so the bubble never flashes an empty box or pops in late. Re-arms per hash on scroll-in.
                var decoded by remember(hash) { mutableStateOf(false) }
                Box(contentAlignment = Alignment.Center) {
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
                            decoded = true
                        },
                        onError = { failed = true },
                        modifier = sizeModifier,
                    )
                    if (!decoded) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            failed -> {
                Column(
                    modifier =
                        Modifier
                            .size(160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("chat_attachment_unavailable"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.chat_attachment_unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            else -> {
                Column(
                    modifier =
                        Modifier
                            .size(160.dp)
                            .background(MaterialTheme.colorScheme.surface),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chat_loading_photo),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
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
        val transformState =
            rememberTransformableState { zoomChange, panChange, _ ->
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
                modifier =
                    Modifier
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
                modifier =
                    Modifier
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
                        text =
                            if (fullscreen.mine) {
                                stringResource(R.string.chat_self_name)
                            } else {
                                fullscreen.senderName
                            },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            relativeTime(
                                fullscreen.sentAt,
                                now,
                                stringResource(R.string.chat_time_just_now),
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

private fun timeLabel(
    row: ChatRow,
    now: Long,
    justNow: String,
): String = relativeTime(row.sentAt, now, justNow)

private fun relativeTime(
    sentAt: Long,
    now: Long,
    justNow: String,
): String {
    // DateUtils renders anything under a minute (and any slight clock skew into the future) as
    // "0 minutes ago"; show a friendlier "Just now" instead.
    if (now - sentAt < DateUtils.MINUTE_IN_MILLIS) return justNow
    return DateUtils
        .getRelativeTimeSpanString(
            sentAt,
            now,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    isBridged: Boolean = false,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Fade in rather than paint on arrival. [ChatUiState.isLoading] now keeps this off screen until
        // Room has answered — a hard flash of "no messages yet" on a conversation that has hundreds used to
        // be the worst thing this screen does, and the skeleton owns that gap instead. What is left is the
        // arrival itself: a thread that turns out to be empty gets one rather than a snap.
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        AnimatedVisibility(visible = shown, enter = KnitMotion.enterFade(), exit = KnitMotion.exitFade()) {
            Text(
                // The radio room's emptiness is about a channel, not about nearby devices.
                text = stringResource(if (isBridged) R.string.chat_mesh_empty else R.string.chat_empty),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Placeholder bubbles for a thread that is still loading (see [ChatUiState.isLoading]). The geometry mirrors
 * the real list exactly — same 12.dp content padding, same 6.dp gaps, same bottom-anchored reverse layout,
 * same 40.dp avatar and bubble corners as [MessageBubble] — so the arriving rows replace it without a jump.
 *
 * The pattern of widths is fixed rather than random: a skeleton that reshuffled between recompositions would
 * read as content changing rather than as content loading. It is also not scrollable and carries no
 * semantics — there is nothing here to read or reach, and the real thread takes the space back as soon as
 * Room answers.
 */
@Composable
private fun ChatSkeleton(
    withAvatars: Boolean,
    modifier: Modifier = Modifier,
) {
    // Hoisted to the list, so every placeholder on screen breathes on one transition rather than its own.
    val blockColor = skeletonBlockColor(skeletonPulseAlpha(label = "chatSkeleton"))
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        reverseLayout = true,
        userScrollEnabled = false,
    ) {
        items(SKELETON_BUBBLES) { bubble ->
            BubbleSkeleton(bubble = bubble, withAvatars = withAvatars, blockColor = blockColor)
        }
    }
}

/** One placeholder bubble: [SkeletonBubble.mine] picks the side, the tail corner and whether an avatar leads. */
@Composable
private fun BubbleSkeleton(
    bubble: SkeletonBubble,
    withAvatars: Boolean,
    blockColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (bubble.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!bubble.mine && withAvatars) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(blockColor))
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .fillMaxWidth(bubble.widthFraction)
                .height(bubble.height)
                .clip(
                    if (bubble.mine) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
                    } else {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
                    },
                ).background(blockColor),
        )
    }
}

/** A placeholder bubble's side, its width as a fraction of the row, and its height (one line, or two). */
private data class SkeletonBubble(
    val mine: Boolean,
    val widthFraction: Float,
    val height: Dp,
)

// The placeholder thread, newest first to match the reversed data the real list is fed. Both sides speak and
// the lengths vary, because a column of identical blocks reads as a broken layout rather than as a
// conversation. Six covers a small phone's thread area; a taller screen keeps its top empty, which is what
// the top of a thread looks like anyway, and the list clips rather than overflows if one is shorter still.
private val SKELETON_BUBBLES =
    listOf(
        SkeletonBubble(mine = false, widthFraction = 0.52f, height = 40.dp),
        SkeletonBubble(mine = true, widthFraction = 0.38f, height = 40.dp),
        SkeletonBubble(mine = true, widthFraction = 0.60f, height = 58.dp),
        SkeletonBubble(mine = false, widthFraction = 0.70f, height = 58.dp),
        SkeletonBubble(mine = false, widthFraction = 0.44f, height = 40.dp),
        SkeletonBubble(mine = true, widthFraction = 0.55f, height = 40.dp),
    )

/**
 * The "now typing" row: the typing peers' avatars (up to three, slightly overlapped for a group/room) beside
 * a received-style bubble carrying the animated [KnitStitchIndicator]. Mirrors a received [MessageBubble]'s
 * avatar + bubble layout so it reads as an incoming message forming. Best-effort presence — the ViewModel
 * feeds it a TTL'd, ephemeral list, so this simply disappears when the list empties.
 */
@Composable
private fun TypingIndicatorRow(
    peers: List<TypingPeer>,
    modifier: Modifier = Modifier,
) {
    if (peers.isEmpty()) return
    val description =
        if (peers.size == 1) {
            stringResource(R.string.chat_typing, peers.first().name)
        } else {
            stringResource(R.string.chat_typing_multiple, peers.first().name)
        }
    Row(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Overlap avatars when more than one person is typing (a group/room), like a small stack.
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            peers.take(3).forEach { peer ->
                Avatar(avatarHash = peer.avatarHash, name = peer.name, size = 30.dp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
        ) {
            KnitStitchIndicator(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    }
}

/**
 * The status line [row] should render as, or null when it is an ordinary message that belongs in a
 * bubble (see [MessageEntity.kind]).
 *
 * The text is composed here rather than stored, so a notice is localized per device, costs no wire
 * bytes, and re-renders against live state. [ChatRow.senderName] is the resolved peer label, so a
 * colliding name arrives already disambiguated as "Alice (JoyfulFerret)" with no work here. A peer rename
 * is the one line that prefers its own snapshot: it stores both names ([PeerRename]) so a second rename
 * reads as a progression ("Old is now Mid", then "Mid is now New") instead of two lines that both end in
 * the newest name; only a row from before the new name was stored still ends in the live label.
 *
 * A kind this build doesn't know falls through to null and draws as a bubble. That is the deliberate
 * degradation: a row written by a newer build shows up looking odd rather than disappearing, which is
 * also why [MessageEntity.isStatusNotice] — the predicate that keeps notices out of unread counts and
 * previews — is written as "not KIND_NORMAL" rather than as a list of the kinds below.
 */
@Composable
private fun statusNoticeText(row: ChatRow): String? =
    when (row.kind) {
        MessageEntity.KIND_MEMBER_LEFT -> {
            stringResource(R.string.chat_notice_member_left, row.senderName)
        }

        // body is the rename pair (PeerRename); the live label stands in for a new name the row lacks —
        // a row written before the new name was stored, or a peer who cleared theirs.
        MessageEntity.KIND_PEER_RENAMED -> {
            val rename = remember(row.body) { PeerRename.decode(row.body) }
            stringResource(R.string.chat_notice_peer_renamed, rename.from, rename.to ?: row.senderName)
        }

        MessageEntity.KIND_PEER_AVATAR -> {
            stringResource(R.string.chat_notice_peer_avatar, row.senderName)
        }

        // body is the NEW group name — the old one is gone from live state by the time this renders.
        MessageEntity.KIND_GROUP_RENAMED -> {
            stringResource(R.string.chat_notice_group_renamed, row.senderName, row.body)
        }

        MessageEntity.KIND_GROUP_PHOTO -> {
            stringResource(R.string.chat_notice_group_photo, row.senderName)
        }

        MessageEntity.KIND_GROUP_CREATED -> {
            if (row.mine) {
                stringResource(R.string.chat_notice_group_created_you)
            } else {
                stringResource(R.string.chat_notice_group_created, row.senderName)
            }
        }

        MessageEntity.KIND_KEY_PIN_REFUSED -> {
            stringResource(R.string.chat_notice_key_pin_refused, row.senderName)
        }

        else -> {
            null
        }
    }

/** A centered, muted status line in the thread (e.g. "Alice left the chat"); not a sender bubble. */
@Composable
private fun SystemNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The provenance line under a heard Meshtastic author's name: their `!hex` id, the board's NodeDB name for
 * them when the line above shows a contact instead, how this board heard them, and — when the post came off
 * somebody's MQTT uplink — that it may not be from anywhere nearby.
 *
 * It is deliberately plain text rather than a badge or a chip. A badge is a mark of standing, and the whole
 * point of this line is the opposite: nothing here is verified, and the name above it is a claim — the one
 * mark of standing, the shield on a signature-verified match, sits beside the name, not here. Always
 * shown while it has something to say, never dismissable — an author whose provenance the reader has scrolled
 * past is exactly the one they would misread.
 *
 * The hop count and SNR are the reader's only handle on *how far away* an unverifiable speaker is, which is
 * why they earn a place on a line that is otherwise about doubt: a direct, strong signal is somebody in the
 * next valley, and twelve hops off an Internet uplink is nobody in particular. They describe the LoRa leg to
 * this board and nothing before it — so [MeshOrigin.viaMqtt] comes last, where the caveat it puts on
 * everything to its left is read after them rather than instead of them.
 */
@Composable
private fun MeshOriginLine(
    origin: MeshOrigin,
    senderName: String,
) {
    val parts =
        buildList {
            // The `!hex` id appears exactly once in the bubble. When the name line already *is* the id — the
            // ordinary case for a stranger's first post — repeating it here would say the same nothing twice.
            if (senderName != origin.nodeLabel) add(origin.nodeLabel)
            // The board's own name for the speaker, when the name line shows a contact instead.
            origin.name?.takeIf { it != senderName }?.let(::add)
            // Zero hops is the firmware's "I heard this myself", not a missing value (that is null, when the
            // sender's build never set hop_start) — so it reads as the plain fact it is, not as "0 hops away".
            origin.hops?.let { hops ->
                add(
                    if (hops == 0) {
                        stringResource(R.string.chat_mesh_direct)
                    } else {
                        pluralStringResource(R.plurals.chat_mesh_hops, hops, hops)
                    },
                )
            }
            origin.snrDeci?.let { add(stringResource(R.string.chat_mesh_snr, it / 10f)) }
            if (origin.viaMqtt) add(stringResource(R.string.chat_mesh_mqtt))
            // What the radio's signature proved, when it proved something short of a verified contact: our
            // board vouched for the number, or a contact's number was signed by some other radio. A verified
            // match wears the shield beside the name instead, and an unsigned post says nothing — unsigned is
            // not evidence (pre-2.8 radios never sign, and a long post cannot be).
            when (origin.signed) {
                MeshSignature.BOARD -> add(stringResource(R.string.chat_mesh_signed))
                MeshSignature.MISMATCH -> add(stringResource(R.string.chat_mesh_signature_mismatch))
                MeshSignature.NONE, MeshSignature.CONTACT -> Unit
            }
        }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("chat_mesh_origin"),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageInput(
    state: TextFieldState,
    isSending: Boolean = false,
    pendingAttachment: AttachmentStore.Ingested?,
    stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent,
    candidates: List<MentionCandidate>,
    replyingTo: ReplyRef? = null,
    myNodeId: String = "",
    onCancelReply: () -> Unit = {},
    onMentionAdded: (Mention) -> Unit,
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit = {},
    // Whether the long-press menu offers "Send a file" (ADR 2026-09.qq2r). Off in the room only, where the
    // long press stays the direct route to the camera it has always been. A recipient whose build predates
    // files is refused at the pick instead, by ChatViewModel.attachFile, so the refusal can say so.
    fileEnabled: Boolean = false,
    onFileClick: () -> Unit = {},
    onClearAttachment: () -> Unit,
    onReceiveImage: (Uri) -> Unit,
    onSend: () -> Unit,
    onTyping: () -> Unit = {},
    onDraftChanged: (String) -> Unit = {},
    linkPreviewLoading: Boolean = false,
    // The LoRa body budget for this draft ([loraBudgetFor]), or null when it would not ride the board. Above
    // it the composer shows the "long message" hint — a hedge, since the true ceiling is a little higher.
    loraBudget: Int? = null,
    // A hard UTF-8 byte cap on the draft ([ChatUiState.publicPostBudget]), or null where the ordinary
    // character cap is the only limit. Set only by the Meshtastic room, whose line has to fit one Meshtastic
    // frame — the opposite kind of limit from [loraBudget]: that one hedges about a frame the message might
    // still reach people without, this one is the frame, so the field refuses the byte that would not fit.
    maxBytes: Int? = null,
    // Replaces the field's "Knit Message" hint. The Meshtastic room passes the destination and how it
    // travels, so a field that sends somewhere other than Knit — and without Knit's encryption — says both
    // before a word is typed rather than after.
    hint: String? = null,
    // Whether the trailing button falls back to Attach on an empty draft, and whether its long-press opens
    // the camera. Off in the bridged room: a photo has no way onto a foreign mesh's text channel, so it
    // would flood inside Knit and silently never leave — an affordance that lies about what it does.
    attachEnabled: Boolean = true,
    // Voice notes. Off in the broadcast room (see the call site). While `voiceRecording` is non-null the
    // whole input row is replaced by the recording bar — there is nothing useful to type mid-recording, and
    // leaving the field live would put the keyboard over the cancel affordance.
    voiceEnabled: Boolean = false,
    voiceRecording: ChatViewModel.VoiceRecording? = null,
    voicePlayback: VoicePlayer.Playback? = null,
    onStartVoice: (locked: Boolean) -> Unit = {},
    onLockVoice: () -> Unit = {},
    onStopVoice: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onVoicePlay: (hash: String, key: String?) -> Unit = { _, _ -> },
    onVoiceSeek: (hash: String, positionMs: Int) -> Unit = { _, _ -> },
) {
    // Capture images committed by the keyboard (Gboard GIFs), drag-and-drop, or paste. The state-based
    // BasicTextField is required here: it advertises the accepted content MIME types to the IME, so the
    // keyboard offers GIFs instead of "images not supported here".
    //
    // Null where [attachEnabled] is false, and the modifier is then left off entirely rather than given a
    // listener that refuses: what the IME reads is the *presence* of a content receiver, so a field that
    // accepts nothing has to say so by having none. That is what turns Gboard's GIF and sticker tabs into
    // "images not supported here" in the bridged room, where a picture has no way onto a text-only channel
    // and a silently-dropped one would look like Knit had sent it.
    val receiveContentListener =
        if (!attachEnabled) {
            null
        } else {
            remember(onReceiveImage) {
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
        }
    // The trailing button doubles as Attach when there's nothing to send, and Send once there is.
    val canSend = state.text.isNotBlank() || pendingAttachment != null

    // Show a spinner in the send button only once a send has been in flight past a short grace period.
    // Most sends complete in well under a frame, so gating on the delay keeps them from flashing a
    // spinner; it surfaces only for a genuinely slow send — chiefly the first send after a cold start,
    // which blocks on the one-time toxicity-model load (see ChatViewModel.isSending). When isSending
    // flips false before the delay elapses the effect restarts and cancels the pending delay, so the
    // flag never trips.
    var showSending by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(isSending) {
        if (isSending) {
            delay(SEND_SPINNER_DELAY_MS)
            showSending = true
        } else {
            showSending = false
        }
    }

    // Track the "@token" the cursor is inside to drive the autocomplete dropdown. snapshotFlow observes
    // the field reactively; the LaunchedEffect ties the collector to composition (cancels on dispose).
    var activeQuery by remember { mutableStateOf<MentionQuery?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() to state.selection }
            .collect { (text, sel) ->
                activeQuery = if (sel.collapsed) activeMentionQuery(text, sel.end) else null
            }
    }
    // Fire a best-effort "now typing" cue on each edit of a non-empty draft; the ViewModel throttles to at
    // most one per interval. drop(1) skips the initial snapshot so opening a thread doesn't announce typing.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect { text -> if (text.isNotBlank()) onTyping() }
    }
    // The draft itself, for the link-preview loop — including the initial snapshot, so a draft that already
    // holds a link when the input composes can grow its card.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect(onDraftChanged)
    }
    val filtered =
        remember(activeQuery, candidates) {
            activeQuery?.let { filterCandidates(candidates, it.query) }.orEmpty()
        }
    val showSuggestions = activeQuery != null && filtered.isNotEmpty()

    fun insertMention(
        query: MentionQuery,
        candidate: MentionCandidate,
    ) {
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
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { activeQuery?.let { insertMention(it, candidate) } }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(avatarHash = candidate.avatarHash, name = candidate.displayName, size = 32.dp)
                                Spacer(Modifier.width(12.dp))
                                // The alias is always shown here — this is where the right Alice gets picked —
                                // muted like a discriminator; a collided label already carries its own.
                                val shown =
                                    if (candidate.discriminator == null) {
                                        PeerLabel.text(candidate.displayName, candidate.alias)
                                    } else {
                                        candidate.displayName
                                    }
                                PeerNameText(
                                    text = shown,
                                    discriminator = candidate.discriminator ?: candidate.alias,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            if (pendingAttachment != null) {
                if (VoiceAudio.isVoice(pendingAttachment.mime)) {
                    // Audition before sending: the same play/waveform/duration row the bubble will draw, so
                    // what the user hears here is visibly the thing that goes out.
                    val live = voicePlayback?.takeIf { it.hash == pendingAttachment.hash }
                    // The description travels on the staged attachment itself, so there is no second
                    // piece of state to keep in step with it.
                    val staged = pendingAttachment.voice
                    val stagedBars = remember(staged) { VoiceAudio.decodePeaks(staged?.peaks) }
                    val stagedTotal = staged?.durationMs ?: 0
                    VoiceNotePreview(
                        durationMs = staged?.durationMs,
                        peaks = stagedBars,
                        positionMs = live?.positionMs,
                        playing = live?.playing == true,
                        // Staged bytes are still plaintext in the blob table — sealing happens on send — so
                        // there is no per-attachment key to hand the player yet.
                        onToggle = { onVoicePlay(pendingAttachment.hash, null) },
                        onSeek = { fraction ->
                            if (stagedTotal > 0) onVoiceSeek(pendingAttachment.hash, (fraction * stagedTotal).toInt())
                        },
                        onClear = onClearAttachment,
                    )
                } else {
                    AttachmentPreview(
                        attachment = pendingAttachment,
                        relay = stagedAttachmentRelay,
                        onClear = onClearAttachment,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (linkPreviewLoading && pendingAttachment == null) {
                LinkPreviewLoadingRow()
            }
            if (replyingTo != null) {
                ReplyPreview(replyTo = replyingTo, myNodeId = myNodeId, onCancel = onCancelReply)
                Spacer(Modifier.height(8.dp))
            }
            if (loraBudget != null) {
                // Derived so the row recomposes only when the draft crosses the budget, not per keystroke.
                val overLora by remember(loraBudget) { derivedStateOf { LoraSizeHint.utf8Length(state.text) > loraBudget } }
                if (overLora) {
                    Text(
                        text = stringResource(R.string.chat_lora_long_message),
                        style = MaterialTheme.typography.bodySmall,
                        // Not an error: the message still goes, over the phone radios — only the board is out.
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .testTag("chat_lora_size_hint")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            // The mic is offered when the composer is otherwise idle, and *kept* for as long as a recording
            // it started is running — until it locks, at which point the stop button takes over the trailing
            // slot and an inline mic beside a running bar would be a second, inert control.
            val showMic =
                voiceEnabled &&
                    if (voiceRecording != null) !voiceRecording.locked else !canSend && !showSending
            // The paperclip keeps the mic's rule: offered while the composer is idle, gone once there is
            // something to send. That matches how attaching already works here — the trailing button is
            // Attach only until you type — so a file is picked first and captioned after, like a photo.
            val showFile = fileEnabled && voiceRecording == null && !canSend && !showSending
            Row(verticalAlignment = Alignment.Bottom) {
                // The field container holds the text field *and* the mic, the way Signal does: sharing the
                // field's background makes the mic read as part of it rather than as a third button
                // competing with send. It stays a control of its own rather than another gesture on the
                // trailing button — that one already spends its tap on send-or-attach and its long-press on
                // the camera (ADR 029), so hold-to-talk would collide head-on.
                //
                // While recording, only the *left half* of this container is replaced — never the container.
                // The mic owns the hold gesture, and a composable that leaves composition has its
                // `pointerInput` coroutine cancelled: taking the whole thing away the instant recording began
                // deleted the very node the finger was resting on, so the release never arrived and every
                // recording ended one frame after it started. The button has to stay under the finger for
                // the whole press.
                //
                // `heightIn` pins the resting height. The hint goes on the first keystroke and the mic on the
                // first non-blank draft, and both measure taller than the bare line of text left behind, so
                // without a floor the field would visibly shrink under the caret as it lost them. It is a
                // floor and not a fixed height: the field still grows as the draft wraps, and with the font
                // scale.
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (voiceRecording != null) {
                        VoiceRecordingBar(
                            elapsedMs = voiceRecording.elapsedMs,
                            amplitude = voiceRecording.amplitude,
                            locked = voiceRecording.locked,
                            onCancel = onCancelVoice,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(
                                        start = 16.dp,
                                        // The inline buttons carry 12dp of their own inset around the icon,
                                        // so the text only has to clear them rather than keep the full margin.
                                        end = if (showMic || showFile) 4.dp else 16.dp,
                                        top = 12.dp,
                                        bottom = 12.dp,
                                    ),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            // The hint is a sibling overlay, so wire it onto the field as its accessibility
                            // label (the field would otherwise be an unnamed edit box); the visible hint is
                            // then marked decorative to avoid TalkBack reading it twice.
                            val messageHint = hint ?: stringResource(R.string.chat_message_hint)
                            if (state.text.isEmpty()) {
                                Text(
                                    messageHint,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                            BasicTextField(
                                state = state,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .then(
                                            receiveContentListener?.let { Modifier.contentReceiver(it) } ?: Modifier,
                                        ).testTag("chat_input")
                                        .semantics { contentDescription = messageHint },
                                // The byte cap replaces the character one rather than chaining after it: a
                                // Meshtastic line is a fifth of [TextLimits.MESSAGE] at its most generous, so
                                // the character cap could never be the one that bit.
                                inputTransformation =
                                    remember(maxBytes) {
                                        maxBytes?.let(::MaxUtf8Bytes) ?: InputTransformation.maxLength(TextLimits.MESSAGE)
                                    },
                                textStyle =
                                    MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                onKeyboardAction = { onSend() },
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    if (showMic) {
                        MicButton(
                            onStart = onStartVoice,
                            onLock = onLockVoice,
                            onStop = onStopVoice,
                            onCancel = onCancelVoice,
                            recording = voiceRecording != null,
                        )
                    }
                    // Outboard of the mic, so the paperclip sits next to the send button it feeds rather
                    // than next to the text. It also keeps the mic where it has always been relative to the
                    // field, which matters more than it sounds: the mic owns a hold gesture, and muscle
                    // memory for a press-and-hold is positional.
                    if (showFile) {
                        AttachFileButton(onClick = onFileClick)
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (voiceRecording != null) {
                    // No send button mid-recording: there is nothing to send yet, and the note stages for
                    // review when the finger lifts. Once the recording is *locked* the finger has already
                    // lifted and the gesture has ended, so this slot becomes the stop button — the control
                    // lands where the thumb already is. Until then it holds an empty slot of the same width,
                    // because collapsing it would widen the field and slide the mic out from under the
                    // finger still holding it.
                    if (voiceRecording.locked) {
                        VoiceStopButton(
                            onClick = onStopVoice,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                    return@Row
                }
                // Deliberately a Surface + combinedClickable rather than a FilledIconButton: the
                // latter wraps Surface(onClick = ...), whose own `clickable` sits inside whatever
                // modifier we pass and consumes the gesture, so an outer long-press never fires.
                // The colours and shape below are FilledIconButton's own defaults, so it looks the same.
                val takePhotoLabel = stringResource(R.string.action_take_photo)
                // The button dips under the finger. graphicsLayer scales the *drawing* only, so the 48.dp
                // touch target the ATF audit checks for is unaffected.
                val sendInteraction = remember { MutableInteractionSource() }
                val sendScale = rememberPressScale(sendInteraction)
                val action =
                    when {
                        showSending -> SendAction.Sending
                        canSend || !attachEnabled -> SendAction.Send
                        else -> SendAction.Attach
                    }
                val actionDescription =
                    when (action) {
                        SendAction.Sending -> stringResource(R.string.chat_sending)
                        SendAction.Send -> stringResource(R.string.action_send)
                        SendAction.Attach -> stringResource(R.string.action_attach_photo)
                    }
                // The byte counter belongs to the button it constrains, not to the thread: it says what the
                // *send* will carry, so it rides directly over the send button rather than across the width of
                // the composer where the LoRa hint (about the message's chances, not its size) already lives.
                // The column takes the button's own `CenterVertically` so a thread with no counter — every
                // thread but the bridged room — lays out byte for byte as it did before.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    if (maxBytes != null) {
                        PostLengthCounter(state = state, maxBytes = maxBytes)
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .graphicsLayer {
                                    scaleX = sendScale.value
                                    scaleY = sendScale.value
                                }.testTag("chat_send")
                                .combinedClickable(
                                    role = Role.Button,
                                    interactionSource = sendInteraction,
                                    // Swallow taps while a send is in flight (the ViewModel guard also drops
                                    // re-entrant sends, but suppressing the click keeps the button from
                                    // feeling dead-but-pressable).
                                    onClick = {
                                        if (!showSending) {
                                            if (canSend || !attachEnabled) {
                                                // The send itself is the only confirmation the composer gives:
                                                // the field clears and the bubble is already at the bottom of a
                                                // list the user may not be looking at.
                                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                                onSend()
                                            } else {
                                                onAttachClick()
                                            }
                                        }
                                    },
                                    // Only in attach mode: long-pressing *Send* to open a camera would be
                                    // surprising, and could interrupt the send it looks like it triggers.
                                    onLongClickLabel = takePhotoLabel.takeIf { attachEnabled && !canSend && !showSending },
                                    onLongClick =
                                        if (attachEnabled && !canSend && !showSending) {
                                            { onCameraClick() }
                                        } else {
                                            null
                                        },
                                ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // Send / attach / spinner used to hard-swap. As with the delivery tick, the single
                            // description rides on the AnimatedContent: `combinedClickable` merges descendants
                            // into the button node, so two labelled children mid-transition would have the
                            // button announce both.
                            val actionEnter = KnitMotion.enterPop()
                            val actionExit = KnitMotion.exitPop()
                            AnimatedContent(
                                targetState = action,
                                transitionSpec = { actionEnter togetherWith actionExit },
                                label = "sendAction",
                                modifier = Modifier.semantics { contentDescription = actionDescription },
                            ) { current ->
                                when (current) {
                                    SendAction.Sending -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            // LocalContentColor is the Surface's onPrimary content colour, so
                                            // the spinner reads against the filled container.
                                            color = LocalContentColor.current,
                                        )
                                    }

                                    SendAction.Send -> {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }

                                    SendAction.Attach -> {
                                        Icon(
                                            imageVector = Icons.Filled.AddPhotoAlternate,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * How close to a hard byte cap the composer starts showing the count. Roughly the last line of a Meshtastic
 * post, so the number appears while there is still a sentence to finish rather than after the field has
 * already begun refusing keystrokes.
 */
private const val COUNTER_FROM_BYTES = 40

/**
 * `183/193` over the send button, for a draft under a hard byte cap ([MaxUtf8Bytes]).
 *
 * Both halves are shown rather than a remainder: the cap here is short and surprising — a Meshtastic line,
 * not a Knit message — so a bare "10 left" would say how much room is left without ever saying what the room
 * was. Appears only in the last [COUNTER_FROM_BYTES] bytes: a permanent counter over a field almost nobody
 * fills would be chrome, while a field that simply stops accepting input with no warning reads as a bug.
 *
 * The visible text is `183/193`, which TalkBack would read as a fraction, so the node carries a spoken label
 * of its own and the number is marked decorative — the same split the composer's hint already uses.
 */
@Composable
private fun PostLengthCounter(
    state: TextFieldState,
    maxBytes: Int,
) {
    // Derived, so this recomposes on the byte count rather than on every keystroke.
    val used by remember(state, maxBytes) {
        derivedStateOf {
            LoraSizeHint.utf8Length(state.text).takeIf { maxBytes - it <= COUNTER_FROM_BYTES }
        }
    }
    used?.let { bytes ->
        val spoken = stringResource(R.string.chat_mesh_post_length_a11y, bytes, maxBytes)
        Text(
            text = stringResource(R.string.chat_mesh_post_length, bytes, maxBytes),
            style = MaterialTheme.typography.labelSmall,
            // Red only once the budget is spent: up to then it is information, not a problem.
            color =
                if (bytes >= maxBytes) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            // Never wrapped or ellipsised: at a large font scale it may out-measure the 48dp button and take
            // a few dp off the field, which is cheaper than a counter that cannot be read.
            softWrap = false,
            modifier =
                Modifier
                    .padding(bottom = 2.dp)
                    .testTag("chat_public_post_length")
                    .semantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
        )
    }
}

/**
 * An [InputTransformation] that rejects any edit taking the field past [maxBytes] of **UTF-8** — the unit a
 * Meshtastic frame is measured in, where `InputTransformation.maxLength` counts UTF-16 units and would let
 * fifty emoji through a two-hundred-byte line.
 *
 * Rejects the whole edit rather than trimming it, exactly as the built-in length filter does: a paste
 * silently cut to fit is the failure this cap exists to remove, so it must not be reintroduced by the cap
 * itself. Deliberately no `applySemantics`: `maxTextLength` is a count of characters, and announcing a byte
 * budget as one would over-promise on every draft that is not plain ASCII. [PostLengthCounter] is a polite
 * live region instead, which stays true whatever the draft is made of.
 */
private class MaxUtf8Bytes(
    private val maxBytes: Int,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (LoraSizeHint.utf8Length(asCharSequence()) > maxBytes) revertAllChanges()
    }
}

/**
 * The attach-a-file button, inline in the composer field beside the mic (ADR 2026-09.qq2r).
 *
 * A control of its own rather than another gesture: the trailing button already spends its tap on
 * send-or-attach and its long-press on the camera (ADR 029), and a menu hung off that long press hid the
 * whole feature behind a gesture with no visible affordance. Styled exactly like [MicButton] at rest — a
 * transparent disc inset inside a full 48dp touch target — so the two read as a pair belonging to the
 * field rather than as buttons competing with send.
 */
@Composable
private fun AttachFileButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .testTag("chat_attach_file")
                .clip(CircleShape)
                // The label rides the icon's contentDescription, not an onClickLabel: with both, TalkBack
                // reads the name and then the same words again as the action.
                .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.AttachFile,
            contentDescription = stringResource(R.string.chat_attach_file),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The record-a-voice-note button: hold to talk, slide up to lock hands-free, slide away to cancel.
 *
 * A press-and-hold gesture is unreachable under TalkBack — an accessibility service consumes the raw touch
 * stream, so the pointer events this relies on never arrive. The button therefore carries a **tap** path as
 * well (`onClick` starts a locked recording, and the recording bar's own stop button ends it), which is what
 * the ATF/a11y suite exercises. That is not a lesser fallback: tap-to-toggle is the better interaction for
 * anyone who can't hold a button steady, and it costs one extra state.
 *
 * Recording only begins once `RECORD_AUDIO` is granted. The permission request is fired on the press that
 * finds it missing, and deliberately does **not** auto-start when the grant lands: the user's finger left
 * the button seconds ago, and recording then would capture the wrong moment. They press again.
 *
 * (The gesture loop is suppressed for multiple jumps deliberately: press, lock, cancel and release are four
 * distinct outcomes of one gesture, and collapsing them into a single exit would make which one actually
 * happened impossible to read.)
 */
@Composable
@Suppress("LoopWithTooManyJumpStatements")
private fun MicButton(
    onStart: (locked: Boolean) -> Unit,
    onLock: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // True while a recording this button started is live. It stays composed throughout (that is what keeps
    // the gesture's pointer stream alive), so it fills in to show the press is being held.
    recording: Boolean = false,
) {
    val context = LocalContext.current
    val micDeniedMessage = stringResource(R.string.chat_voice_mic_denied)
    val gate = rememberMicGate(onDenied = { Toast.makeText(context, micDeniedMessage, Toast.LENGTH_LONG).show() })
    if (!gate.hasMicrophone) return

    val holdLabel = stringResource(R.string.chat_voice_record)
    val tapLabel = stringResource(R.string.chat_voice_record_start)
    // Lock and cancel are crossed by sliding, with the finger over the very control that changed — the two
    // moments in the app a user is least able to *see* the state they just entered. `combinedClickable`'s
    // free long-press haptic never applies here: this is a raw pointerInput, not a click.
    val haptics = LocalHapticFeedback.current
    Box(
        modifier =
            modifier
                .size(48.dp)
                .testTag("chat_voice_record")
                // The accessible path is a semantics *action*, not a `clickable`. A `clickable` would install
                // a second pointer handler on the same node, and both would fire on one press: the gesture
                // below starts an unlocked recording on DOWN, the click starts a locked one on UP, and the
                // second would run with nothing left to stop it. TalkBack's double-tap invokes this action
                // directly rather than replaying pointer events, so the two paths stay disjoint by
                // construction. It starts a *locked* recording — nobody is holding anything — which the
                // recording bar's own stop button ends.
                .semantics(mergeDescendants = true) {
                    contentDescription = holdLabel
                    role = Role.Button
                    onClick(label = tapLabel) {
                        gate.runOrRequest { onStart(true) }
                        true
                    }
                }.pointerInput(gate) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Ask (or refuse) before any recording state exists, so a denied press simply
                            // does nothing rather than leaving a half-armed recorder behind.
                            if (!gate.runOrRequest { onStart(false) }) {
                                waitForUpOrCancellation()
                                continue
                            }
                            var locked = false
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (!locked && dy < -LOCK_SLOP_PX) {
                                    locked = true
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    onLock()
                                }
                                if (!locked && dx < -CANCEL_SLOP_PX) {
                                    cancelled = true
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    onCancel()
                                    break
                                }
                                if (!change.pressed) break
                            }
                            // Three ways out, and only one of them still has a finger on the glass:
                            //  - cancelled: the slide crossed the threshold while still pressed, so the
                            //    release has to be swallowed or it would open the next cycle immediately;
                            //  - unlocked release: the loop broke *because* the finger lifted, so waiting
                            //    for an up here would swallow the NEXT press instead;
                            //  - locked: the finger has lifted too, and the recording deliberately outlives
                            //    it — the recording bar's stop button ends it.
                            if (cancelled) {
                                waitForUpOrCancellation()
                            } else if (!locked) {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                onStop()
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        // The disc is inset inside the touch target rather than filling it: the target stays a full 48dp
        // (it is a gesture surface as much as a button), but a 48dp disc in a 48dp-tall field would meet
        // the field's own rounded edge with no gap and read as a button wedged into it.
        Surface(
            shape = CircleShape,
            // Nothing at rest — inline in the field the mic is *part of* it, and a container of its own
            // would make it a third button competing with send. While recording it fills to solid coral
            // (the tint the user's own bubbles and the send button already use), so the held button reads
            // as active rather than idle.
            color = if (recording) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor =
                if (recording) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * The quoted-reply banner shown above the input while composing a reply (see [MessageInput]): an accent
 * bar, the quoted author (with the viewer-relative "You" swap) and a snippet, plus an ✕ to cancel.
 */
@Composable
private fun ReplyPreview(
    replyTo: ReplyRef,
    myNodeId: String,
    onCancel: () -> Unit,
) {
    val photoLabel = stringResource(R.string.chat_reply_photo)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("reply_preview")
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = quoteAuthorLabel(replyTo, myNodeId, stringResource(R.string.chat_self_name)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val snippet = replyTo.snippet.ifBlank { if (replyTo.hasAttachment) photoLabel else "" }
            if (snippet.isNotEmpty()) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.testTag("reply_cancel"),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_reply_cancel),
            )
        }
    }
}

/**
 * Thumbnail of the staged attachment with a button to remove it before sending, plus — when the photo
 * cannot cross the Internet plane — a caption saying so.
 *
 * The caption is informational and never blocks the send. Refusing to send would be the wrong trade: the
 * mesh carries this photo regardless, so the only thing at stake is whether it *also* takes the Internet
 * shortcut. Telling the user before they tap send is simply kinder than telling them after.
 */
@Composable
private fun AttachmentPreview(
    attachment: AttachmentStore.Ingested,
    relay: AttachmentRelay,
    onClear: () -> Unit,
) {
    val caption =
        when (relay) {
            AttachmentRelay.TooLarge -> R.string.chat_relay_staged_too_large
            AttachmentRelay.Unsupported -> R.string.chat_relay_staged_unsupported
            AttachmentRelay.Silent, AttachmentRelay.Relayable -> null
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            // A named attachment is a file, and there is nothing to thumbnail: handing its bytes to the
            // image loader drew a blank 72dp square with only the ✕ on it, which read as a broken attachment
            // rather than a staged one. It gets the same icon/name/size tile the sent bubble will draw.
            if (attachment.name != null) {
                StagedFileTile(attachment)
            } else if (attachment.link != null) {
                // A link-preview card: the title and host the bubble will draw, beside the same ✕.
                StagedLinkTile(card = attachment.link, hash = attachment.hash)
            } else {
                AsyncImage(
                    model = BlobImage(attachment.hash, attachment.mime),
                    contentDescription = stringResource(R.string.chat_attachment_preview_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            // 48dp touch target (a11y) with the small visible badge kept flush in the corner.
            Box(
                modifier =
                    Modifier
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
        if (caption != null) {
            Text(
                text = stringResource(caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chat_relay_staged_caption"),
            )
        }
    }
}

/**
 * The staged tile for a file: the icon, name and size the bubble will show once it is sent, so what is on
 * screen before the send matches what lands after it. Sized to leave the clear badge its corner.
 */
@Composable
private fun StagedFileTile(attachment: AttachmentStore.Ingested) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, attachment.sizeBytes.toLong())
    val name = attachment.name.orEmpty().ifBlank { stringResource(R.string.chat_file_unnamed) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .heightIn(min = 72.dp)
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // The trailing inset is the clear badge's: it floats in this tile's top-right corner, and
                // without the room it would sit on top of a long filename.
                .padding(start = 12.dp, end = 44.dp, top = 12.dp, bottom = 12.dp)
                .clearAndSetSemantics { contentDescription = "$name, $size" },
    ) {
        Icon(
            fileIconFor(attachment.mime, attachment.name),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Previews exercise the text/no-attachment branches; attachment-bearing rows render only a loading
// placeholder in a preview (Coil/BlobImage has no DB-backed bytes), so sample rows leave attachments null.
@Preview(showBackground = true)
@Composable
fun MessageBubbleTheirsPreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
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
fun MessageBubbleMinePreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
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

/** An incoming message that came in off a relay: same globe, no tick — delivery isn't ours to report. */
@Preview(showBackground = true)
@Composable
fun MessageBubbleTheirsViaInternetPreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
                    id = "m4",
                    body = "Made it to the cabin. No bars up here, so this took the long way round.",
                    mine = false,
                    senderName = "Ada Lovelace",
                    senderNodeId = "node-ada",
                    avatarHash = null,
                    sentAt = PREVIEW_NOW - 5 * 60_000L,
                    received = false,
                    deliveredVia = DeliveryPlane.Internet,
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

/** The same delivered send, acked from out of radio range — the globe says it crossed the Internet. */
@Preview(showBackground = true)
@Composable
fun MessageBubbleMineViaInternetPreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
                    id = "m3",
                    body = "Landed. Signal here is terrible, but this got through.",
                    mine = true,
                    senderName = "You",
                    senderNodeId = "node-self",
                    avatarHash = null,
                    sentAt = PREVIEW_NOW - 2 * 60_000L,
                    received = true,
                    deliveredVia = DeliveryPlane.Internet,
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
fun MessageBubbleWithMentionPreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
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
fun MessageBubbleEmojiPreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
                    id = "m4",
                    body = "😀",
                    mine = false,
                    senderName = "Ada Lovelace",
                    senderNodeId = "node-ada",
                    avatarHash = null,
                    sentAt = PREVIEW_NOW - 3 * 60_000L,
                    received = false,
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
fun ReactionRowPreview() =
    KnitPreview {
        ReactionRow(
            reactions =
                listOf(
                    ReactionSummary("👍", 3, true),
                    ReactionSummary("❤️", 1, false),
                    ReactionSummary("😂", 5, false),
                ),
            onToggle = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ReactionPickerPreview() =
    KnitPreview {
        ReactionPicker(
            quickReactions = RecentReactions.DEFAULTS,
            onPick = {},
            onMore = {},
            onReply = {},
            onCopy = {},
            onDetails = {},
            onDelete = {},
            onBlock = {},
            onDismiss = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun MessageInputPreview() =
    KnitPreview {
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
fun EmptyStatePreview() =
    KnitPreview {
        EmptyState()
    }

// The cold-open placeholder, in its room form (avatars) — a DM's drops the leading circle.
@Preview(showBackground = true, heightDp = 420)
@Composable
fun ChatSkeletonPreview() =
    KnitPreview {
        ChatSkeleton(withAvatars = true, modifier = Modifier.fillMaxSize())
    }

@Preview(showBackground = true)
@Composable
fun LoraNoticePreview() =
    KnitPreview {
        Column {
            LoraNotice(reach = LoraReach.LoraOnly, onClick = {})
            LoraNotice(reach = LoraReach.LoraOnlyDmsOff, onClick = {})
            LoraNotice(reach = LoraReach.LoraOnlySaturated, onClick = {})
            LoraNotice(reach = LoraReach.RoomSaturated, onClick = {})
            LoraNotice(reach = LoraReach.GroupUnsupported, onClick = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun SystemNoticePreview() =
    KnitPreview {
        Column {
            SystemNotice(text = "Ada left the chat")
            SystemNotice(text = "Ada is now Ada Lovelace")
            SystemNotice(text = "Ada Lovelace changed their photo")
            SystemNotice(text = "Ada renamed the group to Analytical Engine")
            SystemNotice(text = "Ada changed the group photo")
            SystemNotice(text = "You created this group")
        }
    }

@Preview(showBackground = true)
@Composable
fun MessageBubbleLinkPreview() =
    KnitPreview {
        MessageBubble(
            row =
                ChatRow(
                    id = "m5",
                    body = "Map here: https://osm.org/go/xyz — see you!",
                    mine = false,
                    senderName = "Ada Lovelace",
                    senderNodeId = "node-ada",
                    avatarHash = null,
                    sentAt = PREVIEW_NOW - 10 * 60_000L,
                    received = false,
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

// The image itself stays blank (Coil/BlobImage has no DB-backed bytes in a preview); this shows the
// black scrim + sender/time/overflow top bar.
@Preview(showBackground = true)
@Composable
fun ChatFullscreenImageViewerPreview() =
    KnitPreview {
        FullscreenImageViewer(
            fullscreen =
                FullscreenImage(
                    image = BlobImage(hash = "preview-hash"),
                    mine = false,
                    senderName = "Ada Lovelace",
                    sentAt = PREVIEW_NOW - 5 * 60_000L,
                ),
            now = PREVIEW_NOW,
            onDismiss = {},
            onSave = {},
        )
    }

// Shared fixture rows for the full-screen previews: a received message, our own delivered reply, and a
// received reply-with-reactions quoting the first.
private fun previewDmRows(): List<ChatRow> =
    listOf(
        ChatRow(
            id = "m1",
            body = "Hey! Are you coming to the trailhead at 8?",
            mine = false,
            senderName = "Ada Lovelace",
            senderNodeId = "node-ada",
            avatarHash = null,
            sentAt = PREVIEW_NOW - 15 * 60_000L,
            received = false,
        ),
        ChatRow(
            id = "m2",
            body = "On my way — see you in 10 minutes.",
            mine = true,
            senderName = "You",
            senderNodeId = "node-self",
            avatarHash = null,
            sentAt = PREVIEW_NOW - 12 * 60_000L,
            received = true,
        ),
        ChatRow(
            id = "m3",
            body = "Great, bringing the map 🗺️",
            mine = false,
            senderName = "Ada Lovelace",
            senderNodeId = "node-ada",
            avatarHash = null,
            sentAt = PREVIEW_NOW - 5 * 60_000L,
            received = false,
            reactions = listOf(ReactionSummary("👍", 1, true)),
            replyTo =
                ReplyRef(
                    messageId = "m2",
                    authorId = "node-self",
                    author = "Walter",
                    snippet = "On my way — see you in 10 minutes.",
                ),
        ),
    )

@Preview(showBackground = true)
@Composable
fun ChatScreenDmPreview() =
    KnitPreview {
        ChatScreenContent(
            conversationId = "node-ada",
            state =
                ChatUiState(
                    rows = previewDmRows(),
                    myNodeId = "node-self",
                    isRoom = false,
                    title = "Ada Lovelace",
                    avatarHash = null,
                    verified = true,
                ),
            inputState = rememberTextFieldState(),
            pendingAttachment = null,
            replyingTo = null,
            now = PREVIEW_NOW,
            onBack = {},
            onOpenProfile = {},
            onOpenGroupDetails = {},
            onSend = {},
            onAttachClick = {},
            onClearAttachment = {},
            onReceiveImage = {},
            onTyping = {},
            onMentionAdded = {},
            onStartReply = {},
            onCancelReply = {},
            onReact = { _, _ -> },
            onDeleteMessage = {},
            onBlock = {},
            onUnblock = {},
            onCopy = {},
            onSaveAttachment = { _, _, _ -> },
        )
    }

@Preview(showBackground = true)
@Composable
fun ChatScreenGroupTypingPreview() =
    KnitPreview {
        ChatScreenContent(
            conversationId = "g-hiking",
            state =
                ChatUiState(
                    rows = previewDmRows().take(2),
                    myNodeId = "node-self",
                    isRoom = false,
                    isGroup = true,
                    memberCount = 3,
                    title = "Hiking Crew",
                    avatarHash = null,
                    typingPeers = listOf(TypingPeer(nodeId = "node-grace", name = "Grace Hopper", avatarHash = null)),
                ),
            inputState = rememberTextFieldState(),
            pendingAttachment = null,
            replyingTo = null,
            now = PREVIEW_NOW,
            onBack = {},
            onOpenProfile = {},
            onOpenGroupDetails = {},
            onSend = {},
            onAttachClick = {},
            onClearAttachment = {},
            onReceiveImage = {},
            onTyping = {},
            onMentionAdded = {},
            onStartReply = {},
            onCancelReply = {},
            onReact = { _, _ -> },
            onDeleteMessage = {},
            onBlock = {},
            onUnblock = {},
            onCopy = {},
            onSaveAttachment = { _, _, _ -> },
        )
    }

// The Nearby room with nobody around: EmptyState body + degraded connection header.
@Preview(showBackground = true)
@Composable
fun ChatScreenRoomEmptyPreview() =
    KnitPreview {
        ChatScreenContent(
            conversationId = "nearby",
            state =
                ChatUiState(
                    rows = emptyList(),
                    myNodeId = "node-self",
                    isRoom = true,
                    neighborCount = 0,
                    transportHealth = TransportHealth.Degraded,
                ),
            inputState = rememberTextFieldState(),
            pendingAttachment = null,
            replyingTo = null,
            now = PREVIEW_NOW,
            onBack = {},
            onOpenProfile = {},
            onOpenGroupDetails = {},
            onSend = {},
            onAttachClick = {},
            onClearAttachment = {},
            onReceiveImage = {},
            onTyping = {},
            onMentionAdded = {},
            onStartReply = {},
            onCancelReply = {},
            onReact = { _, _ -> },
            onDeleteMessage = {},
            onBlock = {},
            onUnblock = {},
            onCopy = {},
            onSaveAttachment = { _, _, _ -> },
        )
    }

// A reply draft in flight: the quoted message banner above a prefilled input.
@Preview(showBackground = true)
@Composable
fun ChatScreenReplyDraftPreview() =
    KnitPreview {
        ChatScreenContent(
            conversationId = "node-ada",
            state =
                ChatUiState(
                    rows = previewDmRows(),
                    myNodeId = "node-self",
                    isRoom = false,
                    title = "Ada Lovelace",
                    avatarHash = null,
                ),
            inputState = rememberTextFieldState("Sounds good"),
            pendingAttachment = null,
            replyingTo =
                ReplyRef(
                    messageId = "m3",
                    authorId = "node-ada",
                    author = "Ada Lovelace",
                    snippet = "Great, bringing the map 🗺️",
                ),
            now = PREVIEW_NOW,
            onBack = {},
            onOpenProfile = {},
            onOpenGroupDetails = {},
            onSend = {},
            onAttachClick = {},
            onClearAttachment = {},
            onReceiveImage = {},
            onTyping = {},
            onMentionAdded = {},
            onStartReply = {},
            onCancelReply = {},
            onReact = { _, _ -> },
            onDeleteMessage = {},
            onBlock = {},
            onUnblock = {},
            onCopy = {},
            onSaveAttachment = { _, _, _ -> },
        )
    }
