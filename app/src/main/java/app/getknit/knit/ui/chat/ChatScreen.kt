package app.getknit.knit.ui.chat

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.ui.components.Avatar
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenProfile: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val inputState = rememberTextFieldState()
    var menuOpen by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Modern Android Photo Picker — needs no runtime permission. ImageOnly still includes GIFs.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::attach)
    }

    // Suppress message notifications while the chat is on screen, and clear any active one. Nav is a
    // Crossfade over an enum (not real navigation), so leaving for Profile disposes this screen — the
    // onDispose toggle re-enables notifications so a message arriving on Profile still notifies.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Knit",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (state.neighborCount > 0) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = connectionLabel(state.neighborCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Profile") },
                                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenProfile()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            MessageInput(
                state = inputState,
                pendingAttachment = pendingAttachment,
                onAttachClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onClearAttachment = viewModel::clearAttachment,
                onReceiveImage = viewModel::attach,
                onSend = {
                    viewModel.send(inputState.text.toString())
                    inputState.clearText()
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
                    MessageBubble(row, onImageClick = { fullscreenImage = it })
                }
            }
        }
    }

    fullscreenImage?.let { path ->
        FullscreenImageViewer(path = path, onDismiss = { fullscreenImage = null })
    }
}

private fun connectionLabel(count: Int): String = when (count) {
    0 -> "No mesh nodes connected"
    1 -> "Connected to 1 mesh node"
    else -> "Connected to $count mesh nodes"
}

@Composable
private fun MessageBubble(row: ChatRow, onImageClick: (String) -> Unit) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    val bubbleShape = if (row.mine) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (row.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!row.mine) {
            Avatar(avatarPath = row.avatarPath, name = row.senderName, size = 40.dp)
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            color = if (row.mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (row.mine) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = maxBubbleWidth),
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
                    AttachmentImage(row.attachmentPath, onImageClick)
                    if (row.body.isNotBlank()) Spacer(Modifier.height(4.dp))
                }
                if (row.body.isNotBlank()) {
                    Text(text = row.body, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = timeLabel(row),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** The image inside a bubble: the photo/GIF once fetched (tap to open fullscreen), or a loading placeholder. */
@Composable
private fun AttachmentImage(path: String?, onImageClick: (String) -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (path != null) Modifier.clickable { onImageClick(path) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = "Photo attachment",
                contentScale = ContentScale.Fit,
                modifier = Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 260.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .size(160.dp)
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text("Loading photo…", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Fullscreen, pinch-to-zoom/pan viewer for a tapped image. Dismissed by the close button or back press. */
@Composable
private fun FullscreenImageViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset += panChange
        }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "Photo",
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
                modifier = Modifier.align(Alignment.TopEnd).navigationBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private fun timeLabel(row: ChatRow): String {
    val time = DateUtils.getRelativeTimeSpanString(
        row.sentAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    return if (row.mine && row.received) "$time ✓" else time
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "No messages yet.\nSay hello to nearby devices.",
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

    Surface(
        tonalElevation = 2.dp,
        // Edge-to-edge: lift the input bar above the IME and the navigation bar.
        modifier = Modifier.imePadding().navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (pendingAttachment != null) {
                AttachmentPreview(path = pendingAttachment.path, onClear = onClearAttachment)
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
                        Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        contentDescription = if (canSend) "Send" else "Attach photo",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** Thumbnail of the staged attachment with a button to remove it before sending. */
@Composable
private fun AttachmentPreview(path: String, onClear: () -> Unit) {
    Box {
        AsyncImage(
            model = File(path),
            contentDescription = "Attachment preview",
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
                    contentDescription = "Remove attachment",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
