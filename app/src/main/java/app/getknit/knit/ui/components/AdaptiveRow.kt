package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.preview.KnitPreview

/**
 * A row's main content with something at its end — a status, a tag, a button — that moves under the content when the
 * two would not share the width comfortably.
 *
 * Beside the content while both fit on one line unwrapped, or while the trailing part takes at most [maxTrailingShare]
 * of the row (its widest, unwrapped) and the content's longest word still fits what is left; under it otherwise, [stackedGap] below, at the end ([stackedAtEnd],
 * for a button) or at the start [stackedIndent] in (for a status that belongs under a title, past a leading dot). A
 * translation runs longer than the English, so a row laid out with a fixed `weight(1f)` title squeezed the title to a
 * word a line, or ellipsized it to nothing, as soon as the trailing part grew.
 *
 * Each slot is one layout: wrap several pieces in a `Row` or a `Column`. Beside, the content fills what the trailing part
 * leaves and both are centred on each other vertically. Placement is relative, so a right-to-left layout mirrors it.
 */
@Composable
fun AdaptiveRow(
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    stackedGap: Dp = 4.dp,
    stackedAtEnd: Boolean = false,
    stackedIndent: Dp = 0.dp,
    maxTrailingShare: Float = DEFAULT_TRAILING_SHARE,
    content: @Composable () -> Unit,
) {
    Layout(
        contents = listOf({ Box { content() } }, { Box { trailing() } }),
        modifier = modifier,
    ) { (mainSlot, trailingSlot), constraints ->
        val main = mainSlot.single()
        val end = trailingSlot.single()
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val beside =
            sideBySide(
                rowWidth = if (constraints.hasBoundedWidth) width else null,
                trailingWidth = end.maxIntrinsicWidth(constraints.maxHeight),
                contentMinWidth = main.minIntrinsicWidth(constraints.maxHeight),
                contentMaxWidth = main.maxIntrinsicWidth(constraints.maxHeight),
                gapPx = gapPx,
                maxTrailingShare = maxTrailingShare,
            )
        if (beside) {
            val trailingPlaced = end.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val mainWidth =
                if (constraints.hasBoundedWidth) {
                    (width - trailingPlaced.width - gapPx).coerceAtLeast(
                        0,
                    )
                } else {
                    Constraints.Infinity
                }
            val mainPlaced =
                main.measure(
                    Constraints(
                        minWidth = if (constraints.hasBoundedWidth) mainWidth else 0,
                        maxWidth = mainWidth,
                        maxHeight = constraints.maxHeight,
                    ),
                )
            val rowWidth = if (constraints.hasBoundedWidth) width else mainPlaced.width + gapPx + trailingPlaced.width
            val height = maxOf(mainPlaced.height, trailingPlaced.height).coerceAtLeast(constraints.minHeight)
            layout(rowWidth, height) {
                mainPlaced.placeRelative(0, (height - mainPlaced.height) / 2)
                trailingPlaced.placeRelative(rowWidth - trailingPlaced.width, (height - trailingPlaced.height) / 2)
            }
        } else {
            val indentPx = if (stackedAtEnd) 0 else stackedIndent.roundToPx()
            val mainPlaced = main.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
            val trailingPlaced = end.measure(Constraints(maxWidth = (width - indentPx).coerceAtLeast(0)))
            val top = mainPlaced.height + stackedGap.roundToPx()
            val height = (top + trailingPlaced.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                mainPlaced.placeRelative(0, 0)
                trailingPlaced.placeRelative(if (stackedAtEnd) width - trailingPlaced.width else indentPx, top)
            }
        }
    }
}

/**
 * Whether the trailing part goes beside the content. Either the two fit on one line unwrapped ([contentMaxWidth], so a
 * short title keeps a long status beside it), or the trailing part takes at most [maxTrailingShare] of [rowWidth] with
 * its gap and the content's longest word ([contentMinWidth]) fits the rest (so a paragraph keeps a button beside it,
 * wrapping in what is left). With no bound on the width there is nothing to share, so it stays beside.
 */
internal fun sideBySide(
    rowWidth: Int?,
    trailingWidth: Int,
    contentMinWidth: Int,
    contentMaxWidth: Int,
    gapPx: Int,
    maxTrailingShare: Float,
): Boolean {
    if (rowWidth == null) return true
    val taken = trailingWidth + gapPx
    if (contentMaxWidth + taken <= rowWidth) return true
    return taken <= rowWidth * maxTrailingShare && contentMinWidth <= rowWidth - taken
}

/** Up to two fifths of the row: a short status or a button beside a title, and no more. */
private const val DEFAULT_TRAILING_SHARE = 0.4f

@Preview(showBackground = true, widthDp = 360)
@Composable
fun AdaptiveRowPreview() =
    KnitPreview {
        Column(modifier = Modifier.padding(16.dp)) {
            AdaptiveRow(trailing = { Text("3 nearby · 2 linked", style = MaterialTheme.typography.bodyMedium) }) {
                Text("Bluetooth", style = MaterialTheme.typography.bodyMedium)
            }
            // The trailing part outgrows its share and moves under the title.
            AdaptiveRow(
                trailing = { TextButton(onClick = {}) { Text("Open the settings for this app") } },
                stackedAtEnd = true,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
