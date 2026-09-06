package app.getknit.knit.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.mesh.protocol.LinkCard
import app.getknit.knit.ui.image.LinkCardImage
import app.getknit.knit.ui.preview.KnitPreview
import coil3.compose.AsyncImage

/**
 * A link-preview card inside a bubble: the picture the sender's phone fetched (when the card has one), the
 * page's title and description, and the host the link points at — drawn from the decoded [card], never
 * from the page again. Tap opens the link; long-press reaches the bubble's reaction picker like every other
 * attachment. One labelled node for TalkBack: "Link preview: title, host".
 *
 * A [flagged] card (the recipient's own classifier hid it — its picture, its text, or both, one verdict)
 * stays behind the same placeholder a flagged photo gets until tapped, and the whole card at that, since the
 * verdict covers the whole card. No skeleton and no spinner for a card that has not decoded: the bubble
 * simply draws nothing until [ChatRow.linkCard] is set, because the body's own link is already tappable.
 */
@Composable
fun LinkPreviewCard(
    card: LinkCard,
    hash: String,
    key: String?,
    flagged: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(hash) { mutableStateOf(false) }
    if (flagged && !revealed) {
        HiddenCard(onReveal = { revealed = true }, onLongClick = onLongClick, modifier = modifier)
        return
    }
    val description = stringResource(R.string.chat_link_card_desc, card.title, card.host)
    Column(
        modifier =
            modifier
                .padding(vertical = 2.dp)
                .width(LINK_CARD_WIDTH)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.chat_open_link),
                    onClick = onOpen,
                    onLongClick = onLongClick,
                )
                // After the clickable, so the action survives and the texts merge into one sentence.
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    testTag = "chat_link_card"
                },
    ) {
        if (card.hasImage) {
            CardPicture(LinkCardImage(hash, key))
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            card.description?.let { text ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            HostLine(card.host)
        }
    }
}

/** The card's picture in a fixed slot, cropped to fill; a transient spinner until Coil has decoded it. */
@Composable
private fun CardPicture(image: LinkCardImage) {
    var decoded by remember(image.hash) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().height(LINK_CARD_IMAGE_HEIGHT).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { decoded = true },
            onError = { decoded = true },
            modifier = Modifier.matchParentSize(),
        )
        if (!decoded) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun HiddenCard(
    onReveal: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(vertical = 2.dp)
                .width(LINK_CARD_WIDTH)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = onReveal, onLongClick = onLongClick)
                .padding(vertical = 20.dp, horizontal = 12.dp)
                .testTag("chat_link_card_hidden"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.moderation_link_hidden),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The host, always left-to-right whatever the page's language, so `example.com` never mirrors. */
@Composable
private fun HostLine(host: String) {
    Text(
        text = host,
        style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The composer's staged card: what will ride with the message once sent — a small picture (or a link glyph
 * when the card has none), the title and the host — beside the same ✕ every staged attachment gets.
 */
@Composable
fun StagedLinkTile(
    card: LinkCard,
    hash: String,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.chat_link_staged_desc, card.title, card.host)
    Row(
        modifier =
            modifier
                .heightIn(min = 72.dp)
                .widthIn(max = 260.dp)
                .padding(start = 12.dp, end = 44.dp, top = 12.dp, bottom = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = description
                    testTag = "chat_link_staged"
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (card.hasImage) {
                AsyncImage(
                    model = LinkCardImage(hash),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HostLine(card.host)
        }
    }
}

/** The line the composer shows while a card is being fetched: transient, so its spinner ends when the fetch does. */
@Composable
fun LinkPreviewLoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("chat_link_preview_loading"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_link_preview_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The card is as wide as a photo attachment, so the two line up down a thread. */
val LINK_CARD_WIDTH = 220.dp
private val LINK_CARD_IMAGE_HEIGHT = 120.dp

private val previewCard =
    LinkCard(
        url = "https://example.com/articles/mesh",
        host = "example.com",
        title = "Mesh networking in the field",
        description = "How a few phones with no signal still find each other, and what that costs in batteries.",
        hasImage = false,
    )

@Preview(showBackground = true)
@Composable
fun LinkPreviewCardPreview() {
    KnitPreview { LinkPreviewCard(card = previewCard, hash = "h", key = null, flagged = false, onOpen = {}, onLongClick = {}) }
}

@Preview(showBackground = true)
@Composable
fun LinkPreviewCardHiddenPreview() {
    KnitPreview { LinkPreviewCard(card = previewCard, hash = "h", key = null, flagged = true, onOpen = {}, onLongClick = {}) }
}

@Preview(showBackground = true)
@Composable
fun StagedLinkTilePreview() {
    KnitPreview { StagedLinkTile(card = previewCard, hash = "h") }
}

@Preview(showBackground = true)
@Composable
fun LinkPreviewLoadingRowPreview() {
    KnitPreview { LinkPreviewLoadingRow() }
}
