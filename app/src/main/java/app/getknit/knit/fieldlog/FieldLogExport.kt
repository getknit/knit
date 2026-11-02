package app.getknit.knit.fieldlog

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.crash.shareFileName
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cache subdirectory the share sheet reads from; matches `<cache-path name="fieldlog" …>` in `res/xml/file_paths.xml`.
 * [FieldLog.clear] empties it too, so a Clear or the switch turned off leaves no staged copy behind.
 */
const val FIELD_LOG_SHARE_DIR = "fieldlog"

/**
 * Turns the field log into the one file the user can hand over (ADR 2026-10.a239): the journal, the latency
 * summaries read from the database at this moment, and a header of build and device facts. Nothing is sent —
 * [stage] writes a copy under `cacheDir/fieldlog/` for the share sheet, the same staging ADR 028 uses for a
 * crash report, because `FileProvider` cannot reach `noBackupFilesDir` where the journal lives.
 */
class FieldLogExport(
    private val context: Context,
    private val log: FieldLog,
    private val messages: MessageDao,
    private val identity: Identity,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** The document as it would be shared now. */
    suspend fun render(): FieldLogDocument.Built =
        withContext(Dispatchers.IO) {
            // What the counters moved by since the last quarter-hour line, so the export ends on the present.
            log.sample()
            val lines = log.lines()
            val now = clock()
            val since = now - FieldJournal.KEEP_MS
            val me = identity.nodeId()
            val latency =
                runCatching {
                    FieldLogDocument.latency(LatencyDirection.IN, messages.inboundDelays(me, since)) +
                        FieldLogDocument.latency(LatencyDirection.ACK, messages.receiptDelays(me, since))
                }.getOrDefault(emptyList())
            FieldLogDocument.build(device(), lines, latency, now)
        }

    /**
     * Stages the document and returns a `content://` [Uri] for it, or null when staging failed. The directory
     * is emptied first so an older copy is never the one shared; the copy is left for `cacheDir` eviction
     * because the receiving app reads the Uri after this returns.
     */
    suspend fun stage(): Uri? =
        withContext(Dispatchers.IO) {
            val doc = render()
            runCatching {
                val dir = File(context.cacheDir, FIELD_LOG_SHARE_DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, fileName())
                dest.writeText(doc.text)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            }.getOrNull()
        }

    /**
     * The name the file goes out under, e.g. `knit-fieldlog-2.10.2-20261102-1432.jsonl`: [stage]'s file, and the
     * attachment's name when Diagnostics sends the log into a chat as bytes (#287).
     */
    fun fileName(): String = shareFileName(currentCrashEnvironment().versionName, clock(), stem = "knit-fieldlog", extension = "jsonl")

    /** ASCII `key: value` facts — the crash report's own header lines, so a reader sees the same device. */
    private fun device(): List<String> {
        val env = currentCrashEnvironment()
        return listOf(
            "app: ${env.appLine()}",
            "device: ${env.manufacturer} ${env.model}",
            "soc: ${env.soc.ifBlank { env.hardware }}",
            "android: ${env.androidLine()}",
        ).map { line -> line.filter { it.code in PRINTABLE } }
    }

    private companion object {
        val PRINTABLE = 0x20..0x7e
    }
}
