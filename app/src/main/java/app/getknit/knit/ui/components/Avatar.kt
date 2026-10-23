package app.getknit.knit.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import app.getknit.knit.ui.theme.AvatarTint
import app.getknit.knit.ui.theme.avatarTintIndex
import app.getknit.knit.ui.theme.knitColors
import app.getknit.knit.ui.theme.rememberPressScale
import coil3.compose.AsyncImage
import java.text.BreakIterator

/**
 * A circular avatar shared by the chat rows and the profile screen. Renders the avatar blob with
 * content hash [avatarHash] (loaded from the encrypted store via Coil) when present, otherwise a
 * colored circle with the first letter of [name]. Source avatars are stored 256² (see AvatarStore),
 * so larger [size]s stay crisp. When [onClick] is non-null the whole circle is tappable, with a
 * circular ripple.
 *
 * The circle's colour is keyed on [nodeId] (ADR 2026-09.j8c7): one of the twelve [AvatarTint]s in
 * `MaterialTheme.knitColors`, chosen by [avatarTintIndex], so the same person wears the same hue in
 * every list, in the notification shade, and on every phone that knows them — and a column of
 * photo-less strangers is not a column of identical discs. Pass null only when the face has no
 * identity behind it (a heard radio author, keyed on its `!hex` label); the colour then keys on
 * [name] instead, which is stable but not unique.
 *
 * Accessibility: the image/initial is decorative on its own, so pass [contentDescription] to give
 * the avatar an accessible name (do this when [onClick] is set, so the tappable target is
 * announced). When [onClick] is set the touch target grows to the 48dp minimum without enlarging
 * the visible circle, and [onClickLabel] describes the action (e.g. "view profile").
 *
 * [badge] is a verified badge id to mark the avatar with ([BadgeMark], docs/SUPPORTER_BADGE.md §7): drawn at the
 * circle's lower end, outside its clip, so the avatar is a box around the circle rather than the circle itself.
 * [onBadgeClick] makes the mark its own tap target; without it the mark is part of the avatar.
 */
@Composable
fun Avatar(
    avatarHash: String?,
    name: String,
    nodeId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    badge: String? = null,
    onBadgeClick: (() -> Unit)? = null,
) {
    val tint = MaterialTheme.knitColors.avatarTint(nodeId ?: name)
    // An avatar is a tap target with no container of its own, so a ripple alone is easy to miss on a
    // photo. Giving it a little under the finger is the confirmation the image can't provide.
    val interaction = remember { MutableInteractionSource() }
    // Only a tappable avatar has a press to react to; a decorative one never starts the collector.
    val press =
        if (onClick != null) {
            val scale = rememberPressScale(interaction)
            Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
                .then(press)
                .size(size),
    ) {
        AvatarCircle(avatarHash, name, size, textStyle, contentDescription, onClick, onClickLabel, interaction, tint)
        if (badge != null) {
            BadgeMark(
                badge = badge,
                avatarSize = size,
                onClick = onBadgeClick,
                // Its centre on the circle's edge at the lower end, the way a presence dot sits.
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = markSize(size) / 6, y = markSize(size) / 6),
            )
        }
    }
}

/** The clipped disc itself: the photo or the initial, with the tap and the accessible name. */
@Composable
@Suppress("LongParameterList") // Avatar's own parameters, passed through
private fun AvatarCircle(
    avatarHash: String?,
    name: String,
    size: Dp,
    textStyle: TextStyle,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    onClickLabel: String?,
    interaction: MutableInteractionSource,
    tint: AvatarTint,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(tint.container)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            // The ambient ripple colours itself from LocalContentColor, which here is
                            // whatever the row or bubble around the avatar set — not this circle's own
                            // content colour. Name it, the way ripple()'s own docs mean it: the colour
                            // the component's text or iconography uses.
                            indication = ripple(color = tint.onContainer),
                            onClickLabel = onClickLabel,
                            role = Role.Button,
                            onClick = onClick,
                        )
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
        // lost to a DB wipe) while the hash itself lingers in DataStore. AsyncImage
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
            AvatarInitial(name = name, size = size, textStyle = textStyle, contentColor = tint.onContainer)
        }
    }
}

/**
 * The fallback shown when there's no usable avatar image: a single uppercased initial of [name],
 * centered in the circle. Scaled to [initialFraction] of the circle's diameter — half by default, the
 * Google/Signal-style fill — instead of a fixed type ramp that looks tiny in large avatars; the size is
 * derived from the dp diameter via [androidx.compose.ui.unit.Dp.toSp] so it ignores the user's font scale
 * and always fits the fixed-size circle. The inherited lineHeight is reset so the (now much larger) glyph
 * isn't clipped by the base style's box.
 *
 * Shared with [GroupAvatar]'s member cells, which pass the cell's short side as [size]; the fraction is
 * the knob a cell can turn without touching the disc.
 */
@Composable
internal fun AvatarInitial(
    name: String,
    size: Dp,
    textStyle: TextStyle,
    contentColor: Color,
    initialFraction: Float = INITIAL_FRACTION,
) {
    val initialSize = with(LocalDensity.current) { (size * initialFraction).toSp() }
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
 *
 * Leading graphemes that are no letter of a name are passed over: punctuation, spaces and the invisible direction
 * marks. A label like "(You)", or a translation that opens with a quote or a bracket, would otherwise draw its
 * punctuation, and an Arabic one opening with a direction mark nothing at all. A name with nothing else is "?".
 *
 * The one initial rule: `MessageNotifier` draws its letter avatars from it too, so the shade's face is the
 * list's.
 */
internal fun avatarInitial(name: String): String {
    val boundary = BreakIterator.getCharacterInstance().apply { setText(name) }
    var start = boundary.first()
    var end = boundary.next()
    while (end != BreakIterator.DONE) {
        val grapheme = name.substring(start, end)
        if (!isPassedOver(grapheme.codePointAt(0))) return grapheme.uppercase()
        start = end
        end = boundary.next()
    }
    return "?"
}

/** Punctuation, a space or a control or format character (a direction mark): nothing to draw as an initial. */
private fun isPassedOver(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.SPACE_SEPARATOR,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.CONTROL,
        Character.FORMAT,
        -> true

        else -> false
    }

/** The initial's height as a fraction of the disc — or, in a cluster cell, of the cell's short side. */
private const val INITIAL_FRACTION = 0.5f

// Previews use the initial-letter fallback (avatarHash = null); a real hash would render through Coil,
// which has no DB-backed blob bytes in a preview and so would only show a placeholder.
@Preview(showBackground = true)
@Composable
fun AvatarInitialPreview() =
    KnitPreview {
        Avatar(avatarHash = null, name = "Ada Lovelace", nodeId = "ada", size = 40.dp)
    }

@Preview(showBackground = true)
@Composable
fun AvatarLargeEmojiPreview() =
    KnitPreview {
        Avatar(avatarHash = null, name = "🦊 Fox", nodeId = "fox", size = 96.dp)
    }

/** Every tint in the palette side by side, the way a list of photo-less contacts would show them. */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AvatarPalettePreview() =
    KnitPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            // Twelve consecutive one-letter keys land in twelve consecutive slots; "l" is the one at slot 0.
            for (key in ('l'..'w').map(Char::toString)) {
                Avatar(avatarHash = null, name = key, nodeId = key, size = 40.dp)
            }
        }
    }
