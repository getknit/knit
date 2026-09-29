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
    val groupMembers = bindingGroups(groups, groupSenders, accepted, verified, authored).flatMap { GroupMembersStore.decode(it.members) }
    return (acceptedDmPeers + explicitlyAccepted + groupMembers + verified).toSet() - blocked - me
}

/**
 * The groups whose members [contactIds] counts: active (non-left) groups the shared [Conversations.isAccepted]
 * rule accepts, given who has posted in each. A group request lends nobody — that is #82.
 */
private fun bindingGroups(
    groups: List<GroupEntity>,
    groupSenders: Map<String, Set<String>>,
    accepted: Set<String>,
    verified: Set<String>,
    authored: Set<String>,
): List<GroupEntity> =
    groups.filter {
        !it.left && Conversations.isAccepted(it.groupId, accepted, verified, authored, groupSenders[it.groupId].orEmpty())
    }

/**
 * Where one peer stands against [contactIds], signal by signal — what removing the contact would clear, and
 * what it cannot (ADR 2026-09.adgd). [accepted], [verified] and [authoredDm] are our own signals: the
 * explicit accept (a card imported, a request accepted, the profile's Message), the out-of-band key check,
 * and a DM thread we have written in. [bindingGroups] are the accepted, active groups the peer is a member
 * of; only the peer's own signed leave shrinks a roster, so those keep them a contact whatever we clear.
 */
internal data class ContactStanding(
    val accepted: Boolean,
    val verified: Boolean,
    val authoredDm: Boolean,
    val bindingGroups: List<GroupEntity>,
    val blocked: Boolean,
) {
    /** Whether any signal a removal clears is present. */
    val ownSignals: Boolean get() = accepted || verified || authoredDm

    /** Exactly `nodeId in contactIds(...)`, for any node id but our own (the caller checks that). */
    val isContact: Boolean get() = !blocked && (ownSignals || bindingGroups.isNotEmpty())
}

/** [nodeId]'s [ContactStanding], judged by the same rule as [contactIds]; never call it with our own id. */
internal fun contactStanding(
    nodeId: String,
    signals: ContactSignals,
    verified: Set<String>,
    blocked: Set<String>,
): ContactStanding {
    val authoredDm = nodeId in signals.authored && Conversations.kindFor(nodeId) == ConversationKind.DM
    return ContactStanding(
        accepted = nodeId in signals.accepted,
        verified = nodeId in verified,
        authoredDm = authoredDm,
        bindingGroups =
            bindingGroups(signals.groups, signals.groupSenders, signals.accepted, verified, signals.authored)
                .filter { nodeId in GroupMembersStore.decode(it.members) },
        blocked = nodeId in blocked,
    )
}

/**
 * The active groups that are accepted only because [nodeId] is a known peer who has posted in them — the
 * sender clause of [Conversations.isAccepted] — and would fall back into message requests once [nodeId]'s
 * own signals are cleared, taking every member they lend to [contactIds] with them. Removing a contact
 * accepts these first, so removing one person never demotes a group (ADR 2026-09.adgd). Membership is not
 * checked: a group [nodeId] has since left can still owe its acceptance to their old posts.
 */
internal fun groupsAcceptedOnlyThrough(
    nodeId: String,
    signals: ContactSignals,
    verified: Set<String>,
): List<String> {
    val acceptedAfter = signals.accepted - nodeId
    val verifiedAfter = verified - nodeId
    val authoredAfter = signals.authored - nodeId
    return signals.groups
        .filter { !it.left }
        .map { it.groupId }
        .filter { groupId ->
            val senders = signals.groupSenders[groupId].orEmpty()
            Conversations.isAccepted(groupId, signals.accepted, verified, signals.authored, senders) &&
                !Conversations.isAccepted(groupId, acceptedAfter, verifiedAfter, authoredAfter, senders)
        }
}
