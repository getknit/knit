package app.getknit.knit.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.sticker.Sticker
import app.getknit.knit.ui.image.BlobImage
import coil3.compose.AsyncImage

/**
 * The square a sticker is drawn in, without a bubble (ADR 2026-11.rk6d): larger than a jumbo emoji, smaller than a
 * photo's 220 dp, the size other messengers give one.
 */
internal val STICKER_SIZE = 160.dp

/**
 * What the long-press menu offers on a sticker (ADR 2026-11.rk6d): Save sticker, or Remove from stickers when the tray
 * already holds it. [savedHashes] are the saved stickers' plain hashes, which a row names by [ChatRow.stickerPlainHash].
 */
class StickerActions(
    val savedHashes: Set<String>,
    val onSave: (Sticker) -> Unit,
    val onRemove: (plainHash: String) -> Unit,
) {
    fun isSaved(row: ChatRow): Boolean = row.stickerPlainHash?.let { it in savedHashes } == true
}

/** The sticker [ChatRow.showsAsSticker] draws, as the tray saves it; null on any other row, and on one it cannot name. */
fun ChatRow.asSticker(): Sticker? {
    if (!showsAsSticker) return null
    val plainHash = stickerPlainHash ?: return null
    val hash = attachmentHash ?: return null
    val mime = attachmentMime ?: return null
    return Sticker(plainHash = plainHash, blobHash = hash, blobKey = attachmentKey, mime = mime, animated = attachmentAnimated)
}

/**
 * A sticker's picture, fitted into [STICKER_SIZE] and pushed to the message's side, so a sticker narrower than the
 * square still lines up with the bubbles around it. It takes no tap: a sticker opens no viewer, and the long-press is
 * the message's own. [hash], [mime] and [key] name the held blob as [BlobImage] does.
 */
@Composable
internal fun StickerImage(
    hash: String,
    mime: String?,
    key: String?,
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = BlobImage(hash, mime, key),
        contentDescription = stringResource(R.string.sticker_label),
        contentScale = ContentScale.Fit,
        alignment = if (mine) Alignment.BottomEnd else Alignment.BottomStart,
        modifier = modifier.size(STICKER_SIZE).testTag("chat_sticker_image"),
    )
}
