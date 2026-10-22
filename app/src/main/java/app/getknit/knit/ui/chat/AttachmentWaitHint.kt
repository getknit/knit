package app.getknit.knit.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.mesh.ArrivingFile

/** Whether nothing will ever bring the bytes: the placeholder then stops waiting ([ArrivalIndicator]'s `stopped`). */
internal val AttachmentWait.stopped: Boolean
    get() = this == AttachmentWait.CommonsTextOnly || this == AttachmentWait.CommonsGone

/**
 * The first line under a missing attachment's spinner when [wait] has one of its own, else null and the bubble
 * keeps its kind's line ("Photo appears once…"). Only a commons has one: its one relay is the only thing that can
 * bring the bytes, so "a device that has it" is never true there (ADR 2026-10.xhfr). Null while the bytes are
 * streaming in ([arrival]), whose progress leads the line instead.
 */
@Composable
internal fun attachmentWaitTitle(
    wait: AttachmentWait,
    arrival: ArrivingFile? = null,
): String? =
    if (arrival != null) {
        null
    } else {
        when (wait) {
            AttachmentWait.Nearby, AttachmentWait.Relay, AttachmentWait.RelayFramesOnly -> null
            AttachmentWait.CommonsRelay -> stringResource(R.string.chat_commons_attachment_coming)
            AttachmentWait.CommonsOffline -> stringResource(R.string.chat_commons_attachment_offline)
            AttachmentWait.CommonsTextOnly -> stringResource(R.string.chat_commons_attachment_text_only)
            AttachmentWait.CommonsGone -> stringResource(R.string.chat_commons_attachment_gone)
        }
    }

/**
 * The second line under a missing attachment's spinner, or null when there is nothing to add. One
 * function for the photo, voice, video and file bubbles so they never drift: the first line is each
 * kind's own ("Photo appears once…") or [attachmentWaitTitle]'s, this one is the Internet plane's half of the
 * answer ([AttachmentWait], work item 50). Null too while the bytes are streaming in over a nearby link
 * ([arrival], #115): they are already on their way, so where else they could come from has nothing to add.
 */
@Composable
internal fun attachmentWaitHint(
    wait: AttachmentWait,
    arrival: ArrivingFile? = null,
): String? =
    if (arrival != null) {
        null
    } else {
        when (wait) {
            AttachmentWait.Nearby, AttachmentWait.CommonsRelay, AttachmentWait.CommonsOffline -> null
            AttachmentWait.Relay -> stringResource(R.string.chat_attachment_wait_relay)
            AttachmentWait.RelayFramesOnly -> stringResource(R.string.chat_attachment_wait_frames_only)
            AttachmentWait.CommonsTextOnly -> stringResource(R.string.chat_commons_attachment_text_only_hint)
            AttachmentWait.CommonsGone -> stringResource(R.string.chat_commons_attachment_gone_hint)
        }
    }
