package app.getknit.knit.ui.call.stt

import android.content.Context
import android.net.Network
import app.getknit.knit.R
import app.getknit.knit.call.SttFile
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttModelInstaller
import app.getknit.knit.call.SttModelState
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * The walkie transcript's speech model on this phone (docs/VOICE_CALLS.md C-3.11-9): fetched only when the user taps
 * Download — on the call screen's card or in Settings (or the debug bridge asks) — into `noBackupFilesDir`, so neither
 * Auto Backup nor a Knit backup ever carries it. Every byte is checked against the pins in [SttModel] by
 * [SttModelInstaller], so the host is trusted with nothing.
 *
 * The download runs on the app's scope, not a screen's, and [keepAlive] is asked to hold the process while it does
 * ([SttDownloadService], a `dataSync` foreground service), so it outlasts the call it was started on and a Settings
 * screen left behind.
 *
 * The fetch binds to the network [AndroidInternetGate] hands out — a validated route, never the Wi-Fi Aware link — through
 * the platform's own `Network.openConnection`: sockets and DNS on that network, https only, no redirect followed. That
 * keeps `okhttp3` to the two importers `rules/mesh.md` allows; a fixed host serving pinned bytes needs none of the
 * preview fetcher's hostile-URL machinery.
 */
class SttModelStore(
    private val context: Context,
    private val gate: AndroidInternetGate,
    private val scope: CoroutineScope,
    val model: SttModel = SttModel.MOONSHINE_SMALL_STREAMING_EN,
    // Asked as a download starts, to hold the process until it ends; a refusal (a start from the background) is logged
    // and the download runs anyway.
    private val keepAlive: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val root = File(context.noBackupFilesDir, "models")
    private val lock = Any()
    private var job: Job? = null

    private val installer = SttModelInstaller(model, File(root, model.id))

    private val _state = MutableStateFlow<SttModelState>(SttModelState.Absent)

    /** Where the model stands; [SttModelState.Ready] only once every file is in and checked. */
    val state: StateFlow<SttModelState> = _state.asStateFlow()

    /** Bumped each time the model on disk changes (installed, deleted): an engine built over the old files is stale. */
    @Volatile
    var generation = 0
        private set

    private val started = AtomicBoolean()

    /**
     * Reads the disk once: whether the model is there. Off the main thread (Koin builds this lazily). Idempotent, so a
     * caller that may come first in a fresh process (the debug bridge) calls it too. Sweeps what the walkie's earlier
     * builds left beside it: the Tiny and Medium models a picker once offered, the Medium model a build then shipped, and
     * the file that kept the pick.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        root.listFiles()?.filter { it.name != model.id }?.forEach { stale ->
            if (stale.deleteRecursively()) log("walkie stt: removed ${stale.name}")
        }
        if (_state.value == SttModelState.Absent && installer.installed()) _state.value = SttModelState.Ready
    }

    /** The model's files by name, while it is [SttModelState.Ready]. */
    fun paths(): Map<String, File>? {
        if (_state.value != SttModelState.Ready) return null
        return model.files.associate { it.name to installer.pathOf(it) }
    }

    /** Whether a download now would ride mobile data or a Data Saver route: the call screen says so before the tap. */
    fun metered(): Boolean = gate.routeKind() == InternetGate.RouteKind.CELLULAR || gate.isDataRestricted()

    /** Fetches the model from its host; false (with the reason in [state]) when there is no route to fetch it over. */
    fun download(): Boolean {
        val network = gate.currentNetwork()
        if (network == null) {
            _state.value = SttModelState.Failed(context.getString(R.string.stt_no_internet))
            return false
        }
        return install("download") { file -> open(network, file) }
    }

    /** Installs the model from files already on the phone, named as the model names them (the debug bridge). */
    fun sideload(from: File): Boolean = install("side-load from $from") { file -> File(from, file.name).inputStream() }

    /** Stops a download under way; what it had is deleted. */
    fun cancel() {
        synchronized(lock) { job }?.cancel()
    }

    /** Removes the model from the phone. */
    suspend fun delete() {
        val running = synchronized(lock) { job }
        running?.cancel()
        running?.join()
        withContext(Dispatchers.IO) { installer.delete() }
        generation++
        _state.value = SttModelState.Absent
    }

    private fun install(
        what: String,
        open: (SttFile) -> InputStream,
    ): Boolean {
        synchronized(lock) {
            if (job?.isActive == true) return true
            // Downloading before the job runs a line, so the keep-alive started below never reads a state it would stop on.
            _state.value = SttModelState.Downloading(0, model.bytes)
            job =
                scope.launch(Dispatchers.IO) {
                    log("walkie stt: $what of ${model.id} (${model.bytes} B)")
                    try {
                        installer.install(open) { done, total -> _state.value = SttModelState.Downloading(done, total) }
                        generation++
                        _state.value = SttModelState.Ready
                        log("walkie stt: ${model.id} installed")
                    } catch (cancelled: CancellationException) {
                        _state.value = if (installer.installed()) SttModelState.Ready else SttModelState.Absent
                        throw cancelled
                    } catch (e: IOException) {
                        log("walkie stt: $what failed: ${e.javaClass.simpleName}: ${e.message}")
                        _state.value = SttModelState.Failed(e.message ?: context.getString(R.string.stt_download_failed))
                    }
                }
        }
        runCatching(keepAlive).onFailure { log("walkie stt: no keep-alive for the download: ${it.javaClass.simpleName}") }
        return true
    }

    // One file over https on [network]: its pinned size checked against what the server declares before a byte is read.
    @Suppress("ThrowsCount") // a refusal per check, and the connection's own failure passed on
    private fun open(
        network: Network,
        file: SttFile,
    ): InputStream {
        val connection = network.openConnection(URI(model.urlOf(file)).toURL()) as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // The pins are over the file's own bytes: no transparent gzip in between.
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            if (code != HttpsURLConnection.HTTP_OK) throw SttModelInstaller.Refused("The model's server answered $code for ${file.name}")
            val declared = connection.contentLengthLong
            if (declared >= 0 && declared != file.bytes) {
                throw SttModelInstaller.Refused("The server's ${file.name} is $declared bytes, not ${file.bytes}")
            }
            return object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    runCatching { super.close() }
                    connection.disconnect()
                }
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
