package app.getknit.knit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import app.getknit.knit.ui.image.BlobImage
import coil3.compose.AsyncImage
import java.text.BreakIterator

/**
 * A circular avatar shared by the chat rows and the profile screen. Renders the avatar blob with
 * content hash [avatarHash] (loaded from the encrypted store via Coil) when present, otherwise a
 * colored circle with the first letter of [name]. Source avatars are stored 256² (see AvatarStore),
 * so larger [size]s stay crisp. When [onClick] is non-null the whole circle is tappable, with a
 * circular ripple.
 *
 * Accessibility: the image/initial is decorative on its own, so pass [contentDescription] to give
 * the avatar an accessible name (do this when [onClick] is set, so the tappable target is
 * announced). When [onClick] is set the touch target grows to the 48dp minimum without enlarging
 * the visible circle, and [onClickLabel] describes the action (e.g. "view profile").
 */
@Composable
fun Avatar(
    avatarHash: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarHash != null) {
            AsyncImage(
                model = BlobImage(avatarHash),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Scale the initial to ~half the circle's diameter (Google/Signal-style fill) instead of a
            // fixed type ramp that looks tiny in large avatars. Derive the size from the dp diameter via
            // toSp() so it ignores the user's font scale and always fits the fixed-size circle. Reset the
            // inherited lineHeight so the (now much larger) glyph isn't clipped by the base style's box.
            val initialSize = with(LocalDensity.current) { (size * 0.5f).toSp() }
            Text(
                text = avatarInitial(name),
                style = textStyle.copy(fontSize = initialSize, lineHeight = TextUnit.Unspecified),
                color = contentColor,
                textAlign = TextAlign.Center,
                // Decorative: the Box (or an adjacent name label) carries the accessible name.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The leading user-perceived character of [name] for the fallback avatar, uppercased — or "?" when
 * [name] is blank.
 *
 * Uses a grapheme [BreakIterator] rather than [String.firstOrNull] so a leading emoji survives
 * intact. An emoji is at least a UTF-16 surrogate pair and is often a multi-codepoint cluster (ZWJ
 * sequence, skin-tone modifier, or a regional-indicator flag pair); taking the first `Char` would
 * slice off a lone surrogate that the font then draws as a missing-glyph "?". The iterator advances
 * by extended grapheme cluster (ICU-backed on Android), so the whole cluster is taken as one unit.
 */
private fun avatarInitial(name: String): String {
    val trimmed = name.trimStart()
    if (trimmed.isEmpty()) return "?"
    val boundary = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
    val end = boundary.next()
    val grapheme = if (end == BreakIterator.DONE) trimmed else trimmed.substring(0, end)
    return grapheme.uppercase()
}
