package app.getknit.knit.ui.chat

import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.normalizeSingleLine

/** Max characters of the quoted body snapshotted into a [ReplyRef]; the quote UI ellipsizes further. */
const val REPLY_SNIPPET_MAX = 120

/**
 * The author label a quote shows: the [selfLabel] ("You") when the quoted message is the viewer's own
 * ([ReplyRef.authorId] == [myNodeId]), else the snapshotted [ReplyRef.author]. The swap is viewer-relative,
 * which is why it's resolved here at render time from [myNodeId] rather than baked into the stored snapshot.
 */
fun quoteAuthorLabel(
    replyTo: ReplyRef,
    myNodeId: String,
    selfLabel: String,
): String = if (replyTo.authorId == myNodeId) selfLabel else replyTo.author

/**
 * The snippet to snapshot into a [ReplyRef] when replying to a message whose body is [body]: blank when
 * [flagged] (a moderation-collapsed original isn't re-exposed through a quote that bypasses tap-to-reveal)
 * or when [body] is blank (an attachment-only original — the quote shows a "photo" placeholder instead),
 * else the body flattened to a single line and capped at [cap] characters.
 */
fun buildReplySnippet(
    body: String,
    flagged: Boolean,
    cap: Int = REPLY_SNIPPET_MAX,
): String = if (flagged) "" else normalizeSingleLine(body).take(cap)
