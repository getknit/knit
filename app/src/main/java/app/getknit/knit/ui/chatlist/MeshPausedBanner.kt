package app.getknit.knit.ui.chatlist

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.mesh.pausedUntilLabel
import app.getknit.knit.ui.preview.KnitPreview
import java.util.concurrent.TimeUnit

/**
 * "Mesh paused until 3:15 PM" with a Resume, pinned above the chat list like [RadioWarningBanner]. One row,
 * everything centred on the text line: the icon, the message, the button. The colour is the primary
 * container — the app's own coral, so it reads as a notice with weight — not the error container, which the
 * clone and all-radios-off banners keep for things that are actually wrong; this one the user asked for.
 * Not dismissible: the deadline is the dismissal, and Resume is the early one. [until] is the wall-clock
 * deadline (`ChatListUiState.pausedUntil`); [now] fixes the label's "today or later" reading.
 */
@Composable
fun MeshPausedBanner(
    until: Long,
    now: Long,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { testTag = "chatlist_mesh_paused_banner" },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.chatlist_mesh_paused_banner, pausedUntilLabel(context, until, now)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onResume,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.semantics { testTag = "chatlist_mesh_paused_banner_resume" },
            ) {
                Text(stringResource(R.string.chatlist_mesh_paused_resume))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MeshPausedBannerPreview() =
    KnitPreview {
        val now = System.currentTimeMillis()
        MeshPausedBanner(until = now + TimeUnit.MINUTES.toMillis(15), now = now, onResume = {})
    }
