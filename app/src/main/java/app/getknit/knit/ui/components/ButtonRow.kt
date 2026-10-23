package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.preview.KnitPreview

/**
 * Buttons that share the width equally while each one's label fits its share on one line, and stack, each the full
 * width, once one would wrap.
 *
 * Two half-width buttons fit an English "Share link" and "Copy link" with room to spare; a translation runs longer, and
 * a label that wraps in half a row makes a tall button with its icon beside two lines. Each button is measured at its
 * widest (unwrapped, with its padding and icon), so the switch happens before a line breaks. Stacked, the buttons keep
 * their order top to bottom. Placement is relative, so a right-to-left layout mirrors the row.
 */
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val bounded = constraints.hasBoundedWidth
        val width = constraints.maxWidth
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        if (buttonsFit(if (bounded) width else null, gapPx, widths)) {
            val slot = if (bounded) slotWidth(width, gapPx, measurables.size) else null
            val placed = measurables.map { it.measure(if (slot == null) Constraints() else Constraints.fixedWidth(slot)) }
            val rowWidth = if (bounded) width else placed.sumOf { it.width } + gapPx * (placed.size - 1).coerceAtLeast(0)
            val height = (placed.maxOfOrNull { it.height } ?: 0).coerceAtLeast(constraints.minHeight)
            layout(rowWidth, height) {
                var x = 0
                placed.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + gapPx
                }
            }
        } else {
            val placed = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placed.sumOf { it.height } + gapPx * (placed.size - 1)
            layout(width, height.coerceAtLeast(constraints.minHeight)) {
                var y = 0
                placed.forEach {
                    it.placeRelative(0, y)
                    y += it.height + gapPx
                }
            }
        }
    }
}

/** Each of [count] buttons' share of [rowWidth], with [gapPx] between neighbours. */
internal fun slotWidth(
    rowWidth: Int,
    gapPx: Int,
    count: Int,
): Int = if (count == 0) 0 else ((rowWidth - gapPx * (count - 1)) / count).coerceAtLeast(0)

/**
 * Whether buttons whose unwrapped widths are [widths] each fit an equal share of [rowWidth] with [gapPx] between them.
 * With no bound on the width there is nothing to share, so they stay in a row.
 */
internal fun buttonsFit(
    rowWidth: Int?,
    gapPx: Int,
    widths: List<Int>,
): Boolean {
    if (rowWidth == null) return true
    val slot = slotWidth(rowWidth, gapPx, widths.size)
    return widths.all { it <= slot }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
fun ButtonRowPreview() =
    KnitPreview {
        Column(modifier = Modifier.padding(16.dp)) {
            ButtonRow {
                Button(onClick = {}) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share link")
                }
                OutlinedButton(onClick = {}) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy link")
                }
            }
            // One label too long for half the row: both buttons take the full width, one under the other.
            ButtonRow(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = {}) { Text("Share your contact link") }
                OutlinedButton(onClick = {}) { Text("Copy link") }
            }
        }
    }
