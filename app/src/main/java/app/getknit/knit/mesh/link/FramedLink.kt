package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FileFeed
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

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
@Suppress("LongParameterList", "LargeClass") // a link's identity, socket, scope, store, callbacks, clock; one link's loops (#203)
class FramedLink(
    val nodeId: String,
    val peer: Peer,
    private val socket: LinkSocket,
    private val scope: CoroutineScope,
    cacheDir: File,
    // Where a cut attachment's prefix goes, and where a resumed one is spliced from (#116) — one store for every
    // link, so any holder can finish what another started.
    partials: PartialBlobs,
    private val metrics: MeshMetrics,
    private val callbacks: LinkCallbacks,
    private val now: () -> Long,
    // The file feed's pace for a slow shared channel (BLE L2CAP), read before every chunk so a link that changes
    // PHY mid-transfer is paced by the one it is on now, and one whose share of the radio's budget changes as other
    // feeds start and end takes the new share (#117); unbounded (Wi-Fi Aware NDP, and the default so existing
    // callers/tests are unchanged). See [TransferPacePolicy] and [PaceWindow] for the why.
    private val pace: () -> PaceConfig = { PaceConfig() },
    private val log: (String) -> Unit = {},
    // The owning transport's files past their end and not yet taken (#138): they outlive this link.
    landing: LandingFiles = LandingFiles(),
    // Poked when [feeding] or [rxInProgress] may have changed, outside every lock, so a reader may ask either. A bare
    // poke: the reader reads the state it wants (the Bluetooth link boost, #120); unbounded transports pass nothing.
    private val onBusy: () -> Unit = {},
    // Poked when a file's feed may have changed how long it has left, outside every lock: its stream is over, fed or not;
    // its pace settled or moved; its first progress report measured it. A holder parks asks for a blob behind another
    // copy's feed and re-reads them here, on the CPU the write woke (ADR 2026-10.g24x, 2026-10.nsr7).
    private val onFeedChanged: () -> Unit = {},
    // Called on the reader every [PROGRESS_EVERY_MS] of chunks of a file streaming in, with its key, the bytes held
    // and the ms since its header, outside every lock: the owner sends it back to the sender (ADR 2026-10.nsr7).
    onRxProgress: (key: String, held: Long, ms: Long) -> Unit = { _, _, _ -> },
    // Whether this side reports a file streaming in the way a windowed sender needs (ADR 2026-10.5buf): every
    // [WINDOWED_PROGRESS_EVERY_MS], and once more that long after the last chunk if no other came. Off, every
    // [PROGRESS_EVERY_MS] of chunks only, as nsr7 shipped it. Read at each report.
    windowedReports: () -> Boolean = { false },
    // Called on the reader when a [LinkFraming.Type.KEEPALIVE] arrives, and on the writer once one of ours went to the
    // socket: the Bluetooth transport's silent-link probe and its answer (#203). Wi-Fi Aware passes neither, and never
    // calls [sendKeepalive], so its links read and write exactly what they did.
    private val onKeepalive: () -> Unit = {},
    private val onKeepaliveWritten: () -> Unit = {},
) {
    private val outbound = Channel<Outbound>(Channel.UNLIMITED)

    // The custody digest waiting in [outbound] to be written, if one is. Whoever swaps it from null queues one
    // [Outbound.DigestDue] and the writer empties it on reaching that marker, so at most one marker is ever
    // queued. [sendDigest] swaps a newer id set in here rather than queueing a second record.
    private val pendingDigest = AtomicReference<List<String>?>(null)
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
    var txInProgress = false
        private set

    // The key of the file streaming out now, null between files, with the bytes it has still to feed and the pace it is
    // fed at (0 = unbounded): what [feed] estimates the stream's end from until the receiver's reports measure it.
    @Volatile private var txKey: String? = null

    @Volatile private var txLeft = 0L

    @Volatile private var txPace = 0

    // When the stream under [txKey] last got a write through to the socket: a full stack buffer blocks a write rather
    // than failing it (a suspended iPhone, a stalled controller), and a feed that has not moved in a while is not ending.
    @Volatile private var txWroteAt = 0L

    // When the writer last got a record through to the socket, or a file was queued for it: a file queued with nothing
    // streaming is next only while the writer moves. A writer blocked on a frame's write (a full stack buffer) holds it,
    // and that is a stall like any other ([feed], ADR 2026-10.nsr7). Writer-side only: [touch] also runs on reads.
    @Volatile private var outAt = 0L

    // How many chunks of the stream under [txKey] have been paced: [feed]'s settle lasts until the second.
    @Volatile private var txPaced = 0

    // The stream's measured progress, from the receiver's `blobprog` reports (ADR 2026-10.nsr7). Guarded by [pendingLock].
    private val meter = FeedMeter(now)

    // The in-flight window a windowed feed is clocked by, from the same reports (nsr7's phase 2). Guarded by
    // [pendingLock]. A report, or a frame queued to go out, wakes the writer out of its wait through [wake]: the frame
    // may be our own report on the peer's feed to us, which held there would starve its window (the bluetooth-reviewer's
    // finding: two feeds crossing on one link).
    private val inFlight = FeedWindow()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Whether the stream going now is clocked by its receiver's reports ([FeedWindow]) rather than paced. */
    @Volatile
    var txWindowed = false
        private set

    // When the file streaming in began, on the reader.
    @Volatile private var rxStartedAt = 0L

    // The receive side's reports back to the sender (ADR 2026-10.nsr7, 2026-10.5buf).
    private val rxReports = RxReports(scope, now, windowedReports, { rxFile.takeIf { !closed } }, onRxProgress)

    // How much of the file streaming in was held when its header came: a resume's kept prefix, which [rxEtaMs] does not
    // count as streamed.
    @Volatile private var rxBase = 0L

    // The receive side: one file streams in at a time, reassembled by [intake] (FileIntake, pulled out of here by
    // #116 so a cut attachment keeps its prefix and a resumed one splices onto it). Every call into it holds
    // [rxLock], which [close] takes too, so a teardown from the owner's thread never races the reader on the file
    // being written; the one call that can copy megabytes runs outside it ([beginRxFile]).
    private val rxLock = Any()
    private val intake = FileIntake(cacheDir, partials, nodeId, now, log, landing)

    // Set by [close] under [rxLock]. The reader can still dispatch records already buffered behind the socket, and
    // a header among them must not open a file that nothing will ever clean up.
    @Volatile
    private var closed = false

    /**
     * The file streaming in right now, with the bytes in so far and the total its header declared — exactly "a
     * FILE_HEADER is in and no FILE_END yet". `BlobExchange` reads its key through `MeshTransport.arrivingFiles`
     * to keep a blob whose bytes are already on the way from being asked for again (a 60 s re-ask against a slow
     * BLE transfer bought a second full copy, work item #79); the chat reads its bytes and declared total to draw
     * the attachment's progress (#115). A torn link clears its own state.
     */
    val rxFile: ArrivingFile? get() = intake.arrivingFile

    /**
     * How long the file streaming in has left, at the rate it has come in since its header (ADR 2026-10.nsr7): null with
     * none, with no declared size, or before a byte of it. What an asker weighs before waiting out a peer's other stream.
     */
    fun rxEtaMs(): Long? {
        val f = rxFile ?: return null
        val total = f.total ?: return null
        val got = f.bytes - rxBase
        val ms = now() - rxStartedAt
        if (got <= 0 || ms <= 0) return null
        return (total - f.bytes).coerceAtLeast(0L) * ms / got
    }

    /** The key (the content hash) of the file streaming in right now. */
    val rxKey: String? get() = rxFile?.key

    /** True while a file streams in: a transport's supervisor holds the link rather than tear it down mid-transfer. */
    val rxInProgress: Boolean get() = rxFile != null

    // The (feeding, receiving) pair [onBusy] last reported, as two bits, and the lock that orders the reports. Every
    // change is followed by a [noteBusy] on the thread that made it, so the last report always reads the last state.
    private var busyReported = 0
    private val busyLock = Any()

    // Files queued behind an in-progress file transfer (only one streams at a time).
    private val stash = ArrayDeque<Outbound.FileSend>()

    // key -> how many sends of it are queued or streaming toward this peer, from the enqueue in [sendFile]
    // to the end of [streamFile]. [stash] is the writer loop's own, so this count is what the enqueue side
    // can see; `MeshTransport.fileInFlightTo` reads it so a re-ask never queues a second copy behind one
    // already on the link (#79). Guarded by [pendingLock]; touched on the enqueue and writer threads.
    private val pendingFileKeys = HashMap<String, Int>()
    private val pendingLock = Any()

    // key -> how many of its queued sends the peer withdrew ([cancelQueued]): the writer skips that many when it reaches
    // them. Guarded by [pendingLock].
    private val cancelledKeys = HashMap<String, Int>()

    // Keys whose stream this link finished feeding while it went on feeding this peer. The stack's buffer drains in
    // order, so a file fed before the one still going is ahead of it there and may not have reached the peer yet:
    // when the feed outpaces the drain a whole photo sits in it, and a re-ask crossing it bought a second copy (#121).
    // Emptied when nothing is queued or streaming, the one point at which the link stops vouching for them. Guarded
    // by [pendingLock].
    private val fedWhileBusy = HashSet<String>()

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
        wake.trySend(Unit) // a windowed writer waiting for room drains it first
    }

    /**
     * Enqueue one empty [LinkFraming.Type.KEEPALIVE] (#203). It goes out ahead of a file's next chunk, between chunks like
     * a frame, so an answer never waits behind a stream; [onKeepaliveWritten] says when the write returned. False once the
     * link is closed. Nothing about it counts as data: it neither touches [lastActivityAt] nor moves a feed's clock.
     */
    fun sendKeepalive(): Boolean =
        outbound.trySend(Outbound.Keepalive).isSuccess.also {
            if (it) wake.trySend(Unit) // a windowed writer waiting for room drains it first
        }

    /**
     * Enqueue a store-and-forward custody digest ([LinkFraming.Type.DIGEST]) for this peer. A link holds at most
     * one digest not yet written: while one waits, [ids] replaces its id set where it stands in the queue
     * (ADR 2026-09.tjfb). A digest is a snapshot of custody's live ids, so the newer one says everything the
     * older one would, and a link busy with a back-fill writes one current digest when it gets there instead of
     * one stale digest per re-offer tick, each ahead of every frame queued after it. An idle link writes each
     * digest as it comes, so there the cadence and the bytes are unchanged.
     */
    fun sendDigest(ids: List<String>) {
        if (pendingDigest.getAndSet(ids) == null) {
            outbound.trySend(Outbound.DigestDue)
            wake.trySend(Unit)
        } else {
            metrics.onDigestReplaced()
        }
    }

    /**
     * Enqueue a file (avatar or attachment) for streaming to this peer. Returns whether the enqueue was
     * accepted — the channel is UNLIMITED, so false means the link is already closed (the composite uses it
     * to fall back to another plane instead of silently losing the file).
     */
    fun sendFile(
        file: File,
        meta: FileMeta,
    ): Boolean {
        // Counted before the enqueue so a reader between the two never sees the file as neither queued nor
        // streaming; a refused enqueue (closed link) takes it straight back.
        notePending(meta.key, +1)
        return outbound.trySend(Outbound.FileSend(file, meta)).isSuccess.also {
            if (!it) notePending(meta.key, -1)
            noteBusy()
        }
    }

    /** True while a file is queued on, or streaming over, this link: from its enqueue to its end. */
    val feeding: Boolean get() = synchronized(pendingLock) { pendingFileKeys.isNotEmpty() }

    /** True while a file is queued, streaming out or streaming in: either direction of the link carries a file. */
    val busy: Boolean get() = feeding || rxInProgress

    /**
     * True while a file under [key] is queued on, or streaming over, this link — or was fed in full while the link
     * is still busy with a later file to this peer, which the stack delivers behind it (#121).
     */
    fun hasPendingFile(key: String): Boolean =
        synchronized(pendingLock) {
            pendingFileKeys.containsKey(key) || (key in fedWhileBusy && pendingFileKeys.isNotEmpty())
        }

    /**
     * The copy of [key] this link is feeding ([FileFeed], ADR 2026-10.nsr7): streaming it now, or holding it queued with
     * nothing else streaming, so the writer is about to take it (its pace not known yet, so no ETA) — live while the
     * writer moves, and stalled after [FeedMeter.STALL_MS] with no record through. Null when [key] is
     * not pending, or is queued behind another file: not fed yet. Unlike [hasPendingFile], a file fed in full no longer
     * counts (ADR 2026-10.g24x).
     *
     * Its ETA is measured from the receiver's reports ([onProgress]) once one has come, and estimated from the pace
     * before: until its second chunk is paced, at least [settlingPace], the pace the owner expects the link to settle
     * into (a boost asked as the stream began), which the first chunk, paced before the answer, cannot show. While the
     * owner says the link is still [earning] that boost, the settle lasts until it has, up to [SETTLE_MAX_CHUNKS]: a dark
     * receiver's link took 10 s to earn on 2026-10-07, and read at the base in that gap its 2 MiB feed looked 17 min
     * long and let the next asker beside it. Counted in chunks, not time: the next chunk's pace is read after a `delay`,
     * which stops while the CPU sleeps.
     */
    fun feed(
        key: String,
        settlingPace: Int = 0,
        earning: Boolean = false,
    ): FileFeed? =
        synchronized(pendingLock) {
            if (!pendingFileKeys.containsKey(key)) return@synchronized null
            when (txKey) {
                null -> {
                    val quiet = now() - outAt
                    FileFeed(etaMs = null, live = quiet <= FeedMeter.STALL_MS, recheckMs = (FeedMeter.STALL_MS - quiet).takeIf { it > 0 })
                }

                key -> {
                    val settled = txPaced >= (if (earning) SETTLE_MAX_CHUNKS else SETTLE_CHUNKS)
                    val pace = if (settled) txPace else maxOf(txPace, settlingPace)
                    meter.feed(txLeft, txWroteAt, pace, settled)
                }

                else -> {
                    null
                }
            }
        }

    /**
     * The next chunk is fed at [bytesPerSec]: what [feed] reads, and what a measured rate is held against. The feed's
     * reading changes when its settle ends or its pace moves, so the holder's parked asks are poked then.
     */
    private fun notePaced(bytesPerSec: Int) {
        val moved = txPaced > 0 && bytesPerSec != txPace
        txPace = bytesPerSec
        txPaced++
        synchronized(pendingLock) { meter.paced(bytesPerSec) }
        if (moved || txPaced == SETTLE_CHUNKS || txPaced == SETTLE_MAX_CHUNKS) onFeedChanged()
    }

    /**
     * The writer's pace wait between chunks, marked for the meter (ADR 2026-10.nsr7). A `delay`, which stops while the
     * CPU sleeps: whatever it runs past [ms] the writer slept, and the feed's stall and deadline don't count it.
     */
    private suspend fun pauseFor(ms: Long) {
        synchronized(pendingLock) { meter.pausing(ms) }
        try {
            delay(ms)
        } finally {
            synchronized(pendingLock) { meter.resumed() }
        }
    }

    /**
     * A windowed feed's wait: until a report comes or [ms] runs out, whichever is first. A [paced] wait (the fallback's)
     * is marked for the meter like any pace wait: whatever it runs past [ms] the writer slept, and neither the meter's
     * stall nor the window's silence counts it. A wait for room is not: the receiver owes a report on the window out,
     * so its silence and the feed's stall count while the writer sleeps (the bluetooth-reviewer's finding).
     */
    private suspend fun awaitReport(
        ms: Long,
        paced: Boolean = false,
    ) {
        val began = now()
        if (paced) synchronized(pendingLock) { meter.pausing(ms) }
        try {
            // stretches: a sleeping writer is woken by the report it waits on; what it sleeps past is discounted only
            // when nothing was owed.
            withTimeoutOrNull(ms) { wake.receive() }
        } finally {
            synchronized(pendingLock) {
                if (paced) meter.resumed()
                inFlight.slept(now() - began - ms, now())
            }
        }
    }

    /**
     * A windowed feed's pace wait while its receiver is silent: [ms], cut short only by the receiver's report, which
     * gives it its window back. A queued frame's wake must not end it, or the pace would follow the link's frames (the
     * bluetooth-reviewer's finding).
     */
    private suspend fun awaitPaceOrReport(ms: Long) {
        val until = now() + ms
        while (true) {
            val left = until - now()
            if (left <= 0 || !synchronized(pendingLock) { inFlight.pacing(now()) }) return
            awaitReport(left, paced = true)
        }
    }

    /**
     * The pace a windowed chunk is noted at for the meter: the window's rate, or [fallback] before one. It moves only
     * past a quarter of the last, since a pace move resets the meter's measured rate and the window's moves every report.
     */
    private fun windowedPace(fallback: Int): Int {
        val r = synchronized(pendingLock) { inFlight.rate }.takeIf { it > 0 } ?: fallback
        val last = txPace
        return if (last > 0 && abs(r - last) * PACE_MOVE_DEN < last) last else r
    }

    /** The window's reading, when its noted rate moves: the device oracle beside the receiver's `rx … in` line. */
    private fun logWindow(rate: Int) {
        val (target, queued, rtt) = synchronized(pendingLock) { Triple(inFlight.target(), inFlight.queued(), inFlight.rttMs) }
        log("bt file window rate=$rate target=$target queued=$queued rtt=$rtt → $nodeId")
    }

    /** The peer holds [held] bytes of the file under [key], [ms] after its header arrived (a `blobprog`, ADR 2026-10.nsr7). */
    fun onProgress(
        key: String,
        held: Long,
        ms: Long,
    ) {
        val measured =
            synchronized(pendingLock) {
                val before = meter.measured
                val ours = meter.report(key, held, ms)
                if (ours) inFlight.report(held, ms, now())
                ours && !before && meter.measured
            }
        wake.trySend(Unit) // a windowed writer waiting for room reads it again
        if (measured) onFeedChanged() // its first rate: the feed's ETA is the drain's from now
    }

    /** The share of its pace this link's last measured stream drained, when that fell short; 1.0 else (ADR 2026-10.nsr7). */
    val drainRatio: Double get() = synchronized(pendingLock) { meter.drainRatio }

    /** The drain this link's last measured stream ran at, in bytes a second; 0 before one, or since a PHY step. */
    val lastDrain: Int get() = synchronized(pendingLock) { meter.lastRate }

    /**
     * The link's PHY stepped: what its last stream measured says nothing of the new one. With [newAir] — a step to or
     * from Coded — the window streaming now forgets its drain too ([FeedWindow.forgetLink]).
     */
    fun forgetDrain(newAir: Boolean) {
        synchronized(pendingLock) {
            meter.forgetDrain()
            inFlight.forgetLink(drain = newAir)
        }
    }

    private fun notePending(
        key: String,
        delta: Int,
    ) = synchronized(pendingLock) {
        val n = (pendingFileKeys[key] ?: 0) + delta
        if (n > 0) pendingFileKeys[key] = n else pendingFileKeys.remove(key)
        if (delta > 0) outAt = now()
    }

    /**
     * The peer withdrew its ask for [key] (a `blobcancel`, ADR 2026-10.nsr7): one send of it still queued here, not yet
     * streaming, is skipped when the writer reaches it. True when one was; a stream already going is never cut short.
     * The send stops counting at once: a later ask of the peer's is not refused as a copy already on the link, nor
     * answered SENDING (the bluetooth-reviewer's finding).
     */
    fun cancelQueued(key: String): Boolean =
        synchronized(pendingLock) {
            val queued = (pendingFileKeys[key] ?: 0) - (if (txKey == key) 1 else 0)
            (queued > 0).also {
                if (it) {
                    cancelledKeys.merge(key, 1, Int::plus)
                    notePending(key, -1)
                    if (pendingFileKeys.isEmpty()) fedWhileBusy.clear()
                }
            }
        }

    /**
     * The writer reached a send of [key]: skipped when the peer withdrew it (the mark consumed), else it becomes the
     * stream under [txKey], its meter started — in one hold of [pendingLock], so a cancel never lands between the read
     * and the stream it would have meant.
     */
    private fun beginOrSkip(key: String): Boolean =
        synchronized(pendingLock) {
            val n = cancelledKeys[key]
            if (n != null) {
                if (n > 1) cancelledKeys[key] = n - 1 else cancelledKeys.remove(key)
                return@synchronized false
            }
            txKey = key
            meter.start(key)
            true
        }

    /** [key]'s stream is over: [fed] when its end went to the socket. An idle link vouches for nothing it fed. */
    private fun noteStreamed(
        key: String,
        fed: Boolean,
    ) = synchronized(pendingLock) {
        notePending(key, -1)
        if (fed) fedWhileBusy += key
        if (pendingFileKeys.isEmpty()) fedWhileBusy.clear()
    }

    /** Pokes [onBusy] when the (feeding, receiving) pair moved since the last poke; never under [pendingLock] or [rxLock]. */
    private fun noteBusy() {
        val changed =
            synchronized(busyLock) {
                val now = (if (feeding) FEEDING_BIT else 0) or (if (rxInProgress) RECEIVING_BIT else 0)
                (now != busyReported).also { busyReported = now }
            }
        if (changed) onBusy()
    }

    fun close() {
        readerJob?.cancel()
        writerJob?.cancel()
        rxReports.close()
        outbound.close()
        synchronized(rxLock) {
            closed = true
            intake.cut() // an attachment cut mid-stream keeps its prefix for the next ask (#116)
        }
        synchronized(pendingLock) {
            pendingFileKeys.clear() // whatever was queued died with the link
            cancelledKeys.clear()
            fedWhileBusy.clear()
        }
        noteBusy()
        socket.close()
    }

    /** Mark data-path activity so a supervisor's quiescence window resets (frames + file records only). */
    private fun touch() {
        lastActivityAt = now()
    }

    // --- Read side ---

    private suspend fun readLoop(input: java.io.InputStream) {
        try {
            while (scope.isActive && !closed) {
                val msg = LinkFraming.read(input) ?: break
                when (msg.type) {
                    LinkFraming.Type.FRAME -> {
                        touch()
                        handleFrame(msg.payload)
                    }

                    LinkFraming.Type.FILE_HEADER -> {
                        touch()
                        rxStartedAt = now()
                        rxReports.started(rxStartedAt)
                        beginRxFile(msg.payload)
                        rxBase = rxFile?.bytes ?: 0L
                        noteBusy()
                    }

                    LinkFraming.Type.FILE_CHUNK -> {
                        touch()
                        appendRxFile(msg.payload)
                        noteBusy() // a chunk past the ceiling aborts the file
                        rxReports.chunk()
                    }

                    LinkFraming.Type.FILE_END -> {
                        touch()
                        endRxFile()
                        noteBusy()
                    }

                    LinkFraming.Type.DIGEST -> {
                        touch()
                        handleDigest(msg.payload)
                    }

                    LinkFraming.Type.KEEPALIVE -> {
                        // Its arrival is the whole point: the owner reads it as a probe to answer (#203).
                        onKeepalive()
                    }

                    LinkFraming.Type.HELLO -> {
                        // Legacy record from an older peer. Identity was already consumed at accept, so
                        // ignore any stray.
                    }
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
    @Suppress("BlockingMethodInNonBlockingContext") // only under [start]'s launch(Dispatchers.IO), on an IO worker
    private suspend fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.output)
            while (scope.isActive) {
                val item = nextOutbound() ?: break
                when (item) {
                    is Outbound.Frame -> {
                        writeRecordSafely(out, LinkFraming.Type.FRAME, item.bytes)
                        out.flush()
                        outAt = now()
                        touch()
                    }

                    Outbound.DigestDue -> {
                        if (writePendingDigest(out)) {
                            out.flush()
                            outAt = now()
                            touch()
                        }
                    }

                    Outbound.Keepalive -> {
                        writeKeepalive(out)
                    }

                    is Outbound.FileSend -> {
                        if (beginOrSkip(item.meta.key)) {
                            streamFile(out, item)
                        } else {
                            // Off the `file ` prefix: a holder's `file <KIND>/<hash>` lines count its serves (#79).
                            log("blob withdrawn ${item.meta.kind.wire}/${item.meta.key} by $nodeId before it streamed")
                            onFeedChanged()
                            noteBusy()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            log("write loop ended for $nodeId: ${e.message}")
            callbacks.onLinkDown(nodeId)
        }
    }

    /**
     * Encodes and writes one record, but **drops** (rather than kills the link) a record the codec rejects —
     * e.g. a payload past [LinkFraming.MAX_PAYLOAD_BYTES], whose `require` throws *before* any bytes are
     * written, so the stream stays intact and the loop continues. A real socket [IOException] from the write
     * itself still propagates to end the write loop and bring the link down.
     */
    private fun writeRecordSafely(
        out: OutputStream,
        type: LinkFraming.Type,
        payload: ByteArray,
    ) {
        try {
            LinkFraming.write(out, type, payload)
        } catch (e: IllegalArgumentException) {
            log("dropping oversized $type record for $nodeId: ${e.message}")
        }
    }

    /**
     * Writes one [LinkFraming.Type.KEEPALIVE] and flushes it, ahead of anything written after it, then says so: the
     * owner times the peer's answer from the write's return, not its enqueue (#203).
     */
    private fun writeKeepalive(out: OutputStream) {
        LinkFraming.write(out, LinkFraming.Type.KEEPALIVE)
        out.flush()
        onKeepaliveWritten()
    }

    /** How a stream's feed ended: the pace it last read, and whether its window clocked it (its `file …` line names both). */
    private class FeedEnd(
        val pace: PaceConfig,
        val windowed: Boolean,
    )

    /**
     * Feeds [input]'s chunks to [out] to its end, with live frames between them. A windowed feed writes while its
     * receiver's reports leave room (ADR 2026-10.nsr7's phase 2) and is paced the old way while the receiver is silent;
     * coming back to the pace restarts its window.
     */
    @Suppress("BlockingMethodInNonBlockingContext") // on [writeLoop]'s IO thread, as [writeLoop] says
    private suspend fun feedChunks(
        out: OutputStream,
        input: FileInputStream,
    ): FeedEnd {
        var cfg = PaceConfig() // read before every chunk below; the window rebases onto the first
        val window = PaceWindow(cfg, now())
        val buf = ByteArray(LinkFraming.FILE_CHUNK_BYTES)
        var windowed = false
        var why = "back" // what the stream's `bt file window` line last said: a stream starts windowed
        while (true) {
            // Interleave live frames between chunks — and flush them NOW so they ride the pace gap ahead
            // of the next chunk instead of trailing behind it in the socket buffer.
            if (drainFramesInto(out)) out.flush()
            cfg = pace()
            val wasWindowed = windowed
            val wait =
                synchronized(pendingLock) {
                    inFlight.queueMs = cfg.windowQueueMs
                    inFlight.minChunk = cfg.windowChunkBytes
                    windowed = cfg.windowed && !inFlight.pacing(now())
                    if (windowed) inFlight.waitMs() else 0L
                }
            txWindowed = windowed
            if (wasWindowed && !windowed) window.restart(cfg, now())
            // Logged on each change, a reason changing while paced included (silent, then a two-second reporter).
            val says = if (windowed) "back" else synchronized(pendingLock) { inFlight.pacedWhy() }
            if (cfg.windowed && says != why) log("bt file window $says → $nodeId")
            why = says
            // Waiting for room, frames queued meanwhile go first and the window is read again.
            if (wait > 0) {
                awaitReport(wait)
            } else if (!feedChunk(out, input, buf, cfg, windowed, window)) {
                return FeedEnd(cfg, windowed)
            }
        }
    }

    /** Writes [input]'s next chunk to [out] and paces it (a paced feed); false at its end. */
    @Suppress("LongParameterList", "BlockingMethodInNonBlockingContext") // a chunk's six inputs; [writeLoop]'s IO thread
    private suspend fun feedChunk(
        out: OutputStream,
        input: FileInputStream,
        buf: ByteArray,
        cfg: PaceConfig,
        windowed: Boolean,
        window: PaceWindow,
    ): Boolean {
        val chunk = synchronized(pendingLock) { inFlight.chunkFor(windowed, cfg) }
        val paceNow = if (windowed) windowedPace(cfg.bytesPerSec) else cfg.bytesPerSec
        if (windowed && paceNow != txPace) logWindow(paceNow)
        notePaced(paceNow) // known before this chunk's write, which may block
        if (!windowed) window.rebase(cfg, now())
        val n = input.read(buf, 0, chunk.coerceIn(1, buf.size))
        if (n == -1) return false
        LinkFraming.write(out, LinkFraming.Type.FILE_CHUNK, if (n == buf.size) buf else buf.copyOf(n))
        synchronized(pendingLock) {
            inFlight.wrote(n, now())
            meter.wrote(n)
        }
        txLeft -= n
        txWroteAt = now()
        outAt = txWroteAt
        out.flush() // hand the chunk to the stack so the pace below measures real fed bytes
        touch()
        // On a paced (BLE) link, hold the feed at ~link capacity so the stack TX queue stays shallow:
        // interleaved frames sit near the wire head and the freed ACL budget carries reverse traffic.
        if (!windowed) {
            val paced = window.fed(n, now())
            // A windowed feed paced while its receiver is silent goes back to its window at the receiver's report.
            if (paced > 0) {
                if (cfg.windowed) {
                    awaitPaceOrReport(paced)
                } else {
                    pauseFor(paced)
                }
            }
        }
        return true
    }

    @Suppress("NestedBlockDepth") // open → header → (drain frames → read chunk → write chunk → pace) loop → end
    private suspend fun streamFile(
        out: OutputStream,
        item: Outbound.FileSend,
    ) {
        txInProgress = true // don't let a supervisor tear down mid transfer
        txLeft = 0L
        txPace = 0
        txWroteAt = now()
        txPaced = 0
        // [txKey] and the meter were set by [beginOrSkip].
        val startedAt = now()
        val meta = item.meta
        var fed = false // its end went to the socket
        try {
            // Opened before the header goes out: a source that has gone costs this one file, not the link, and the
            // offset is checked against the bytes actually there.
            val input = openSource(item.file, meta) ?: return
            input.use {
                val length = input.channel.size()
                // A resume (#116): the asker holds the bytes before the offset, so the stream starts there. Up to the
                // whole length — a prefix that is already complete gets an empty tail and its end — and anything
                // else is a whole file. FileInputStream reads from its channel's position.
                val offset = meta.offset.takeIf { it in 1..length } ?: 0L
                if (offset > 0) input.channel.position(offset)
                val bytes = length - offset
                txLeft = bytes
                synchronized(pendingLock) {
                    meter.sized(offset, bytes)
                    inFlight.start(offset, now())
                }
                // The stream's length rides the header so the receiver can show how far it has got (#115), and so
                // does the byte it starts at when it is the rest of a cut transfer.
                val header = FileHeaderWire(meta.kind.wire, meta.key, meta.mime, size = bytes, offset = offset.takeIf { it > 0 })
                LinkFraming.write(out, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(header))
                val end = feedChunks(out, input)
                LinkFraming.write(out, LinkFraming.Type.FILE_END)
                out.flush()
                fed = true
                touch()
                if (offset > 0) metrics.onFileResumedOut()
                // The per-plane feed evidence (this same codec runs over the NAN NDP and BLE L2CAP sockets), with the
                // pace the feed ended on (0 = unbounded). On a paced link it times the hand-off to the stack, not the air:
                // the receiver's `rx … in <ms>ms` line is the drain (#117). A resume's offset rides last, so every parse of the line
                // before it holds — and `file <KIND>/<hash>` still counts the serves (ADR 2026-09.4tx5).
                val from = if (offset > 0) " from $offset" else ""
                // A windowed feed names the rate its window last measured where a paced one names its pace, and says so
                // last, so every parse of the line before it holds.
                val measured = if (end.windowed) synchronized(pendingLock) { inFlight.rate } else 0
                val rate = measured.takeIf { it > 0 } ?: end.pace.bytesPerSec
                val clocked = if (end.windowed) " window" else ""
                log("file ${meta.kind.wire}/${meta.key} ${bytes}B in ${now() - startedAt}ms @$rate → $nodeId$from$clocked")
            }
        } finally {
            txInProgress = false
            txWindowed = false
            // One hold: a cancel never reads this stream as over and its count as still queued, or the reverse.
            synchronized(pendingLock) {
                txKey = null
                meter.end()
                noteStreamed(meta.key, fed)
            }
            onFeedChanged()
            noteBusy()
        }
    }

    /** [file] opened to stream, or null — logged, and the file skipped — when it is gone (an evicted blob, a cleared cache). */
    private fun openSource(
        file: File,
        meta: FileMeta,
    ): FileInputStream? =
        runCatching { FileInputStream(file) }
            .onFailure { log("source of ${meta.kind.wire}/${meta.key} unreadable, not sent → $nodeId: ${it.message}") }
            .getOrNull()

    /**
     * Writes the waiting digest with the newest id set [sendDigest] handed it, and empties the slot, so a digest
     * sent while this one is on the socket queues behind it as a fresh one. False when the slot was already
     * empty, which a marker never meets while [pendingDigest]'s invariant holds.
     */
    private fun writePendingDigest(out: OutputStream): Boolean {
        val ids = pendingDigest.getAndSet(null) ?: return false
        writeRecordSafely(out, LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(ids)))
        return true
    }

    /** Next outbound item: a file stashed during a prior transfer, else the channel head. */
    private suspend fun nextOutbound(): Outbound? = stash.removeFirstOrNull() ?: outbound.receiveCatching().getOrNull()

    /**
     * Write any queued frames now (called between file chunks); stash any files for later. Returns whether any
     * record was actually written to [out] (a stashed file doesn't count), so the caller can flush it ahead of
     * the next chunk.
     */
    private fun drainFramesInto(out: OutputStream): Boolean {
        var wrote = false
        while (true) {
            val item = outbound.tryReceive().getOrNull() ?: break
            when (item) {
                is Outbound.Frame -> {
                    writeRecordSafely(out, LinkFraming.Type.FRAME, item.bytes)
                    wrote = true
                }

                Outbound.DigestDue -> {
                    wrote = writePendingDigest(out) || wrote
                }

                // Flushed with what came before it, between chunks (#203): an answer waits for no stream.
                Outbound.Keepalive -> {
                    writeKeepalive(out)
                }

                is Outbound.FileSend -> {
                    stash.addLast(item)
                }
            }
        }
        return wrote
    }

    // --- Inbound file reassembly (FileIntake, under [rxLock]) ---

    private fun beginRxFile(headerPayload: ByteArray) {
        val resume = synchronized(rxLock) { if (closed) null else intake.header(headerPayload) } ?: return
        // The rest of a cut transfer (#116): copy the kept prefix outside the lock — it can be megabytes, and a
        // teardown must not wait on it — then install it, or give it up if the link closed meanwhile.
        resume.copy()
        val installed =
            synchronized(rxLock) {
                if (closed) {
                    resume.abandon()
                    false
                } else {
                    intake.install(resume)
                }
            }
        if (installed) metrics.onFileResumedIn()
    }

    private fun appendRxFile(chunk: ByteArray) = synchronized(rxLock) { if (!closed) intake.chunk(chunk) }

    private fun endRxFile() {
        val finished = synchronized(rxLock) { if (closed) null else intake.end() } ?: return
        scope.launch(Dispatchers.IO) { intake.finalize(finished)?.let { callbacks.onFile(it) } }
    }

    private sealed interface Outbound {
        class Frame(
            val bytes: ByteArray,
        ) : Outbound

        // A digest is due; its ids are whatever [pendingDigest] holds when the writer gets here.
        data object DigestDue : Outbound

        // One empty keepalive record, a probe or its answer (#203).
        data object Keepalive : Outbound

        class FileSend(
            val file: File,
            val meta: FileMeta,
        ) : Outbound
    }

    companion object {
        private const val FEEDING_BIT = 1
        private const val RECEIVING_BIT = 2

        // A stream's first chunk is paced before the link has answered the boost it asked for: [feed] reads the
        // settling pace until the second chunk's pace is known.
        private const val SETTLE_CHUNKS = 2

        // How long a feed whose link is still earning its boost may be read at the settling pace: about 16 s at the base.
        private const val SETTLE_MAX_CHUNKS = 8

        // How often a file streaming in is reported back to its sender: [RxReports].
        const val PROGRESS_EVERY_MS = RxReports.EVERY_MS
        const val WINDOWED_PROGRESS_EVERY_MS = RxReports.WINDOWED_EVERY_MS

        // A windowed feed's noted pace moves only past 1/PACE_MOVE_DEN of the last ([windowedPace]).
        private const val PACE_MOVE_DEN = 4
    }
}
