package app.getknit.knit.ui.contacts

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * The "new message" picker: tap people to select, then confirm. One selected person opens (or resumes)
 * a 1:1 DM; two or more create a group. Reached from the chat-list FAB; [onPick] receives the chosen
 * conversation id — a peer's node id for a DM, or the new group's id once it's created.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onPick: (conversationId: String) -> Unit,
    viewModel: ContactsViewModel = koinViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<String>() }
    val context = LocalContext.current
    val groupFullMessage =
        stringResource(R.string.contacts_group_full, ContactsViewModel.MAX_OTHER_MEMBERS + 1)

    // A created group opens its chat once the row is persisted (avoids a startup race in the chat VM).
    LaunchedEffect(Unit) {
        viewModel.created.collect { onPick(it) }
    }

    fun toggle(nodeId: String) {
        if (nodeId in selected) {
            selected.remove(nodeId)
        } else if (selected.size >= ContactsViewModel.MAX_OTHER_MEMBERS) {
            Toast.makeText(context, groupFullMessage, Toast.LENGTH_SHORT).show()
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
                title = { Text(stringResource(R.string.contacts_title)) },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        if (selected.size == 1) {
                            onPick(selected.first())
                        } else {
                            viewModel.createGroup(selected.toList())
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.contacts_start_chat),
                    )
                }
            }
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.contacts_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(contacts, key = { it.nodeId }) { contact ->
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

@Composable
private fun ContactRow(contact: Contact, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A selection control: expose the whole row as one checkbox so a screen reader announces
            // "<name>, checkbox, checked/not checked" and the selection-state icon is decorative.
            .toggleable(value = selected, onValueChange = { onClick() }, role = Role.Checkbox)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarHash = contact.avatarHash, name = contact.displayName, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = contact.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A filled dot marks a contact currently connected to the mesh.
        if (contact.online) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
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
fun ContactRowSelectedOnlinePreview() = KnitPreview {
    ContactRow(
        contact = Contact(nodeId = "node-ada", displayName = "Ada Lovelace", avatarHash = null, online = true),
        selected = true,
        onClick = {},
    )
}

@Preview(showBackground = true)
@Composable
fun ContactRowUnselectedOfflinePreview() = KnitPreview {
    ContactRow(
        contact = Contact(nodeId = "node-grace", displayName = "Grace Hopper", avatarHash = null, online = false),
        selected = false,
        onClick = {},
    )
}
