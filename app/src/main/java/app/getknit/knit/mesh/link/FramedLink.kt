package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Callbacks a [FramedLink] raises up to its owning transport. Kept transport-neutral (no radio types) so
 * both [app.getknit.knit.mesh.wifiaware.WifiAwareTransport] and the Bluetooth transport supply an impl that
 * forwards into their own flows and teardown. [onLinkDown] may fire more than once for one link (both the
 * read and write loop can end), so implementations must be idempotent.
 */
interface LinkCallbacks {
    fun onInbound(frame: InboundFrame)

    fun onDigest(digest: ReceivedDigest)

    fun onFile(file: ReceivedFile)

    fun onLinkDown(nodeId: String)
}

/**
 * One live data-path link to a peer over a connected [LinkSocket] — the **transport-agnostic** per-connection
 * machinery extracted from the Wi-Fi Aware transport so the Bluetooth transport reuses it byte-for-byte. It
 * owns the [LinkFraming] record loops (frames + interleaved file streaming + store-and-forward digests) and
 * raises decoded results up via [LinkCallbacks]; it does **not** own discovery, admission, or teardown policy
 * (those stay in each transport — e.g. NAN's single-NDI quiescence supervisor reads [lastActivityAt]/
 * [linkStartedAt]/[rxInProgress]/[txInProgress] here to decide when to tear an ephemeral sync down; Bluetooth
 * keeps links persistent).
 *
 * Pure of Android: the wall clock is injected ([now]) and logging is a lambda ([log]) so the loops are
 * JVM-unit-testable over a piped [LinkSocket] ([app.getknit.knit.FramedLinkTest]). Callers pass
 * `SystemClock::elapsedRealtime` for [now] so the quiescence props share the supervisor's clock.
 */
