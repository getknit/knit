package app.getknit.knit.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The spinner over an attachment whose bytes have not arrived, for the first [SPINNER_PATIENCE_MS] — when an
 * arrival is likely — and a still hourglass after that (#72). An indeterminate spinner is an infinite
 * animation, so one undeliverable photo in view used to keep Compose drawing every frame for as long as the
 * screen stayed on. The text beside it already says what the bubble is waiting for, so the glyph is
 * decorative.
 *
 * The stall is held in [rememberSaveable] on purpose: a lazy list keeps each item's saved state across
 * scroll-out and back, so a placeholder that already settled does not spin another half minute every time it
 * returns to the window. Keyed on [key] (the blob hash), so a different attachment in a reused slot starts
 * over. Nothing needs undoing on arrival: the caller stops composing this the moment the bytes are in.
 */
@Composable
internal fun WaitingIndicator(
    key: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    var stalled by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!stalled) {
            delay(SPINNER_PATIENCE_MS)
            stalled = true
        }
    }
    if (stalled) {
        Icon(
            Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            tint = color,
            modifier = modifier.size(size).testTag("chat_attachment_stalled"),
        )
    } else {
        CircularProgressIndicator(modifier = modifier.size(size), strokeWidth = 2.dp, color = color)
    }
}

/** How long a missing attachment spins before it settles to a still glyph. */
internal const val SPINNER_PATIENCE_MS = 30_000L
