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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.preview.KnitPreview
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
        modifier =
            modifier
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
                ).then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        // Show the avatar image when its blob is present; fall back to the initial letter otherwise.
        // "Otherwise" includes a *dangling* hash — one whose content-addressed blob is gone (GC'd, or
        // lost to a destructive DB migration) while the hash itself lingers in DataStore. AsyncImage
        // draws nothing on a failed load, so without the onError fallback a dangling hash renders a
        // permanently blank circle with no initial (the own-profile avatar hit this; a peer's hash is
        // null until known, so chat rows never did). Keyed on [avatarHash] so a fresh hash retries.
        var imageFailed by remember(avatarHash) { mutableStateOf(false) }
        if (avatarHash != null && !imageFailed) {
            AsyncImage(
                model = BlobImage(avatarHash),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { imageFailed = true },
            )
        } else {
            AvatarInitial(name = name, size = size, textStyle = textStyle, contentColor = contentColor)
        }
    }
}

/**
 * The fallback shown when there's no usable avatar image: a single uppercased initial of [name],
 * centered in the circle. Scaled to ~half the circle's diameter (Google/Signal-style fill) instead of
 * a fixed type ramp that looks tiny in large avatars — the size is derived from the dp diameter via
 * [androidx.compose.ui.unit.Dp.toSp] so it ignores the user's font scale and always fits the fixed-size
 * circle. The inherited lineHeight is reset so the (now much larger) glyph isn't clipped by the base
 * style's box.
 */
@Composable
private fun AvatarInitial(
    name: String,
    size: Dp,
    textStyle: TextStyle,
    contentColor: Color,
) {
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

// Previews use the initial-letter fallback (avatarHash = null); a real hash would render through Coil,
// which has no DB-backed blob bytes in a preview and so would only show a placeholder.
@Preview(showBackground = true)
@Composable
fun AvatarInitialPreview() =
    KnitPreview {
        Avatar(avatarHash = null, name = "Ada Lovelace", size = 40.dp)
    }

@Preview(showBackground = true)
@Composable
fun AvatarLargeEmojiPreview() =
    KnitPreview {
        Avatar(avatarHash = null, name = "🦊 Fox", size = 96.dp)
    }
