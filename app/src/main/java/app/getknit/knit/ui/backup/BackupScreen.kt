package app.getknit.knit.ui.backup

import android.content.ClipData
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.backup.BackupFormat
import app.getknit.knit.data.backup.BackupKeys
import app.getknit.knit.data.backup.BackupProblem
import app.getknit.knit.ui.components.DetailCard
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore. Two halves on one screen: make a backup file (a recovery key first, then the
 * document picker, then the write) and restore from one (the picker, the key, the verified staging,
 * then the confirmation that replaces everything and restarts). [restoreOnly] is the onboarding door — a
 * phone with nothing on it yet has nothing to back up.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    restoreOnly: Boolean = false,
    viewModel: BackupViewModel = koinViewModel(),
) {
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.action_copied)
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME), viewModel::writeBackup)
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::pickRestore)

    BackupScreenContent(
        backup = backup,
        restore = restore,
        restoreOnly = restoreOnly,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onStartBackup = viewModel::startBackup,
        onCancelBackup = viewModel::cancelBackup,
        onCopyKey = { key ->
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(CLIP_LABEL, BackupKeys.display(key)).toClipEntry())
                // Android 13+ shows its own copy confirmation, so the snackbar only fires below it.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbarHostState.showSnackbar(copiedMessage)
            }
        },
        onPickBackupFile = { createDocument.launch(suggestedName()) },
        onPickRestoreFile = { openDocument.launch(arrayOf(ANY_MIME)) },
        onStageRestore = viewModel::stageRestore,
        onCancelRestore = viewModel::cancelRestore,
        onConfirmRestore = viewModel::confirmRestore,
        formatSize = { Formatter.formatShortFileSize(context, it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // the stateless twin of the screen: every callback the two halves need
internal fun BackupScreenContent(
    backup: BackupPhase,
    restore: RestorePhase,
    restoreOnly: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onStartBackup: () -> Unit,
    onCancelBackup: () -> Unit,
    onCopyKey: (String) -> Unit,
    onPickBackupFile: () -> Unit,
    onPickRestoreFile: () -> Unit,
    onStageRestore: (String) -> Unit,
    onCancelRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    formatSize: (Long) -> String,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_backup"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (restoreOnly) R.string.backup_restore_title else R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!restoreOnly) {
                BackupSection(backup, onStartBackup, onCancelBackup, onCopyKey, onPickBackupFile, formatSize)
            }
            RestoreSection(restore, restoreOnly, onPickRestoreFile, onStageRestore, onCancelRestore, onConfirmRestore, formatSize)
        }
    }
}