@Suppress("LongParameterList") // a link needs its identity, socket, scope, cache dir, metrics, callbacks, clock
class FramedLink(
    val nodeId: String,
    val peer: Peer,
    private val socket: LinkSocket,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val metrics: MeshMetrics,
    private val callbacks: LinkCallbacks,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val outbound = Channel<Outbound>(Channel.UNLIMITED)
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    // Quiescence bookkeeping for a transport's per-link supervisor (elapsedRealtime, via [now]).
    @Volatile
    var linkStartedAt = 0L
        private set

    @Volatile
    var lastActivityAt = 0L
        private set

    @Volatile
    var rxInProgress = false
        private set

    @Volatile
    var txInProgress = false
        private set

    // Files queued behind an in-progress file transfer (only one streams at a time).
    private val stash = ArrayDeque<Outbound.FileSend>()

    // Inbound file reassembly (one active file per socket, so no per-file id needed).
    private var rxOut: OutputStream? = null
    private var rxTemp: File? = null
    private var rxMeta: FileMeta? = null
    private var rxBytes = 0L
    private var rxAborted = false

    /** Starts the read + write loops. Stamps the quiescence window at link-up (a backfill will extend it). */
    fun start() {
        val t = now()
        linkStartedAt = t
        lastActivityAt = t
        // socket.input is already a stable buffered stream (LinkSocket contract) — the responder may have
        // read the HELLO from it, so do NOT wrap it again or those buffered bytes would be stranded.
        readerJob = scope.launch(Dispatchers.IO) { readLoop(socket.input) }
        writerJob = scope.launch(Dispatchers.IO) { writeLoop() }
    }

    /** Enqueue a mesh frame for delivery to this peer. */
    fun send(bytes: ByteArray) {
        outbound.trySend(Outbound.Frame(bytes))
        metrics.onBytesSent(bytes.size.toLong())
    }

    /** Enqueue a store-and-forward custody digest ([LinkFraming.Type.DIGEST]) for this peer. */
    fun sendDigest(ids: List<String>) {
        outbound.trySend(Outbound.Digest(ids))
    }

    /**
     * Enqueue a file (avatar or attachment) for streaming to this peer. Returns whether the enqueue was
     * accepted — the channel is UNLIMITED, so false means the link is already closed (the composite uses it
     * to fall back to another plane instead of silently losing the file).
     */
    fun sendFile(
        file: File,
        meta: FileMeta,
    ): Boolean = outbound.trySend(Outbound.FileSend(file, meta)).isSuccess

    fun close() {
        readerJob?.cancel()
        writerJob?.cancel()
        outbound.close()
        closeRx()
        socket.close()
    }

    /** Mark data-path activity so a supervisor's quiescence window resets (frames + file records only). */
    private fun touch() {
        lastActivityAt = now()
    }

    // --- Read side ---

    private suspend fun readLoop(input: java.io.InputStream) {
        try {
            while (scope.isActive) {
                val msg = LinkFraming.read(input) ?: break
                when (msg.type) {
                    LinkFraming.Type.FRAME -> {
                        touch()
                        handleFrame(msg.payload)
                    }

                    LinkFraming.Type.FILE_HEADER -> {
                        touch()
                        beginRxFile(msg.payload)
                    }

                    LinkFraming.Type.FILE_CHUNK -> {
                        touch()
                        appendRxFile(msg.payload)
                    }

                    LinkFraming.Type.FILE_END -> {
                        touch()
                        endRxFile()
                    }

                    LinkFraming.Type.DIGEST -> {
                        touch()
                        handleDigest(msg.payload)
                    }

                    LinkFraming.Type.KEEPALIVE -> {
                        Unit
                    }

                    // legacy record from an older peer; ignore
                    LinkFraming.Type.HELLO -> {
                        Unit
                    } // identity already consumed at accept; ignore any stray
                }
            }
        } catch (e: IOException) {
            log("read loop ended for $nodeId: ${e.message}")
        } finally {
            callbacks.onLinkDown(nodeId)
        }
    }

    private fun handleFrame(bytes: ByteArray) {
        val wire = WireCodec.decodeWire(bytes)
        if (wire == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        val envelope = WireCodec.decodeEnvelope(wire.signed)
        if (envelope == null) {
            metrics.onDropped(DropReason.DECODE_FAILED)
            return
        }
        callbacks.onInbound(InboundFrame(wire, envelope, nodeId))
    }

    private fun handleDigest(payload: ByteArray) {
        val digest = LinkFraming.decodeDigest(payload) ?: return
        callbacks.onDigest(ReceivedDigest(nodeId, digest.ids))
    }

    // --- Write side ---

    /**
     * Drains the outbound queue to the socket. Frames are written immediately; a file is streamed
     * as header→chunks→end, but pending frames are flushed *between* chunks so a large blob never stalls
     * live traffic. Only one file streams at a time (later files wait in [stash]).
     */
    private suspend fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.output)
            while (scope.isActive) {
                val item = nextOutbound() ?: break
                when (item) {
                    is Outbound.Frame -> {
                        LinkFraming.write(out, LinkFraming.Type.FRAME, item.bytes)
                        out.flush()
                        touch()
                    }

                    is Outbound.Digest -> {
                        LinkFraming.write(out, LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(item.ids)))
                        out.flush()
                        touch()
                    }

                    is Outbound.FileSend -> {
                        streamFile(out, item)
                    }
                }
            }
        } catch (e: IOException) {
            log("write loop ended for $nodeId: ${e.message}")
            callbacks.onLinkDown(nodeId)
        }
    }

    @Suppress("NestedBlockDepth") // header → (drain frames → read chunk → write chunk) loop → end
    private fun streamFile(out: OutputStream, item: Outbound.FileSend) {
        txInProgress = true // don't let a supervisor tear down mid transfer
        val startedAt = now()
        val bytes = item.file.length()
        try {
            val header = FileHeaderWire(item.meta.kind.name, item.meta.key, item.meta.mime)
            LinkFraming.write(out, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(header))
            item.file.inputStream().use { input ->
                val buf = ByteArray(LinkFraming.FILE_CHUNK_BYTES)
                while (true) {
                    drainFramesInto(out) // interleave live frames between chunks
                    val n = input.read(buf)
                    if (n == -1) break
                    LinkFraming.write(out, LinkFraming.Type.FILE_CHUNK, if (n == buf.size) buf else buf.copyOf(n))
                    touch()
                }
            }
            LinkFraming.write(out, LinkFraming.Type.FILE_END)
            out.flush()
            touch()
            // The per-plane throughput evidence (this same codec runs over the NAN NDP and BLE L2CAP sockets).
            log("file ${item.meta.kind}/${item.meta.key} ${bytes}B in ${now() - startedAt}ms → $nodeId")
        } finally {
            txInProgress = false
        }
    }

    /** Next outbound item: a file stashed during a prior transfer, else the channel head. */
    private suspend fun nextOutbound(): Outbound? = stash.removeFirstOrNull() ?: outbound.receiveCatching().getOrNull()

    /** Write any queued frames now (called between file chunks); stash any files for later. */
    private fun drainFramesInto(out: OutputStream) {
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: break
            when (item) {
                is Outbound.Frame -> {
                    LinkFraming.write(out, LinkFraming.Type.FRAME, item.bytes)
                }

                is Outbound.Digest -> {
                    LinkFraming.write(out, LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(item.ids)))
                }

                is Outbound.FileSend -> {
                    stash.addLast(item)
                }
            }
        }
    }

    // --- Inbound file reassembly ---

    private fun beginRxFile(headerPayload: ByteArray) {
        closeRx()
        val header =
            LinkFraming.decodeFileHeader(headerPayload) ?: run {
                rxAborted = true
                return
            }
        val temp = File.createTempFile("link-rx-", ".tmp", cacheDir)
        rxTemp = temp
        rxOut = BufferedOutputStream(temp.outputStream())
        rxMeta =
            FileMeta(
                kind = runCatching { FileKind.valueOf(header.kind) }.getOrDefault(FileKind.ATTACHMENT),
                key = header.key,
                mime = header.mime,
            )
        rxBytes = 0L
        rxAborted = false
        rxInProgress = true
    }

    private fun appendRxFile(chunk: ByteArray) {
        if (rxAborted) return
        val out = rxOut ?: return
        rxBytes += chunk.size
        if (rxBytes > MAX_INCOMING_FILE_BYTES) {
            log("incoming file from $nodeId exceeds ceiling; aborting")
            abortRx()
            return
        }
        runCatching { out.write(chunk) }.onFailure { abortRx() }
    }

    private fun endRxFile() {
        val (temp, meta) = finishRxFile() ?: return
        scope.launch(Dispatchers.IO) { finalizeIncomingFile(temp, meta) }
    }

    /** Finish the active file, returning (temp, meta) to finalize, or null if none/aborted. */
    private fun finishRxFile(): Pair<File, FileMeta>? {
        val out = rxOut
        val temp = rxTemp
        val meta = rxMeta
        rxOut = null
        rxTemp = null
        rxMeta = null
        rxInProgress = false
        runCatching { out?.close() }
        if (rxAborted || temp == null || meta == null) {
            temp?.delete()
            return null
        }
        return temp to meta
    }

    private fun abortRx() {
        rxAborted = true
        rxInProgress = false
        runCatching { rxOut?.close() }
        rxOut = null
        rxTemp?.delete()
        rxTemp = null
    }

    private fun closeRx() {
        rxInProgress = false
        runCatching { rxOut?.close() }
        rxOut = null
        rxTemp?.delete()
        rxTemp = null
        rxMeta = null
    }

    /** Moves a fully-received file into the cache under a safe name and announces it (avatar by node, attachment by hash). */
    private fun finalizeIncomingFile(
        temp: File,
        meta: FileMeta,
    ) {
        // [meta.key] is peer-supplied and interpolated into the filename: reject anything but a 64-hex
        // content hash so a "../" can't escape the cache dir (path traversal → arbitrary in-sandbox write).
        if (!isValidBlobHash(meta.key)) {
            temp.delete()
            log("Rejecting ${meta.kind} from $nodeId: malformed blob key")
            return
        }
        val dest =
            when (meta.kind) {
                FileKind.AVATAR -> {
                    cacheDir.listFiles { f -> f.name.startsWith("avatar-$nodeId-") }?.forEach { it.delete() }
                    File(cacheDir, "avatar-$nodeId-${meta.key}.jpg")
                }

                FileKind.ATTACHMENT -> {
                    File(cacheDir, "attach-${meta.key}.${extForMime(meta.mime)}")
                }
            }
        val cacheRoot = cacheDir.canonicalPath + File.separator
        if (!dest.canonicalPath.startsWith(cacheRoot)) {
            temp.delete()
            log("Rejecting ${meta.kind} from $nodeId: path escapes cache dir")
            return
        }
        runCatching {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }.onSuccess {
            callbacks.onFile(ReceivedFile(nodeId, dest.absolutePath, meta.kind, meta.key, meta.mime))
        }.onFailure {
            temp.delete()
            dest.delete()
            log("Failed saving ${meta.kind} from $nodeId: ${it.message}")
        }
    }

    private fun extForMime(mime: String): String =
        when (mime.lowercase()) {
            "image/gif" -> "gif"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }

    private sealed interface Outbound {
        class Frame(
            val bytes: ByteArray,
        ) : Outbound

        class Digest(
            val ids: List<String>,
        ) : Outbound

        class FileSend(
            val file: File,
            val meta: FileMeta,
        ) : Outbound
    }

    private companion object {
        // Receive-side ceiling on a file, matching the send cap (AttachmentStore.MAX_BYTES = 8 MiB) plus
        // headroom for E2E framing (GCM IV+tag) — refuses an unbounded malicious stream that exhausts disk.
        const val MAX_INCOMING_FILE_BYTES = 8L * 1024 * 1024 + 64 * 1024
    }
}
