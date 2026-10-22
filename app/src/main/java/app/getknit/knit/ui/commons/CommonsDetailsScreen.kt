package app.getknit.knit.ui.commons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.RoomAvatar
import app.getknit.knit.ui.group.GroupMemberRow
import app.getknit.knit.ui.group.MemberRow
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A commons' room details (the commons analogue of [app.getknit.knit.ui.group.GroupDetailsScreen]): the room glyph,
 * the relay's name for it and the relay it lives on, the members this phone has seen in it, and Leave. Reached from
 * the room's header or its overflow. Tapping a member opens their profile via [onOpenMemberProfile]; the local user's
 * own row is labeled "You". Leaving deletes the thread, so [onLeft] pops back past the (now-gone) chat — as it does
 * when the room is left from the relay row while this screen is open.
 */
@Composable
fun CommonsDetailsScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenMemberProfile: (nodeId: String) -> Unit,
    onLeft: () -> Unit,
    viewModel: CommonsDetailsViewModel = koinViewModel { parametersOf(conversationId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.left.collect { onLeft() } }
    LaunchedEffect(state.exists) { if (!state.exists) onLeft() }
    CommonsDetailsScreenContent(
        state = state,
        onBack = onBack,
        onOpenMemberProfile = onOpenMemberProfile,
        onLeave = viewModel::leave,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommonsDetailsScreenContent(
    state: CommonsDetailsUiState,
    onBack: () -> Unit,
    onOpenMemberProfile: (nodeId: String) -> Unit,
    onLeave: () -> Unit,
) {
    var showLeaveConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.commons_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RoomAvatar(size = 96.dp)
            Text(text = state.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            state.relayHost?.let { host ->
                Text(
                    text = stringResource(R.string.commons_details_relay, host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = pluralStringResource(R.plurals.chat_group_member_count, state.members.size, state.members.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.stale) {
                Text(
                    text = stringResource(R.string.commons_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("commons_details_stale"),
                )
            }

            HorizontalDivider()

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.commons_details_members),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.members.forEach { member -> MemberRow(member = member, onOpen = onOpenMemberProfile) }
            }

            OutlinedButton(
                onClick = { showLeaveConfirm = true },
                modifier = Modifier.testTag("commons_leave"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                )
                Text(stringResource(R.string.commons_leave), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.commons_leave_confirm_title)) },
            // The relay row's own words for the same act, so the two doors say the same thing.
            text = { Text(stringResource(R.string.relays_commons_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        onLeave()
                    },
                    modifier = Modifier.testTag("commons_leave_confirm"),
                ) {
                    Text(stringResource(R.string.commons_leave_confirm_action), color = MaterialTheme.colorScheme.error)
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

@Preview(showBackground = true)
@Composable
fun CommonsDetailsScreenPreview() =
    KnitPreview {
        CommonsDetailsScreenContent(
            state =
                CommonsDetailsUiState(
                    conversationId = "c-preview",
                    title = "Home",
                    relayHost = "relay.example.org",
                    members =
                        listOf(
                            GroupMemberRow(
                                nodeId = "node-self",
                                displayName = "Ada Lovelace",
                                avatarHash = null,
                                online = false,
                                isSelf = true,
                            ),
                            GroupMemberRow(
                                nodeId = "node-grace",
                                displayName = "Grace Hopper",
                                avatarHash = null,
                                online = true,
                                isSelf = false,
                            ),
                            GroupMemberRow(
                                nodeId = "node-radia",
                                displayName = "Radia Perlman",
                                avatarHash = null,
                                online = false,
                                isSelf = false,
                            ),
                        ),
                ),
            onBack = {},
            onOpenMemberProfile = {},
            onLeave = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun CommonsDetailsStalePreview() =
    KnitPreview {
        CommonsDetailsScreenContent(
            state =
                CommonsDetailsUiState(
                    conversationId = "c-preview",
                    title = "Home",
                    relayHost = "relay.example.org",
                    members =
                        listOf(
                            GroupMemberRow(
                                nodeId = "node-self",
                                displayName = "Ada Lovelace",
                                avatarHash = null,
                                online = false,
                                isSelf = true,
                            ),
                        ),
                    stale = true,
                ),
            onBack = {},
            onOpenMemberProfile = {},
            onLeave = {},
        )
    }
