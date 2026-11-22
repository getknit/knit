package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.linkpreview.LinkPreviewPolicy
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob

/**
 * The card for [message] when it carries one that has been decoded ([cards], by blob hash) **and** whose link the
 * body actually contains — a sender cannot attach one page's card to another page's link. The compare is on
 * normalized links, so a typed `http://` and the card's `https://` form still match. The chat's bubble and the
 * Links tab of "Media, links and files" (ADR 2026-11.dgvf) both read it.
 */
internal fun linkCardFor(
    message: MessageEntity,
    cards: Map<String, LinkCard>,
): LinkCard? {
    if (message.attachmentMime != LinkPreviewBlob.MIME) return null
    val card = message.attachmentHash?.let { cards[it] } ?: return null
    return card.takeIf { c -> findUrls(message.body).any { LinkPreviewPolicy.sameUrl(it.url, c.url) } }
}
