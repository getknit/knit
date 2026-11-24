package app.getknit.knit.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.sticker.Sticker
import app.getknit.knit.ui.image.BlobImage
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
        modifier =
            modifier
                .size(STICKER_SIZE)
                .stickerOutline(stickerOutlineColor())
                .testTag("chat_sticker_image"),
    )
}

/**
 * The outline a sticker gets on a dark background (ADR 2026-11.rk6d's 2026-11-23 amendment), or [Color.Unspecified]
 * for none. A sticker is drawn on the chat's own background, and one drawn for a light page (black lettering with no
 * border of its own) all but vanishes on a dark one. A light theme draws none: dark ink reads there, and a dark ring
 * would spoil the white border most stickers carry. Read off the background the sticker sits on rather than the system
 * setting, so a theme Knit chose for itself decides it.
 */
@Composable
internal fun stickerOutlineColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < DARK_BACKGROUND) scheme.onSurface else Color.Unspecified
}

/**
 * Traces [color] around the opaque shape of what this node draws, [width] wide: the content is recorded once, drawn
 * tinted at [OUTLINE_STEPS] points on a circle of that radius, then drawn as itself on top — the die-cut border a
 * printed sticker has. A sticker that already carries a white border looks unchanged, since the outline hides under
 * it; black lettering gains a light edge. An animated sticker is recorded afresh each frame, so every frame is traced.
 * Nothing at all is drawn extra when [color] is [Color.Unspecified].
 */
internal fun Modifier.stickerOutline(
    color: Color,
    width: Dp = STICKER_OUTLINE_WIDTH,
): Modifier =
    if (color == Color.Unspecified) {
        this
    } else {
        drawWithCache {
            // Two layers, because a layer is drawn with the properties it holds when the frame renders, not when it is
            // drawn: one tint toggled on and off around the stamps would leave every stamp untinted. The outline layer
            // holds the stamps of the content layer and keeps its tint for good; the content layer keeps none.
            val content = obtainGraphicsLayer()
            val outline = obtainGraphicsLayer().apply { colorFilter = ColorFilter.tint(color, BlendMode.SrcIn) }
            val radius = width.toPx()
            val offsets =
                List(OUTLINE_STEPS) { step ->
                    val angle = 2 * PI * step / OUTLINE_STEPS
                    Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
                }
            onDrawWithContent {
                content.record { this@onDrawWithContent.drawContent() }
                outline.record { offsets.forEach { offset -> translate(offset.x, offset.y) { drawLayer(content) } } }
                drawLayer(outline)
                drawLayer(content)
            }
        }
    }

/** How wide the outline is: enough to lift black lettering off a dark page, thin enough to read as a border. */
private val STICKER_OUTLINE_WIDTH = 2.dp

/** Points the outline is stamped at: twelve leave no visible gap at 2 dp, even on a hairline stroke's diagonal. */
private const val OUTLINE_STEPS = 12

/** A background darker than this is a dark theme's: Material's dark surfaces sit far below it, its light ones far above. */
private const val DARK_BACKGROUND = 0.4f
