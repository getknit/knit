package app.getknit.knit.ui

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity

/**
 * Minimal entity builders for ViewModel/UI tests: default the noise so a test states only the fields it
 * asserts on. The wide Room data classes (e.g. [MessageEntity] has 18 fields) are otherwise unreadable
 * inline. [MessageEntity.id] defaults to a value derived from the distinguishing fields so a list of
 * messages gets distinct primary keys without every call spelling one out. [msg]'s long parameter list is
 * intentional — defaulting every field so a test names only what it asserts on is the whole point of a builder.
 */
@Suppress("LongParameterList")
fun msg(
    senderId: String,
    sentAt: Long = 0L,
    conversationId: String = Conversations.NEARBY,
    recipientId: String? = null,
    body: String = "hi",
    kind: Int = MessageEntity.KIND_NORMAL,
    attachmentHash: String? = null,
    moderation: Int = MessageEntity.MODERATION_NONE,
    received: Boolean = false,
    id: String = "$conversationId#$senderId#$sentAt",
): MessageEntity =
    MessageEntity(
        id = id,
        senderId = senderId,
        recipientId = recipientId,
        conversationId = conversationId,
        body = body,
        sentAt = sentAt,
        received = received,
        attachmentHash = attachmentHash,
        moderation = moderation,
        kind = kind,
    )

fun peer(
    nodeId: String,
    name: String = "",
    avatarHash: String? = null,
    pubKey: String? = null,
    verified: Boolean = false,
    updatedAt: Long = 0L,
): PeerEntity =
    PeerEntity(
        nodeId = nodeId,
        name = name,
        avatarHash = avatarHash,
        pubKey = pubKey,
        verified = verified,
        updatedAt = updatedAt,
    )

fun group(
    groupId: String,
    members: List<String>,
    name: String = "",
    createdBy: String = members.firstOrNull().orEmpty(),
    createdAt: Long = 0L,
    left: Boolean = false,
    photoHash: String? = null,
): GroupEntity =
    GroupEntity(
        groupId = groupId,
        name = name,
        members = GroupMembersStore.encode(members),
        createdBy = createdBy,
        createdAt = createdAt,
        left = left,
        photoHash = photoHash,
    )

fun reaction(
    messageId: String,
    reactorNodeId: String,
    emoji: String?,
    updatedAt: Long = 0L,
): ReactionEntity = ReactionEntity(messageId, reactorNodeId, emoji, updatedAt)
