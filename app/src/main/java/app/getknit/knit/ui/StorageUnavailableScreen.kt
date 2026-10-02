package app.getknit.knit.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * What `MainActivity` shows instead of the app while [StorageGate] is [StorageGate.State.Unavailable]: this phone's
 * Keystore refused to unwrap Knit's storage key, nothing was deleted, and [onRetry] opens again. The layout is the
 * welcome page's — the brand mark, a heading, one paragraph — because this is the whole screen, not a banner over
 * one: nothing behind it can be read until storage opens. ADR 2026-10.47rw.
 *
 * A Keystore that never answers again would leave the phone here for good, so [onStartOver] is the way out the
 * user chooses — never the app: a quiet secondary action behind one confirmation that names what goes, offered
 * beside Try again rather than instead of it, because a refusal is usually gone a moment or a reboot later.
 */
@Composable
fun StorageUnavailableScreen(
    trying: Boolean,
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming) {
        StartOverDialog(
            onConfirm = {
                confirming = false
                onStartOver()
            },
            onDismiss = { confirming = false },
        )
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .testTag("storage_unavailable"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Decorative; the title below is the heading.
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(BRAND_MARK_SIZE),
                )
                Text(
                    text = stringResource(R.string.storage_unavailable_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.storage_unavailable_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry, enabled = !trying, modifier = Modifier.testTag("storage_retry")) {
                    // Composed only while a retry runs, so the screen settles between taps.
                    if (trying) {
                        CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.storage_unavailable_retry))
                    }
                }
                TextButton(
                    onClick = { confirming = true },
                    enabled = !trying,
                    modifier = Modifier.padding(top = 8.dp).testTag("storage_start_over"),
                ) {
                    Text(stringResource(R.string.storage_unavailable_start_over))
                }
            }
        }
    }
}

/** The one confirmation before [StorageUnavailableScreen]'s Start over clears the phone: what goes, and the way back. */
@Composable
private fun StartOverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_start_over_title)) },
        text = { Text(stringResource(R.string.storage_start_over_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("storage_start_over_confirm")) {
                Text(
                    text = stringResource(R.string.storage_start_over_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val BRAND_MARK_SIZE = 96.dp
private val PROGRESS_SIZE = 18.dp

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StorageUnavailableScreenPreview() {
    KnitPreview { StorageUnavailableScreen(trying = false, onRetry = {}, onStartOver = {}) }
}
