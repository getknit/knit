package app.getknit.knit.ui.translate

import android.content.Context
import android.net.Network
import app.getknit.knit.R
import app.getknit.knit.call.SttFile
import app.getknit.knit.call.SttModelInstaller
import app.getknit.knit.call.SttModelState
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.InternetGate
import app.getknit.knit.transfer.TransferSink
import app.getknit.knit.transfer.TransferSource
import app.getknit.knit.translate.ModelArchive
import app.getknit.knit.translate.ModelBundleSink
import app.getknit.knit.translate.TranslateDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * The translation spike's models on this phone (ADR 2026-10.t2h6), one per direction, each fetched only when the reader
 * taps Download (or the debug bridge side-loads it) into `noBackupFilesDir/translate/`. That is its own folder on
 * purpose: the speech model's store sweeps every sibling of its own under `models/`. Every byte is checked against the
 * pins in [TranslateDirection] by [SttModelInstaller], so the host is trusted with nothing.
 *
 * The fetch is the speech model's: bound to the network [AndroidInternetGate] hands out, `Network.openConnection`,
 * https only, no redirect followed, no transparent gzip. Unlike speech there is no foreground service holding the
 * process: a spike's download runs on the app scope while the process lives.
 */
class TranslateModelStore(
    private val context: Context,
    private val gate: AndroidInternetGate,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val root = File(context.noBackupFilesDir, "translate")

    private inner class Slot(
        direction: TranslateDirection,
    ) {
        val installer = SttModelInstaller(direction.id, direction.files, File(root, direction.id))
        val state = MutableStateFlow<SttModelState>(SttModelState.Absent)
        var job: Job? = null

        @Volatile
        var generation = 0
    }

    private val slots = TranslateDirection.ALL.associateWith { Slot(it) }
    private val lock = Any()
    private val started = AtomicBoolean()

    // A direct transfer's staging (ADR 2026-11.ejn8): one directory per transfer, named here, never by a peer.
    private val incoming = File(root, INCOMING)
    private val liveStages: MutableSet<File> = ConcurrentHashMap.newKeySet()

    private fun slot(direction: TranslateDirection): Slot = checkNotNull(slots[direction]) { "no slot for $direction" }

    /** Where [direction]'s model stands; [SttModelState.Ready] only once every file is in and checked. */
    fun state(direction: TranslateDirection): StateFlow<SttModelState> = slot(direction).state.asStateFlow()

    /** Bumped each time [direction]'s model on disk changes: an engine loaded over the old files is stale. */
    fun generation(direction: TranslateDirection): Int = slot(direction).generation

    /** Reads the disk once: which models are there. Off the main thread; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        for (slot in slots.values) {
            if (slot.state.value == SttModelState.Absent && slot.installer.installed()) slot.state.value = SttModelState.Ready
        }
        // What a transfer cut short by a process death left: every child of incoming/, never a name read from anywhere.
        incoming
            .listFiles()
            .orEmpty()
            .filter { it !in liveStages }
            .forEach { it.deleteRecursively() }
    }

    /** [direction]'s files by name, while it is [SttModelState.Ready]. */
    fun paths(direction: TranslateDirection): Map<String, File>? {
        start()
        val slot = slot(direction)
        if (slot.state.value != SttModelState.Ready) return null
        return direction.files.associate { it.name to slot.installer.pathOf(it) }
    }

    /**
     * [directions]' files as one [ModelArchive], for a direct transfer to a contact (ADR 2026-11.ejn8), while every one
     * is [SttModelState.Ready]; null otherwise. Named and typed as the archive it is, so a phone that takes it as a
     * file saves something any archive tool opens. Nothing is opened until the bytes are due.
     */
    fun bundle(directions: List<TranslateDirection>): TransferSource? {
        start()
        if (directions.isEmpty() || directions.any { slot(it).state.value != SttModelState.Ready }) return null
        if (directions.any { d -> d.files.any { f -> slot(d).installer.pathOf(f).length() != f.bytes } }) return null
        val plan = plan(directions)
        val byId = directions.associateBy { it.id }
        return TransferSource(ModelArchive.FILE_NAME, ModelArchive.size(plan), ModelArchive.MIME) {
            ModelArchive.stream(plan) { dir, file -> slot(byId.getValue(dir)).installer.pathOf(file).inputStream() }
        }
    }

    /**
     * A staging sink for a bundle of [directions] arriving from a contact (ADR 2026-11.ejn8), in a directory this phone
     * names under `incoming/`. Its commit installs each direction through [SttModelInstaller.adopt], which checks every
     * file against its pins again.
     */
    fun receiver(directions: List<TranslateDirection>): TransferSink {
        // Sweep first, so the sweep can never meet a stage this process is filling.
        start()
        val stage = File(incoming, UUID.randomUUID().toString())
        liveStages += stage
        return ModelBundleSink(
            stage = stage,
            plan = plan(directions),
            adopt = { staged -> adoptAll(directions, staged) },
            release = { liveStages -= it },
        )
    }

    // Installs each staged direction as its slot's job, so a Download tap meanwhile returns early; a direction already
    // Ready, or one a download is fetching, is left to what holds it. Throws when one could not be installed.
    private suspend fun adoptAll(
        directions: List<TranslateDirection>,
        staged: Map<String, File>,
    ) {
        val failed = directions.filterNot { adopt(it, checkNotNull(staged[it.id])) }
        if (failed.isNotEmpty()) throw IOException("couldn't install ${failed.joinToString()}")
    }

    private suspend fun adopt(
        direction: TranslateDirection,
        from: File,
    ): Boolean {
        val slot = slot(direction)
        val job =
            synchronized(lock) {
                if (slot.job?.isActive == true || slot.state.value == SttModelState.Ready) return true
                scope
                    .launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try {
                            if (slot.installer.adopt { file -> File(from, file.name) }) slot.generation++
                            log("translate: ${direction.id} installed from a direct transfer")
                        } catch (e: IOException) {
                            log("translate: adopting ${direction.id} failed: ${e.javaClass.simpleName}: ${e.message}")
                        } finally {
                            slot.state.value = if (slot.installer.installed()) SttModelState.Ready else SttModelState.Absent
                        }
                    }.also { slot.job = it }
            }
        job.join()
        return slot.state.value == SttModelState.Ready
    }

    /** Whether a download now would ride mobile data or a Data Saver route. */
    fun metered(): Boolean = gate.routeKind() == InternetGate.RouteKind.CELLULAR || gate.isDataRestricted()

    /** Fetches [direction]'s model; false (with the reason in its state) when there is no route to fetch it over. */
    fun download(direction: TranslateDirection): Boolean {
        val network = gate.currentNetwork()
        if (network == null) {
            slot(direction).state.value = SttModelState.Failed(context.getString(R.string.translate_no_internet))
            return false
        }
        return install(direction, "download") { file -> open(network, direction, file) }
    }

    /** Installs [direction]'s model from files already on the phone, named as the model names them (the debug bridge). */
    fun sideload(
        direction: TranslateDirection,
        from: File,
    ): Boolean = install(direction, "side-load from $from") { file -> File(from, file.name).inputStream() }

    private fun install(
        direction: TranslateDirection,
        what: String,
        open: (SttFile) -> InputStream,
    ): Boolean {
        start()
        val slot = slot(direction)
        synchronized(lock) {
            if (slot.job?.isActive == true) return true
            slot.state.value = SttModelState.Downloading(0, direction.bytes)
            slot.job =
                scope.launch(Dispatchers.IO) {
                    log("translate: $what of ${direction.id} (${direction.bytes} B)")
                    try {
                        slot.installer.install(open) { done, total -> slot.state.value = SttModelState.Downloading(done, total) }
                        slot.generation++
                        slot.state.value = SttModelState.Ready
                        log("translate: ${direction.id} installed")
                    } catch (cancelled: CancellationException) {
                        slot.state.value = if (slot.installer.installed()) SttModelState.Ready else SttModelState.Absent
                        throw cancelled
                    } catch (e: IOException) {
                        log("translate: $what failed: ${e.javaClass.simpleName}: ${e.message}")
                        slot.state.value = SttModelState.Failed(e.message ?: context.getString(R.string.translate_download_failed))
                    }
                }
        }
        return true
    }

    // One file over https on [network]: its pinned size checked against what the server declares before a byte is read.
    @Suppress("ThrowsCount") // a refusal per check, and the connection's own failure passed on
    private fun open(
        network: Network,
        direction: TranslateDirection,
        file: SttFile,
    ): InputStream {
        val connection = network.openConnection(URI(direction.urlOf(file)).toURL()) as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
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

    companion object {
        /** A bundle's layout: each direction's files under its id, in the order given. */
        fun plan(directions: List<TranslateDirection>): List<Pair<String, List<SttFile>>> = directions.map { it.id to it.files }

        private const val INCOMING = "incoming"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
