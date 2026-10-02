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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 */
@Composable
fun StorageUnavailableScreen(
    trying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            }
        }
    }
}

private val BRAND_MARK_SIZE = 96.dp
private val PROGRESS_SIZE = 18.dp

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StorageUnavailableScreenPreview() {
    KnitPreview { StorageUnavailableScreen(trying = false, onRetry = {}) }
}
