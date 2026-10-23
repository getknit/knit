package app.getknit.knit.call

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One file of a speech model, pinned: its name, its exact size and its SHA-256 (lower-case hex). */
data class SttFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/** Which of Moonshine's streaming English architectures a [SttModel] is: what the engine is told when it loads the files. */
enum class SttArch { TINY_STREAMING, SMALL_STREAMING, MEDIUM_STREAMING }

/**
 * A speech model the walkie's transcript can download (C-3.11-9): its files, fetched by name under [baseUrl] and
 * pinned by size and hash here, so the host is trusted with nothing — a byte it changes is a download that fails.
 * [baseUrl] is https or nothing. The app ships one, [MOONSHINE_SMALL_STREAMING_EN].
 */
class SttModel(
    val id: String,
    val baseUrl: String,
    val files: List<SttFile>,
    val arch: SttArch,
) {
    init {
        require(baseUrl.startsWith("https://") && baseUrl.endsWith("/")) { "a model is fetched over https, from a directory" }
        require(files.isNotEmpty() && files.all { it.name.matches(SAFE_NAME) }) { "a model's files are plain names" }
    }

    /** Every file's bytes together: what a download costs. */
    val bytes: Long get() = files.sumOf { it.bytes }

    fun urlOf(file: SttFile): String = baseUrl + file.name

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        /**
         * Moonshine v2 Small Streaming, English (123M parameters, int8 `.ort`, MIT): 7.84 % on the Open ASR average,
         * ahead of Whisper Small's 8.59 at half its size and a fraction of its time. The one model, for the walkie and a live
         * call's captions alike: Tiny (12.0 %) read too poorly, and Medium (6.65 %), shipped for a day, could not keep up
         * with a live call's continuous speech on the lab's oldest phones, where Small could (ADR 2026-10.nz3g). Moonshine's own files
         * (`small-streaming-en/quantized_26_08_21`, the per-channel quantization), rehosted under getknit.app with the
         * library's licence. `scripts/stt-model.py --check` re-derives these pins from upstream; never edit them by hand.
         */
        val MOONSHINE_SMALL_STREAMING_EN =
            SttModel(
                id = "moonshine-small-streaming-en-2026-08-21",
                baseUrl = "https://getknit.app/models/stt/moonshine-small-streaming-en-2026-08-21/",
                files =
                    listOf(
                        SttFile("adapter.ort", 2_870_368, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762"),
                        SttFile("cross_kv.ort", 5_356_536, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6"),
                        SttFile("decoder_kv.ort", 81_878_600, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b"),
                        SttFile("encoder.ort", 44_148_576, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993"),
                        SttFile("frontend.model.ort", 26_944, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f"),
                        SttFile("frontend.weights.ort", 7_769_464, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338"),
                        SttFile("streaming_config.json", 512, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a"),
                        SttFile("tokenizer.bin", 249_974, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d"),
                        SttFile("LICENSE", 14_180, "fa7d1174dd8af6a7cd280be20b80d10095ed4c19b5b20b61a7715c3ad790dc5f"),
                    ),
                arch = SttArch.SMALL_STREAMING,
            )
    }
}

/** Where the walkie's speech model stands on this phone. */
sealed interface SttModelState {
    data object Absent : SttModelState

    data class Downloading(
        val done: Long,
        val total: Long,
    ) : SttModelState

    data object Ready : SttModelState

    data class Failed(
        val reason: String,
    ) : SttModelState
}

/**
 * Puts a model — [id] and its [files] — into [dir], one file at a time, each streamed to `<name>.part` through a
 * running SHA-256 and renamed into place only when its size and hash are the pinned ones. A file past its pinned size
 * is cut off there, not read to the end. The model counts as installed ([installed]) once a marker naming it is
 * written, after every file is in: a phone that died half way holds no marker, and the next install re-checks what is
 * there and fetches the rest.
 *
 * [open] is the source: an https fetch on the phone, a directory for a side-load, bytes in a test. Pure JVM
 * (`SttModelInstallerTest`); one install at a time.
 */
class SttModelInstaller(
    private val id: String,
    private val files: List<SttFile>,
    private val dir: File,
) {
    constructor(model: SttModel, dir: File) : this(model.id, model.files, dir)

    // Every file's bytes together: what an install reports progress against.
    private val bytes: Long = files.sumOf { it.bytes }

    /** Why an install did not finish, in words the call screen shows. */
    class Refused(
        message: String,
    ) : IOException(message)

    private val marker = File(dir, MARKER)

    /** Whether the model is on the phone: its marker, and every file at its pinned size (hashed at install, not here). */
    fun installed(): Boolean =
        runCatching { marker.isFile && marker.readText() == id && files.all { File(dir, it.name).length() == it.bytes } }
            .getOrDefault(false)

    /** The path of [file] in [dir]. */
    fun pathOf(file: SttFile): File = File(dir, file.name)

    /**
     * Installs the model from [open], reporting the bytes in hand against the total to [progress]. Throws [Refused] (or
     * the source's [IOException]) when a file cannot be had or fails its pins, and cancellation as it comes; a file half
     * written is deleted either way.
     */
    suspend fun install(
        open: (SttFile) -> InputStream,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        if (installed()) return
        if (!dir.isDirectory && !dir.mkdirs()) throw Refused("Couldn't make the model's folder")
        marker.delete()
        var done = 0L
        progress(done, bytes)
        for (file in files) {
            val target = pathOf(file)
            if (!(target.length() == file.bytes && sha256(target) == file.sha256)) {
                fetch(file, open, target) { n -> progress(done + n, bytes) }
            }
            done += file.bytes
            progress(done, bytes)
        }
        marker.writeText(id)
    }

    /** Removes the model: the marker first, so a delete cut short leaves a model that is not installed. */
    fun delete() {
        marker.delete()
        dir.deleteRecursively()
    }

    private suspend fun fetch(
        file: SttFile,
        open: (SttFile) -> InputStream,
        target: File,
        progress: (Long) -> Unit,
    ) {
        val part = File(dir, file.name + PART)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val count = open(file).use { input -> part.outputStream().use { output -> copy(file, input, output, digest, progress) } }
            refuseUnless(count == file.bytes) { "${file.name} came short: $count of ${file.bytes} bytes" }
            refuseUnless(digest.digest().toHex() == file.sha256) { "${file.name} failed its checksum" }
            target.delete()
            refuseUnless(part.renameTo(target)) { "Couldn't put ${file.name} in place" }
        } finally {
            part.delete()
        }
    }

    // [input] to [output] through [digest], never past [file]'s pinned size; the bytes copied.
    private suspend fun copy(
        file: SttFile,
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        progress: (Long) -> Unit,
    ): Long =
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_BYTES)
            var count = 0L
            var reported = 0L
            while (true) {
                ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                count += n
                refuseUnless(count <= file.bytes) { "${file.name} is larger than it should be" }
                digest.update(buffer, 0, n)
                output.write(buffer, 0, n)
                if (count - reported >= REPORT_BYTES) {
                    reported = count
                    progress(count)
                }
            }
            count
        }

    private inline fun refuseUnless(
        ok: Boolean,
        reason: () -> String,
    ) {
        if (!ok) throw Refused(reason())
    }

    private fun sha256(file: File): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().toHex()
        }.getOrNull()

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (b in this@toHex) {
                append(HEX[(b.toInt() shr NIBBLE) and LOW])
                append(HEX[b.toInt() and LOW])
            }
        }

    private companion object {
        const val MARKER = "installed"
        const val PART = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_BYTES = 256 * 1024L
        const val HEX = "0123456789abcdef"
        const val NIBBLE = 4
        const val LOW = 0x0F
    }
}
