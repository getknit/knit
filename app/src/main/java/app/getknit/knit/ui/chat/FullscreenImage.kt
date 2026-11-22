package app.getknit.knit.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.getknit.knit.R
import app.getknit.knit.ui.image.BlobImage
import coil3.compose.AsyncImage

/** A tapped attachment plus the bubble metadata the fullscreen viewer shows in its top bar. */
internal data class FullscreenImage(
    val image: BlobImage,
    val mine: Boolean,
    val senderName: String,
    val sentAt: Long,
)

/**
 * Fullscreen, pinch-to-zoom/pan viewer for a tapped image. The top bar floats white over the black
 * backdrop, Signal-style: a back arrow (left) dismisses it, the sender ("You"/peer) and relative send
 * time sit in the middle, and an overflow menu (right) offers Save, and Show in chat where the viewer was opened
 * from outside the thread ("Media, links and files", ADR 2026-11.dgvf); back press / outside tap also dismiss.
 */
@Composable
internal fun FullscreenImageViewer(
    fullscreen: FullscreenImage,
    now: Long,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShowInChat: (() -> Unit)? = null,
) {
    val image = fullscreen.image
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var menuOpen by remember { mutableStateOf(false) }
        val transformState =
            rememberTransformableState { _, zoomChange, panChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 5f)
                offset += panChange
            }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = image,
                contentDescription = stringResource(R.string.chat_image_viewer_desc),
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
            )
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text =
                            if (fullscreen.mine) {
                                stringResource(R.string.chat_self_name)
                            } else {
                                fullscreen.senderName
                            },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            relativeTime(
                                fullscreen.sentAt,
                                now,
                                stringResource(R.string.chat_time_just_now),
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_more_options),
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_save)) },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSave()
                            },
                        )
                        onShowInChat?.let { show ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.media_show_in_chat)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
                                modifier = Modifier.testTag("viewer_show_in_chat"),
                                onClick = {
                                    menuOpen = false
                                    show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun relativeTime(
    sentAt: Long,
    now: Long,
    justNow: String,
): String {
    // DateUtils renders anything under a minute (and any slight clock skew into the future) as
    // "0 minutes ago"; show a friendlier "Just now" instead.
    if (now - sentAt < DateUtils.MINUTE_IN_MILLIS) return justNow
    return DateUtils
        .getRelativeTimeSpanString(
            sentAt,
            now,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
}
