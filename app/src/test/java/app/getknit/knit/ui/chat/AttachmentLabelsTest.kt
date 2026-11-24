package app.getknit.knit.ui.chat

import android.content.Context
import android.text.format.Formatter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.AttachmentStore.IngestResult.Reason
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-line stand-ins the chat list, the request list, a quote and the details screen show for a message: a
 * flagged body says only that it is hidden while the filter is on (ADR 2026-10.9rh6, and a reply quoting flagged
 * words too, ADR 2026-10.gpa3), a shared position is named rather than printed (ADR 2026-09.tss4), a body-less message
 * names its attachment's kind, and a size follows only when the sender declared one. Expected words come from the
 * string resources, so the test pins the choice of label, not its translation.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentLabelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun string(id: Int) = context.getString(id)

    private fun message(
        body: String = "",
        hash: String? = null,
        mime: String? = null,
        name: String? = null,
        size: Long? = null,
        animated: Boolean = false,
        sticker: Boolean = false,
        videoMs: Long? = null,
        moderation: Int = MessageEntity.MODERATION_NONE,
        replyToFlagged: Boolean = false,
    ) = MessageEntity(
        id = "m1",
        senderId = "peer",
        body = body,
        sentAt = 1_700_000_000_000L,
        attachmentHash = hash,
        attachmentMime = mime,
        attachmentName = name,
        attachmentSize = size,
        attachmentAnimated = animated,
        attachmentSticker = sticker,
        videoDurationMs = videoMs,
        moderation = moderation,
        replyToFlagged = replyToFlagged,
    )

    @Test
    fun aFlaggedBodyIsHiddenOnlyWhileTheFilterIsOn() {
        val flagged = message(body = "something rude", moderation = MessageEntity.MODERATION_TEXT_FLAGGED)

        assertEquals(string(R.string.moderation_preview_hidden), messagePreview(context, flagged, hideFlagged = true))
        assertEquals("something rude", messagePreview(context, flagged, hideFlagged = false))
    }

    @Test
    fun aReplyQuotingFlaggedWordsIsHiddenLikeAFlaggedBody() {
        val reply = message(body = "agreed", replyToFlagged = true)

        assertEquals(string(R.string.moderation_preview_hidden), messagePreview(context, reply, hideFlagged = true))
        assertEquals("agreed", messagePreview(context, reply, hideFlagged = false))
    }

    @Test
    fun aSharedPositionIsNamedNotPrinted() {
        val shared = message(body = "meet here geo:37.4219983,-122.084;u=12")

        assertEquals("meet here ${string(R.string.chat_list_preview_location)}", messagePreview(context, shared, hideFlagged = true))
    }

    @Test
    fun aBodyWinsOverItsAttachment() {
        val captioned = message(body = "look", hash = "h", mime = "image/jpeg")

        assertEquals("look", messagePreview(context, captioned, hideFlagged = false))
    }

    @Test
    fun aBodylessMessageNamesItsAttachmentAndAnEmptyOneSaysNothing() {
        assertEquals(string(R.string.chat_list_preview_photo), messagePreview(context, message(hash = "h", mime = "image/jpeg"), false))
        assertEquals(
            string(R.string.chat_list_preview_gif),
            messagePreview(context, message(hash = "h", mime = "image/webp", animated = true), false),
        )
        assertEquals("", messagePreview(context, message(body = "   "), hideFlagged = false))
    }

    @Test
    fun aStickerIsCalledOneAnimatedOrNot() {
        // ADR 2026-11.rk6d: a sticker's shape names it ahead of its frames.
        assertEquals(
            string(R.string.chat_list_preview_sticker),
            messagePreview(context, message(hash = "h", mime = "image/webp", sticker = true), false),
        )
        assertEquals(
            string(R.string.chat_list_preview_sticker),
            messagePreview(context, message(hash = "h", mime = "image/webp", animated = true, sticker = true), false),
        )
    }

    @Test
    fun eachKindHasItsOwnLabel() {
        assertEquals(string(R.string.chat_list_preview_voice), attachmentLabel(context, "audio/aac", name = null))
        assertEquals(string(R.string.chat_list_preview_link), attachmentLabel(context, LinkPreviewBlob.MIME, name = null))
        assertEquals(string(R.string.chat_list_preview_video), attachmentLabel(context, "video/mp4", name = null, video = true))
        // A photo whose type never arrived is still a photo: only a named or typed non-image is a file.
        assertEquals(string(R.string.chat_list_preview_photo), attachmentLabel(context, mime = null, name = null))
    }

    @Test
    fun aVideoWithoutItsDescriptionIsAFile() {
        // An older build's undescribed `.mp4` (ADR 2026-10.hmfe's amendment) is offered as a file, by its own name if it
        // has one.
        val unnamed = context.getString(R.string.chat_list_preview_file, string(R.string.chat_file_unnamed))
        assertEquals(unnamed, attachmentLabel(context, "video/mp4", name = null))
        assertEquals(
            context.getString(R.string.chat_list_preview_file, "clip.mp4"),
            attachmentLabel(context, "video/mp4", name = "clip.mp4"),
        )
    }

    @Test
    fun aNameMakesAnyAttachmentAFile() {
        assertEquals(
            context.getString(R.string.chat_list_preview_file, "IMG_0001.jpg"),
            attachmentLabel(context, "image/jpeg", name = "IMG_0001.jpg"),
        )
    }

    @Test
    fun theDescribedVideoIsReadFromItsDuration() {
        val described = message(hash = "h", mime = "video/mp4", videoMs = 12_000L)
        val bare = message(hash = "h", mime = "video/mp4")

        assertEquals(string(R.string.chat_list_preview_video), attachmentPreview(context, described))
        assertEquals(
            context.getString(R.string.chat_list_preview_file, string(R.string.chat_file_unnamed)),
            attachmentPreview(context, bare),
        )
    }

    @Test
    fun aSizeFollowsTheLabelOnlyWhenTheSenderDeclaredOne() {
        val label = context.getString(R.string.chat_list_preview_file, "report.pdf")

        assertEquals(label, attachmentLabel(context, "application/pdf", "report.pdf"))
        assertEquals(
            context.getString(R.string.chat_attachment_with_size, label, Formatter.formatShortFileSize(context, 2_500_000L)),
            attachmentPreview(context, message(hash = "h", mime = "application/pdf", name = "report.pdf", size = 2_500_000L)),
        )
    }

    @Test
    fun eachIngestFailureHasItsOwnMessage() {
        assertEquals(R.string.chat_image_capture_failed, ingestFailureMessage(Reason.Unreadable))
        assertEquals(R.string.chat_file_too_large, ingestFailureMessage(Reason.TooLarge))
        assertEquals(R.string.chat_file_package_refused, ingestFailureMessage(Reason.Installable))
    }
}
