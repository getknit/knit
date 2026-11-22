package app.getknit.knit.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import app.getknit.knit.data.FileTypes
import app.getknit.knit.ui.launchOrSay
import kotlinx.coroutines.flow.Flow

/**
 * What a tap on a received file does, for every screen that lists one: the chat's bubble and the Files tab of
 * "Media, links and files" (ADR 2026-11.dgvf). One copy of the rules of ADR 2026-09.7ad3:
 *
 * - Nothing on the device can look inside an archive or an executable, so the recipient is told that before they
 *   save one rather than after ([risky], drawn by [FileOpenerDialog]), and it is never opened.
 * - Everything else opens: from the copy saved last time, or through the storage picker first. The user names the
 *   destination and Knit streams the decrypted bytes into it, then opens that copy, now and on every later tap.
 *   Nothing is opened straight from the blob store — that would mean a plaintext staging file or a provider
 *   serving decrypted bytes, against ADR 029's invariant.
 */
@Stable
internal class FileOpener(
    private val onOpen: (PendingSave) -> Unit,
    private val startSave: (PendingSave) -> Unit,
) {
    /** A risky file waiting on the recipient's word before it is saved; null when nothing is asked. */
    var risky: PendingSave? by mutableStateOf(null)
        internal set

    /** The tap on a received file. */
    fun open(pending: PendingSave) {
        if (FileTypes.isRisky(pending.mime, pending.name)) risky = pending else onOpen(pending)
    }

    /** The recipient's answer to [risky]: saved through the picker on [save], forgotten otherwise. */
    fun answerRisky(save: Boolean) {
        val pending = risky ?: return
        risky = null
        if (save) startSave(pending)
    }
}

/**
 * The [FileOpener] for a screen whose ViewModel asks for the picker through [saveNeeded] and hands back the copy
 * to open through [savedFiles]: [onOpen] looks for the saved copy, and [onWriteTo] writes the picked document.
 * Hosts the document picker and the two collectors; the screen draws [FileOpenerDialog] for it.
 */
@Composable
internal fun rememberFileOpener(
    saveNeeded: Flow<PendingSave>,
    savedFiles: Flow<SavedFile>,
    onOpen: (PendingSave) -> Unit,
    onWriteTo: (PendingSave, Uri) -> Unit,
): FileOpener {
    val context = LocalContext.current
    val currentOpen by rememberUpdatedState(onOpen)
    val currentWriteTo by rememberUpdatedState(onWriteTo)
    var savingFile by remember { mutableStateOf<PendingSave?>(null) }
    val fileSaver =
        rememberLauncherForActivityResult(CreateNamedDocument()) { uri ->
            val pending = savingFile
            savingFile = null
            if (uri != null && pending != null) currentWriteTo(pending, uri)
        }
    val startSave: (PendingSave) -> Unit = { pending ->
        savingFile = pending
        if (!fileSaver.launchOrSay(context, pending)) savingFile = null
    }
    val currentStartSave by rememberUpdatedState(startSave)
    val opener = remember { FileOpener(onOpen = { currentOpen(it) }, startSave = { currentStartSave(it) }) }
    LaunchedEffect(saveNeeded) {
        saveNeeded.collect { currentStartSave(it) }
    }
    LaunchedEffect(savedFiles) {
        savedFiles.collect { saved ->
            if (!openSavedFile(context, saved.uri.toString(), saved.mime)) {
                Toast.makeText(context, R.string.chat_transfer_no_app, Toast.LENGTH_SHORT).show()
            }
        }
    }
    return opener
}

/** The question [FileOpener.open] asks before a risky file is saved; nothing while none is waiting. */
@Composable
internal fun FileOpenerDialog(opener: FileOpener) {
    val pending = opener.risky ?: return
    AlertDialog(
        onDismissRequest = { opener.answerRisky(save = false) },
        title = { Text(stringResource(R.string.chat_file_risky_title)) },
        text = {
            Text(
                stringResource(
                    R.string.chat_file_risky_body,
                    pending.name ?: stringResource(R.string.chat_file_unnamed),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { opener.answerRisky(save = true) }) {
                Text(stringResource(R.string.chat_file_risky_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { opener.answerRisky(save = false) }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
