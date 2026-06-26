package app.getknit.knit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.getknit.knit.ui.image.BlobImage
import coil3.compose.AsyncImage

/**
 * A circular avatar shared by the chat rows and the profile screen. Renders the avatar blob with
 * content hash [avatarHash] (loaded from the encrypted store via Coil) when present, otherwise a
 * colored circle with the first letter of [name]. Source avatars are stored 256² (see AvatarStore),
 * so larger [size]s stay crisp. When [onClick] is non-null the whole circle is tappable, with a
 * circular ripple.
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
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = textStyle,
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
