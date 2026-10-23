package app.getknit.knit.ui.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.openUrl
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.util.compactTimeAgo
import app.getknit.knit.ui.util.relativeTimeLabels
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.io.File

private const val CRASH_MIME = "text/plain"

/**
 * The stored crash report, with the three ways to hand it over — a prefilled GitHub bug form, the
 * clipboard, or a file share — plus a way to delete every stored report.
 *
 * Reached from the "Last crash" row in Diagnostics. Nothing here transmits on its own: the report moves
 * only when the user picks a share target or confirms the GitHub form, and the app never posts anything
 * itself — it hands the browser a link the user submits.
 */
@Composable
fun CrashLogScreen(
    onBack: () -> Unit,
    viewModel: CrashLogViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by rememberCurrentTimeMillis()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved at composition — lint forbids LocalContext.getString here — then mapped from the emitted
    // resource id, the same idiom as DiagnosticsScreen.
    val copiedMsg = stringResource(R.string.crash_copied)
    val deletedMsg = stringResource(R.string.crash_deleted)
    val shareErrorMsg = stringResource(R.string.crash_share_error)
    LaunchedEffect(copiedMsg, deletedMsg, shareErrorMsg) {
        viewModel.events.collect { resId ->
            snackbarHostState.showSnackbar(
                when (resId) {
                    R.string.crash_copied -> copiedMsg
                    R.string.crash_deleted -> deletedMsg
                    else -> shareErrorMsg
                },
            )
        }
    }

    CrashLogScreenContent(
        state = state,
        now = now,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onCopy = { text ->
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(CLIP_LABEL, text).toClipEntry())
                // Android 13+ shows its own copy confirmation; skip the snackbar there.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) viewModel.onCopied()
            }
        },
        onShare = {
            scope.launch {
                val uri = viewModel.stageForShare()
                if (uri == null) viewModel.onShareFailed() else launchCrashShareChooser(context, uri)
            }
        },
        onReportOnGitHub = {
            // Clipboard first, so the paste target is populated before the browser takes focus: the
            // prefilled form carries an excerpt, the clipboard carries the whole report.
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(CLIP_LABEL, state.text).toClipEntry())
                viewModel.issueUrl()?.let { openUrl(context, it) }
            }
        },
        onDelete = viewModel::delete,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrashLogScreenContent(
    state: CrashLogUiState,
    now: Long,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: () -> Unit,
    onReportOnGitHub: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingReport by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.testTag("screen_crash_log"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { BarTitle(stringResource(R.string.crash_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val report = state.ref
        if (report == null) {
            // A blank box while loading rather than a spinner: the read is a few milliseconds and a
            // spinner would only flash.
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (!state.loading) {
                    Text(
                        text = stringResource(R.string.crash_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Split once, not on every recomposition: a report runs to tens of thousands of characters.
            val lines = remember(state.text) { state.text.lines() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item { CrashHeaderSection(report, now) }
                item {
                    CrashActionsSection(
                        onReport = { confirmingReport = true },
                        onCopy = { onCopy(state.text) },
                        onShare = onShare,
                    )
                }
                item { SectionHeader(stringResource(R.string.crash_trace_header)) }
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                item {
                    // Destructive, so it sits at the bottom rather than beside Copy and Share.
                    TextButton(
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crash_action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (confirmingReport) {
        ConfirmDialog(
            title = R.string.crash_report_title,
            body = R.string.crash_report_body,
            confirm = R.string.crash_report_confirm,
            onConfirm = {
                confirmingReport = false
                onReportOnGitHub()
            },
            onDismiss = { confirmingReport = false },
        )
    }
    if (confirmingDelete) {
        ConfirmDialog(
            title = R.string.crash_delete_title,
            body = R.string.crash_delete_body,
            confirm = R.string.crash_delete_confirm,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun CrashHeaderSection(
    report: CrashReportRef,
    now: Long,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = report.summary, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.crash_when, compactTimeAgo(report.at, now, labels = relativeTimeLabels())),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.crash_explainer),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        // Says the surface is incomplete without naming Tink/SQLCipher/tflite, and says what to do
        // instead — a Java handler never sees a native crash or an ANR.
        Text(
            text = stringResource(R.string.crash_native_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CrashActionsSection(
    onReport: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Primary, because it is the action that actually gets the trace to a maintainer.
        Button(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.BugReport, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crash_action_report))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crash_action_copy))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crash_action_share))
        }
    }
}

/**
 * Shared by this screen's delete-all and Diagnostics' moderation-latch reset — `internal` rather than
 * `private` for the second caller, the same widening [SectionHeader]/[EmptyLine] took.
 */
@Composable
internal fun ConfirmDialog(
    title: Int,
    body: Int,
    confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Fires the system share sheet with the staged report. Parallel to `launchApkShareChooser` rather than a
 * generalisation of it — that one hardcodes the APK mime type and its own chooser title, and widening it
 * would drag the invite flow into this change for no gain.
 */
fun launchCrashShareChooser(
    context: Context,
    uri: Uri,
    // The field log's share reuses this with its own title (ADR 2026-10.a239).
    title: Int = R.string.crash_share_chooser_title,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = CRASH_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, uri.lastPathSegment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val chooser =
        Intent
            .createChooser(send, context.getString(title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

private const val CLIP_LABEL = "crash log"

private val PREVIEW_REPORT =
    CrashReportRef(
        at = PREVIEW_NOW - 2 * 60 * 60_000L,
        summary = "IllegalStateException at BluetoothMeshTransport.kt:552",
        appVersion = "2.3.0 (13) release",
        device = "Google Pixel 8 (shiba)",
        androidVersion = "16 (SDK 36)",
        file = File("crash-1700000000000-deadbeef.txt"),
    )

private val PREVIEW_TRACE =
    """
    FATAL EXCEPTION: DefaultDispatcher-worker-3
    app: 2.3.0 (13) release
    device: Google Pixel 8 (shiba)
    ----
    java.lang.IllegalStateException: hello reply [node] != expected [node]
    ${"\t"}at app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport.hello(BluetoothMeshTransport.kt:552)
    ${"\t"}at app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:91)
    Caused by: java.io.IOException: [path]
    ${"\t"}... 12 more
    """.trimIndent()

@Preview(showBackground = true)
@Composable
fun CrashLogScreenPreview() =
    KnitPreview {
        CrashLogScreenContent(
            state = CrashLogUiState(loading = false, ref = PREVIEW_REPORT, text = PREVIEW_TRACE),
            now = PREVIEW_NOW,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onCopy = {},
            onShare = {},
            onReportOnGitHub = {},
            onDelete = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun CrashLogScreenEmptyPreview() =
    KnitPreview {
        CrashLogScreenContent(
            state = CrashLogUiState(loading = false),
            now = PREVIEW_NOW,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onCopy = {},
            onShare = {},
            onReportOnGitHub = {},
            onDelete = {},
        )
    }
