package app.getknit.knit.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.storage.StorageManager
import app.getknit.knit.data.message.AppUpdateRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** Where one staged update's install stands, keyed by its transfer id in [UpdateInstaller.states]. */
enum class InstallState { Installing, NeedsGrant, Failed, Gone }

/**
 * Installs a staged peer update (ADR 2026-11.29m7): this app's own package only, from [staging] only, through one
 * [PackageInstaller] session. It takes a transfer id and the record its transfer wrote, never a path or a URI, so nothing
 * that arrives as a share can reach it. The session is written from one descriptor, hashed as the bytes go in, and
 * abandoned when they no longer match the pin; `setAppPackageName` makes the platform refuse any other package as well.
 *
 * The status comes back to [UpdateStatusReceiver] alone (unexported, no intent filter, an explicit `PendingIntent`),
 * which acts only on the session [staging]'s [PendingInstall] names.
 */
class UpdateInstaller(
    context: Context,
    private val staging: UpdateStaging,
    private val build: AndroidAppBuild,
    private val log: (String) -> Unit = {},
) {
    private val app = context.applicationContext
    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())

    /** Installs this process started, by transfer id, until the update replaces it or the install fails. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    /**
     * Bytes an update could stage in, for the asker's space check: what the volume can allocate, cache it could clear
     * included (the platform's own reckoning, not the raw free space).
     */
    fun freeBytes(): Long =
        runCatching {
            val storage = app.getSystemService(StorageManager::class.java)
            val dir = staging.directory.also { it.mkdirs() }
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        }.getOrDefault(0L)

    /** Whether Knit holds the person's "Install unknown apps" grant, without which no session can commit. */
    fun mayInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** The staged APK of transfer [transferId], if it is still there and newer than this build. */
    suspend fun staged(
        transferId: String,
        record: AppUpdateRecord,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val installed = build.local()?.build ?: return@withContext false
            staging.stagedApk(transferId) != null && record.versionCode > installed.versionCode
        }

    /**
     * Installs transfer [transferId]'s staged APK, which its transfer pinned as [record]. On success the platform
     * replaces this process; the new build's `BootReceiver` brings the mesh and the UI back.
     */
    suspend fun install(
        transferId: String,
        record: AppUpdateRecord,
    ): InstallState =
        withContext(Dispatchers.IO) {
            val state = installLocked(transferId, record)
            _states.value += transferId to state
            state
        }

    @Suppress("ReturnCount") // a refusal ladder; each early return is one reason
    private suspend fun installLocked(
        transferId: String,
        record: AppUpdateRecord,
    ): InstallState {
        if (!mayInstall()) return InstallState.NeedsGrant
        val apk = staging.stagedApk(transferId) ?: return InstallState.Gone
        val installed = build.local()?.takeIf { it.canInstall }?.build ?: return InstallState.Failed
        val facts = build.inspect(apk)
        val why = facts?.let { UpdateEligibility.archiveRefusal(it, app.packageName, installed, build.signers(), record.versionCode) }
        if (facts == null || why != null) {
            log("update: install of $transferId refused: ${why ?: "unreadable"}")
            staging.discard(transferId)
            return InstallState.Failed
        }
        return if (commitSession(apk, record, transferId)) InstallState.Installing else InstallState.Failed
    }

    /** One session for [apk], our own package only, written and committed; false when it could not be (and is abandoned). */
    private fun commitSession(
        apk: File,
        record: AppUpdateRecord,
        transferId: String,
    ): Boolean {
        val installer = app.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val sessionId =
            try {
                installer.createSession(params)
            } catch (e: IOException) {
                log("update: no install session: $e")
                return false
            }
        val written =
            try {
                installer.openSession(sessionId).use { session -> writeAndCommit(session, sessionId, apk, record, transferId) }
            } catch (e: IOException) {
                log("update: install session $sessionId failed: $e")
                false
            } catch (e: SecurityException) {
                log("update: install session $sessionId refused: $e")
                false
            }
        if (written) {
            log("update: committed session $sessionId for ${record.versionCode}")
        } else {
            runCatching { installer.abandonSession(sessionId) }
            staging.clearPending()
        }
        return written
    }

    private fun writeAndCommit(
        session: PackageInstaller.Session,
        sessionId: Int,
        apk: File,
        record: AppUpdateRecord,
        transferId: String,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(apk).use { input ->
            session.openWrite(SESSION_APK, 0, apk.length()).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
                session.fsync(out)
            }
        }
        // The bytes the session holds are the bytes the transfer pinned, or nothing is committed.
        if (AppUpdateRecord.hexOf(digest.digest()) != record.sha256) {
            log("update: staged APK for $transferId no longer matches its pin")
            return false
        }
        staging.writePending(transferId, record.versionCode, sessionId)
        session.commit(statusSender(sessionId).intentSender)
        return true
    }

    private fun statusSender(sessionId: Int): PendingIntent {
        // Explicit: the receiver is unexported and has no intent filter, so only this PendingIntent reaches it. Mutable,
        // because the installer fills the status extras in.
        val intent = Intent(app, UpdateStatusReceiver::class.java).setPackage(app.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(app, sessionId, intent, flags)
    }

    /** [UpdateStatusReceiver]'s verdict on a failure: the install of [transferId] ended without the update. */
    internal fun onFailed(transferId: String) {
        staging.discard(transferId)
        staging.clearPending()
        _states.value += transferId to InstallState.Failed
    }

    companion object {
        private const val SESSION_APK = "knit.apk"
        private const val BUFFER_BYTES = 256 * 1024
    }
}

/**
 * What [UpdateStatusReceiver] does with one status (ADR 2026-11.29m7), decided apart from Android so
 * `UpdateStagingTest.aStatusCountsOnlyForTheSessionKnitRecorded` shows a forged status does nothing. Any app could send
 * the receiver an intent if it were reachable, and a `STATUS_PENDING_USER_ACTION` carries an intent for Knit to launch
 * under its own identity; so a status counts only for the session [pending] names, and the confirm intent is launched
 * only when it is the installer's.
 */
object InstallStatusPolicy {
    /** The platform installer's confirmation action, the one intent a pending status may ask Knit to launch. */
    const val CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"

    sealed interface Action {
        /** Not ours: a session Knit did not start, or no install in flight. Nothing happens. */
        data object Ignore : Action

        /** Launch the installer's confirmation, with its grants and clip data stripped. */
        data object Confirm : Action

        /** The install ended without the update: clear the staging and the pending record. */
        data class Failed(
            val transferId: String,
        ) : Action

        /** The update landed (seldom seen: the platform replaces this process first). */
        data object Done : Action
    }

    fun decide(
        status: Int,
        sessionId: Int,
        confirmAction: String?,
        pending: PendingInstall?,
    ): Action {
        if (pending == null || pending.sessionId != sessionId) return Action.Ignore
        return when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmAction ==
                    CONFIRM_INSTALL
                ) {
                    Action.Confirm
                } else {
                    Action.Failed(pending.transferId)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Action.Done
            }

            else -> {
                Action.Failed(pending.transferId)
            }
        }
    }
}
