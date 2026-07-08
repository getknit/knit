package app.getknit.knit.ui.requests

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.GroupAvatar
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The Message Requests inbox — the pending DM/group conversations partitioned out of the main chat list.
 * Each row offers Accept (moves it into the chat list) plus, behind an overflow, Block (DM only) and
 * Delete, each with a confirm dialog. Reached from the chat-list badge and from the quiet coalesced
 * notification's deep-link.
 */
@Composable
fun MessageRequestsScreen(
    onBack: () -> Unit,
    viewModel: MessageRequestsViewModel = koinViewModel(),
) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val notifier = koinInject<Notifier>()
    // While this inbox is on screen, suppress the coalesced "N message requests" heads-up (and clear any
    // already showing) — mirrors ChatViewModel's per-conversation setVisibleConversation.
    DisposableEffect(Unit) {
        notifier.setRequestsVisible(true)
        onDispose { notifier.setRequestsVisible(false) }
    }
    MessageRequestsScreenContent(
        requests = requests,
        onAccept = viewModel::accept,
        onBlock = viewModel::block,
        onDelete = viewModel::delete,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageRequestsScreenContent(
    requests: List<RequestRow>,
    onAccept: (conversationId: String) -> Unit,
    onBlock: (nodeId: String) -> Unit,
    onDelete: (conversationId: String) -> Unit,
    onBack: () -> Unit,
) {
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
                title = { Text(stringResource(R.string.message_requests_title)) },
            )
        },
    ) { padding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.message_requests_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(requests, key = { it.conversationId }) { row ->
                    RequestRowItem(
                        row = row,
                        onAccept = { onAccept(row.conversationId) },
                        onBlock = { onBlock(row.conversationId) },
                        onDelete = { onDelete(row.conversationId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestRowItem(
    row: RequestRow,
    onAccept: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RequestLeadingVisual(row)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.lastPreview?.let { preview ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onAccept) {
            Text(stringResource(R.string.message_requests_accept))
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Block targets the DM peer (conversationId == node id); it has no meaning for a group.
                if (!row.isGroup) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_requests_block)) },
                        leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            showBlockConfirm = true
                        },
                    )
                }
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
                        showDeleteConfirm = true
                    },
                )
            }
        }
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(stringResource(R.string.message_requests_block_confirm_title)) },
            text = { Text(stringResource(R.string.message_requests_block_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onBlock()
                    showBlockConfirm = false
                }) {
                    Text(
                        text = stringResource(R.string.message_requests_block),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.message_requests_delete_confirm_title)) },
            text = { Text(stringResource(R.string.message_requests_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text(
                        text = stringResource(R.string.chat_list_delete_action),
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

/** Leading glyph: the group photo (or people glyph) for a group request, an [Avatar] for a DM request. */
@Composable
private fun RequestLeadingVisual(row: RequestRow) {
    val size = 44.dp
    if (row.isGroup) {
        GroupAvatar(photoHash = row.avatarHash, size = size)
    } else {
        Avatar(avatarHash = row.avatarHash, name = row.title, size = size)
    }
}

@Preview(showBackground = true)
@Composable
fun MessageRequestsScreenContentPreview() =
    KnitPreview {
        MessageRequestsScreenContent(
            requests =
                listOf(
                    RequestRow(
                        conversationId = "8f3a2b1c9d4e",
                        title = "Stranger",
                        avatarHash = null,
                        isGroup = false,
                        lastPreview = "hey, is this thing on?",
                        lastMessageAt = 0L,
                    ),
                    RequestRow(
                        conversationId = "g-abc123",
                        title = "Hikers",
                        avatarHash = null,
                        isGroup = true,
                        lastPreview = "Alice: welcome to the group!",
                        lastMessageAt = 0L,
                    ),
                ),
            onAccept = {},
            onBlock = {},
            onDelete = {},
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun MessageRequestsScreenContentEmptyPreview() =
    KnitPreview {
        MessageRequestsScreenContent(
            requests = emptyList(),
            onAccept = {},
            onBlock = {},
            onDelete = {},
            onBack = {},
        )
    }
