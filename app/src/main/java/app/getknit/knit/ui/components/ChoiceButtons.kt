package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.preview.KnitPreview

/**
 * One answer out of a few, all in view: a segmented button row while every label fits its segment, a list of radio
 * buttons once one does not.
 *
 * A segmented row gives each choice an equal slice of the width, so a label has a third of the row, less its padding
 * and the check, whatever its language. A translation runs longer than the English, and a long word cannot wrap at
 * all, so each label is measured against its slice: one that takes more than [MAX_SEGMENT_LINES] lines, or has a word
 * wider than the slice, switches the whole control to the list, where a label has the full width. The same holds at a
 * large font scale. A segment that wraps grows, and the row keeps its segments one height.
 *
 * [check] draws Material's check on the selected segment. The label is then centred with an equal slot on its far
 * side, so it holds still as the check crossfades in; when the labels fit only without that slot, they keep the row
 * and give up the centring. [tagOf] names each choice's node, segment or row.
 */
@Composable
fun <T> ChoiceButtons(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    check: Boolean = true,
    tagOf: (T) -> String? = { null },
) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        fun allFit(centred: Boolean): Boolean {
            val px = with(density) { segmentLabelWidth(maxWidth, choices.size, check, centred).roundToPx() }
            return choices.all { (_, label) -> fitsSegment(measurer, label, style, px) }
        }
        // Centred if the labels fit beside the balancing slot; else segmented without it, a little off centre, while
        // they fit there. Unbounded (a horizontally scrolling parent) leaves no slice to measure; the row sizes to its
        // labels.
        val centred = check && allFit(centred = true)
        val segmented = !constraints.hasBoundedWidth || centred || allFit(centred = false)
        if (segmented) {
            SegmentedChoices(choices, selected, onSelect, check, centred, tagOf)
        } else {
            RadioChoices(choices, selected, onSelect, tagOf)
        }
    }
}

@Composable
private fun <T> SegmentedChoices(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    check: Boolean,
    centred: Boolean,
    tagOf: (T) -> String?,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        choices.forEachIndexed { index, (choice, label) ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                // 48 dp is the accessibility touch target (Material's container is 40, which the ATF suite flags);
                // a label on two lines makes it taller, and fillMaxHeight keeps its neighbours level with it.
                modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight().tagged(tagOf(choice)),
                icon =
                    if (check) {
                        {
                            SegmentedButtonDefaults.Icon(
                                active = choice == selected,
                                // An empty box of the same size when inactive, rather than the default null. Null makes
                                // the unselected icon measure zero wide, and the row's measure policy then slides the
                                // label half an icon-width left to re-centre it, so the word slides under your finger as
                                // the check appears. A constant-width slot holds the word still.
                                inactiveContent = { Spacer(Modifier.size(SegmentedButtonDefaults.IconSize)) },
                            )
                        }
                    } else {
                        {}
                    },
                // Material centres the check and the word together, which leaves the word off centre, most visibly on
                // the segments showing no check. Matching the check's slot on the far side puts the word's midpoint on
                // the segment's. Padding rather than an offset: the space is really there, so nothing is pushed
                // outside the button and clipped. On the right, not the end: Material places the check left of the
                // word in a right-to-left layout too (`place`, not `placeRelative`), so end padding there would sit
                // beside the check and push the word further off.
                label = {
                    Text(
                        text = label,
                        textAlign = TextAlign.Center,
                        modifier = if (centred) Modifier.absolutePadding(right = CHECK_SLOT) else Modifier,
                    )
                },
            )
        }
    }
}

@Composable
private fun <T> RadioChoices(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tagOf: (T) -> String?,
) {
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        choices.forEach { (choice, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = choice == selected, role = Role.RadioButton, onClick = { onSelect(choice) })
                        .tagged(tagOf(choice)),
            ) {
                // The row is the click target and carries the semantics; the button only draws the state.
                RadioButton(selected = choice == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun Modifier.tagged(tag: String?): Modifier = if (tag == null) this else testTag(tag)

/**
 * The width a label has in one of [count] equal segments of [rowWidth]: less Material's padding, the check's slot, and,
 * when [centred], the matching slot on the label's far side.
 */
internal fun segmentLabelWidth(
    rowWidth: Dp,
    count: Int,
    check: Boolean,
    centred: Boolean,
): Dp = rowWidth / count - SEGMENT_PADDING * 2 - (if (check) CHECK_SLOT else 0.dp) - (if (centred) CHECK_SLOT else 0.dp)

/**
 * Whether [label] reads whole in a segment [widthPx] wide: in at most [MAX_SEGMENT_LINES] lines, with no word broken
 * across them. A word wider than the segment is what a line break can't fix (German compounds, a CJK label with no
 * spaces), and the layout would split it mid-word.
 */
internal fun fitsSegment(
    measurer: TextMeasurer,
    label: String,
    style: TextStyle,
    widthPx: Int,
): Boolean {
    if (widthPx <= 0) return false
    val wrapped = measurer.measure(label, style, constraints = Constraints(maxWidth = widthPx))
    if (wrapped.lineCount > MAX_SEGMENT_LINES) return false
    return label.split(' ').filter { it.isNotEmpty() }.all { word ->
        measurer.measure(word, style, softWrap = false).size.width <= widthPx
    }
}

/** A segment's label may take two lines; past that the control reads better as a list. */
private const val MAX_SEGMENT_LINES = 2

/**
 * The width a segmented button keeps for its check: Material's [SegmentedButtonDefaults.IconSize] plus the 8 dp
 * `IconSpacing` its `SegmentedButton.kt` holds privately, with no public constant to read it from.
 */
private val CHECK_SLOT = SegmentedButtonDefaults.IconSize + 8.dp

/** Each side of a segment's content: `SegmentedButtonDefaults.ContentPadding`, Material's text-button padding. */
private val SEGMENT_PADDING = 12.dp

@Preview(showBackground = true)
@Composable
fun ChoiceButtonsPreview() =
    KnitPreview {
        Column(modifier = Modifier.padding(16.dp)) {
            ChoiceButtons(
                choices = listOf(1 to "System", 2 to "Light", 3 to "Dark"),
                selected = 1,
                onSelect = {},
            )
            Spacer(Modifier.height(16.dp))
            // A label too long for its third of the row: the control becomes a list.
            ChoiceButtons(
                choices = listOf(1 to "Never", 2 to "Always ask over 1 MB", 3 to "Always ask over 5 MB"),
                selected = 2,
                onSelect = {},
            )
        }
    }
