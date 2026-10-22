package app.getknit.knit.ui.call

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.contacts.contactStanding
import app.getknit.knit.ui.contacts.observeContactSignals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Who is calling, beyond the name and face (experimental, `CALLS_WIRED`): their own profile status, where they stand
 * with this phone, and the newest message in the DM thread. All read from this phone's own store; nothing is asked of
 * the peer. [status] and [relationship] are shown over the lock screen too — the status is a public profile field and
 * the relationship names no group — while [namedRelationship] (the same line with the groups named) and [lastMessage]
 * wait for the phone to be unlocked.
 */
internal data class CallerContext(
    val status: String = "",
    val relationship: String? = null,
    val lastMessage: LastMessage? = null,
    val namedRelationship: String? = null,
) {
    /** The line to draw: the groups named once the phone is unlocked, counted while it is locked. */
    fun relationshipShown(locked: Boolean): String? = if (locked) relationship else namedRelationship ?: relationship
}

/** The newest ordinary message in the thread: who wrote it, when, and the row itself for the preview's wording. */
internal data class LastMessage(
    val fromMe: Boolean,
    val sentAt: Long,
    val message: MessageEntity,
)

/** Where the caller stands, as the screen draws it — group counts only, never a group's name. */
internal data class Relationship(
    val contact: Boolean,
    val verified: Boolean,
    val blocked: Boolean,
    val groupsTogether: Int,
    val anyMessages: Boolean,
)

/**
 * "Verified contact · 2 groups together", "Not a contact · no messages yet": one line. With [groupNames] (only off
 * the lock screen) the count gives way to the names: "Contact · in Climbing, Lab +1".
 */
internal fun relationshipText(
    context: Context,
    r: Relationship,
    groupNames: List<String> = emptyList(),
): String {
    val standing =
        when {
            r.blocked -> R.string.call_caller_blocked
            r.verified -> R.string.call_caller_verified
            r.contact -> R.string.call_caller_contact
            else -> R.string.call_caller_stranger
        }.let(context::getString)
    val extra =
        when {
            groupNames.isNotEmpty() -> {
                groupsLine(context, groupNames)
            }

            r.groupsTogether > 0 -> {
                context.resources.getQuantityString(
                    R.plurals.call_caller_groups_together,
                    r.groupsTogether,
                    r.groupsTogether,
                )
            }

            !r.contact && !r.anyMessages -> {
                context.getString(R.string.call_caller_no_messages)
            }

            else -> {
                null
            }
        }
    return extra?.let { context.getString(R.string.joined_with_dot, standing, it) } ?: standing
}

/** "in Climbing, Lab", then "+N" for the rest: the screen has one line for it. */
private fun groupsLine(
    context: Context,
    names: List<String>,
): String {
    val shown = names.take(NAMED_GROUPS).joinToString(", ")
    val rest = names.size - NAMED_GROUPS
    return if (rest >
        0
    ) {
        context.getString(R.string.call_caller_in_groups_more, shown, rest)
    } else {
        context.getString(R.string.call_caller_in_groups, shown)
    }
}

/**
 * Reads [peerId]'s context once. Standing is the contacts list's own rule ([contactStanding], ADR 2026-09.wnh6), read
 * the way `ContactRemover` reads it, so the call screen cannot call someone a contact the list does not.
 */
internal suspend fun callerContext(
    context: Context,
    peerId: String,
    peers: PeerRepository,
    messages: MessageRepository,
    groups: GroupRepository,
    settings: SettingsStore,
    identity: Identity,
): CallerContext {
    // The title of a group with no stored name and no other member.
    val unnamedGroup = context.getString(R.string.group_unnamed)
    val me = identity.nodeId()
    val peer = peers.find(peerId)
    val signals = observeContactSignals(messages, groups, settings, flowOf(me)).first()
    val verified = peers.verifiedNodeIds().toSet()
    val blocked = settings.blockedNodeIds.first()
    val standing = contactStanding(peerId, signals, verified, blocked)
    val shared = groups.groupsWith(peerId)
    // A group's title as the chat list draws it: its stored name, else its other members' labels.
    val labels = peers.labelIndex()
    val groupNames =
        shared
            .map { g ->
                groupTitle(g.name, GroupMembersStore.decode(g.members), me, fallback = unnamedGroup) { labels.labelFor(it).text }
            }.sortedBy { it.lowercase() }
    // A DM thread's id is the peer's node id. Status notices (a member left, …) are not messages anyone wrote.
    val newest =
        messages
            .observeNewestMessages(peerId, LAST_MESSAGE_WINDOW)
            .first()
            .lastOrNull { it.kind == MessageEntity.KIND_NORMAL }
    val relationship =
        Relationship(
            contact = standing.isContact,
            verified = standing.verified,
            blocked = standing.blocked,
            groupsTogether = shared.size,
            anyMessages = newest != null,
        )
    return CallerContext(
        status = peer?.status.orEmpty().trim(),
        relationship = relationshipText(context, relationship),
        namedRelationship = relationshipText(context, relationship, groupNames),
        lastMessage = newest?.let { LastMessage(fromMe = it.senderId == me, sentAt = it.sentAt, message = it) },
    )
}

// Enough rows that a run of status notices at the thread's end still leaves a message behind them.
private const val LAST_MESSAGE_WINDOW = 8

// How many group names the line spells out before it counts the rest.
private const val NAMED_GROUPS = 2