@Composable
private fun BackupSection(
    phase: BackupPhase,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onCopyKey: (String) -> Unit,
    onPickFile: () -> Unit,
    formatSize: (Long) -> String,
) {
    SectionCard(title = stringResource(R.string.backup_section_backup), body = stringResource(R.string.backup_section_backup_body)) {
        when (phase) {
            BackupPhase.Idle -> {
                Button(onClick = onStart, modifier = Modifier.testTag("backup_start")) {
                    Text(stringResource(R.string.backup_action_create))
                }
            }

            is BackupPhase.KeyShown -> {
                var saved by rememberSaveable { mutableStateOf(false) }
                KeyCard(phase.key, onCopyKey)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it }, modifier = Modifier.testTag("backup_key_saved"))
                    Text(stringResource(R.string.backup_key_saved), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    Button(onClick = onPickFile, enabled = saved, modifier = Modifier.testTag("backup_pick_file")) {
                        Text(stringResource(R.string.backup_action_save_file))
                    }
                }
            }

            is BackupPhase.Writing -> {
                Progress(phase.done, phase.total, stringResource(R.string.backup_writing), formatSize)
            }

            is BackupPhase.Written -> {
                Text(
                    stringResource(R.string.backup_written, formatDate(phase.createdAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("backup_written"),
                )
                KeyCard(phase.key, onCopyKey)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_done)) }
            }

            is BackupPhase.Failed -> {
                Text(
                    stringResource(R.string.backup_failed, problemText(phase.problem)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun RestoreSection(
    phase: RestorePhase,
    restoreOnly: Boolean,
    onPickFile: () -> Unit,
    onStage: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    formatSize: (Long) -> String,
) {
    SectionCard(
        title = stringResource(R.string.backup_section_restore),
        body = stringResource(if (restoreOnly) R.string.backup_section_restore_body_onboarding else R.string.backup_section_restore_body),
    ) {
        when (phase) {
            RestorePhase.Idle -> {
                OutlinedButton(onClick = onPickFile, modifier = Modifier.testTag("restore_pick_file")) {
                    Text(stringResource(R.string.backup_action_choose_file))
                }
            }

            is RestorePhase.Picked -> {
                KeyEntry(badKey = phase.badKey, onStage = onStage, onCancel = onCancel)
            }

            is RestorePhase.Staging -> {
                Progress(phase.done, phase.total, stringResource(R.string.backup_restoring_reading), formatSize)
            }

            is RestorePhase.Staged -> {
                Text(stringResource(R.string.backup_restore_ready), style = MaterialTheme.typography.bodyMedium)
                RestoreConfirmDialog(
                    name = phase.manifest.displayName,
                    createdAt = phase.manifest.createdAt,
                    appVersion = phase.manifest.appVersionName,
                    onConfirm = onConfirm,
                    onDismiss = onCancel,
                )
            }

            is RestorePhase.Failed -> {
                Text(
                    stringResource(R.string.backup_restore_failed, problemText(phase.problem)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("restore_failed"),
                )
                if (phase.problem == BackupProblem.WRONG_KEY_OR_DAMAGED) {
                    KeyEntry(badKey = false, onStage = onStage, onCancel = onCancel)
                } else {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_done)) }
                }
            }

            RestorePhase.Restarting -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.backup_restarting), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The recovery key, in its six groups, with a copy button: what the user must keep. */
@Composable
private fun KeyCard(
    key: String,
    onCopy: (String) -> Unit,
) {
    Text(stringResource(R.string.backup_key_intro), style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = BackupKeys.display(key),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f).testTag("backup_key"),
        )
        IconButton(onClick = { onCopy(key) }, modifier = Modifier.size(48.dp).testTag("backup_key_copy")) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.backup_key_copy))
        }
    }
}

@Composable
private fun KeyEntry(
    badKey: Boolean,
    onStage: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text(stringResource(R.string.backup_key_label)) },
        isError = badKey,
        supportingText = { Text(stringResource(if (badKey) R.string.backup_key_not_a_key else R.string.backup_key_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("restore_key"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = { onStage(typed) }, enabled = typed.isNotBlank(), modifier = Modifier.testTag("restore_stage")) {
            Text(stringResource(R.string.backup_action_unlock))
        }
    }
}

@Composable
private fun Progress(
    done: Long,
    total: Long,
    label: String,
    formatSize: (Long) -> String,
) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    if (total > 0) {
        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.backup_progress, formatSize(done), formatSize(total)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** The one dialog that can replace everything: it says whose backup, from when, and what it costs. */
@Composable
private fun RestoreConfirmDialog(
    name: String?,
    createdAt: Long,
    appVersion: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (name != null) {
                        stringResource(R.string.backup_restore_confirm_named, name, formatDate(createdAt), appVersion)
                    } else {
                        stringResource(R.string.backup_restore_confirm_unnamed, formatDate(createdAt), appVersion)
                    },
                )
                Text(stringResource(R.string.backup_restore_confirm_body))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("restore_confirm")) {
                Text(stringResource(R.string.backup_action_restore))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SectionCard(
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    DetailCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun problemText(problem: BackupProblem?): String =
    stringResource(
        when (problem) {
            BackupProblem.NOT_A_BACKUP -> R.string.backup_problem_not_a_backup
            BackupProblem.NEWER_FORMAT, BackupProblem.NEWER_APP -> R.string.backup_problem_newer_app
            BackupProblem.WRONG_KEY_OR_DAMAGED -> R.string.backup_problem_wrong_key
            BackupProblem.TRUNCATED, BackupProblem.MISMATCH -> R.string.backup_problem_damaged
            BackupProblem.NO_SPACE -> R.string.backup_problem_no_space
            null -> R.string.backup_problem_io
        },
    )

@Composable
private fun formatDate(at: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
}

private fun suggestedName(): String =
    "knit-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.${BackupFormat.FILE_EXTENSION}"

private const val CLIP_LABEL = "Knit recovery key"
private const val ANY_MIME = "*/*"
