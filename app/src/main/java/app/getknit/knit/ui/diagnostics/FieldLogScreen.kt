package app.getknit.knit.ui.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.components.leftToRight
import app.getknit.knit.ui.preview.KnitPreview
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * The field log (ADR 2026-10.a239): what it is, the switch, the file as it would be sent, and Share and Clear.
 * Reached from Diagnostics. Nothing leaves the phone from here unless the user picks a share target.
 */
@Composable
fun FieldLogScreen(
    onBack: () -> Unit,
    viewModel: FieldLogViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clearedMsg = stringResource(R.string.field_log_cleared)
    val shareErrorMsg = stringResource(R.string.crash_share_error)
    LaunchedEffect(clearedMsg, shareErrorMsg) {
        viewModel.events.collect { resId ->
            snackbarHostState.showSnackbar(if (resId == R.string.field_log_cleared) clearedMsg else shareErrorMsg)
        }
    }
    FieldLogScreenContent(
        state = state,
        size = Formatter.formatShortFileSize(context, state.bytes),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSetEnabled = viewModel::setEnabled,
        onShare = {
            scope.launch {
                val uri = viewModel.stageForShare()
                if (uri == null) {
                    viewModel.onShareFailed()
                } else {
                    launchCrashShareChooser(context, uri, R.string.field_log_share_chooser_title)
                }
            }
        },
        onClear = viewModel::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldLogScreenContent(
    state: FieldLogUiState,
    size: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.testTag("screen_field_log"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { BarTitle(stringResource(R.string.field_log_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { FieldLogIntro() }
            item { FieldLogSwitchRow(enabled = state.enabled, onSetEnabled = onSetEnabled) }
            if (!state.loading && state.events > 0) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = pluralStringResource(R.plurals.field_log_summary, state.events, state.events, size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.field_log_action_share))
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.field_log_preview_header)) }
                items(state.preview) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.leftToRight(),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                if (state.more > 0) {
                    item { EmptyLine(pluralStringResource(R.plurals.field_log_more, state.more, state.more)) }
                }
                item {
                    // Destructive, so it sits at the bottom rather than beside Share.
                    TextButton(
                        onClick = { confirmingClear = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.field_log_action_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (!state.loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(if (state.enabled) R.string.field_log_empty else R.string.field_log_off),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirmingClear) {
        ConfirmDialog(
            title = R.string.field_log_clear_title,
            body = R.string.field_log_clear_body,
            confirm = R.string.field_log_clear_confirm,
            onConfirm = {
                confirmingClear = false
                onClear()
            },
            onDismiss = { confirmingClear = false },
        )
    }
}

@Composable
private fun FieldLogIntro() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = stringResource(R.string.field_log_explainer), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.field_log_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FieldLogSwitchRow(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onSetEnabled)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.field_log_switch), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.field_log_switch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The row is the toggle; the switch only shows its state.
        Switch(checked = enabled, onCheckedChange = null)
    }
}

private val PREVIEW_LINES =
    listOf(
        "# knit field log, schema 1",
        "# app: 2.9.0 (31) release",
        "# device: Google Pixel 8",
        "{\"t\":0,\"e\":\"mesh_on\",\"vc\":31}",
        "{\"t\":4,\"e\":\"power\",\"screen\":true,\"charging\":false,\"low\":false}",
        "{\"t\":61022,\"e\":\"bt_up\",\"peer\":\"p3f9a01c2\",\"dialed\":true,\"dial\":850,\"sighted\":true,\"links\":1}",
    )

@Preview(showBackground = true)
@Composable
fun FieldLogScreenPreview() =
    KnitPreview {
        FieldLogScreenContent(
            state = FieldLogUiState(loading = false, enabled = true, events = 412, bytes = 61_440, preview = PREVIEW_LINES, more = 406),
            size = "60 kB",
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onSetEnabled = {},
            onShare = {},
            onClear = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun FieldLogScreenOffPreview() =
    KnitPreview {
        FieldLogScreenContent(
            state = FieldLogUiState(loading = false, enabled = false),
            size = "0 B",
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onSetEnabled = {},
            onShare = {},
            onClear = {},
        )
    }
