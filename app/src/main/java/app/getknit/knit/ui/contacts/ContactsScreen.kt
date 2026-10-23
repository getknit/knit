package app.getknit.knit.ui.contacts

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.PeerNameText
import app.getknit.knit.ui.components.peerBadge
import app.getknit.knit.ui.components.skeletonBlockColor
import app.getknit.knit.ui.components.skeletonPulseAlpha
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.knitColors
import org.koin.androidx.compose.koinViewModel

/**
 * The "new message" picker: tap people to select, then confirm. One selected person opens (or resumes)
 * a 1:1 DM; two or more create a group. Reached from the chat-list FAB; [onPick] receives the chosen
 * conversation id — a peer's node id for a DM, or the new group's id once it's created.
 */
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onPick: (conversationId: String) -> Unit,
    onAddContact: () -> Unit = {},
    viewModel: ContactsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val groupFullMessage =
        pluralStringResource(
            R.plurals.contacts_group_full,
            ContactsViewModel.MAX_OTHER_MEMBERS + 1,
            ContactsViewModel.MAX_OTHER_MEMBERS + 1,
        )

    // A created group opens its chat once the row is persisted (avoids a startup race in the chat VM).
    LaunchedEffect(Unit) {
        viewModel.created.collect { onPick(it) }
    }

    ContactsScreenContent(
        state = state,
        onBack = onBack,
        onPickSingle = onPick,
        onCreateGroup = viewModel::createGroup,
        onGroupFull = { Toast.makeText(context, groupFullMessage, Toast.LENGTH_SHORT).show() },
        onAddContact = onAddContact,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactsScreenContent(
    state: ContactsUiState,
    onBack: () -> Unit,
    onPickSingle: (nodeId: String) -> Unit,
    onCreateGroup: (memberIds: List<String>) -> Unit,
    onGroupFull: () -> Unit,
    onAddContact: () -> Unit = {},
) {
    val selected = remember { mutableStateListOf<String>() }

    fun toggle(nodeId: String) {
        if (nodeId in selected) {
            selected.remove(nodeId)
        } else if (selected.size >= ContactsViewModel.MAX_OTHER_MEMBERS) {
            onGroupFull()
        } else {
            selected.add(nodeId)
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
                title = { BarTitle(stringResource(R.string.contacts_title)) },
                actions = {
                    IconButton(onClick = onAddContact, modifier = Modifier.size(48.dp).testTag("contacts_add")) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = stringResource(R.string.add_contact_title))
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        if (selected.size == 1) {
                            onPickSingle(selected.first())
                        } else {
                            onCreateGroup(selected.toList())
                        }
                    },
                    modifier = Modifier.testTag("contacts_fab"),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.contacts_start_chat),
                    )
                }
            }
        },
    ) { padding ->
        // The picker's contacts are a combine of Room + DataStore + mesh flows that emits nothing for the
        // first frames after this screen is created — exactly the frames the chat-list → picker transition
        // is playing over. Drawing the "no contacts yet" empty state there flashed a centred column that
        // the real rows then replaced in one frame, which is what made the transition read as jagged; show
        // the same-shaped skeleton instead and cross-fade to whichever state actually arrives.
        val listEnter = KnitMotion.enterFade()
        val listExit = KnitMotion.exitFade()
        AnimatedContent(
            targetState = state.isLoading,
            transitionSpec = { listEnter togetherWith listExit },
            label = "contactsLoading",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { loading ->
            when {
                loading -> {
                    ContactsSkeleton(modifier = Modifier.fillMaxSize())
                }

                state.contacts.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.contacts_empty),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onAddContact, modifier = Modifier.testTag("contacts_add_empty")) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.add_contact_title))
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.contacts, key = { it.nodeId }) { contact ->
                            ContactRow(
                                contact = contact,
                                selected = contact.nodeId in selected,
                                onClick = { toggle(contact.nodeId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Placeholder rows shown while the contact list is still loading (see [ContactsUiState.isLoading]). A row's
 * shape mirrors [ContactRow] — leading avatar circle, name line, trailing selection dot — so the real list
 * lands without a layout jump.
 */
@Composable
private fun ContactsSkeleton(modifier: Modifier = Modifier) {
    val alpha = skeletonPulseAlpha(label = "contactsSkeleton")
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        repeat(SKELETON_ROW_COUNT) { ContactSkeletonRow(pulseAlpha = alpha) }
    }
}

private const val SKELETON_ROW_COUNT = 6

@Composable
private fun ContactSkeletonRow(pulseAlpha: Float) {
    val blockColor = skeletonBlockColor(pulseAlpha)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(blockColor))
        Spacer(Modifier.width(12.dp))
        // The name line claims the row's slack the way PeerNameText does, then draws across half of it.
        Box(modifier = Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(blockColor),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(24.dp).clip(CircleShape).background(blockColor))
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("contact_${contact.nodeId}")
                // A selection control: expose the whole row as one checkbox so a screen reader announces
                // "<name>, checkbox, checked/not checked" and the selection-state icon is decorative.
                .toggleable(value = selected, onValueChange = { onClick() }, role = Role.Checkbox)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarHash = contact.avatarHash,
            name = contact.displayName,
            nodeId = contact.nodeId,
            size = 48.dp,
            badge = peerBadge(contact.nodeId),
        )
        Spacer(Modifier.width(12.dp))
        PeerNameText(
            text = contact.displayName,
            discriminator = contact.discriminator,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        // A filled dot marks a contact currently connected to the mesh.
        if (contact.online) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.knitColors.positive),
            )
            Spacer(Modifier.width(12.dp))
        }
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            // Decorative: the row's Checkbox role already announces the selection state.
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ContactsScreenPreview() =
    KnitPreview {
        ContactsScreenContent(
            state =
                ContactsUiState(
                    contacts =
                        listOf(
                            Contact(nodeId = "node-ada", displayName = "Ada Lovelace", avatarHash = null, online = true),
                            Contact(nodeId = "node-grace", displayName = "Grace Hopper", avatarHash = null, online = true),
                            Contact(nodeId = "node-edsger", displayName = "Edsger Dijkstra", avatarHash = null, online = false),
                            Contact(nodeId = "node-radia", displayName = "Radia Perlman", avatarHash = null, online = false),
                        ),
                ),
            onBack = {},
            onPickSingle = {},
            onCreateGroup = {},
            onGroupFull = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ContactsScreenEmptyPreview() =
    KnitPreview {
        ContactsScreenContent(
            state = ContactsUiState(),
            onBack = {},
            onPickSingle = {},
            onCreateGroup = {},
            onGroupFull = {},
        )
    }

// Cold-start loading state: the skeleton placeholder rows shown until the state flow first emits.
@Preview(showBackground = true)
@Composable
fun ContactsScreenLoadingPreview() =
    KnitPreview {
        ContactsScreenContent(
            state = ContactsUiState(isLoading = true),
            onBack = {},
            onPickSingle = {},
            onCreateGroup = {},
            onGroupFull = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ContactRowSelectedOnlinePreview() =
    KnitPreview {
        ContactRow(
            contact = Contact(nodeId = "node-ada", displayName = "Ada Lovelace", avatarHash = null, online = true),
            selected = true,
            onClick = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun ContactRowUnselectedOfflinePreview() =
    KnitPreview {
        ContactRow(
            contact = Contact(nodeId = "node-grace", displayName = "Grace Hopper", avatarHash = null, online = false),
            selected = false,
            onClick = {},
        )
    }
