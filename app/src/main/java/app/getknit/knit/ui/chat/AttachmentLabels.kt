package app.getknit.knit.ui.chat

import android.content.Context
import android.text.format.Formatter.formatShortFileSize
import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.location.GeoUri
import app.getknit.knit.mesh.NotificationLabels

/**
 * The one-line stand-in a message with no body gets: the chat list's preview, the message-request list's,
 * the label a quoted attachment writes into `ReplyRef.snippet`, and the message-details body line.
 *
 * [size] is appended when it is known — a file's declared byte count. An image or a voice note carries none,
 * and the line simply ends after the name rather than inventing one. An image with more than one frame
 * ([MessageEntity.attachmentAnimated]) reads "GIF" rather than "Photo", and one with a sticker's shape
 * ([MessageEntity.attachmentSticker], ADR 2026-11.rk6d) reads "Sticker", animated or not.
 *
 * The mirror of this logic without a `Context` lives in `InboundPipeline.attachmentPreview` (that layer is
 * deliberately Android-light, `rules/mesh.md`, and reads these strings through [notificationLabels]) and the two
 * are changed together — as are the two callers of [GeoUri.describe], this file's [messagePreview] and the
 * pipeline's notification body.
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
        video = message.videoDurationMs != null,
        sticker = message.attachmentSticker,
    )

/**
 * The one-line preview of a whole message: its body with a shared position named rather than printed
 * (`geo:37.42…` reads as "📍 Location"), or, for a body-less message, [attachmentPreview]'s stand-in. With
 * [hideFlagged] (content filtering on), text the moderator flagged — or a reply quoting flagged words, ADR
 * 2026-10.gpa3 — says only that it is hidden: a preview has no tap to reveal, and since only the public rooms screen
 * on the way out, a DM's flagged text lands here (ADR 2026-10.9rh6).
 */
fun messagePreview(
    context: Context,
    message: MessageEntity,
    hideFlagged: Boolean,
): String =
    when {
        message.hiddenByFilter(hideFlagged) -> {
            context.getString(R.string.moderation_preview_hidden)
        }

        message.body.isNotBlank() -> {
            GeoUri.describe(message.body, context.getString(R.string.chat_list_preview_location))
        }

        message.attachmentHash != null -> {
            attachmentPreview(context, message)
        }

        else -> {
            ""
        }
    }

/** [attachmentPreview]'s form for a caller that holds the fields rather than the row. */
fun attachmentLabel(
    context: Context,
    mime: String?,
    name: String?,
    size: Long? = null,
    animated: Boolean = false,
    video: Boolean = false,
    sticker: Boolean = false,
): String {
    val label =
        when (attachmentKindOf(mime, name, video)) {
            AttachmentKind.Video -> {
                context.getString(R.string.chat_list_preview_video)
            }

            AttachmentKind.Voice -> {
                context.getString(R.string.chat_list_preview_voice)
            }

            AttachmentKind.Photo -> {
                context.getString(
                    when {
                        sticker -> R.string.chat_list_preview_sticker
                        animated -> R.string.chat_list_preview_gif
                        else -> R.string.chat_list_preview_photo
                    },
                )
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

/** The words a notification says in place of a body it cannot show, in the reader's language (see [NotificationLabels]). */
fun notificationLabels(context: Context): NotificationLabels =
    NotificationLabels(
        photo = context.getString(R.string.chat_list_preview_photo),
        gif = context.getString(R.string.chat_list_preview_gif),
        voice = context.getString(R.string.chat_list_preview_voice),
        link = context.getString(R.string.chat_list_preview_link),
        location = context.getString(R.string.chat_list_preview_location),
        file = context.getString(R.string.chat_list_preview_file),
        hidden = context.getString(R.string.moderation_preview_hidden),
        video = context.getString(R.string.chat_list_preview_video),
        reaction = context.getString(R.string.reaction_preview),
        unnamed = context.getString(R.string.chat_meshcore_unnamed),
        claimedName = context.getString(R.string.chat_meshcore_name_claimed),
    )

/** What to say about an ingest that produced nothing. */
@StringRes
fun ingestFailureMessage(reason: AttachmentStore.IngestResult.Reason): Int =
    when (reason) {
        AttachmentStore.IngestResult.Reason.Unreadable -> R.string.chat_image_capture_failed
        AttachmentStore.IngestResult.Reason.TooLarge -> R.string.chat_file_too_large
        AttachmentStore.IngestResult.Reason.Installable -> R.string.chat_file_package_refused
    }
