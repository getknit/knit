package app.getknit.knit.ui.chat

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.ui.theme.KnitMotion

/**
 * The glyph over an attachment whose bytes have not all arrived, shared by the photo, voice and file bubbles
 * (#115). While they stream in against a declared total, a ring fills toward it; while they stream in with no
 * total (a sender whose build predates the field, or one the bytes have overrun), a still download glyph, and
 * the count beside it ([arrivalText]) shows the movement; before any link carries them, the [WaitingIndicator]
 * that settles to an hourglass (#72) — or, when nothing will ever bring them ([stopped]: a commons whose relay takes no
 * attachments or no longer runs the room), the cloud-off glyph at once, since a wait that cannot end is no wait.
 *
 * Nothing here is infinite: the ring eases to each new sample on a finite spec, so a stalled transfer lets
 * Compose idle like a settled screen (ADR 047), and it is the plain determinate indicator — the wavy one
 * animates its wave for as long as it is composed. A hard swap between the three, never an `AnimatedContent`.
 */
@Composable
internal fun ArrivalIndicator(
    key: String?,
    arrival: ArrivingFile?,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    stopped: Boolean = false,
) {
    val fraction = arrival?.fraction
    when {
        fraction != null -> {
            val shown by animateFloatAsState(fraction, KnitMotion.effects(), label = "arrival")
            CircularProgressIndicator(
                progress = { shown },
                modifier = modifier.size(size).testTag("chat_attachment_progress"),
                color = color,
                strokeWidth = 2.dp,
                // The ring's own hue for what is still to come, as the voice waveform draws its unplayed bars: the
                // default track is the slate secondary container, a foreign accent inside a warm bubble.
                trackColor = color.copy(alpha = TRACK_ALPHA),
            )
        }

        arrival != null -> {
            Icon(
                Icons.Outlined.Downloading,
                contentDescription = null,
                tint = color,
                modifier = modifier.size(size).testTag("chat_attachment_arriving"),
            )
        }

        stopped -> {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = color,
                modifier = modifier.size(size).testTag("chat_attachment_stopped"),
            )
        }

        else -> {
            WaitingIndicator(key = key, size = size, modifier = modifier, color = color)
        }
    }
}

/** How much of [arrival] has crossed: "84 kB of 204 kB" against a total, "84 kB received" without one. */
@Composable
internal fun arrivalText(arrival: ArrivingFile): String {
    val context = LocalContext.current
    val bytes = Formatter.formatShortFileSize(context, arrival.bytes)
    val total = arrival.total?.takeIf { arrival.fraction != null }
    return if (total != null) {
        stringResource(R.string.chat_attachment_arriving, bytes, Formatter.formatShortFileSize(context, total))
    } else {
        stringResource(R.string.chat_attachment_arriving_bytes, bytes)
    }
}

/** The unfilled part of the ring, against the filled part's colour. */
private const val TRACK_ALPHA = 0.3f
