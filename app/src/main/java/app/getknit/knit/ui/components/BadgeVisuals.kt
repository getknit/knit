package app.getknit.knit.ui.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.getknit.knit.R
import app.getknit.knit.badge.Badges
import app.getknit.knit.badge.VerifiedBadge
import app.getknit.knit.ui.preview.KnitPreview

/**
 * How a badge id draws (docs/SUPPORTER_BADGE.md §7, ADR 2026-10.r99s): its artwork and its one-word name. Keyed by id,
 * so a future badge is a row here; an id with no row is never drawn, which the verifier already guarantees.
 */
object BadgeVisuals {
    /**
     * [badge]'s artwork for a mark [markSize] across, drawn in its own colours (C-7-4). The supporter badge is the
     * Yarnworld logo, and below [SMALL_MARK] its simplified form: the full logo's stitching and islands blur there.
     */
    @DrawableRes
    fun iconFor(
        badge: String,
        markSize: Dp,
    ): Int? =
        when (badge) {
            Badges.SUPPORTER -> if (markSize < SMALL_MARK) R.drawable.ic_badge_supporter_small else R.drawable.ic_badge_supporter
            else -> null
        }

    @StringRes
    fun labelFor(badge: String): Int? =
        when (badge) {
            Badges.SUPPORTER -> R.string.badge_supporter
            else -> null
        }

    /** The mark's accessible name, which says what it is rather than repeating the pill's one word. */
    @StringRes
    fun markLabelFor(badge: String): Int? =
        when (badge) {
            Badges.SUPPORTER -> R.string.badge_supporter_mark
            else -> null
        }
}

/**
 * Every verified badge by node id ([app.getknit.knit.badge.PeerBadges.shown]), provided once at the app's root. Empty in
 * previews and tests, so a screen draws no mark unless one is handed down.
 */
val LocalPeerBadges = compositionLocalOf<Map<String, List<VerifiedBadge>>> { emptyMap() }

/** The first badge [nodeId] shows, by its own order, or null: what an avatar passes as its `badge`. */
@Composable
fun peerBadge(nodeId: String?): String? = nodeId?.let { LocalPeerBadges.current[it]?.firstOrNull()?.b }

/**
 * A badge's artwork, untinted, ringed in the surface colour: the profile's Supporter pill leads with it, and the badge
 * sheet draws it large. Sized as if for an avatar of diameter [avatarSize] (0 gives the 14 dp floor). Tappable only
 * when [onClick] is set. A name's mark is [NameBadgeMark], which needs no ring.
 */
@Composable
fun BadgeMark(
    badge: String,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val size = markSize(avatarSize)
    val icon = BadgeVisuals.iconFor(badge, size) ?: return
    val label = BadgeVisuals.markLabelFor(badge)?.let { stringResource(it) }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                // The surface shows as a ring round the inset artwork, parting it from the avatar.
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).semantics { label?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        // A logo in its own colours, never a tinted glyph: no theme or wallpaper recolours it.
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size - RING * 2),
        )
    }
}

/**
 * The mark a badge puts after a person's name ([PeerNameText]'s `badge`, docs/SUPPORTER_BADGE.md C-7-1): the badge's
 * artwork, untinted, about as tall as the name's [textStyle] so it grows with the font scale. No surface ring: beside
 * text there is no photo to part it from. Never a tap target on its own (the profile's pill is the one that opens the
 * sheet), and it carries the start gap itself, so an id with no artwork leaves no stray space after the name.
 */
@Composable
fun NameBadgeMark(
    badge: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val size = nameMarkSize(textStyle)
    val icon = BadgeVisuals.iconFor(badge, size) ?: return
    val label = BadgeVisuals.markLabelFor(badge)?.let { stringResource(it) }
    // A logo in its own colours, never a tinted glyph: no theme or wallpaper recolours it (C-7-4).
    Image(
        painter = painterResource(icon),
        contentDescription = label,
        contentScale = ContentScale.Fit,
        modifier = modifier.padding(start = NAME_MARK_GAP).size(size),
    )
}

/** The name mark's diameter: the name's font size in dp (font scale included), never under 14 dp nor over 28 dp. */
@Composable
private fun nameMarkSize(textStyle: TextStyle): Dp {
    val fontSize = textStyle.fontSize.takeIf { it.isSpecified } ?: MaterialTheme.typography.bodyLarge.fontSize
    return with(LocalDensity.current) { fontSize.toDp() }.coerceIn(MIN_MARK, MAX_MARK)
}

private val NAME_MARK_GAP = 4.dp

/** The mark's diameter for an avatar of [avatarSize]: about a third of it, never under 14 dp nor over 28 dp. */
internal fun markSize(avatarSize: Dp): Dp = (avatarSize * MARK_FRACTION).coerceIn(MIN_MARK, MAX_MARK)

private const val MARK_FRACTION = 0.36f
private val MIN_MARK = 14.dp

// A mark narrower than this draws the badge's simplified artwork: the profile pill, and a name set smaller than 16 sp.
private val SMALL_MARK = 16.dp
private val MAX_MARK = 28.dp
private val RING = 1.5.dp

/**
 * Every name style the mark follows (the lists' titleMedium, the DM header's titleLarge), a long name that ellipsizes
 * with the mark kept, and a name collision's muted alias, light and dark (docs/SUPPORTER_BADGE.md §7).
 */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun NameBadgeStylesPreview() =
    KnitPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(12.dp).width(260.dp)) {
            PeerNameText(
                text = "Ada",
                discriminator = null,
                style = MaterialTheme.typography.titleMedium,
                badge = Badges.SUPPORTER,
            )
            PeerNameText(
                text = "Ada",
                discriminator = null,
                style = MaterialTheme.typography.titleLarge,
                badge = Badges.SUPPORTER,
            )
            PeerNameText(
                text = "Adaeze Okonkwo-Fairweather of the Long Valley",
                discriminator = null,
                style = MaterialTheme.typography.titleMedium,
                badge = Badges.SUPPORTER,
            )
            PeerNameText(
                text = "Alice (JoyfulFerret)",
                discriminator = "JoyfulFerret",
                style = MaterialTheme.typography.titleMedium,
                badge = Badges.SUPPORTER,
            )
        }
    }
