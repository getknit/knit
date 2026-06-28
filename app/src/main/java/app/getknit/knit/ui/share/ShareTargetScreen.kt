package app.getknit.knit.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.chatlist.ChatListViewModel
import app.getknit.knit.ui.chatlist.ConversationListItem
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import org.koin.androidx.compose.koinViewModel

/**
 * The share-sheet destination picker: when another app shares text/an image into Knit, tap a
 * conversation — the Nearby room, a group, or an existing DM — to open it with the shared content
 * staged in the draft. The target list reuses [ChatListViewModel]'s conversation rows (so it shows
 * Nearby + active groups + DMs with history); [onPick] receives the chosen conversation id, and
 * [onBack] (UI or hardware Back) abandons the share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetScreen(
    onBack: () -> Unit,
    onPick: (conversationId: String) -> Unit,
    viewModel: ChatListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A ticking clock so each row's relative timestamp recomposes as time passes (see ChatListScreen).
    val now by rememberCurrentTimeMillis()

    // Hardware Back must also clear the inbox, else a lingering payload would prefill the next chat.
    BackHandler(onBack = onBack)

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
                title = { Text(stringResource(R.string.share_target_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(state.conversations, key = { it.id }) { row ->
                ConversationListItem(row = row, now = now, onClick = { onPick(row.id) })
            }
        }
    }
}
