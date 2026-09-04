package app.getknit.knit.ui.chat

import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.LinkPreviewBlob

/**
 * What an attachment is, for every surface that has to name one in a line of text.
 *
 * The order the cases are tested in is the whole content of this type. A **file** is identified by its own
 * [MessageEntity.attachmentName] rather than by its MIME, because the name is the more specific fact and the
 * only one worth showing; a **voice note** by its MIME; a **link-preview card** by its one MIME
 * ([LinkPreviewBlob.MIME]), checked before the photo arm because a card is the one nameless non-image the app
 * originates on purpose — in the Nearby room too; and everything else is a **photo**, which is what every
 * attachment was before voice notes, files and cards existed. The last arm also catches a non-image with no
 * name that is not a card — a shape no shipped build originates, but one a future or hostile peer could — so
 * it is checked explicitly rather than left to fall through as a photo.
 */
enum class AttachmentKind { Photo, Voice, File, Link }

/** The [AttachmentKind] of an attachment with this [mime] and [name]. */
fun attachmentKindOf(
    mime: String?,
    name: String?,
): AttachmentKind =
    when {
        name != null -> AttachmentKind.File
        VoiceAudio.isVoice(mime) -> AttachmentKind.Voice
        mime == LinkPreviewBlob.MIME -> AttachmentKind.Link
        mime == null || mime.startsWith("image/") -> AttachmentKind.Photo
        else -> AttachmentKind.File
    }
