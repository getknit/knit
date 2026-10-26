package app.getknit.knit.mesh

import app.getknit.knit.data.video.VideoAttachment
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * The attachments a Nearby room post may carry on the receiver: the kinds the room's composer offers and the receive
 * side screens — a picture, a link-preview card, a short video (`docs/CONTENT_MODERATION.md` §7, ADR 2026-10.79j9).
 * The mic and the paperclip are hidden only in the sender's composer, and a room attachment is key-less, so its MIME
 * is the author's claim: a modified client could name a voice note or a file, and nothing classifies either. Anything
 * else is dropped whole — from the row (hash, MIME, size, description), and from custody's carrier column — so it is
 * never pulled, carried, described or drawn. The frame itself is untouched: custody and the relay carry the signed
 * bytes as they came, and every node decides this from the frame alone.
 */
object RoomAttachments {
    /** Whether a room post may name an attachment of [mime]. No MIME is a picture, screened as one. */
    fun admits(mime: String?): Boolean =
        mime == null || mime.startsWith("image/") || mime == LinkPreviewBlob.MIME || VideoAttachment.isVideo(mime)

    /**
     * Whether [env] is a Nearby room post — a chat addressed to nobody, in no group, sealed to nobody — whose
     * attachment the room does not [admits]. The one room test for the row, the carrier and custody alike.
     */
    fun refuses(
        env: RelayEnvelope,
        content: ChatContent,
    ): Boolean =
        env.type == FrameType.CHAT &&
            env.recipientId == null &&
            env.group == null &&
            content.enc == null &&
            content.attachmentHash != null &&
            !admits(content.attachmentMime)

    /**
     * Whether [env] is a room post that was nothing but a refused attachment: once the attachment is dropped there is
     * nothing left to draw, so no row is kept at all — no empty bubble, no notification, no unread count.
     */
    fun dropsWhole(
        env: RelayEnvelope,
        content: ChatContent,
    ): Boolean = refuses(env, content) && content.body.isBlank()

    /** [content] with its attachment dropped when [env] [refuses] it; as-is otherwise. */
    fun admitted(
        env: RelayEnvelope,
        content: ChatContent,
    ): ChatContent =
        if (!refuses(env, content)) {
            content
        } else {
            content.copy(
                attachmentHash = null,
                attachmentMime = null,
                attachmentSize = null,
                video = null,
                attachmentName = null,
                attachmentKey = null,
            )
        }
}
