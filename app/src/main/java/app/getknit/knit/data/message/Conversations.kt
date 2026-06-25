package app.getknit.knit.data.message

/**
 * Conversation identity helpers. A "conversation" groups messages into one thread: the public
 * broadcast room ([NEARBY]) or a 1:1 DM keyed by the *other* party's node id.
 *
 * Pure Kotlin with no Android dependencies so the mesh layer and the UI can share it (and it stays
 * unit-testable on the JVM).
 */
object Conversations {

    /** Stable id of the public broadcast room, surfaced in the chat list as "Nearby". */
    const val NEARBY: String = "nearby"

    /**
     * The conversation a message belongs to, from [selfId]'s perspective. Broadcast messages
     * ([recipientId] null) belong to [NEARBY]; a DM belongs to a thread keyed by the other party —
     * the [recipientId] for a message we sent, the [senderId] for one we received.
     */
    fun idFor(senderId: String, recipientId: String?, selfId: String): String = when {
        recipientId == null -> NEARBY
        senderId == selfId -> recipientId
        else -> senderId
    }

    /**
     * Whether an inbound chat addressed with [recipientId] is for us ([selfId]). Broadcast messages
     * ([recipientId] null) are for everyone; a DM is only for its named recipient. A node that is
     * merely relaying someone else's DM gets `false` and must not persist/notify/ack it.
     */
    fun isForMe(recipientId: String?, selfId: String): Boolean =
        recipientId == null || recipientId == selfId
}
