package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.FileFeed
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.bluetooth.BleFastRoutePolicy
import app.getknit.knit.mesh.furthestByKey
import app.getknit.knit.mesh.link.FileHeaderWire
import app.getknit.knit.mesh.link.FileIntake
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.LinkCrossings
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.protocol.BlobCancelContent
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The in-process radio behind a [LabNode]: a [MeshTransport] whose links are other [LabTransport]s in the same
 * JVM. What it adds over `FakeLoopTransport` (which the single-SUT rigs use) is a **directed, holdable pipe per
 * link**: a scenario can [hold] what one side sends the other and [release] it in an order of its choosing,
 * which is how "custody serves the two in either order" becomes a deterministic case rather than a lucky one.
 *
 * **Bare, it is a link plane with no fast plane** — the Bluetooth plane before its side channel: every
 * originated frame floods once over `send(wire, null)`, every custody re-serve unicasts over
 * `send(wire, peer)`, and `fastFanout`/`fastSend` are the interface's no-ops.
 *
 * **With [pages] it is the Bluetooth plane as shipped** (ADR 2026-09.sjaa): [hasFastPlane], and the fast path
 * routes through the real `BleFastRoutePolicy` — [fastFanout] is the link copy to every linked peer (through
 * the same pipe as the flood, held and lost with it: one L2CAP stream) plus a page on the [LabPages] every
 * member in range hears, arriving from the frame's *author*; [fastSend] is the linked addressee over its
 * pipe and never a page. A node joins the pages the way it takes a board (`MeshLab.node(pages = …)`).
 *
 * Either way a pipe carries a frame **once**, through the same [LinkCrossings] the phone's transport keeps:
 * the flood copy and the fast path's link copy are one write, a frame that just came in is never handed back
 * over the pipe it came by, and what the memo skipped is in [dupSkipped]. Before it, every `shouldFastFanout`
 * frame crossed a real L2CAP link twice and the far end's SeenSet ate the second.
 */
