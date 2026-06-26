package app.getknit.knit.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.Avatar
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Read-only "contact details" view of another peer (keyed by [nodeId]): avatar, display name, live
 * online/offline state, free-text status, and node id. Offers a Message action (open/start a DM via
 * [onMessage]) and Block/Unblock in the overflow menu. Reached by tapping a peer's avatar in a chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailsScreen(
    nodeId: String,
    onBack: () -> Unit,
    onMessage: (nodeId: String) -> Unit,
    viewModel: ProfileDetailsViewModel = koinViewModel { parametersOf(nodeId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.chat_more_options),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                                    menuOpen = false
                                    if (state.isBlocked) viewModel.unblock() else viewModel.block()
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                avatarHash = state.avatarHash,
                name = state.displayName,
                size = 96.dp,
                background = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                textStyle = MaterialTheme.typography.displaySmall,
            )

            Text(
                text = state.displayName,
                style = MaterialTheme.typography.titleLarge,
            )

            // Live presence: a filled dot + label, matching the contact-list online indicator.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.online) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (state.online) R.string.profile_details_online
                        else R.string.profile_details_offline,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.status.isNotBlank()) {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = stringResource(R.string.profile_node_id, state.nodeId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledIconButton(onClick = { onMessage(state.nodeId) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Message,
                        contentDescription = stringResource(R.string.profile_details_message),
                    )
                }
                Text(
                    text = stringResource(R.string.profile_details_message),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
