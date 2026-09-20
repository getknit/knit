package app.getknit.knit.ui.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The "also active on another phone" banner (ADR 2026-09.ypcc), pinned above the chat list like
 * [RadioWarningBanner] and drawn in the same critical colours: the identity is what is wrong, not a radio.
 * [onSignOut] opens the confirmation (never the wipe itself); [onDismiss] says the other phone is gone —
 * persisted, and undone by the next frame the other phone publishes.
 */
@Composable
fun CloneBanner(
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 3.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { testTag = "chatlist_clone_banner" },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PhonelinkErase, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chatlist_clone_banner_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.semantics { testTag = "chatlist_clone_banner_signout" },
                ) {
                    Text(stringResource(R.string.chatlist_clone_banner_action))
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { testTag = "chatlist_clone_banner_dismiss" },
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chatlist_clone_banner_dismiss),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CloneBannerPreview() =
    KnitPreview {
        CloneBanner(onSignOut = {}, onDismiss = {})
    }
