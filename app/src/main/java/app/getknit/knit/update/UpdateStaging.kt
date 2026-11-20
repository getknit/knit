package app.getknit.knit.update

import app.getknit.knit.mesh.protocol.FrameId
import java.io.File

/**
 * Where a peer update's APK waits between its transfer and its install (ADR 2026-11.29m7): one directory per transfer
 * under [root] (`noBackupFilesDir/update/`), named after the transfer's id, a canonical `FrameId` this checks before it
 * names anything, so a second update transfer can never write over one already checked and no name read off the wire
 * reaches a path. A directory holds the APK and, once its transfer's sink committed, a [COMMITTED] marker; nothing
 * without the marker is ever installed. Beside them sits the one [PendingInstall] an install in flight leaves for the
 * status receiver and the next process. Plain `java.io`, so `UpdateStagingTest` runs it on the JVM.
 */
class UpdateStaging(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The directory for transfer [transferId], or null when the id is not a canonical `FrameId`. */
    fun dirFor(transferId: String): File? = transferId.takeIf { FrameId.toBytesOrNull(it) != null }?.let { File(root, it) }

    /** Where transfer [transferId]'s bytes are written while they arrive. */
    fun incomingApk(transferId: String): File? = dirFor(transferId)?.let { File(it, APK) }

    /** The APK transfer [transferId] staged, or null unless its sink committed it. */
    fun stagedApk(transferId: String): File? {
        val dir = dirFor(transferId) ?: return null
        val apk = File(dir, APK)
        return apk.takeIf { it.isFile && File(dir, COMMITTED).isFile }
    }

    /** Every transfer whose APK is staged, by id. */
    fun committedIds(): Set<String> =
        root
            .listFiles()
            ?.filter { it.isDirectory && FrameId.toBytesOrNull(it.name) != null && File(it, COMMITTED).isFile }
            ?.mapTo(HashSet()) { it.name }
            .orEmpty()

    /** The staging's root, for the installer's space check against its volume. */
    val directory: File get() = root

    /** Marks transfer [transferId]'s APK staged: checked, kept, installable. */
    fun markCommitted(transferId: String): Boolean {
        val marker = dirFor(transferId)?.let { File(it, COMMITTED) } ?: return false
        // The commit's own time, read by the sweep: a file's mtime follows the wall clock of whoever last touched it.
        runCatching { marker.writeText(clock().toString()) }
        return marker.isFile
    }

    /** Removes everything transfer [transferId] staged. */
    fun discard(transferId: String) {
        dirFor(transferId)?.deleteRecursively()
    }

    /**
     * Removes every directory that never committed (a transfer the process died in) and every staged APK older than
     * [keepMs], so a 100 MB APK nobody installed does not stay behind. Leaves [PendingInstall]'s file alone.
     */
    fun sweep(keepMs: Long) {
        val now = clock()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val at = runCatching { File(dir, COMMITTED).readText().trim().toLong() }.getOrNull()
            if (at == null || now - at > keepMs) dir.deleteRecursively()
        }
    }

    /** Removes every staged APK and the pending install: the update landed, or the person reset the app. */
    fun clearAll() {
        root.deleteRecursively()
    }

    /** Records the install session [sessionId] committing transfer [transferId]'s APK for [versionCode], now. */
    fun writePending(
        transferId: String,
        versionCode: Long,
        sessionId: Int,
    ) {
        root.mkdirs()
        File(root, PENDING).writeText("$transferId $versionCode $sessionId ${clock()}")
    }

    /** The install in flight, or null when none was recorded or the record does not parse. */
    fun readPending(): PendingInstall? {
        val parts =
            runCatching { File(root, PENDING).readText() }
                .getOrNull()
                ?.trim()
                ?.split(' ')
                ?.takeIf { it.size == PENDING_FIELDS } ?: return null
        val transferId = parts[0].takeIf { FrameId.toBytesOrNull(it) != null } ?: return null
        val versionCode = parts[1].toLongOrNull() ?: return null
        val sessionId = parts[2].toIntOrNull() ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        return PendingInstall(transferId, versionCode, sessionId, at)
    }

    fun clearPending() {
        File(root, PENDING).delete()
    }

    companion object {
        private const val APK = "knit.apk"
        private const val COMMITTED = "committed"
        private const val PENDING = "pending"
        private const val PENDING_FIELDS = 4
    }
}

/** One install session in flight: which transfer's APK it commits, for which version, and when it began. */
data class PendingInstall(
    val transferId: String,
    val versionCode: Long,
    val sessionId: Int,
    val at: Long,
)
