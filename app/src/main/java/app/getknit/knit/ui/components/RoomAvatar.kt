package app.getknit.knit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.icons.KnitIcons
import app.getknit.knit.ui.preview.KnitPreview

/**
 * A room's circular avatar, on a neutral tinted disc, mirroring [GroupAvatar]/[Avatar]. Shared by the chat-list room
 * row, the chat header and search so each room renders identically everywhere.
 *
 * - **Knit's own rooms** (Nearby, a commons): the Knit mesh mark ([R.drawable.ic_knit_room]) fills the circle
 *   edge-to-edge. The drawable is a full disc with the "K" punched out as holes that reveal the [secondaryContainer]
 *   behind it.
 * - **A radio room** ([bridged]: the Meshtastic or the MeshCore room, a board's channel bridged into Knit): the radio
 *   glyph ([KnitIcons.RadioRoom]) at [GLYPH_FRACTION] of the disc, as a photo-less group wears its people glyph. The K
 *   beside a public channel's name read as one of Knit's own channels; `NotificationAvatars.bridgedRoomAvatar` is its
 *   twin in the shade.
 *
 * The room has nothing to open (no profile / details), so unlike [Avatar]/[GroupAvatar] it's never
 * clickable. Accessibility: pass [contentDescription] only where the circle stands alone; leave it null
 * when an adjacent label already names the room (the chat-list row and the header both do).
 */
@Composable
fun RoomAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    bridged: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (bridged) {
            Icon(
                KnitIcons.RadioRoom,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * GLYPH_FRACTION),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_knit_room),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The radio glyph's side as a fraction of the disc: [GroupAvatar]'s, and `NotificationAvatars`' `GLYPH_FRACTION`. */
private const val GLYPH_FRACTION = 0.6f

@Preview(showBackground = true)
@Composable
fun RoomAvatarPreview() =
    KnitPreview {
        RoomAvatar(size = 96.dp)
    }

/** A radio room's avatar: the radio glyph in place of the Knit mesh mark. */
@Preview(showBackground = true)
@Composable
fun RoomAvatarBridgedPreview() =
    KnitPreview {
        RoomAvatar(size = 96.dp, bridged = true)
    }
