package app.getknit.knit.debug

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.MeshController

/**
 * Sends a reaction to a stored message, routed on its thread's kind as `ChatViewModel.react` does: sealed to the DM
 * peer or the group, the cleartext room frame for Nearby, the relay for a commons, and nothing in the Meshtastic room,
 * which has no tapback form. Like the screen it toggles: [emoji] on a message that already wears ours retracts it.
 * One copy for the headless bridge's `REACT` and the USB control's `react`.
 */
internal object DebugReact {
    sealed interface Outcome {
        data object Sent : Outcome

        /** No such message here (only when the caller does not [allowUnknown]). */
        data object NoMessage : Outcome

        /** The Meshtastic room, a group this phone holds no row for, or a commons it has left: nowhere to send it. */
        data object Refused : Outcome
    }

    /**
     * [allowUnknown] keeps the bridge's old reach: a message this phone does not hold yet is reacted to as a room
     * message, so a soak can react to an id before it lands.
     */
    suspend fun react(
        mesh: MeshController,
        messages: MessageRepository,
        groups: GroupRepository,
        messageId: String,
        emoji: String,
        allowUnknown: Boolean = false,
    ): Outcome {
        val conv = messages.conversationOf(messageId)
        if (conv == null) {
            if (!allowUnknown) return Outcome.NoMessage
            mesh.sendReaction(messageId, emoji, recipientId = null, group = null)
            return Outcome.Sent
        }
        when (Conversations.kindFor(conv)) {
            ConversationKind.MESHTASTIC, ConversationKind.MESHCORE -> {
                return Outcome.Refused
            }

            ConversationKind.COMMONS -> {
                return if (mesh.sendCommonsReaction(conv, messageId, emoji)) Outcome.Sent else Outcome.Refused
            }

            ConversationKind.GROUP -> {
                val group = groups.find(conv)?.toGroupInfo() ?: return Outcome.Refused
                mesh.sendReaction(messageId, emoji, recipientId = null, group = group)
            }

            ConversationKind.DM -> {
                mesh.sendReaction(messageId, emoji, recipientId = conv, group = null)
            }

            ConversationKind.NEARBY -> {
                mesh.sendReaction(messageId, emoji, recipientId = null, group = null)
            }
        }
        return Outcome.Sent
    }
}
