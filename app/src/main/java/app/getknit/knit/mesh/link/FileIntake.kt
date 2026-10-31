package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.transferExtForMime
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The receiving end of one link's file stream: `FILE_HEADER`, `FILE_CHUNK` and `FILE_END` records in, whole files
 * out (knit-ios's `FileIntake` is its twin). One file streams at a time per socket, so no record names its file.
 *
 * - **A header** starts a file. One that arrives mid-file drops the file it interrupts; one that does not decode
 *   leaves the chunks behind it unread.
 * - **A chunk** joins the file being kept; one that takes it past the file's ceiling drops it, and its later chunks
 *   and its `FILE_END` do nothing. The ceiling is read once, at the header: [MAX_AVATAR_BYTES] for a pushed avatar,
 *   the per-hash one [PartialBlobs.capFor] names for an attachment fetched as a profile photo, else
 *   [MAX_INCOMING_FILE_BYTES]. A header that declares more is skipped. Past a per-hash ceiling this link's peer is
 *   also marked for the hash ([PartialBlobs.markHolder], #234), so the want that asked passes it over for the session
 *   rather than wait out a grace and ask it again: the wire cannot stop the feed, and nothing else would tell the asker.
 * - **An end** finishes the file; [finalize] then moves it into the cache under a safe name for its owner to
 *   announce. The hash check over the bytes is the store's (`MeshBlobStore.saveIncoming`).
 * - **A cut** — the link closed mid-file — hands an attachment's bytes to [PartialBlobs] instead of deleting
 *   them, and a header whose `offset` names how many the asker kept splices the rest onto a copy of them (work item
 *   #116, ADR 2026-10.wtyc). An avatar is pushed, never asked for, so it is never kept or resumed. A key resumes at
 *   most once per [RESUME_WINDOW_MS] on one link: each resume copies up to 8 MiB of flash for a ~100 B header (#161).
 *
 * [arrivingFile] is the published "a header is in and no end yet" snapshot `MeshTransport.arrivingFiles` reads
 * (ADR 2026-09.4tx5, 2026-10.y9qh): one reference, so a reader never pairs one file's key with another's count.
 * [landing] carries a file on from its end until the transport's consumer has taken it (#138): the bytes are in, but
 * not yet held, and a reader of both never finds them in neither.
 *
 * Not thread-safe by itself: its owner ([FramedLink]) makes every call under one lock — the chunk writes included,
 * so a `close()` from the transport's thread never races a write on the stream it closes. The one step that can
 * move megabytes, [Resume.copy], runs between [header] and [install] outside that lock, so a link's teardown never
 * waits on a copy; the owner re-takes the lock and installs, or [Resume.abandon]s it if the link closed meanwhile.
 */
internal class FileIntake(
    private val cacheDir: File,
    private val partials: PartialBlobs,
    private val peer: String,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    // The transport's files past their end and not yet taken ([LandingFiles], #138); a rig's own by default.
    private val landing: LandingFiles = LandingFiles(),
) {
    /** A whole file, its `FILE_END` in: [finalize] it outside the owner's lock. */
    class Finished(
        val temp: File,
        val meta: FileMeta,
    )

    /**
     * A resumed header's prefix, still to be copied: the owner runs [copy] outside its lock, then hands this to
     * [install] (or [abandon]s it). [meta]'s offset is the byte the stream starts at.
     */
    class Resume internal constructor(
        internal val temp: File,
        internal val meta: FileMeta,
        internal val size: Long?,
        private val prefix: PartialBlobs.Prefix,
        // The ceiling its header was read under ([ceilingOf]), carried to [install] so it is never re-read.
        internal val limitBytes: Long,
        internal val limitMarks: Boolean,
    ) {
        internal val epoch: Long = prefix.epoch
        internal var out: OutputStream? = null
            private set
        internal var copied = false
            private set

        /**
         * Copies exactly the first `offset` bytes of the kept prefix into a fresh temp file and leaves that stream
         * open, so the chunks append to the very stream the prefix went into (reopening it would truncate it).
         */
        fun copy() {
            copied =
                runCatching {
                    val stream = BufferedOutputStream(FileOutputStream(temp))
                    out = stream
                    prefix.use { copyExactly(it.input, stream, meta.offset) }
                }.getOrDefault(false)
        }

        /** Gives the copy up: the prefix closed, the temp deleted. Safe to call more than once. */
        fun abandon() {
            runCatching { prefix.close() }
            runCatching { out?.close() }
            temp.delete()
        }
    }

    private sealed interface State {
        data object Idle : State

        // A header we will not keep, until the next header: its chunks are dropped and its end yields nothing.
        data object Skipping : State

        // A resumed header whose prefix is being copied outside the owner's lock.
        class Opening(
            val resume: Resume,
        ) : State

        class Keeping(
            val temp: File,
            val out: OutputStream,
            val meta: FileMeta,
            // The epoch of the prefix this stream was spliced onto, or null for one from byte 0 (PartialBlobs.keep).
            val basedOn: Long?,
            // The blob's bytes in so far, a resumed prefix included, so the ceiling counts the whole file.
            var bytes: Long,
            val startedAt: Long,
            // The ceiling read at the header ([ceilingOf]), and whether it is a per-hash one whose breach marks the peer.
            val limit: Ceiling,
        ) : State
    }

    /** The most a file may hold, and whether passing it marks this link's peer for the hash ([PartialBlobs.markHolder], #234). */
    private class Ceiling(
        val bytes: Long,
        val marks: Boolean,
    )

    private var state: State = State.Idle

    // Attachment key -> when its kept prefix last opened for a resume on this link ([RESUME_WINDOW_MS], #161). Bounded:
    // an entry is added only when [PartialBlobs.open] found a prefix (at most [PartialBlobs.MAX_COUNT] at once), and
    // pruned once it ages out of the window.
    private val resumedAt = HashMap<String, Long>()

    private val arriving = AtomicReference<ArrivingFile?>(null)

    /** The file streaming in right now, with the bytes in so far and the total its header declared. */
    val arrivingFile: ArrivingFile? get() = arriving.get()

    /**
     * A `FILE_HEADER`. Returns a [Resume] when the stream is the rest of a file whose prefix we kept — the owner
     * copies it outside its lock and calls [install] — or null when the header was taken (or refused) here.
     */
    fun header(payload: ByteArray): Resume? {
        discard()
        val header = LinkFraming.decodeFileHeader(payload) ?: return skip()
        // No offset is a whole file, and so is an explicit 0: a stream from the first byte splices onto nothing.
        val offset = header.offset?.takeIf { it != 0L }
        val meta = FileMeta(FileKind.fromWire(header.kind), header.key, header.mime, offset ?: 0)
        val limit = ceilingOf(meta)
        if (overCeiling(meta, limit, header.size, offset ?: 0L)) return skip()
        if (offset == null) {
            begin(meta, header.size, limit)
            return null
        }
        return resumeFrom(meta, header.size, offset, limit)
    }

    /** [header] for a stream from [offset] on: the kept prefix opened for the owner to copy, or null when refused. */
    private fun resumeFrom(
        meta: FileMeta,
        size: Long?,
        offset: Long,
        limit: Ceiling,
    ): Resume? {
        val valid = meta.kind == FileKind.ATTACHMENT && isValidBlobHash(meta.key) && offset in 1..limit.bytes
        // Gated before the prefix opens, so a refused resume costs no read of it (#161).
        val recent = if (valid) resumedRecently(meta.key) else null
        val prefix = if (valid && recent == null) partials.open(meta.key, offset) else null
        if (prefix == null) {
            log("rx ${label(meta)} refused ← $peer from $offset: ${recent ?: "${partials.length(meta.key)}B kept"}")
            return skip()
        }
        // Stamped as it opens: the copy is the cost, whether or not it installs.
        resumedAt[meta.key] = now()
        // A temp file that will not open fails the link as it always has; the prefix it would have copied is closed.
        val temp = runCatching { File.createTempFile(RX_PREFIX, RX_SUFFIX, cacheDir) }.onFailure { prefix.close() }.getOrThrow()
        val resume = Resume(temp, meta, size, prefix, limit.bytes, limit.marks)
        state = State.Opening(resume)
        return resume
    }

    /**
     * Takes a [Resume] the owner has copied: the file is kept from its offset on (true), or skipped if the copy
     * failed (false).
     */
    fun install(resume: Resume): Boolean {
        val opening = state as? State.Opening
        val out = resume.out
        if (opening?.resume !== resume || !resume.copied || out == null) {
            resume.abandon()
            if (opening?.resume === resume) {
                log("rx ${label(resume.meta)} refused ← $peer from ${resume.meta.offset}: the kept prefix would not copy")
                skip()
            }
            return false
        }
        val meta = resume.meta
        val limit = Ceiling(resume.limitBytes, resume.limitMarks)
        state = State.Keeping(resume.temp, out, meta, resume.epoch, meta.offset, now(), limit)
        // The size is the tail's (WIRE_COMPAT rule 2), so the whole is the offset plus it: the ring resumes where it was.
        val total =
            resume.size
                ?.takeIf { it >= 0 }
                ?.let { meta.offset + it }
                ?.takeIf { it in 1..limit.bytes }
        arriving.set(ArrivingFile(meta.key, meta.offset, total))
        // The resume's device oracle: the offset rides last, so every parse of the line before it holds.
        log("rx ${label(meta)} ${resume.size ?: "?"}B ← $peer from ${meta.offset}")
        return true
    }

    /** A `FILE_CHUNK`: appended to the file being kept, which it may take past the ceiling. */
    fun chunk(bytes: ByteArray) {
        val keeping = state as? State.Keeping ?: return
        val next = keeping.bytes + bytes.size
        if (next > keeping.limit.bytes) {
            log("incoming file from $peer exceeds ceiling; aborting")
            refuseOver(keeping.meta, keeping.limit)
            abort(keeping)
            return
        }
        if (runCatching { keeping.out.write(bytes) }.isFailure) {
            abort(keeping)
            return
        }
        keeping.bytes = next
        arriving.updateAndGet { it?.copy(bytes = next) }
    }

    /** A `FILE_END`: the file being kept, whole, or null when there is none. */
    fun end(): Finished? {
        val current = state
        if (current is State.Opening) current.resume.abandon() // never: the reader installs before its next record
        val keeping = current as? State.Keeping
        state = State.Idle
        // Landing before it stops arriving: a reader between the two would find the bytes in neither and ask again.
        keeping?.let { landing.land(peer, ArrivingFile(it.meta.key, it.bytes, arriving.get()?.total)) }
        arriving.set(null)
        if (keeping == null) return null
        if (runCatching { keeping.out.close() }.isFailure) {
            landing.take(peer, keeping.meta.key)
            keeping.temp.delete()
            return null
        }
        // How long the bytes took to cross: the sender's `file …` line times only its feed into the stack (#114).
        val onAir = keeping.bytes - keeping.meta.offset
        log("rx ${label(keeping.meta)} ${onAir}B in ${now() - keeping.startedAt}ms ← $peer${from(keeping.meta)}")
        return Finished(keeping.temp, keeping.meta)
    }

    /**
     * The link closed. An attachment cut mid-stream is handed to [PartialBlobs] so the next ask resumes from where
     * its bytes stopped; anything else is dropped. A resume still copying is the reader's to abandon at [install].
     */
    fun cut() {
        val keeping = state as? State.Keeping
        state = State.Idle
        arriving.set(null)
        if (keeping == null) return
        val flushed = runCatching { keeping.out.close() }.isSuccess
        if (flushed && keeping.meta.kind == FileKind.ATTACHMENT) {
            partials.keep(keeping.meta.key, keeping.temp, keeping.basedOn) // deletes the temp itself if it refuses
        } else {
            keeping.temp.delete()
        }
    }

    /**
     * Moves a finished file into the cache under a safe name and returns it to announce (an avatar by node, an
     * attachment by hash), or null when it is refused — and then it is no longer landing. Runs outside the owner's
     * lock: it copies the whole file.
     */
    fun finalize(finished: Finished): ReceivedFile? = moveToCache(finished) ?: null.also { landing.take(peer, finished.meta.key) }

    private fun moveToCache(finished: Finished): ReceivedFile? {
        val meta = finished.meta
        // [meta.key] is peer-supplied and interpolated into the filename: reject anything but a 64-hex
        // content hash so a "../" can't escape the cache dir (path traversal → arbitrary in-sandbox write).
        if (!isValidBlobHash(meta.key)) {
            finished.temp.delete()
            log("Rejecting ${meta.kind} from $peer: malformed blob key")
            return null
        }
        return runCatching {
            val dest = destinationFor(meta)
            val cacheRoot = cacheDir.canonicalPath + File.separator
            if (!dest.canonicalPath.startsWith(cacheRoot)) {
                dest.delete()
                error("path escapes cache dir")
            }
            runCatching { finished.temp.copyTo(dest, overwrite = true) }.onFailure { dest.delete() }.getOrThrow()
            ReceivedFile(peer, dest.absolutePath, meta.kind, meta.key, meta.mime, resumedFrom = meta.offset)
        }.onFailure {
            log("Failed saving ${meta.kind} from $peer: ${it.message}")
        }.also { finished.temp.delete() }
            .getOrNull()
    }

    private fun destinationFor(meta: FileMeta): File =
        when (meta.kind) {
            FileKind.AVATAR -> {
                cacheDir.listFiles { f -> f.name.startsWith("avatar-$peer-") }?.forEach { it.delete() }
                File(cacheDir, "avatar-$peer-${meta.key}.jpg")
            }

            // A name of its own: two links finishing one blob at once (a clique's first ask reaches every holder)
            // must never copy over the file the other's hash check is reading.
            FileKind.ATTACHMENT -> {
                File.createTempFile("attach-${meta.key}-", ".${transferExtForMime(meta.mime)}", cacheDir)
            }
        }

    private fun begin(
        meta: FileMeta,
        size: Long?,
        limit: Ceiling,
    ) {
        val temp = File.createTempFile(RX_PREFIX, RX_SUFFIX, cacheDir)
        state =
            State.Keeping(
                temp,
                BufferedOutputStream(FileOutputStream(temp)),
                meta,
                basedOn = null,
                bytes = 0L,
                startedAt = now(),
                limit = limit,
            )
        // The declared size is the sender's label, never a bound: the header refused one past the ceiling, and a 0 or
        // a negative one is shown as no total at all (#115).
        arriving.set(ArrivingFile(meta.key, 0L, size?.takeIf { it in 1..limit.bytes }))
        // The receive side's oracle that the total crossed. `rx`, not `file`: a bare `file <KIND>/<hash>` grep
        // counts the serves (ADR 2026-09.4tx5's device check), and the key is the peer's until finalize checks it.
        log("rx ${label(meta)} ${size ?: "?"}B ← $peer")
    }

    /**
     * The ceiling a file of [meta] is kept under, read once at its header (#234): a pushed avatar's is
     * [MAX_AVATAR_BYTES]; an attachment's is the per-hash one a profile photo's want set ([PartialBlobs.capFor]), whose
     * breach marks this link's peer, or [MAX_INCOMING_FILE_BYTES].
     */
    private fun ceilingOf(meta: FileMeta): Ceiling =
        when (meta.kind) {
            FileKind.AVATAR -> {
                Ceiling(MAX_AVATAR_BYTES, marks = false)
            }

            FileKind.ATTACHMENT -> {
                val cap = if (isValidBlobHash(meta.key)) partials.capFor(meta.key) else null
                if (cap != null &&
                    cap < MAX_INCOMING_FILE_BYTES
                ) {
                    Ceiling(cap, marks = true)
                } else {
                    Ceiling(MAX_INCOMING_FILE_BYTES, marks = false)
                }
            }
        }

    /**
     * Whether a header for [meta] declares more than a photo's ceiling [limit] (#234): [size] is the stream's, from
     * byte [offset]. The declared size is the sender's label, but one over the ceiling describes a stream this link
     * would abort anyway, so it is skipped now, and under a per-hash ceiling marked as a passed one is. A kept prefix
     * already past a per-hash ceiling cannot become a file under it: it is dropped, so no later ask resumes onto it.
     * The attachment ceiling keeps its own rule — counted chunk by chunk, the prefix included.
     */
    private fun overCeiling(
        meta: FileMeta,
        limit: Ceiling,
        size: Long?,
        offset: Long,
    ): Boolean {
        if (limit.bytes >= MAX_INCOMING_FILE_BYTES) return false
        // Compared without adding the two, so no pair of the peer's numbers overflows past the check.
        if (offset <= limit.bytes && (size ?: 0L) <= limit.bytes - offset) return false
        log("rx ${label(meta)} ${size ?: "?"}B ← $peer${from(meta)} over its ceiling of ${limit.bytes}B; skipped")
        if (offset > limit.bytes && limit.marks) partials.drop(meta.key)
        refuseOver(meta, limit)
        return true
    }

    /**
     * A file of [meta] passed [limit]: when that is a per-hash ceiling, [peer] — the node id this link's handshake
     * named — is marked for the hash (#234). Its bytes are the only evidence, and they speak for this link alone.
     */
    private fun refuseOver(
        meta: FileMeta,
        limit: Ceiling,
    ) {
        if (!limit.marks) return
        partials.markHolder(meta.key, peer)
        log("rx ${label(meta)} ← $peer passed over: its ceiling is ${limit.bytes}B")
    }

    /**
     * Why a resume of [key] is refused now — `resumed <n>ms ago` when one opened its prefix on this link inside
     * [RESUME_WINDOW_MS] — or null when it may open. Only a resume is gated: a whole file copies nothing, and a failed
     * splice is asked for again from byte 0.
     */
    private fun resumedRecently(key: String): String? {
        val t = now()
        resumedAt.values.removeAll { t - it >= RESUME_WINDOW_MS }
        return resumedAt[key]?.let { "resumed ${t - it}ms ago" }
    }

    /** A header mid-file drops the file it interrupts: a sender that moves on without an end abandoned it. */
    private fun discard() {
        val current = state
        if (current is State.Keeping) {
            runCatching { current.out.close() }
            current.temp.delete()
        } else if (current is State.Opening) {
            current.resume.abandon()
        }
        state = State.Idle
        arriving.set(null)
    }

    private fun abort(keeping: State.Keeping) {
        runCatching { keeping.out.close() }
        keeping.temp.delete()
        skip()
    }

    private fun skip(): Resume? {
        state = State.Skipping
        arriving.set(null)
        return null
    }

    /** A file's kind and key for a log line, the key only once it reads as a content hash (it is the peer's). */
    private fun label(meta: FileMeta): String = "${meta.kind.wire}/${meta.key.takeIf { isValidBlobHash(it) } ?: "<bad key>"}"

    private fun from(meta: FileMeta): String = if (meta.offset > 0) " from ${meta.offset}" else ""

    internal companion object {
        // Receive-side ceiling on a file, matching the send cap (AttachmentStore.MAX_BYTES = 8 MiB) plus
        // headroom for E2E framing (GCM IV+tag) — refuses an unbounded malicious stream that exhausts disk.
        const val MAX_INCOMING_FILE_BYTES = 8L * 1024 * 1024 + 64 * 1024

        // Receive-side ceiling on a profile photo (#234), pushed ([FileKind.AVATAR]) or fetched for a profile: exactly
        // this many bytes are kept, one more is not. Our own encoder keeps a face well under it (`AvatarStore`), so it
        // bounds only what a peer sends. Shared with knit-ios.
        const val MAX_AVATAR_BYTES = 1_048_576L

        // The shortest gap between two resumes of one key on one link (#161, ADR 2026-10.wtyc's amendment). An honest
        // holder sends one at most once a round: the asker opens a round of asks per blob at most every ROUND_MS, the
        // holder serves one asker one hash at most once per SERVE_MEMO_MS (the same 45 s), a cut closes the link (the
        // next one has an intake of its own), and a failed splice is asked for again from byte 0, a whole file.
        const val RESUME_WINDOW_MS = BlobExchange.ROUND_MS

        private const val RX_PREFIX = "link-rx-"
        private const val RX_SUFFIX = ".tmp"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Copies exactly [n] bytes of [input] to [out]; false when the input ends first. */
        private fun copyExactly(
            input: InputStream,
            out: OutputStream,
            n: Long,
        ): Boolean {
            val buf = ByteArray(COPY_BUFFER_BYTES)
            var left = n
            while (left > 0) {
                val read = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (read == -1) return false
                out.write(buf, 0, read)
                left -= read
            }
            return true
        }
    }
}
