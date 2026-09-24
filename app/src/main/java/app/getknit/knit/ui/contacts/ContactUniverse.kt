package app.getknit.knit.ui.contacts

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations

/**
 * Who counts as an **established contact** — the rule the "new message" picker draws and search's People
 * section shares, deliberately not every stranger seen in the Nearby room (see [ContactsViewModel]). After
 * removing [me] and [blocked], a contact is any node in the union of: accepted DM peers (a DM thread among
 * [conversations] whose peer passes the shared [Conversations.isAccepted] rule, so an unanswered stranger
 * DM stays a request); peers the user explicitly [accepted] (a contact card imported, or a request
 * accepted — a contact before a single message exists in the thread); co-members of any active (non-left)
 * group in [groups] that the same predicate accepts, given its [groupSenders] (a group we created or posted
 * in, or one a known peer has spoken in — even with no ordinary messages yet, since the creator's
 * "created this group" line is authored), so a stranger's unanswered group invitation stays a request and
 * lends none of its members to this set (#82); and [verified] peers (a QR-verified contact never chatted
 * with, who may not have a cached profile yet).
 */
internal fun contactIds(
    conversations: Set<String>,
    authored: Set<String>,
    groups: List<GroupEntity>,
    groupSenders: Map<String, Set<String>>,
    accepted: Set<String>,
    verified: Set<String>,
    blocked: Set<String>,
    me: String,
): Set<String> {
    // A DM thread's conversationId IS the peer's node id; keep only those the shared accept predicate treats
    // as a real conversation (matching the chat list / requests split).
    val acceptedDmPeers =
        conversations
            .filter { Conversations.kindFor(it) == ConversationKind.DM }
            .filter { Conversations.isAccepted(it, accepted, verified, authored) }
    val explicitlyAccepted = accepted.filter { Conversations.kindFor(it) == ConversationKind.DM }
    val groupMembers =
        groups
            .filter { !it.left && Conversations.isAccepted(it.groupId, accepted, verified, authored, groupSenders[it.groupId].orEmpty()) }
            .flatMap { GroupMembersStore.decode(it.members) }
    return (acceptedDmPeers + explicitlyAccepted + groupMembers + verified).toSet() - blocked - me
}
