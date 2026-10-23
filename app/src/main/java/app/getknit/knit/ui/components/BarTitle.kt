package app.getknit.knit.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * A top app bar's title: one line, always. The bar's height is fixed, so a title that wrapped in a longer language
 * pushed into its edges; this one steps its size down a little first (to [MIN_SIZE]) and ellipsizes only past that.
 * The screen's own content names what the title would have said in full, so the cut never costs a reader the screen.
 */
@Composable
fun BarTitle(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = MIN_SIZE, maxFontSize = LocalTextStyle.current.fontSize),
    )
}

/** How far a bar title may shrink: Material's titleSmall, from titleLarge's 22. */
private val MIN_SIZE = 16.sp
