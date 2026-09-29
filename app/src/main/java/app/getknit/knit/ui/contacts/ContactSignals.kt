package app.getknit.knit.ui.contacts

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The stored facts [contactIds] and [contactStanding] judge a contact by, less the peer directory's
 * verified set and the block list (each reader already holds those). The three facts from the messages
 * table — which DM threads exist, which threads we have spoken in, and who has posted in each group — are
 * read as distinct-id queries rather than as the table, and arrive pre-combined with the groups and the
 * accepted set so a reader's own combine stays within the 5-flow typed overload.
 */
internal data class ContactSignals(
    val conversations: Set<String>,
    val authored: Set<String>,
    val groups: List<GroupEntity>,
    val groupSenders: Map<String, Set<String>>,
    val accepted: Set<String>,
)

/**
 * [ContactSignals] as a live flow. [me] resolves after construction: until it does, the authored set is
 * empty, and the reader must treat its own state as unresolved rather than judge anyone by it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeContactSignals(
    messages: MessageRepository,
    groups: GroupRepository,
    settings: SettingsStore,
    me: Flow<String?>,
): Flow<ContactSignals> {
    val authored: Flow<Set<String>> =
        me.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(emptySet()) else messages.observeConversationsIAuthoredIn(id).map { it.toSet() }
        }
    // Who has posted in each group, less blocked senders — the chat list's input to the group half of
    // `Conversations.isAccepted`, so a stranger's group invitation is a request here exactly when it is there.
    val groupSenders: Flow<Map<String, Set<String>>> =
        settings.blockedNodeIds.distinctUntilChanged().flatMapLatest { messages.observeGroupSenders(it) }
    return combine(
        messages.observeConversations(),
        authored,
        groups.observeGroups(),
        groupSenders,
        settings.acceptedConversations,
    ) { conversations, mine, groupList, senders, accepted ->
        ContactSignals(conversations.toSet(), mine, groupList, senders, accepted)
    }
}