class LabTransport(
    val nodeId: String,
    /**
     * Where a file *sent to this node* is staged before the blob store ingests it. The real transports write
     * socket bytes into the receiver's own cache; the lab copies the sender's temp file here for the same
     * reason — `MeshBlobStore.saveIncoming` deletes what it reads, and two receivers of one blob handed the
     * sender's single path would race each other for it (the loser drops the blob silently).
     */
    private val stagingDir: File,
    /**
     * This node's store for the prefixes of transfers a cut link left it (#116, ADR 2026-10.wtyc) — the receiving
     * end of a resume, shared with its manager as the phone's one store is shared by both radios.
     */
    internal val partials: PartialBlobs,
    /** The side channel's air this node advertises on and listens to, or null for a node without one. */
    private val pages: LabPages? = null,
    /**
     * The lab's scheduling noise ([LabChaos]), or null. It stretches time at the pipe — the sender descheduled
     * around a delivery, a collector late to the frame it was handed — and never what a pipe means: [send]
     * still returns after the far end has the frame, and a pipe still delivers in order.
     */
    private val chaos: LabChaos? = null,
) : MeshTransport {
    /**
     * Puts this node's scanner on the pages. [LabNode.boot] calls it once the router is collecting: a page
     * heard before that would be emitted into nobody (and counted in by [awaitInboundDrained] for good).
     */
    fun joinPages() {
        pages?.join(this)
    }

    /** The side channel is the fast plane, exactly as `BluetoothMeshTransport` declares it. */
    override val hasFastPlane: Boolean get() = pages != null

    /** Every frame this node heard off a page, as `type id from` in arrival order (own echoes excluded). */
    val heardOnPages = CopyOnWriteArrayList<String>()

    /** One publish of the link set, stamped so a collector's hand-offs can be told apart from each other. */
    private class Links(
        val peers: Set<Peer>,
        val generation: Long,
    )

    private val links = MutableStateFlow(Links(emptySet(), 0L))
    private val generation = AtomicLong()

    /**
     * What `neighbors.value` answers right now — the link set as the pipes have it, set on **both** ends of
     * a link before either end's collectors are woken. The two ends publish one after the other, and the
     * first end's reaction (its profile push, its custody digest) reaches the second end before the second
     * publish on one slow core; the second end's manager then answers through the composite, which asks
     * `neighbors.value` for a child holding the link, finds none and drops the answer on the floor — a serve
     * that only the 60 s re-offer would repeat (`RoomTickPlanesLabTest` under the throttled loop, custody
     * parity never reached inside the await). Collectors still see every publish in order, through [links].
     */
    @Volatile
    private var current: Set<Peer> = emptySet()

    /**
     * Per live collector of [neighbors], the generation it was last handed. A `StateFlow` conflates: a
     * collector busy inside its body is handed only the newest value once it returns, so a link taken down
     * and brought back while `MeshManager.watchNeighbors` is still processing the previous value is a link
     * that never went down to it — no newcomer, no profile push, no digest exchange. On one slow core that
     * window is real; [awaitNeighborsObserved] is how [MeshLab.unlink] and [LabNode.restart] wait for every
     * collector to have been handed the departure before the link comes back.
     */
    private val handed = ConcurrentHashMap<Any, Long>()

    /**
     * Per live collector of [neighbors], the generation whose body it last returned from — [handed]'s other end, read
     * by [awaitNeighborsHandled]. A direct collector (`MeshManager.watchNeighbors`) returns from `emit` only once its
     * newcomer hooks have; one behind a `combine` returns at the combine's buffer, so for it the record is early.
     */
    private val handledLinks = ConcurrentHashMap<Any, Long>()

    /**
     * The link set, with the `StateFlow` contract kept (the current value on subscribe, distinct-until-changed,
     * conflated) — only the hand-off to each collector, and its return, is recorded on the way through.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class) // value, replayCache and collect are the whole contract
    override val neighbors: StateFlow<Set<Peer>> =
        object : StateFlow<Set<Peer>> {
            override val value: Set<Peer> get() = current
            override val replayCache: List<Set<Peer>> get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<Set<Peer>>): Nothing {
                val token = Any()
                var last: Set<Peer>? = null
                try {
                    links.collect { published ->
                        handed[token] = published.generation
                        // A collector slow to act on what it was handed; conflation is the StateFlow's own, as on
                        // a phone. Recorded first, so [awaitNeighborsObserved] sees a brand-new collector at once.
                        chaos?.jitter()
                        if (published.peers != last) {
                            last = published.peers
                            collector.emit(published.peers)
                        }
                        handledLinks[token] = published.generation
                    }
                } finally {
                    handed.remove(token)
                    handledLinks.remove(token)
                }
            }
        }

    override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()

    // Under chaos each collector may be late to what it was handed — the router busy on the frame before.
    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = BUFFER)

    /**
     * Frames handed to [inbound] against frames its collector has finished. A link frame is counted in [received]
     * before its crossing is logged on the sender, so a scenario that waits on that line and then on
     * [awaitInboundDrained] never sees the frame logged but not yet counted; a page is counted in [hearPage].
     */
    private val framesIn = AtomicLong()
    private val framesHandled = AtomicLong()

    /**
     * The inbound frames, counted out as the collector *finishes* each one: a direct collector (the router's
     * `collect { handleInbound(…) }`) returns from `emit` only once `handleInbound` has, so [awaitInboundDrained]
     * means "every frame this node was handed has been handled" — its custody writes and the sends it
     * provoked included. Behind a composite's `merge` (a node with a board) `emit` returns at the channel, so
     * there the signal is early; [LabNode.restart] and [MeshLab.unlink] keep their settle for that case, which
     * a chaos stall can outlast: a board scenario that re-links right after a delivery awaits the event first.
     */
    override val inbound: Flow<InboundFrame> =
        flow {
            _inbound.asSharedFlow().lagged().collect {
                emit(it)
                framesHandled.incrementAndGet()
            }
        }

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = BUFFER)
    override val incomingFiles: Flow<ReceivedFile> = _incomingFiles.asSharedFlow().lagged()

    // Files handed to this node's consumer that it has not taken yet ([fileTaken]), by "<from>|<key>": the lab's twin
    // of a transport's `LandingFiles` (#138), so a pipe torn down meanwhile leaves them standing. Under chaos the
    // collector is late to them, as a phone's is behind the file it is storing.
    private val landing = ConcurrentHashMap<String, ArrivingFile>()

    /** [file] from [from] is on its way to this node's consumer: it counts as arriving until taken. */
    private fun markLanding(
        from: String,
        key: String,
        file: ArrivingFile,
    ) {
        landing["$from|$key"] = file
    }

    /** Hands [received] to [target]'s consumer, landing until it is taken — every file a pipe delivers. */
    private suspend fun deliverFile(
        target: LabTransport,
        received: ReceivedFile,
    ) {
        target.markLanding(nodeId, received.key, ArrivingFile(received.key, File(received.path).length(), total = null))
        target._incomingFiles.emit(received)
    }

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = BUFFER)

    // Digests a peer advertised to this node against digests its collector has answered: [awaitDigestsAnswered].
    private val digestsIn = AtomicLong()
    private val digestsAnswered = AtomicLong()

    /**
     * The custody digests peers advertised, counted out as the collector *finishes* each one, as [inbound] counts
     * frames: the manager's `collect { forwardSync.onDigest(…) }` returns from `emit` only once its reply (every
     * frame the peer lacked) is sent. Early behind a composite's `merge`, as [inbound] is.
     */
    override val incomingDigests: Flow<ReceivedDigest> =
        flow {
            _incomingDigests.asSharedFlow().lagged().collect {
                emit(it)
                digestsAnswered.incrementAndGet()
            }
        }

    private fun <T> Flow<T>.lagged(): Flow<T> = chaos?.let { c -> onEach { c.jitter() } } ?: this

    /**
     * Outbound pipes, keyed by the far node id. Concurrent on purpose: the scenario thread links and unlinks
     * while the stacks' `Dispatchers.Default` workers are inside [send] — a plain map iterated on one thread
     * and written on another throws, or hands the sender a stale view of the links.
     */
    private val pipes = ConcurrentHashMap<String, Pipe>()

    /**
     * One direction of a link: frames from this transport toward [target], held while [holding], and dropped
     * on the floor when [lossy] says so (a radio that lost the packet — nothing is parked, nothing is told).
     */
    private class Pipe(
        val target: LabTransport,
    ) {
        // Written and read under the `held` lock, so a frame is either parked before a release takes the batch
        // or delivered after it — never parked into a batch already taken, with no one left to release it.
        var holding = false
        val held = mutableListOf<WireEnvelope>()

        /** Parks [wire] if this pipe is holding; false means deliver it. One lock with [release]'s batch. */
        fun park(wire: WireEnvelope): Boolean =
            synchronized(held) {
                if (holding) held += wire
                holding
            }

        @Volatile
        var lossy: (WireEnvelope) -> Boolean = { false }

        // A file whose header has crossed and whose bytes have not: staged at the receiver, parked here. The
        // receiver reports its key as arriving and the sender as in flight, exactly as a slow BLE link would.
        // Under the `heldFiles` lock, for the reason [holding] is under `held`'s.
        var holdingFiles = false

        // With [holdingFiles], how many bytes of the blob a file sent now crosses before it parks ([streamFiles]);
        // null parks it with none, as [holdFiles] always has.
        var streamUntil: Long? = null

        // With [holdingFiles], a file sent now parks fed but unseen ([stackFiles]): neither end reports it.
        var stacking = false

        // Set by [disconnect] under the `heldFiles` lock: a stream begun on a pipe already torn down is cut at once.
        var closed = false
        val heldFiles = mutableListOf<HeldFile>()

        /** Parks [file] if this pipe is holding files; false means land it. One lock with [releaseFiles]. */
        fun parkFile(file: ReceivedFile): Boolean =
            synchronized(heldFiles) {
                if (holdingFiles) heldFiles += if (stacking) HeldFile.Stacked(file) else HeldFile.Whole(file)
                holdingFiles
            }
    }

    /**
     * A file parked on a pipe: one staged whole ([holdFiles]), one streaming into the receiver's intake, or one fed
     * into the sender's stack and not yet seen by either end ([stackFiles]).
     */
    private sealed interface HeldFile {
        val key: String

        /** What the receiver reports while it waits: nothing in yet, or the stream's real count. */
        val arriving: ArrivingFile?

        /** Whether the sender still counts it as queued or streaming ([fileInFlightTo]). */
        val inFlight: Boolean get() = true

        class Whole(
            val file: ReceivedFile,
        ) : HeldFile {
            override val key: String get() = file.key
            override val arriving: ArrivingFile get() = ArrivingFile(key, bytes = 0, total = null)
        }

        /** Fed in full and sitting in the sender's stack behind what is arriving: the sender is done with it (#121). */
        class Stacked(
            val file: ReceivedFile,
        ) : HeldFile {
            override val key: String get() = file.key
            override val arriving: ArrivingFile? get() = null
            override val inFlight: Boolean get() = false
        }

        class Streaming(
            val stream: FileStream,
        ) : HeldFile {
            override val key: String get() = stream.key
            override val arriving: ArrivingFile? get() = stream.arriving
        }
    }

    /**
     * One file crossing a pipe into the receiver's real [FileIntake] — the production header, splice, ceiling and
     * cut (#116) — fed from the sender's bytes rather than a socket. Only the slicing is the lab's.
     */
    private class FileStream(
        private val meta: FileMeta,
        private val intake: FileIntake,
        private val source: ByteArray,
        private val offset: Long,
    ) {
        val key: String get() = meta.key
        private var fed = offset

        val arriving: ArrivingFile? get() = intake.arrivingFile

        /** The header crosses — a resume is spliced onto the receiver's kept prefix, as a link's reader does it. */
        fun begin() {
            val size = source.size - offset
            val header = FileHeaderWire(meta.kind.wire, meta.key, meta.mime, size = size, offset = offset.takeIf { it > 0 })
            intake.header(LinkFraming.encodeFileHeader(header))?.let { resume ->
                resume.copy()
                intake.install(resume)
            }
        }

        /** Chunks cross until the receiver holds [until] bytes of the blob (or all of them). */
        fun feedTo(until: Long) {
            val end = minOf(until, source.size.toLong())
            while (fed < end) {
                val n = minOf(LinkFraming.FILE_CHUNK_BYTES.toLong(), end - fed).toInt()
                intake.chunk(source.copyOfRange(fed.toInt(), fed.toInt() + n))
                fed += n
            }
        }

        /** The rest crosses and the end with it: the received file, or null when the intake refused it. */
        fun land(): ReceivedFile? {
            feedTo(source.size.toLong())
            return intake.end()?.let { intake.finalize(it) }
        }

        /** The link dropped under it: the receiver keeps what came, for the next ask. */
        fun cut() = intake.cut()
    }

    /** Every frame a lossy pipe dropped, for a scenario that asserts on what the air ate. */
    val lost = CopyOnWriteArrayList<WireEnvelope>()

    /**
     * A `blobreq` that crossed one of this node's pipes: to whom, for which hash, from which byte (#116), and whether
     * it asked for the bytes over Wi-Fi Aware only (ADR 2026-10.js2p).
     */
    data class BlobAsk(
        val to: String,
        val hash: String,
        val offset: Long?,
        val fastOnly: Boolean = false,
        // The ask's pull budget and deadline, null when it carried none (ADR 2026-10.2drc).
        val pull: Int? = null,
        val until: Long? = null,
    )

    /** Every `blobreq` this node sent that reached its peer, in arrival order — what an ask said, offset included. */
    val blobAsks = CopyOnWriteArrayList<BlobAsk>()

    /**
     * Every `blobcancel` this node sent that reached its peer, as (to, hash), recorded on arrival — so a scenario that
     * asserts none was sent reads it once the peer could have heard one, not when the sender's counter moved.
     */
    val blobCancels = CopyOnWriteArrayList<Pair<String, String>>()

    /** A `blobhave` that crossed one of this node's pipes: to whom, and the hashes it named (ADR 2026-10.6e5t). */
    data class BlobQuery(
        val to: String,
        val hashes: List<String>,
    )

    /** Every `blobhave` this node sent that reached its peer, in arrival order. */
    val blobQueries = CopyOnWriteArrayList<BlobQuery>()

    /** A `blobhas` that crossed one of this node's pipes: to whom, and each hash's state. */
    data class BlobAnswer(
        val to: String,
        val states: Map<String, Int>,
    )

    /** Every `blobhas` this node sent that reached its peer, in arrival order. */
    val blobAnswers = CopyOnWriteArrayList<BlobAnswer>()

    /**
     * The capabilities this node's link advertises to whoever it links with — its HELLO. Every build answers a holder
     * query, so it carries `CAP_BLOB_HAVE` unless the node plays one that predates it (`LabLimits.answersHolderQueries`).
     * Read when a link comes up, so set it before linking.
     */
    @Volatile
    var advertises: Long = 0L

    /** The plane a scenario declares a file from each peer would ride ([bulkRouteTo]); the lab has no Wi-Fi Aware. */
    val bulkRoutes = ConcurrentHashMap<String, BulkRoute>()

    /** Peers a scenario declares as linked on a slow (Coded) link ([slowLinkTo]). */
    val slowLinks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Peers a scenario declares as linked over a link near its floor ([liveLinkFading]). */
    val fadingLinks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How many files a scenario declares this node is feeding ([feedingLoad]) — a `blobhas` answer's load. */
    @Volatile
    var feeding: Int = 0

    /**
     * A holder on a build that predates the resume (#116): whatever offset the ask named, it streams the whole file
     * with no offset in the header — what an older Android build or the iOS port before its companion change does.
     */
    @Volatile
    var ignoreOffsets = false

    /** A holder whose bytes are wrong (a bug, a bad disk): the first byte of every stream it sends is flipped. */
    @Volatile
    var corruptFiles = false

    /** Every frame this transport handed a peer, as `to type id` in send order — the diagnosis of "who served that". */
    val sent = CopyOnWriteArrayList<String>()

    /**
     * Every file this transport handed a peer, as `to kind key` in send order — the phone's `file …` log line,
     * the oracle for "one copy per (hash, link)" (#79). A held file is recorded when it is sent, not released. A
     * resume ends ` from <offset>` (#116), as the phone's line does, so an exact match still counts whole copies.
     */
    val files = CopyOnWriteArrayList<String>()

    /**
     * Whom this node advertised its custody digest to, in send order — the newcomer hooks run it last, so a
     * scenario that re-links "as the 60 s re-offer would" waits on it to know the whole batch has run.
     */
    val digestsSent = CopyOnWriteArrayList<String>()

    /** Every write [crossings] skipped, as `to key via`: the second copy of a frame for one pipe, or an echo. */
    val dupSkipped = CopyOnWriteArrayList<String>()

    /** How many writes of [key] (`FrameKey`) toward [nodeId] [crossings] has skipped, by either path. */
    fun skipsToward(
        nodeId: String,
        key: String,
    ): Int = dupSkipped.count { it.startsWith("${nodeId.take(NODE_ID_CHARS)} $key ") }

    /**
     * One write per pipe per frame, either way, as `BluetoothMeshTransport` keeps it, and on the node's [clock], which
     * its router's seen window reads too. `LinkCrossings` holds a frame back only while the far end's router would drop
     * it as seen, so the two windows must lapse together. On a phone they are two clocks that run together (the memo's
     * `elapsedRealtime`, the router's wall clock); in the lab a calendar jump is time passing for both. On the wall
     * clock, which a jump does not move, a frame that crossed a live pipe could never cross it again past the window.
     * The memo stamps on this node's clock and the far router reads its own, so they lapse together only while the two
     * nodes' skews hold still: a `LabClock.skew` changed with links up needs a re-link before the jump.
     */
    private val crossings = LinkCrossings(clock = { clock() })

    /**
     * Links both ways so the two become neighbors, as a data path coming up does. With [publish] false the
     * pipes exist but neither side's `neighbors` moves yet — [publishNeighbors] does that — so a topology of
     * several links can come up at once (see [MeshLab.linkAll]). [lossy] is this side's [lossy] predicate from
     * the pipe's first frame on — set after the link, a frame the far end is already sending has a window.
     */
    fun connect(
        other: LabTransport,
        publish: Boolean = true,
        lossy: (WireEnvelope) -> Boolean = { false },
        arm: () -> Unit = {},
    ) {
        if (other.nodeId == nodeId) return
        crossings.forget(other.nodeId)
        other.crossings.forget(nodeId)
        pipes[other.nodeId] = Pipe(other).also { it.lossy = lossy }
        other.pipes[nodeId] = Pipe(this)
        // The pipes exist and neither end lists the link yet — not in `neighbors.value`, not to a collector — so what
        // [arm] holds or streams is in place before anything can send over it (`MeshLab.link`).
        arm()
        // Both ends answer `neighbors.value` with the new link before either end's collectors run (see [current]).
        presentNeighbors()
        other.presentNeighbors()
        if (publish) {
            publishNeighbors()
            other.publishNeighbors()
        }
    }

    /** Publishes the current link set as `neighbors`, which is what starts the profile push on the far side. */
    fun publishNeighbors() = refreshNeighbors()

    /**
     * Unlinks both ways (out of range). Held frames on that link are dropped, as a torn-down link drops them; a file
     * streaming across it either way is cut, and its receiver keeps what came — `FramedLink.close()` (#116).
     */
    fun disconnect(other: LabTransport) {
        listOfNotNull(pipes.remove(other.nodeId), other.pipes.remove(nodeId)).forEach { cutStreams(it) }
        crossings.forget(other.nodeId)
        other.crossings.forget(nodeId)
        presentNeighbors()
        other.presentNeighbors()
        refreshNeighbors()
        other.refreshNeighbors()
    }

    /** Unlinks from every peer and returns them, so a caller can wait for each to observe the departure. */
    fun disconnectAll(): List<LabTransport> = pipes.values.map { it.target }.onEach { disconnect(it) }

    /**
     * Suspends until every live collector of [neighbors] has been handed the link set as last published — so
     * a departure just published cannot be conflated away by a link the caller brings up next. Fails, rather
     * than waits forever, on a collector that never wakes.
     */
    suspend fun awaitNeighborsObserved() {
        val target = links.value.generation
        val seen = withTimeoutOrNull(MeshLab.AWAIT_MS) { while (handed.values.any { it < target }) delay(1) } != null
        check(seen) { "$nodeId: a neighbor collector never observed link publish #$target (${handed.values.sorted()})" }
    }

    /**
     * Suspends until every live collector of [neighbors] has *returned* from the link set as last published —
     * [awaitNeighborsObserved] proves the hand-off, this proves the body ran to its end. For this node's manager
     * that is every newcomer hook `MeshManager.watchNeighbors` runs on a link-up: the profile push, the digest, the
     * key and tick re-offers and, last, the group-seed flush, which sends a seed only once one exists. A scenario that
     * mints a seed next and needs the link-up to have sent none of its own waits here. Early behind a composite's
     * `combine` (a node with a board), as [awaitInboundDrained] is behind its `merge`.
     */
    suspend fun awaitNeighborsHandled() {
        val target = links.value.generation
        val done =
            withTimeoutOrNull(MeshLab.AWAIT_MS) {
                while (handed.keys.any { (handledLinks[it] ?: -1L) < target }) delay(1)
            } != null
        check(done) { "$nodeId: a neighbor collector never returned from link publish #$target (${handledLinks.values.sorted()})" }
    }

    /**
     * Suspends until this node's router has finished handling every frame it was handed ([inbound]):
     * `handleInbound` has returned, so `onDeliver`'s inline work is done — its custody row, and a DM's receipt
     * sealed on a link plane. Not what it launches: a relay fires 0–150 ms later, an escalated group or room
     * tick after `AckSync`'s debounce. [LabNode.restart] waits on it for every peer of the node going down, and
     * [MeshLab.unlink] for both ends, so a frame a node is still handling when the link dies is custodied before
     * the next link's digest exchange reads its store. Fails, rather than waits forever, on a collector that never finishes.
     */
    suspend fun awaitInboundDrained() {
        val drained =
            withTimeoutOrNull(MeshLab.AWAIT_MS) { while (framesHandled.get() < framesIn.get()) delay(1) } != null
        check(drained) { "$nodeId: the router never finished its inbound frames (${framesHandled.get()} of ${framesIn.get()})" }
    }

    /**
     * Suspends until this node has answered every custody digest a peer advertised to it: each reply's frames are
     * sent, and a send returns once the far end holds the frame, so the far end's [awaitInboundDrained] after this
     * means the exchange has landed. A digest handed to a node whose manager is not collecting is never answered,
     * and this fails on it rather than waits forever: not for a node stopped or restarted since it booted.
     */
    suspend fun awaitDigestsAnswered() {
        val answered =
            withTimeoutOrNull(MeshLab.AWAIT_MS) { while (digestsAnswered.get() < digestsIn.get()) delay(1) } != null
        check(answered) { "$nodeId: the manager never answered its custody digests (${digestsAnswered.get()} of ${digestsIn.get()})" }
    }

    /** From now on, frames this node sends [to] are parked instead of delivered — until [release]. */
    fun hold(to: LabTransport) {
        val pipe = pipe(to)
        synchronized(pipe.held) { pipe.holding = true }
    }

    /**
     * From now on, a frame this node sends [to] for which [drop] answers true is lost — not parked, not
     * delivered, recorded in [lost]. The default answers false, so `lossy(to)` restores a clean link. Judged
     * before [hold], so a held pipe still loses what it would have lost.
     */
    fun lossy(
        to: LabTransport,
        drop: (WireEnvelope) -> Boolean = { false },
    ) {
        pipe(to).lossy = drop
    }

    /** What is parked for [to] right now, in send order — for a scenario that waits for a frame to be held. */
    fun held(to: LabTransport): List<WireEnvelope> = pipe(to).let { synchronized(it.held) { it.held.toList() } }

    /**
     * From now on, a file this node sends [to] is staged and parked with its header across — the receiver
     * sees it [arrivingFiles], the sender [fileInFlightTo] — until [releaseFiles]. The slow BLE transfer of
     * work item #79, made a state a scenario can hold a re-ask against.
     */
    fun holdFiles(to: LabTransport) {
        val pipe = pipe(to)
        synchronized(pipe.heldFiles) {
            pipe.holdingFiles = true
            pipe.streamUntil = null
            pipe.stacking = false
        }
    }

    /**
     * From now on, a file this node sends [to] parks fed but unseen until [releaseFiles]: the receiver does not see it
     * [arrivingFiles] and the sender no longer counts it [fileInFlightTo]. A holder whose feed outpaced the drain, so
     * that a whole photo sits in its Bluetooth stack behind the one streaming in, as a holder that cannot see its own
     * stack reads it (an older build, the iOS port). Arm [holdFiles] first, so something is arriving ahead of it, as
     * the stack has it (#121).
     */
    fun stackFiles(to: LabTransport) {
        val pipe = pipe(to)
        synchronized(pipe.heldFiles) {
            pipe.holdingFiles = true
            pipe.streamUntil = null
            pipe.stacking = true
        }
    }

    /**
     * From now on, a file this node sends [to] crosses into the receiver's real `FileIntake` until the receiver holds
     * [untilBytes] of the blob — its header and those bytes across — and parks there: the receiver reports it
     * arriving with that count, the sender in flight, until [releaseFiles] lands the rest or [disconnect] cuts it
     * (the receiver then keeps the prefix, as a phone does when a link drops mid-transfer, #116). Arm it before the
     * link publishes (`MeshLab.link`'s `arm`) when the send is a link-up's re-ask.
     */
    fun streamFiles(
        to: LabTransport,
        untilBytes: Long,
    ) {
        val pipe = pipe(to)
        synchronized(pipe.heldFiles) {
            pipe.holdingFiles = true
            pipe.streamUntil = untilBytes
            pipe.stacking = false
        }
    }

    /** The keys of the files parked for [to] right now, in send order. */
    fun heldFiles(to: LabTransport): List<String> = pipe(to).let { synchronized(it.heldFiles) { it.heldFiles.map { f -> f.key } } }

    /** Lands everything parked for [to] and stops holding files (frames held by [hold] are untouched). */
    suspend fun releaseFiles(to: LabTransport) {
        val pipe = pipe(to)
        // Closed until what parked has landed, as [release] keeps it: a file sent meanwhile lands after the batch.
        while (true) {
            val batch =
                synchronized(pipe.heldFiles) {
                    pipe.heldFiles.toList().also {
                        // Landing before they stop being parked, in the lock a reader takes: never in neither (#138).
                        it.forEach { held -> pipe.target.markLanding(nodeId, held.key, held.arriving ?: ArrivingFile(held.key, 0, null)) }
                        pipe.heldFiles.clear()
                        if (it.isEmpty()) {
                            pipe.holdingFiles = false
                            pipe.streamUntil = null
                            pipe.stacking = false
                        }
                    }
                }
            if (batch.isEmpty()) return
            batch.forEach { held ->
                val file =
                    when (held) {
                        is HeldFile.Whole -> held.file
                        is HeldFile.Stacked -> held.file
                        is HeldFile.Streaming -> held.stream.land()
                    }
                if (file == null) pipe.target.fileTaken(nodeId, held.key) else deliverFile(pipe.target, file)
            }
        }
    }

    /**
     * What every linked sender has parked toward this node: the headers are in, the bytes are not — or, for a
     * streamed file, some of them are, and the receiver's intake says how many.
     */
    override fun arrivingFiles(): Map<String, ArrivingFile> =
        (
            pipes.values.flatMap { link ->
                link.target.pipes[nodeId]?.let { toMe -> synchronized(toMe.heldFiles) { toMe.heldFiles.mapNotNull { it.arriving } } }
                    ?: emptyList()
            } + landing.values // parked, then landing: the order a file moves in, read so it is never in neither
        ).furthestByKey()

    override fun fileTaken(
        fromNodeId: String,
        key: String,
    ) {
        landing.remove("$fromNodeId|$key")
    }

    override fun bulkRouteTo(nodeId: String): BulkRoute = bulkRoutes[nodeId] ?: BulkRoute.None

    override fun slowLinkTo(nodeId: String): Boolean = nodeId in slowLinks

    override fun liveLinkFading(nodeId: String): Boolean = nodeId in fadingLinks

    override fun feedingLoad(nodeId: String): Int = feeding

    /** A linked sender's file streaming in to this node: one parked toward us with its header across. */
    override fun fileArrivingFrom(nodeId: String): Boolean {
        val toMe = pipes[nodeId]?.target?.pipes?.get(this.nodeId) ?: return false
        return synchronized(toMe.heldFiles) { toMe.heldFiles.any { it.arriving != null } }
    }

    /** Queued on the link: a file parked toward [nodeId] under [key]. An unheld lab file lands at once. */
    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = pipes[nodeId]?.let { p -> synchronized(p.heldFiles) { p.heldFiles.any { it.key == key && it.inFlight } } } ?: false

    /**
     * The lab's clock, for [crossings]' window and for how long a held pipe's feed has gone unfed ([fileFeed]); [MeshLab]
     * sets the node's.
     */
    var clock: () -> Long = System::currentTimeMillis

    /**
     * How long the head of a held pipe reads as a live feed once someone first looked at it ([fileFeed]), before it
     * counts as stalled: a held pipe is a feed whose receiver reports nothing (ADR 2026-10.nsr7). [MeshLab] sets it
     * from `LabLimits.feedStallMs`.
     */
    var feedStallMs: Long = MeshLab.FEED_STALL_MS

    // When each held head was first read as a feed: its stall window counts from there. By identity, under itself.
    private val feedSeenAt = java.util.IdentityHashMap<Any, Long>()

    /**
     * Fed on a shared-budget link: the first file parked toward [nodeId] that its sender still feeds is [key]'s, on a
     * pipe the scenario has not declared Wi-Fi Aware ([bulkRoutes]) or Coded ([slowLinks]) — the Bluetooth link a phone
     * splits its pace over (ADR 2026-10.g24x). A link feeds one file at a time, so a copy parked behind another is
     * queued, not fed, as `FramedLink.feed` reads it. No ETA (the lab has no rates), and live until [feedStallMs] after
     * it was first read: nothing reports its progress.
     */
    override fun fileFeed(
        nodeId: String,
        key: String,
    ): FileFeed? {
        if (bulkRouteTo(nodeId) == BulkRoute.Linked || slowLinkTo(nodeId)) return null
        val p = pipes[nodeId] ?: return null
        val head = synchronized(p.heldFiles) { p.heldFiles.firstOrNull { it.inFlight } } ?: return null
        if (head.key != key) return null
        val seen = synchronized(feedSeenAt) { feedSeenAt.getOrPut(head) { clock() } }
        return FileFeed(etaMs = null, live = clock() - seen < feedStallMs)
    }

    /**
     * Delivers everything parked for [to], in the order [reorder] returns (default: as sent), and stops holding
     * — unless [keepHolding], which parks what the far side sends back in answer to the batch as well. A
     * scenario that calls `release` and then `hold` again has a gap between the two in which a delivered frame's
     * whole answer can cross (a key request and the served key, on one slow core: `RestartLabTest` found the
     * key it meant to strand already delivered). Returns the batch as ordered, for a scenario that wants to
     * assert on the frames themselves.
     *
     * The pipe stays closed while the batch is delivered: a frame this node sends meanwhile parks behind it,
     * and is delivered after it, in send order, before the pipe opens — a pipe delivers in order, releases
     * included, so nothing sent during a release can overtake the batch (or undo a [reorder]).
     */
    suspend fun release(
        to: LabTransport,
        keepHolding: Boolean = false,
        reorder: (List<WireEnvelope>) -> List<WireEnvelope> = { it },
    ): List<WireEnvelope> {
        val pipe = pipe(to)
        val ordered = reorder(takeHeld(pipe, open = false))
        deliverHeld(pipe, ordered)
        if (keepHolding) return ordered
        while (true) {
            val more = takeHeld(pipe, open = true)
            if (more.isEmpty()) return ordered
            deliverHeld(pipe, more)
        }
    }

    /** Takes what is parked; with [open], an empty take also stops holding, under the same lock. */
    private fun takeHeld(
        pipe: Pipe,
        open: Boolean,
    ): List<WireEnvelope> =
        synchronized(pipe.held) {
            pipe.held.toList().also {
                pipe.held.clear()
                if (open && it.isEmpty()) pipe.holding = false
            }
        }

    private suspend fun deliverHeld(
        pipe: Pipe,
        frames: List<WireEnvelope>,
    ) {
        // Two held copies of one frame (the flood's and the fast path's) are one write here, as on the phone.
        frames.forEach { if (crosses(pipe, FrameKey.ofSigned(it), VIA_LINK)) pipe.target.deliver(it, nodeId, VIA_LINK) }
    }

    /**
     * Whether the node's manager is collecting [inbound], [incomingFiles] and [incomingDigests] yet. Each is a
     * [MutableSharedFlow] with no replay that drops what is emitted before its collector subscribes, and each
     * collector is its own `launch` on the session dispatcher (`MeshManager.start` subscribes the files and
     * digests after the profile seed) — so a link brought up too early loses the profile push, a peer's first
     * digest (nothing re-offers it for 60 s) or a first-contact avatar. [MeshLab.node] waits on this.
     */
    val collecting: Boolean
        get() =
            _inbound.subscriptionCount.value > 0 &&
                _incomingFiles.subscriptionCount.value > 0 &&
                _incomingDigests.subscriptionCount.value > 0

    override fun start() = Unit

    override fun stop() = Unit

    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        // One lag before the pipes are read, none between them: a phone's `send` never suspends mid-flood, so a
        // cancelled caller cannot leave half the links served, and a pipe torn down during the lag is not written.
        chaos?.jitter()
        val targets = if (to == null) pipes.values.toList() else listOfNotNull(pipes[to.nodeId])
        val key = FrameKey.ofSigned(wire)
        targets.forEach { pipe ->
            when {
                pipe.lossy(wire) -> lost += wire

                // A held frame is judged by the memo at [release], not here.
                pipe.park(wire) -> Unit

                !crosses(pipe, key, VIA_LINK) -> Unit

                else -> pipe.target.deliver(wire, nodeId, VIA_LINK)
            }
        }
        // The far end has the frame; the sender's own bookkeeping after `send` returns may come late.
        chaos?.jitter()
    }

    /**
     * The phone's `writeOnce`: true when [key] is new to this pipe (write it), false when it already crossed
     * either way (skip it, recorded in [dupSkipped]). Judged after `lossy`, which models a radio that lost the
     * packet rather than a link that took it, and after `hold`: a held frame is judged when [release] delivers
     * it, because a scenario may drop it from the batch (the air lost it too) and a frame the far end never
     * got must not count as crossed — the served key `KeyExchangeLabTest` waits for is the same signed bytes
     * as the profile the hold swallowed. An unsigned frame (null key) always crosses, as on the phone.
     */
    private fun crosses(
        pipe: Pipe,
        key: String?,
        via: String,
    ): Boolean {
        if (key == null || crossings.firstCrossing(pipe.target.nodeId, key)) return true
        dupSkipped += "${pipe.target.nodeId.take(NODE_ID_CHARS)} $key via=$via"
        return false
    }

    /**
     * The fan-out arm of the fast path, routed here only while [pages] makes this a fast plane: the real
     * `BleFastRoutePolicy.fanout` — the link copy to every linked peer over its pipe (held and lost as the
     * flood is, one stream), plus a page when another member is in range. Non-suspending like
     * `FramedLink.send`; the pipe's buffer is the phone's socket buffer, so a full one is a lab bug, not a drop.
     */
    override fun fastFanout(wire: WireEnvelope) {
        val pages = pages ?: return
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        val route = BleFastRoutePolicy.fanout(env, pipes.keys.toSet(), sideAvailable = pages.others(this).isNotEmpty())
        val key = FrameKey.of(wire, env)
        route.linkTargets.forEach { pipes[it]?.let { pipe -> offer(pipe, wire, key) } }
        if (route.side != null) pages.air(this, wire, env)
    }

    /** The targeted arm: the linked addressee over its pipe, never a page (`BleFastRoutePolicy.send`). */
    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (pages == null) return
        val key = FrameKey.ofSigned(wire)
        BleFastRoutePolicy.send(to.nodeId, pipes.keys.toSet()).linkTargets.forEach { pipes[it]?.let { pipe -> offer(pipe, wire, key) } }
    }

    /** [send]'s pipe discipline for a non-suspending caller — the fast path's link copy. */
    private fun offer(
        pipe: Pipe,
        wire: WireEnvelope,
        key: String?,
    ) {
        chaos?.stall()
        when {
            pipe.lossy(wire) -> lost += wire

            // A held frame is judged by the memo at [release], not here.
            pipe.park(wire) -> Unit

            !crosses(pipe, key, VIA_FAST) -> Unit

            else -> pipe.target.deliverNow(wire, nodeId, VIA_FAST)
        }
    }

    /**
     * A page from [LabPages] landed on this node's scanner: it enters where a link frame does, from the
     * frame's author (a page carries no hop identity), and our own frame paged back by a neighbor is
     * dropped — `BluetoothMeshTransport`'s side listener, line for line.
     */
    internal fun hearPage(
        wire: WireEnvelope,
        env: RelayEnvelope,
    ) {
        if (env.senderId == nodeId) return // OWN_ECHO
        heardOnPages += "${env.type} ${env.id} ${env.senderId.take(NODE_ID_CHARS)}"
        framesIn.incrementAndGet()
        emitNow(InboundFrame(wire, env, env.senderId))
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        chaos?.jitter()
        val pipe = pipes[to.nodeId] ?: return false
        val target = pipe.target
        // As `FramedLink.streamFile` reads it: a resume up to the whole length, anything else a whole file — and an
        // older holder's whole file whatever the ask said (#116).
        val offset = if (ignoreOffsets) 0L else meta.offset.takeIf { it in 1..file.length() } ?: 0L
        val line = "${to.nodeId} ${meta.kind.wire} ${meta.key}" + if (offset > 0) " from $offset" else ""
        if (offset > 0 || corruptFiles || streams(pipe)) return sendThroughIntake(pipe, file, meta, offset, line)
        files += line
        // The receiver ingests and then deletes the staged copy; it must be the receiver's own copy.
        val staged = File(target.stagingDir.apply { mkdirs() }, "${meta.key}-${UUID.randomUUID()}")
        file.copyTo(staged, overwrite = true)
        val received = ReceivedFile(nodeId, staged.absolutePath, meta.kind, meta.key, meta.mime)
        if (pipe.parkFile(received)) return true
        deliverFile(target, received)
        chaos?.jitter()
        return true
    }

    private fun streams(pipe: Pipe): Boolean = synchronized(pipe.heldFiles) { pipe.holdingFiles && pipe.streamUntil != null }

    /**
     * A resume, a holder's wrong bytes or a streamed hold: the bytes cross into the receiver's real `FileIntake`,
     * which splices a resume onto its kept prefix exactly as a phone's link does. A streamed hold parks after its
     * mark; anything else lands (or parks whole, under a plain hold) once its end is in.
     */
    private suspend fun sendThroughIntake(
        pipe: Pipe,
        file: File,
        meta: FileMeta,
        offset: Long,
        // The `files` line, recorded only once the send is committed: a pipe torn down under it refuses the send,
        // `BlobExchange` forgets the serve, and a line for it would read as a second copy when the next ask is served.
        line: String,
    ): Boolean {
        // A holder with wrong bytes: the first byte this stream carries is flipped, whatever its offset.
        val source = file.readBytes().also { if (corruptFiles && offset < it.size) it[offset.toInt()] = it[offset.toInt()].inc() }
        val stream = FileStream(meta, pipe.target.intakeFrom(nodeId), source, offset)
        val parked =
            synchronized(pipe.heldFiles) {
                val until = pipe.streamUntil
                when {
                    pipe.closed -> {
                        return false
                    }

                    !pipe.holdingFiles || until == null -> {
                        false
                    }

                    else -> {
                        files += line
                        stream.begin()
                        stream.feedTo(until)
                        pipe.heldFiles += HeldFile.Streaming(stream)
                        true
                    }
                }
            }
        if (parked) return true
        files += line
        stream.begin()
        val received = stream.land() ?: return true // refused at the receiver: the link carried it for nothing
        if (pipe.parkFile(received)) return true
        deliverFile(pipe.target, received)
        chaos?.jitter()
        return true
    }

    /** A receive side for a file from [from], as each phone link holds one: this node's staging dir and partials. */
    private fun intakeFrom(from: String): FileIntake =
        FileIntake(stagingDir.apply { mkdirs() }, partials, from, now = System::currentTimeMillis)

    /** [pipe] went down: every file streaming across it is cut, under its lock, so none is begun after. */
    private fun cutStreams(pipe: Pipe) {
        val cut =
            synchronized(pipe.heldFiles) {
                pipe.closed = true
                // Out of the list in the same lock, so a release racing the teardown never lands what was cut.
                pipe.heldFiles.filterIsInstance<HeldFile.Streaming>().also { pipe.heldFiles.removeAll(it.toSet()) }
            }
        cut.forEach { it.stream.cut() }
    }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        chaos?.jitter()
        digestsSent += to.nodeId
        pipes[to.nodeId]?.target?.let { target ->
            target.digestsIn.incrementAndGet()
            target._incomingDigests.emit(ReceivedDigest(nodeId, ids))
        }
        chaos?.jitter()
    }

    private fun pipe(to: LabTransport): Pipe = checkNotNull(pipes[to.nodeId]) { "$nodeId is not linked to ${to.nodeId}" }

    private suspend fun deliver(
        wire: WireEnvelope,
        fromNodeId: String,
        via: String,
    ) {
        val frame = received(wire, fromNodeId, via) ?: return
        _inbound.emit(frame)
    }

    private fun deliverNow(
        wire: WireEnvelope,
        fromNodeId: String,
        via: String,
    ) {
        val frame = received(wire, fromNodeId, via) ?: return
        emitNow(frame)
    }

    /** Mirror the real transport: decode the routing envelope on receipt (drop undecodable bytes), and log it on the sender. */
    private fun received(
        wire: WireEnvelope,
        fromNodeId: String,
        via: String,
    ): InboundFrame? {
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return null
        crossings.firstCrossing(fromNodeId, FrameKey.of(wire, envelope)) // in counts: it never goes back this way
        when (envelope.type) {
            FrameType.BLOB_REQ -> {
                WireCodec.decodePayload<BlobReqContent>(envelope.payload)?.let { ask ->
                    pipes[fromNodeId]?.target?.blobAsks?.add(
                        BlobAsk(nodeId, ask.hash, ask.offset, ask.fastOnly == true, ask.pull, ask.until),
                    )
                }
            }

            FrameType.BLOB_CANCEL -> {
                WireCodec.decodePayload<BlobCancelContent>(envelope.payload)?.let { c ->
                    pipes[fromNodeId]?.target?.blobCancels?.add(nodeId to c.hash)
                }
            }

            FrameType.BLOB_HAVE -> {
                WireCodec.decodePayload<BlobHaveContent>(envelope.payload)?.let { q ->
                    pipes[fromNodeId]?.target?.blobQueries?.add(BlobQuery(nodeId, q.hashes))
                }
            }

            FrameType.BLOB_HAS -> {
                WireCodec.decodePayload<BlobHasContent>(envelope.payload)?.let { a ->
                    pipes[fromNodeId]?.target?.blobAnswers?.add(BlobAnswer(nodeId, a.items.associate { it.hash to it.state }))
                }
            }
        }
        framesIn.incrementAndGet() // before the sender's log line: see framesIn
        pipes[fromNodeId]?.target?.sent?.add(
            "#${SEQ.incrementAndGet()} ${nodeId.take(NODE_ID_CHARS)} ${envelope.type} ${envelope.id} relay=${wire.relay} via=$via",
        )
        return InboundFrame(wire, envelope, fromNodeId)
    }

    private fun emitNow(frame: InboundFrame) {
        check(_inbound.tryEmit(frame)) { "$nodeId: inbound buffer full ($BUFFER) — a fast-path frame was dropped" }
    }

    /** Leaves the pages, for a node whose live stack is going down ([LabNode.restart] boots a new transport). */
    fun detach() {
        pages?.leave(this)
    }

    /** Makes `neighbors.value` answer with the pipes as they are now, without waking a collector. */
    private fun presentNeighbors() {
        current = pipes.map { (id, pipe) -> Peer(id, capabilities = pipe.target.advertises) }.toSet()
    }

    private fun refreshNeighbors() {
        presentNeighbors()
        links.value = Links(current, generation.incrementAndGet())
    }

    private companion object {
        const val BUFFER = 1024
        const val NODE_ID_CHARS = 6

        /** How a frame crossed a pipe, in [sent]: the router's flood / unicast, or the fast path's link copy. */
        const val VIA_LINK = "link"
        const val VIA_FAST = "fast"

        /** One counter across every transport in the JVM, so two nodes' send logs interleave by time. */
        val SEQ =
            java.util.concurrent.atomic
                .AtomicLong()
    }
}

/**
 * A Nearby-room post [nodeId] wrote — a chat frame with no recipient and no group. The distinction matters
 * on a held pipe: a node's *first* sealed frame to a peer carries the X3DH init, and the peer answers it with a
 * sealed profile (`IntroSync.onPeerFrameOpened`) — a second chat frame from the same author that a relay
 * carries some jitter later. A scenario that waits for "a chat frame from alice" and releases by that test
 * sometimes holds two (`KeyExchangeLabTest` flaked exactly so); this names the one it means.
 */
internal fun WireEnvelope.isRoomPostFrom(nodeId: String): Boolean =
    WireCodec.decodeEnvelope(signed)?.let {
        it.type == FrameType.CHAT && it.senderId == nodeId && it.recipientId == null && it.group == null
    } == true

/** The cleartext `profile` frame [nodeId] signed — what a rename floods, and what a first contact pushes. */
internal fun WireEnvelope.isProfileFrom(nodeId: String): Boolean =
    WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.PROFILE && it.senderId == nodeId } == true
