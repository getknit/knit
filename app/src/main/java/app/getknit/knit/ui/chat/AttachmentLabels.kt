package app.getknit.knit.ui.chat

import android.content.Context
import android.text.format.Formatter.formatShortFileSize
import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.location.GeoUri

/**
 * The one-line stand-in a message with no body gets: the chat list's preview, the message-request list's,
 * the label a quoted attachment writes into `ReplyRef.snippet`, and the message-details body line.
 *
 * [size] is appended when it is known — a file's declared byte count. An image or a voice note carries none,
 * and the line simply ends after the name rather than inventing one. An image with more than one frame
 * ([MessageEntity.attachmentAnimated]) reads "GIF" rather than "Photo".
 *
 * The mirror of this logic without a `Context` lives in `InboundPipeline.attachmentPreview` (that layer is
 * deliberately Android-light, `rules/mesh.md`) and the two are changed together — as are the two callers of
 * [GeoUri.describe], this file's [messagePreview] and the pipeline's notification body.
 */
fun attachmentPreview(
    context: Context,
    message: MessageEntity,
): String =
    attachmentLabel(
        context,
        message.attachmentMime,
        message.attachmentName,
        message.attachmentSize,
        animated = message.attachmentAnimated,
    )

/**
 * The one-line preview of a whole message: its body with a shared position named rather than printed
 * (`geo:37.42…` reads as "📍 Location"), or, for a body-less message, [attachmentPreview]'s stand-in.
 */
fun messagePreview(
    context: Context,
    message: MessageEntity,
): String =
    when {
        message.body.isNotBlank() -> GeoUri.describe(message.body, context.getString(R.string.chat_list_preview_location))
        message.attachmentHash != null -> attachmentPreview(context, message)
        else -> ""
    }

/** [attachmentPreview]'s form for a caller that holds the fields rather than the row. */
fun attachmentLabel(
    context: Context,
    mime: String?,
    name: String?,
    size: Long? = null,
    animated: Boolean = false,
): String {
    val label =
        when (attachmentKindOf(mime, name)) {
            AttachmentKind.Voice -> {
                context.getString(R.string.chat_list_preview_voice)
            }

            AttachmentKind.Photo -> {
                context.getString(if (animated) R.string.chat_list_preview_gif else R.string.chat_list_preview_photo)
            }

            AttachmentKind.File -> {
                context.getString(
                    R.string.chat_list_preview_file,
                    name ?: context.getString(R.string.chat_file_unnamed),
                )
            }

            AttachmentKind.Link -> {
                context.getString(R.string.chat_list_preview_link)
            }
        }
    return if (size == null) label else context.getString(R.string.chat_attachment_with_size, label, formatShortFileSize(context, size))
}

/** What to say about an ingest that produced nothing. */
@StringRes
fun ingestFailureMessage(reason: AttachmentStore.IngestResult.Reason): Int =
    when (reason) {
        AttachmentStore.IngestResult.Reason.Unreadable -> R.string.chat_image_capture_failed
        AttachmentStore.IngestResult.Reason.TooLarge -> R.string.chat_file_too_large
        AttachmentStore.IngestResult.Reason.Installable -> R.string.chat_file_package_refused
    }
