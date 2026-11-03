package app.getknit.knit.data.message

import app.getknit.knit.clampToUtf16
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.ellipsizeToUtf16
import app.getknit.knit.location.GeoUri
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.normalizeSingleLine
import java.util.Locale

/** Max characters of the quoted body snapshotted into a [app.getknit.knit.mesh.protocol.ReplyRef]; the quote UI ellipsizes further. */
const val REPLY_SNIPPET_MAX = 120

/**
 * The snippet to snapshot into a [app.getknit.knit.mesh.protocol.ReplyRef] when replying to a message whose body is
 * [body]: blank when [flagged] (a moderation-collapsed original isn't re-exposed through a quote that bypasses
 * tap-to-reveal) or when [body] is blank (an attachment-only original — the quote shows a "photo" placeholder
 * instead), else the body flattened to a single line and capped at [cap] characters.
 *
 * [attachmentLabel] is the one exception, and the reason this takes a label rather than deciding for itself.
 * A quoted **voice note** or **file** must not read as "📷 Photo", but `ReplyRef` carries no MIME and no name
 * — only `hasAttachment` — so a recipient cannot tell them apart from the wire. Rather than spend an additive
 * wire field on a cosmetic label, the sender writes the label into the snippet, which is already a free-text
 * string whose documented job is to describe the quoted message. The cost, stated: a cross-locale quote shows
 * the label in the *sender's* language until the recipient holds the original ([settleQuote] rewrites it in the
 * recipient's), and a quoted file's name crosses in the snippet even though the file itself may never be fetched.
 * If either ever matters, the fix is a nullable `ReplyRef.attachmentMime`, legal under `docs/WIRE_COMPAT.md` rule 1.
 */
fun buildReplySnippet(
    body: String,
    flagged: Boolean,
    attachmentLabel: String? = null,
    cap: Int = REPLY_SNIPPET_MAX,
): String =
    when {
        flagged -> ""
        body.isBlank() && attachmentLabel != null -> attachmentLabel.clampToUtf16(cap)
        else -> normalizeSingleLine(body).clampToUtf16(cap)
    }

/**
 * The quoted words in a "Reacted 👍 to “…”" line (the notification and the chat list's preview): [buildReplySnippet]'s
 * rule, but a body longer than [REPLY_SNIPPET_MAX] ends in "…" rather than mid-word before the closing quote. Display
 * only — a reply's quote snapshot stays [buildReplySnippet]'s, since the recipient rebuilds and compares it.
 */
fun reactionSnippet(
    preview: String,
    attachmentLabel: String? = null,
): String = buildReplySnippet(preview, flagged = false, attachmentLabel, cap = Int.MAX_VALUE).ellipsizeToUtf16(REPLY_SNIPPET_MAX)

/**
 * The localized stand-ins a quote names a body-less or positional original by. [fileFormat] takes the file's name
 * as its one argument. A plain photo has no label here: its snippet stays blank and `hasAttachment` makes the quote
 * say "Photo" in the reader's own language.
 */
data class QuoteLabels(
    val location: String,
    val voice: String,
    val fileFormat: String,
    val gif: String,
) {
    companion object {
        /** The English labels — the test rigs' default; the app hands the verifier the reader's own (`quoteLabels(context)`). */
        val ENGLISH = QuoteLabels(location = "📍 Location", voice = "🎤 Voice message", fileFormat = $$"📎 %1$s", gif = "🎞️ GIF")
    }
}

/**
 * The snippet a quote of a message carries — the one rule both ends apply: the composer when it snapshots a quote,
 * and the recipient when it rebuilds one from the original it holds ([settleQuote]). A position reads as its label,
 * a body-less file, voice note or GIF as theirs, and [flagged] blanks it (see [buildReplySnippet]).
 */
fun quoteSnippet(
    body: String,
    attachmentName: String?,
    attachmentMime: String?,
    animated: Boolean,
    flagged: Boolean,
    labels: QuoteLabels,
): String =
    buildReplySnippet(
        GeoUri.describe(body, labels.location),
        flagged,
        attachmentLabel =
            when {
                attachmentName != null -> labels.fileFormat.format(Locale.getDefault(), attachmentName)
                VoiceAudio.isVoice(attachmentMime) -> labels.voice
                animated -> labels.gif
                else -> null
            },
    )

/** Whether a quote of a message with this attachment shows an attachment glyph — a link card shows its link text instead. */
fun quoteHasAttachment(
    attachmentHash: String?,
    attachmentMime: String?,
): Boolean = attachmentHash != null && attachmentMime != LinkPreviewBlob.MIME

/**
 * Whether [original] is the message [reply] quotes, as this phone holds it (ADR 2026-10.gpa3). A frame id is random
 * and bound to nobody, so the id alone proves nothing: the original must sit in the reply's own thread, under the
 * author the quote names, and be somebody's words — not a status line, not a heard Meshtastic post (whose sender
 * column is this phone by convention), and not the reply itself. Anything else is [MessageEntity.REPLY_MISMATCH].
 */
fun quoteVerdict(
    reply: MessageEntity,
    original: MessageEntity,
): Int {
    val somebodysWords = original.kind == MessageEntity.KIND_NORMAL && original.originNode == null
    val named = original.conversationId == reply.conversationId && original.senderId == reply.replyToAuthorId
    return if (somebodysWords && named && original.id != reply.id) MessageEntity.REPLY_VERIFIED else MessageEntity.REPLY_MISMATCH
}

/** What a reply's quote columns become once its original has been judged — see [settleQuote]. */
data class QuoteSettlement(
    val check: Int,
    val snippet: String,
    val hasAttachment: Boolean,
    val flagged: Boolean,
)

/**
 * The quote [reply] shows once [original] is judged ([quoteVerdict]). A verified quote is rebuilt from the original
 * — the sender's snapshot was only a claim — and keeps the original's words even when they are flagged, because the
 * reader's Content filtering toggle, not this column, decides whether they show; [QuoteSettlement.flagged] carries
 * the original's own verdict and nothing it inherited, so a reply to a reply hidden for its quote is not hidden too.
 * A mismatch keeps nothing of the claim.
 */
fun settleQuote(
    reply: MessageEntity,
    original: MessageEntity,
    labels: QuoteLabels,
): QuoteSettlement =
    when (quoteVerdict(reply, original)) {
        MessageEntity.REPLY_VERIFIED -> {
            QuoteSettlement(
                check = MessageEntity.REPLY_VERIFIED,
                snippet =
                    quoteSnippet(
                        original.body,
                        original.attachmentName,
                        original.attachmentMime,
                        original.attachmentAnimated,
                        flagged = false,
                        labels = labels,
                    ),
                hasAttachment = quoteHasAttachment(original.attachmentHash, original.attachmentMime),
                flagged = original.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
            )
        }

        else -> {
            QuoteSettlement(MessageEntity.REPLY_MISMATCH, snippet = "", hasAttachment = false, flagged = false)
        }
    }

/** A copy with [settlement] written into the quote columns. */
fun MessageEntity.settledWith(settlement: QuoteSettlement): MessageEntity =
    copy(
        replyCheck = settlement.check,
        replyToSnippet = settlement.snippet,
        replyToHasAttachment = settlement.hasAttachment,
        replyToFlagged = settlement.flagged,
    )
