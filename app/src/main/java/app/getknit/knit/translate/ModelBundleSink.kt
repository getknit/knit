package app.getknit.knit.translate

import app.getknit.knit.call.SttFile
import app.getknit.knit.transfer.TransferSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Where a direct transfer's bundle of translation models lands (ADR 2026-11.ejn8). The bundle is the [ModelArchive] of
 * [plan], and this phone writes that archive's layout from its own pins and holds what arrives against it: every
 * header, padding and trailer byte must be the one it would have written, and every file's bytes go to
 * `<stage>/<direction id>/<name>` through a running SHA-256. A byte past the archive's end is refused, so a sender
 * cannot make this phone write more than its own table allows, and nothing a peer wrote is ever parsed.
 *
 * [stage] is a directory this phone named; nothing a peer wrote ever reaches a path here. [check] passes only when
 * the whole archive arrived as expected and every file at its size and hash. [commit] hands the staged directories to
 * [adopt], which installs them through `SttModelInstaller.adopt` (checking each file again), and then removes the
 * stage. [discard] removes it on any other end. Either way [release] is told, so the store's sweep knows the stage is
 * no longer live.
 *
 * Pure JVM (`ModelBundleSinkTest`).
 */
class ModelBundleSink(
    private val stage: File,
    private val plan: List<Pair<String, List<SttFile>>>,
    private val adopt: suspend (staged: Map<String, File>) -> Unit,
    private val release: (File) -> Unit = {},
) : TransferSink {
    private class Piece(
        val dir: String,
        val file: SttFile,
    )

    private val pieces = plan.flatMap { (dir, files) -> files.map { Piece(dir, it) } }
    private val segments = ModelArchive.segments(plan)
    private val digests = arrayOfNulls<String>(pieces.size)
    private var splitter: Splitter? = null

    override val uri: String = "models"

    override fun stream(): OutputStream = splitter ?: Splitter().also { splitter = it }

    override suspend fun check(): Boolean =
        withContext(Dispatchers.IO) {
            val out = splitter ?: Splitter().also { splitter = it }
            out.close()
            out.complete && !out.mismatched && pieces.indices.all { digests[it] == pieces[it].file.sha256 }
        }

    override suspend fun commit() {
        withContext(Dispatchers.IO) { splitter?.close() }
        try {
            adopt(plan.associate { (dir, _) -> dir to File(stage, dir) })
        } finally {
            withContext(Dispatchers.IO) { stage.deleteRecursively() }
            release(stage)
        }
    }

    override suspend fun discard() {
        withContext(Dispatchers.IO) {
            runCatching { splitter?.close() }
            stage.deleteRecursively()
        }
        release(stage)
    }

    // Walks the expected archive: fixed stretches compared byte for byte, each file's data written and hashed.
    private inner class Splitter : OutputStream() {
        private var index = 0
        private var at = 0L
        private var out: OutputStream? = null
        private val digest = MessageDigest.getInstance("SHA-256")
        private var closed = false

        /** A fixed byte that differed from the one this phone would have written: the bundle fails its check. */
        var mismatched = false
            private set

        val complete: Boolean get() = index == segments.size

        init {
            advance()
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (closed) throw IOException("the bundle's stream is closed")
            var from = off
            var left = len
            while (left > 0) {
                if (index >= segments.size) throw IOException("more bytes than the bundle's pins")
                val segment = segments[index]
                val n = minOf(segment.length - at, left.toLong()).toInt()
                when (segment) {
                    is ModelArchive.Segment.Fixed -> {
                        for (i in 0 until n) if (b[from + i] != segment.bytes[(at + i).toInt()]) mismatched = true
                    }

                    is ModelArchive.Segment.Data -> {
                        // Past a mismatch nothing more is kept: the bundle is already refused.
                        if (!mismatched) current(pieces[segment.piece]).write(b, from, n)
                        digest.update(b, from, n)
                    }
                }
                at += n
                from += n
                left -= n
                advance()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            out?.close()
            out = null
        }

        private fun current(piece: Piece): OutputStream =
            out ?: run {
                val dir = File(stage, piece.dir)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't make the model's staging folder")
                File(dir, piece.file.name).outputStream().buffered(BUFFER_BYTES).also { out = it }
            }

        // Moves past every segment now complete, recording a file's hash as its data ends; an empty file closes unwritten.
        private fun advance() {
            while (index < segments.size && at == segments[index].length) {
                val segment = segments[index]
                if (segment is ModelArchive.Segment.Data) {
                    if (out == null && !mismatched) current(pieces[segment.piece])
                    out?.close()
                    out = null
                    digests[segment.piece] = digest.digest().toHex()
                }
                index++
                at = 0L
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val HEX = "0123456789abcdef"
        const val NIBBLE = 4
        const val LOW = 0x0F

        fun ByteArray.toHex(): String =
            buildString(size * 2) {
                for (b in this@toHex) {
                    append(HEX[(b.toInt() shr NIBBLE) and LOW])
                    append(HEX[b.toInt() and LOW])
                }
            }
    }
}
