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
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The Nearby broadcast room's circular avatar: the Knit mesh mark ([R.drawable.ic_knit_room]) on a
 * neutral tinted disc, mirroring [GroupAvatar]/[Avatar]. Shared by the chat-list room row and the Nearby
 * chat header so both render the room identically. The mark fills the circle edge-to-edge — the drawable
 * is a full disc with the "K" punched out as holes that reveal the [secondaryContainer] behind it.
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
        Icon(
            painter = painterResource(R.drawable.ic_knit_room),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun RoomAvatarPreview() =
    KnitPreview {
        RoomAvatar(size = 96.dp)
    }
