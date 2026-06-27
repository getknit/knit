package app.getknit.knit.data.message

import java.security.MessageDigest

/**
 * Conversation identity helpers. A "conversation" groups messages into one thread: the public
 * broadcast room ([NEARBY]), a 1:1 DM keyed by the *other* party's node id, or a group keyed by a
 * [groupIdFor] id derived from its member set.
 *
 * Pure Kotlin with no Android dependencies so the mesh layer and the UI can share it (and it stays
 * unit-testable on the JVM).
 */
object Conversations {

    /** Stable id of the public broadcast room, surfaced in the chat list as "Nearby". */
    const val NEARBY: String = "nearby"

    /**
     * The conversation a message belongs to, from [selfId]'s perspective. A group message ([groupId]
     * non-null) belongs to that group's thread, regardless of sender. Otherwise: broadcast messages
     * ([recipientId] null) belong to [NEARBY]; a DM belongs to a thread keyed by the other party —
     * the [recipientId] for a message we sent, the [senderId] for one we received.
     */
    fun idFor(senderId: String, recipientId: String?, selfId: String, groupId: String? = null): String =
        when {
            groupId != null -> groupId
            recipientId == null -> NEARBY
            senderId == selfId -> recipientId
            else -> senderId
        }

    /**
     * Whether an inbound chat addressed with [recipientId] is for us ([selfId]). Broadcast messages
     * ([recipientId] null) are for everyone; a DM is only for its named recipient. A node that is
     * merely relaying someone else's DM gets `false` and must not persist/notify/ack it.
     *
     * Group membership is decided separately by [isGroupMember] (a group's recipientId is null, so this
     * helper would wrongly call every group message "for me").
     */
    fun isForMe(recipientId: String?, selfId: String): Boolean =
        recipientId == null || recipientId == selfId

    /** Whether [selfId] is in a group's [members] roster — the group analogue of [isForMe]. */
    fun isGroupMember(members: List<String>, selfId: String): Boolean = selfId in members

    /**
     * Stable, order-agnostic id for a group defined by [members] (node ids). Derived from the sorted,
     * de-duplicated member set, so every device — and anyone who re-creates the same set of people —
     * resolves to the *same* group id rather than minting a duplicate thread. Prefixed [GROUP_ID_PREFIX]
     * and hex-encoded so it can't collide with the 8-char `[a-z0-9]` node ids or the [NEARBY] room in
     * conversation-id space. Pure (SHA-256 over the canonical member string), like
     * [app.getknit.knit.identity.NodeId.derive].
     */
    @Suppress("MagicNumber") // nibble math (4-bit shifts, 0xF masks) for hex encoding
    fun groupIdFor(members: List<String>): String {
        val canonical = members.toSortedSet().joinToString(separator = ",")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((GROUP_ID_SALT + canonical).encodeToByteArray())
        val hex = digest.take(GROUP_ID_BYTES).joinToString("") { byte ->
            val v = byte.toInt()
            "${HEX[(v shr 4) and 0xF]}${HEX[v and 0xF]}"
        }
        return GROUP_ID_PREFIX + hex
    }

    /** Prefix marking a derived group id; the hyphen guarantees it can't equal a node id. */
    const val GROUP_ID_PREFIX: String = "g-"
    private const val GROUP_ID_SALT = "knit-group-id-v1:"
    private const val GROUP_ID_BYTES = 12 // 24 hex chars — ample to avoid collisions
    private const val HEX = "0123456789abcdef"
}
