package app.getknit.knit.debug

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.PublicPostOutcome
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.protocol.DeclaredLangs
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan

/**
 * Sends a text into any thread by its id, routing on the thread's kind exactly as `ChatViewModel.send` does: the
 * Nearby room and a DM through [MeshController.sendChat], a group by its joined [GroupRepository] row, a commons
 * through its relay, the Meshtastic room through this phone's own board. One copy for the headless bridge's `SEND`
 * and the USB control spike (`usbcontrol/`), so neither drifts from the other.
 */
internal object DebugSend {
    sealed interface Outcome {
        /** Handed to the mesh (or the relay). */
        data object Sent : Outcome

        /** The on-device content filter refused it. */
        data object Blocked : Outcome

        /** A `g-…` id this phone has not joined. */
        data object UnknownGroup : Outcome

        /** The Meshtastic room's board queued it. */
        data object Queued : Outcome

        /** The Meshtastic room's board refused it, for [reason]. */
        data class Refused(
            val reason: PublicPostRefusal,
        ) : Outcome
    }

    suspend fun send(
        mesh: MeshController,
        groups: GroupRepository,
        conversation: String,
        text: String,
        replyTo: ReplyRef? = null,
        styles: List<StyleSpan> = emptyList(),
        declared: DeclaredLangs = DeclaredLangs.NONE,
        mentions: List<Mention> = emptyList(),
        // The message's id when the caller minted it (FrameId.new) to read its row back; the Meshtastic room mints its own.
        frameId: String? = null,
        // An ingested picture to send with [text] as its caption; the caller refuses the Meshtastic room first.
        attachment: AttachmentStore.Ingested? = null,
    ): Outcome {
        val sent =
            when (Conversations.kindFor(conversation)) {
                ConversationKind.NEARBY -> {
                    mesh.sendChat(
                        text,
                        mentions = mentions,
                        recipientId = null,
                        group = null,
                        replyTo = replyTo,
                        styles = styles,
                        declared = declared,
                        frameId = frameId,
                        attachment = attachment,
                    )
                }

                ConversationKind.DM -> {
                    mesh.sendChat(
                        text,
                        mentions = mentions,
                        recipientId = conversation,
                        replyTo = replyTo,
                        styles = styles,
                        declared = declared,
                        frameId = frameId,
                        attachment = attachment,
                    )
                }

                ConversationKind.GROUP -> {
                    groups.find(conversation)?.let {
                        mesh.sendChat(
                            text,
                            mentions = mentions,
                            group = it.toGroupInfo(),
                            replyTo = replyTo,
                            styles = styles,
                            declared = declared,
                            frameId = frameId,
                            attachment = attachment,
                        )
                    }
                }

                // A commons post goes to the relay that runs the room, never a radio (§7.4).
                ConversationKind.COMMONS -> {
                    mesh.sendCommons(
                        conversation,
                        text,
                        mentions,
                        replyTo,
                        styles = styles,
                        declared = declared,
                        frameId = frameId,
                        attachment = attachment,
                    )
                }

                // The Meshtastic room posts through this phone's own board, and the outcome says why not.
                ConversationKind.MESHTASTIC -> {
                    return when (val outcome = mesh.sendPublicPost(text)) {
                        PublicPostOutcome.Queued -> Outcome.Queued
                        PublicPostOutcome.Blocked -> Outcome.Blocked
                        is PublicPostOutcome.Refused -> Outcome.Refused(outcome.reason)
                    }
                }
            }
        return when (sent) {
            null -> Outcome.UnknownGroup
            true -> Outcome.Sent
            false -> Outcome.Blocked
        }
    }
}
