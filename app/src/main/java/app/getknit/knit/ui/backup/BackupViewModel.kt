package app.getknit.knit.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.backup.BackupException
import app.getknit.knit.data.backup.BackupKeys
import app.getknit.knit.data.backup.BackupManifest
import app.getknit.knit.data.backup.BackupProblem
import app.getknit.knit.data.backup.BackupWriter
import app.getknit.knit.data.backup.RestoreStager
import app.getknit.knit.mesh.MeshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException

/** Where the backup half of the screen is. The recovery key lives here, in memory, and nowhere else. */
sealed interface BackupPhase {
    data object Idle : BackupPhase

    /** A key was minted; the user is looking at it and has not yet picked a file. */
    data class KeyShown(
        val key: String,
    ) : BackupPhase

    data class Writing(
        val key: String,
        val done: Long,
        val total: Long,
    ) : BackupPhase

    /** The file is written; the key stays on screen until the user leaves. */
    data class Written(
        val key: String,
        val createdAt: Long,
    ) : BackupPhase

    data class Failed(
        val key: String,
        val problem: BackupProblem?,
    ) : BackupPhase
}

/** Where the restore half is. */
sealed interface RestorePhase {
    data object Idle : RestorePhase

    /** A file was picked; the key field is showing. */
    data class Picked(
        val uri: Uri,
        val badKey: Boolean = false,
    ) : RestorePhase

    data class Staging(
        val done: Long,
        val total: Long,
    ) : RestorePhase

    /** Decrypted, verified and staged; the confirm dialog is up. */
    data class Staged(
        val manifest: BackupManifest,
    ) : RestorePhase

    data class Failed(
        val uri: Uri,
        val problem: BackupProblem?,
    ) : RestorePhase

    /** The user confirmed; the app is on its way down. */
    data object Restarting : RestorePhase
}

/**
 * Drives both halves of the Backup and restore screen. Backup and restore each run on the application
 * scope of the view model under [NonCancellable] once started — a half-written backup file is deleted
 * rather than left behind, and a restore that reached the staging directory is either verified whole
 * or removed.
 */
class BackupViewModel(
    private val context: Context,
    private val writer: BackupWriter,
    private val stager: RestoreStager,
) : ViewModel() {
    private val _backup = MutableStateFlow<BackupPhase>(BackupPhase.Idle)
    val backup: StateFlow<BackupPhase> = _backup

    private val _restore = MutableStateFlow<RestorePhase>(RestorePhase.Idle)
    val restore: StateFlow<RestorePhase> = _restore

    /** Mints the recovery key; the file picker follows once the user says they have it. */
    fun startBackup() {
        _backup.value = BackupPhase.KeyShown(BackupKeys.generate())
    }

    /** The user backed out before picking a file: the key is forgotten. */
    fun cancelBackup() {
        if (_backup.value is BackupPhase.Writing) return
        _backup.value = BackupPhase.Idle
    }

    /** Writes the backup to the document the picker returned (null: the picker was dismissed). */
    fun writeBackup(uri: Uri?) {
        val key = (_backup.value as? BackupPhase.KeyShown)?.key ?: return
        if (uri == null) return
        _backup.value = BackupPhase.Writing(key, 0, 0)
        viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val manifest =
                        context.contentResolver.openOutputStream(uri, "wt")?.let { raw ->
                            BufferedOutputStream(raw).use { out ->
                                writer.write(out, key) { done, total -> _backup.value = BackupPhase.Writing(key, done, total) }
                            }
                        } ?: throw IOException("could not open $uri")
                    _backup.value = BackupPhase.Written(key, manifest.createdAt)
                } catch (e: BackupException) {
                    Log.w(TAG, "backup failed: ${e.message}", e)
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    _backup.value = BackupPhase.Failed(key, e.problem)
                } catch (e: IOException) {
                    Log.w(TAG, "backup failed", e)
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    _backup.value = BackupPhase.Failed(key, null)
                }
            }
        }
    }

    /** The picker returned a file to restore from (null: dismissed). */
    fun pickRestore(uri: Uri?) {
        if (uri == null) return
        _restore.value = RestorePhase.Picked(uri)
    }

    fun cancelRestore() {
        when (val phase = _restore.value) {
            is RestorePhase.Staging, RestorePhase.Restarting -> return
            is RestorePhase.Staged -> stager.discard()
            else -> Unit
        }
        _restore.value = RestorePhase.Idle
    }

    /** Decrypts the picked file under the typed key into staging; nothing on the phone changes yet. */
    fun stageRestore(typedKey: String) {
        val uri =
            when (val phase = _restore.value) {
                is RestorePhase.Picked -> phase.uri
                is RestorePhase.Failed -> phase.uri
                else -> return
            }
        val key = BackupKeys.parse(typedKey)
        if (key == null) {
            _restore.value = RestorePhase.Picked(uri, badKey = true)
            return
        }
        _restore.value = RestorePhase.Staging(0, 0)
        viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    val manifest =
                        context.contentResolver.openInputStream(uri)?.let { raw ->
                            BufferedInputStream(raw).use { input ->
                                stager.stage(input, key) { done, total -> _restore.value = RestorePhase.Staging(done, total) }
                            }
                        } ?: throw IOException("could not open $uri")
                    _restore.value = RestorePhase.Staged(manifest)
                } catch (e: BackupException) {
                    Log.w(TAG, "restore refused: ${e.message}", e)
                    _restore.value = RestorePhase.Failed(uri, e.problem)
                } catch (e: IOException) {
                    Log.w(TAG, "restore failed", e)
                    _restore.value = RestorePhase.Failed(uri, null)
                }
            }
        }
    }

    /**
     * The user confirmed: the mesh comes down, everything that names a conversation on this phone is
     * cleared, and the trampoline relaunches the app over the staged files. Does not return.
     */
    fun confirmRestore() {
        if (_restore.value !is RestorePhase.Staged || !stager.isStaged()) return
        _restore.update { RestorePhase.Restarting }
        // Not MeshService.stop(): that one records "mesh off" in the settings, and the restored settings
        // decide that. A plain stopService clears the sticky restart without writing anything.
        context.stopService(Intent(context, MeshService::class.java))
        NotificationManagerCompat.from(context).cancelAll()
        runCatching {
            val flags =
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or
                    ShortcutManagerCompat.FLAG_MATCH_PINNED
            val ids = ShortcutManagerCompat.getShortcuts(context, flags).map { it.id }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }
        RestartActivity.relaunch(context)
    }

    private companion object {
        const val TAG = "BackupViewModel"
    }
}
