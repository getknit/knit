package app.getknit.knit.mesh

import app.getknit.knit.mesh.inet.LinkPairFilter
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process [MeshTransport] used for development and tests without any radios. Instances are
 * wired into an arbitrary topology with [connect]; a [send] is delivered to the linked peers'
 * [inbound] streams, so a multi-node mesh (including multi-hop relay) can be exercised on the JVM.
 */
class FakeLoopTransport(
    val nodeId: String,
) : MeshTransport {
    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()

    // No real radios, so always healthy.
    override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 32)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = 32)
    override val incomingDigests = _incomingDigests.asSharedFlow()

    private val links = mutableMapOf<String, FakeLoopTransport>()

    /** Keys a test declares as streaming in right now (a real link reads them off its `FILE_HEADER`). */
    val arriving = mutableSetOf<String>()

    /** `fromNodeId to key` for every file the consumer took ([fileTaken]), in order. */
    val taken = CopyOnWriteArrayList<Pair<String, String>>()

    /** `nodeId to key` pairs a test declares as queued or streaming toward that peer. */
    val inFlight = mutableSetOf<Pair<String, String>>()

    /** `nodeId to key` -> the copy a test declares as being fed on a shared-budget link toward that peer ([fileFeed]). */
    val feeds: MutableMap<Pair<String, String>, FileFeed> = java.util.concurrent.ConcurrentHashMap()

    /** nodeId -> the rate a test declares a file to that peer would go at ([expectedFileRate]); absent is unknown. */
    val rates: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /** Every progress report this transport was handed for its links ([onFileProgress]): `nodeId`, key, held, ms. */
    val progressTaken = java.util.concurrent.CopyOnWriteArrayList<FileProgress>()

    /** Poked by a test to say a file streaming in here reached a point ([fileProgress]). */
    val rxProgress = MutableSharedFlow<FileProgress>(extraBufferCapacity = 8)
    override val fileProgress = rxProgress.asSharedFlow()

    /** Poked by a test to say a feed ended ([fileFeedsChanged]). */
    val feedEnds = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val fileFeedsChanged = feedEnds.asSharedFlow()

    /** Node ids a test declares as streaming a file in to us right now. */
    val arrivingFrom = mutableSetOf<String>()

    /** The plane a test declares a file from each peer would ride ([bulkRouteTo]); unnamed peers ride none. */
    val bulkRoutes = mutableMapOf<String, BulkRoute>()

    /** Peers a test saw this node arm a bulk transfer toward ([expectBulkTransfer]), in order. */
    val bulkArmed = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** The capabilities this node's link advertises to its neighbours (its HELLO); set before [connect]. */
    var advertises: Long = 0L

    /** Peers a test declares as linked on a slow (Coded) link ([slowLinkTo]). */
    val slowLinks = mutableSetOf<String>()

    /** How many files a test declares this node is feeding right now ([feedingLoad]). */
    var feeding: Int = 0

    /** What every [sendFile] that reached a peer asked of the link, offset included (#116), in order. */
    val filesSent = java.util.concurrent.CopyOnWriteArrayList<FileMeta>()

    /** Bidirectionally links this transport with [other] so they become neighbors. */
    fun connect(other: FakeLoopTransport) {
        if (other.nodeId == nodeId) return
        links[other.nodeId] = other
        other.links[nodeId] = this
        refreshNeighbors()
        other.refreshNeighbors()
    }

    /** Bidirectionally unlinks this transport from [other] (simulates a peer moving out of range). */
    fun disconnect(other: FakeLoopTransport) {
        links.remove(other.nodeId)
        other.links.remove(nodeId)
        refreshNeighbors()
        other.refreshNeighbors()
    }

    override fun start() = Unit

    override fun stop() = Unit

    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val targets = if (to == null) links.values.toList() else listOfNotNull(links[to.nodeId])
        targets.forEach { if (linkCarries(wire, it.nodeId)) it.deliver(wire, nodeId) else linkDropped += it.nodeId to wire }
    }

    /** Every frame a direct link of [internetOnly] dropped on the way out, as (peer, frame). */
    val linkDropped = CopyOnWriteArrayList<Pair<String, WireEnvelope>>()

    // A direct link's outbound rule for a blob control frame (L-7-2): every hash it names addressed to both ends.
    private suspend fun linkCarries(
        wire: WireEnvelope,
        peer: String,
    ): Boolean {
        if (peer !in internetOnly) return true
        val view = linkViews[peer] ?: return true
        val hashes = WireCodec.decodeEnvelope(wire.signed)?.let { LinkPairFilter.blobHashes(it) } ?: return true
        return hashes.all { view.admitsBlob(it) }
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        // In-process: hand the file straight to the linked peer's incomingFiles (same filesystem). A resume lands as
        // an honest splice would — the whole file, saying where its stream began.
        val target = links[to.nodeId] ?: return false
        filesSent += meta
        target._incomingFiles.emit(ReceivedFile(nodeId, file.absolutePath, meta.kind, meta.key, meta.mime, resumedFrom = meta.offset))
        return true
    }

    override fun arrivingFiles(): Map<String, ArrivingFile> = arriving.associateWith { ArrivingFile(it, bytes = 0, total = null) }

    /** Runs once a file has been taken off [arriving]: where a test puts a step that races the consumer. */
    var afterTaken: (() -> Unit)? = null

    /** A taken file leaves [arriving], as a link's landing one does (#138). */
    override fun fileTaken(
        fromNodeId: String,
        key: String,
    ) {
        taken += fromNodeId to key
        arriving -= key
        afterTaken?.invoke()
    }

    override fun fileArrivingFrom(nodeId: String): Boolean = nodeId in arrivingFrom

    /** How long a test says the file streaming in from a node has left ([fileArrivingEtaMs]). */
    val arrivingLeft = mutableMapOf<String, Long>()

    override fun fileArrivingEtaMs(nodeId: String): Long? = arrivingLeft[nodeId]

    /** Copies a test declares queued on a link, as (peer, key); a cancel takes one off ([cancelQueuedFile]). */
    val queuedFiles = mutableSetOf<Pair<String, String>>()
    val cancelled = mutableListOf<Pair<String, String>>()

    override fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = queuedFiles.remove(nodeId to key).also { if (it) cancelled += nodeId to key }

    override fun bulkRouteTo(nodeId: String): BulkRoute = bulkRoutes[nodeId] ?: BulkRoute.None

    override fun expectBulkTransfer(nodeId: String): Boolean {
        bulkArmed += nodeId
        return bulkRoutes[nodeId] == BulkRoute.Reachable
    }

    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = (nodeId to key) in inFlight

    override fun fileFeed(
        nodeId: String,
        key: String,
    ): FileFeed? = feeds[nodeId to key]

    override fun expectedFileRate(nodeId: String): Int = rates[nodeId] ?: 0

    override fun onFileProgress(
        nodeId: String,
        key: String,
        held: Long,
        ms: Long,
    ) {
        progressTaken += FileProgress(nodeId, key, held, ms)
    }

    override fun feedingLoad(nodeId: String): Int = feeding

    override fun slowLinkTo(nodeId: String): Boolean = nodeId in slowLinks

    /**
     * Peers a test declares linked over a contact's direct Internet link alone (ADR 2026-10.a5yj): never [radioLinked],
     * a file to one rides the Internet ([fileRidesInternet]), and a blob control frame naming a hash its [linkViews]
     * entry does not admit is dropped on the way out, as the link drops it.
     */
    val internetOnly = mutableSetOf<String>()

    /** The [PairView] a test declares for the direct link to a peer ([pairView]). */
    val linkViews = mutableMapOf<String, PairView>()

    override fun radioLinked(nodeId: String): Boolean = nodeId in links && nodeId !in internetOnly

    /**
     * Peers a test declares radio-linked with a free direct link beside the radio too (L-7-11): a file to one rides the
     * link ([fileRidesInternet]) when it carries the blob, while frames keep to the radio.
     */
    val freeLinks = mutableSetOf<String>()

    /**
     * Peers a test declares the pair's rule lets raise a free link ([freeLinkMay]), for a blob their [linkViews] entry
     * admits (every blob without one): nothing of this node's own state counts, as the seam says.
     */
    val freeLinkMays = mutableSetOf<String>()

    /**
     * Peers a test declares the links hold a free link to that this end has yet to publish ([freeLinkComing]), for a blob
     * their [linkViews] entry admits (every blob without one).
     */
    val freeLinkComings = mutableSetOf<String>()

    /** Every [freeLinkMay] read, as (peer, blob), in order. */
    val freeLinkMayReads = CopyOnWriteArrayList<Pair<String, String>>()

    override fun fileRidesInternet(nodeId: String): Boolean = nodeId in internetOnly || nodeId in freeLinks

    override suspend fun freeLinkMay(
        nodeId: String,
        blobKey: String,
    ): Boolean {
        freeLinkMayReads += nodeId to blobKey
        return nodeId in freeLinkMays && linkViews[nodeId]?.admitsBlob(blobKey) != false
    }

    override suspend fun freeLinkComing(
        nodeId: String,
        blobKey: String,
    ): Boolean = nodeId in freeLinkComings && linkViews[nodeId]?.admitsBlob(blobKey) != false

    override suspend fun pairView(nodeId: String): PairView? = linkViews[nodeId]?.takeIf { nodeId in links }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        // In-process round-trip: deliver our advertised ids to the linked peer's incomingDigests.
        links[to.nodeId]?._incomingDigests?.emit(ReceivedDigest(nodeId, ids))
    }

    private suspend fun deliver(
        wire: WireEnvelope,
        fromNodeId: String,
    ) {
        // Mirror the real transport: decode the routing envelope on receipt (drop undecodable bytes).
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return
        // A loop is a link: the frame's hop identity is the linked peer, as `FramedLink.handleFrame` says (ADR 2026-11.264w).
        _inbound.emit(InboundFrame(wire, envelope, fromNodeId, onLink = true))
    }

    private fun refreshNeighbors() {
        _neighbors.value = links.map { (id, link) -> Peer(id, capabilities = link.advertises) }.toSet()
    }
}
