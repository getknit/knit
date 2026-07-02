package app.getknit.knit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.preview.KnitPreview
import coil3.compose.AsyncImage

/**
 * A group's circular avatar, shared by the chat header and the group-details screen. Renders the
 * group's photo blob (content hash [photoHash], loaded from the encrypted store via Coil) when present,
 * otherwise a neutral people glyph — mirroring the chat-list room-logo style. When [onClick] is
 * non-null the whole circle is tappable with a circular ripple and grows to the 48dp minimum touch
 * target (the visible circle stays [size]).
 *
 * Accessibility: pass [contentDescription] to give the avatar an accessible name (do this when
 * [onClick] is set, so the tappable target is announced), and [onClickLabel] to describe the action.
 */
@Composable
fun GroupAvatar(
    photoHash: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
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
        // Show the group's photo when its blob is present; fall back to the people glyph otherwise.
        // "Otherwise" includes a *dangling* hash — one whose content-addressed blob is gone — since
        // AsyncImage draws nothing on a failed load. Keyed on [photoHash] so a fresh hash retries.
        var imageFailed by remember(photoHash) { mutableStateOf(false) }
        if (photoHash != null && !imageFailed) {
            AsyncImage(
                model = BlobImage(photoHash),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { imageFailed = true },
            )
        } else {
            Icon(
                Icons.Filled.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GroupAvatarPreview() = KnitPreview {
    GroupAvatar(photoHash = null, size = 96.dp)
}
