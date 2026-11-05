package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.PairView
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.furthestByKey
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LandingFiles
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkClaims
import app.getknit.knit.mesh.link.LinkCrossings
import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.PaceConfig
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A contact's direct Internet links as one child of the composite (ADR 2026-10.a5yj, Phase 1): each link's byte stream
 * ([DirectLinks] hands it over through [adopt] once the punch is up) runs the same `LinkHandshake` and `FramedLink` a
 * radio's socket does, so frames, digests and files cross it as they cross a Bluetooth or Wi-Fi Aware link, and the
 * mesh above needs nothing new to use it.
 *
 * **Not a radio.** [shortRange] and [viaInternet] are true and false the right way round: the composite leaves a link
 * out of every "nearby" set, of the walkie's radio path and of its [health], and stamps [TransportKind.DirectLink] on
 * what arrives, so a contact reached this way is never shown as near, never "met" and earns no Your mesh credit.
 * [hasFastPlane] is true only so the composite never turns a room post's fast fan-out into a send here: [fastFanout]
 * sends nothing, and the room never crosses a link.
 *
 * **Who goes first.** The offerer writes the first HELLO and the answerer replies (`LinkHandshake`'s order, so neither
 * blocks first). Both ends already know who the other is — the lane's keys bind both node ids — so a HELLO that names
 * anyone else ends the link; the HELLO carries the peer's protocol version and capability bits. A handshake that has not
 * finished in [handshakeMs] closes the link (a stream read cannot be cancelled, so the watchdog closes the socket under
 * it, as the Bluetooth plane does).
 *
 * **The pair's own traffic only.** Every frame, either way, and every file out passes [LinkPairFilter] against the link's
 * peer, its lookups answered by [scope]: a frame that fails is dropped here (counted in [refused]), so the room, a
 * stranger's DM this phone carries or a group the two do not share never crosses, whichever end would send it. A frame in
 * is filtered on its link's own consumer, in arrival order, since the rule may read the database.
 *
 * **Use.** A content frame or a file sent or taken calls [onUse] for that peer ([DirectLinks.noteUse]): what keeps a
 * link from its idle close, and what tells an answer that came to nothing from one that carried something. Digests,
 * handshakes, the stream's own acks, the blob exchange's control frames (which re-ask on their own rounds) and a frame
 * handed back to the phone that wrote it (custody re-serving an author its own sends) never do, so a link that only
 * keeps itself in sync idles out; a link moving a file is [busy], which the session reads too. A
 * walkie call holds its link through the pauses between bursts, or raises one ([holdLink], [DirectLinks.raise]).
 *
 * **Digests** are filtered here by the link's [view], from the ids' custody envelopes, so a contact never learns what
 * else this phone carries; [pairView] lends the same view to custody and the blob exchange for the answers they send
 * back over this link. A file in is taken only when it is an attachment addressed to both ends, or an avatar.
 */
@Suppress("TooManyFunctions", "LongParameterList") // the radio seam's file half delegates per link; a link's graph
class DirectLinkTransport(
    private val selfId: suspend () -> String,
    private val linkScope: LinkScope,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val partials: PartialBlobs,
    private val metrics: MeshMetrics,
    private val now: () -> Long,
    private val onUse: (peerId: String) -> Unit = {},
    // A link to [peerId] was adopted — a first one, or one replacing an older link under the same node id, which the
    // composite's neighbour set cannot show: custody answers that peer's next digest in full (`ForwardSync.linked`).
    private val onLinkUp: (peerId: String) -> Unit = {},
    // A live call's hold on its link to a peer (`holdLink`, a walkie's): true while one is held. The links decide it.
    private val onHold: (peerId: String) -> Boolean = { false },
    // Whether the link to a peer was made free (`DirectLinks.freeLinkTo`, L-7-11): stamped before the link is adopted.
    private val freeLink: (peerId: String) -> Boolean = { false },
    // An attachment's ask for a free link beside a radio (`DirectLinks.raiseBulk`), whether the pair qualifies for one
    // (`DirectLinks.mayRaiseBulk`), and whether one is up or being raised (`DirectLinks.bulkUnderWay`). The links decide.
    private val onBulk: suspend (peerId: String) -> Boolean = { false },
    private val bulkMay: suspend (peerId: String) -> Boolean = { false },
    private val bulkRaising: (peerId: String) -> Boolean = { false },
    private val handshakeMs: Long = HANDSHAKE_MS,
    private val log: (String) -> Unit = {},
    // The planes' shared claims (ADR 2026-11.264w): every link here is proven by its lane. One whose peer's HELLO carries
    // CAP_LINK_PROOF (a build that proves its radio links) tells the radios to close an unproven link claiming that peer
    // (R3) and makes it proof-capable for the run; one from a build without the bit tells them nothing, or its own
    // unproven radio links would be refused. Never asked to refuse a link: R1 never runs on this plane. Null in a test.
    private val claims: LinkClaims? = null,
) : MeshTransport {
    override val kind = TransportKind.DirectLink
    override val shortRange = false
    override val viaInternet = true
    override val hasFastPlane = true

    private val links = ConcurrentHashMap<String, FramedLink>()
    private val landing = LandingFiles()

    // Which frames already crossed which link, either way: the router's flood and the DM fast path both hand a DM to the
    // link to its addressee, and a link writes it once, as a Bluetooth link does. Forgotten with the link.
    private val crossings = LinkCrossings(clock = now)

    /** Whether the plane is started: the composite runs it, and its switch holds it on (what a link's demand waits on). */
    @Volatile
    var running = false
        private set

    // Our node id, once a link has asked for it: the filter's other half.
    @Volatile private var self: String? = null

    // Each peer's shared groups as last looked up, and when: the filter reads them for every frame that is not addressed
    // between the pair, so a burst of custody reads the database once per [GROUPS_TTL_MS].
    private val groupsSeen = ConcurrentHashMap<String, Pair<Long, Map<String, Set<String>>>>()

    /** Frames dropped by [LinkPairFilter], either way, since start. */
    val refused =
        java.util.concurrent.atomic
            .AtomicLong()

    // The ids of the last frames [LinkPairFilter] refused, either way: what a scenario asks to prove one particular frame
    // met the rule, where [refused] counts every frame that did.
    private val refusedRecent = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** The ids of the last [REFUSED_KEPT] frames this plane refused, either way. */
    fun refusedIds(): Set<String> = refusedRecent.toSet()

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors: StateFlow<Set<Peer>> = _neighbors.asStateFlow()

    // The adopted links that were made free, updated with [neighbors], so a peer is named here only once a file can go.
    private val _freeLinked = MutableStateFlow<Set<String>>(emptySet())
    override val freeLinked: StateFlow<Set<String>> = _freeLinked.asStateFlow()

    // The composite never reads it ([viaInternet]); the switch around this plane reads Unavailable while it holds it off.
    override val health: StateFlow<TransportHealth> = MutableStateFlow(TransportHealth.Healthy)

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = INBOUND_BUFFER)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = FILE_BUFFER)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = FILE_BUFFER)
    override val incomingDigests = _incomingDigests.asSharedFlow()

    // A file landed in one session and never taken would read as arriving in every session after, and its blob would
    // never be asked for again (#138): each session starts and ends with nothing landing, as the Bluetooth plane's does.
    override fun start() {
        landing.clear()
        running = true
    }

    override fun stop() {
        running = false
        // Straight over the map, never `toList()`: a link dropping as the plane stops can empty it between the size
        // `toList()` reads and the element it then takes (NoSuchElementException, a lab chaos teardown).
        for (id in links.keys) links.remove(id)?.close()
        crossings.clear()
        landing.clear()
        publish()
    }

    override fun heal() {
        // Nothing to rescan: a link is punched on demand, and a dead one ends itself.
    }

    /**
     * Takes the up link's stream to [peerId]: shakes hands (the [offerer] first) and runs a `FramedLink` over it. A stream
     * handed over while this plane is off is closed at once, which ends the link.
     */
    fun adopt(
        peerId: String,
        socket: LinkSocket,
        offerer: Boolean,
    ) {
        if (!running) return socket.close()
        scope.launch(Dispatchers.IO) {
            val shaken = AtomicBoolean(false)
            val watchdog =
                scope.launch {
                    delay(handshakeMs)
                    if (!shaken.get()) socket.close()
                }
            val hello = runCatching { handshake(socket, peerId, offerer) }.getOrNull()
            shaken.set(true)
            watchdog.cancel()
            if (hello == null || !running) {
                log("link ${peerId.take(SHORT)}: no handshake")
                return@launch socket.close()
            }
            run(Peer(peerId, hello.protoVersion, hello.capabilities), socket)
        }
    }

    // The two-way HELLO, the offerer writing first: the peer's advert when it names [peerId], else null. No link proof: the
    // stream runs inside the lane, whose keys only the pair can derive and which bind both ids (`LinkKeys.lane`, ADR
    // 2026-10.a5yj), so the peer is proven before its HELLO is read. Its bit and nonce ride as on any HELLO; no PROOF follows.
    private suspend fun handshake(
        socket: LinkSocket,
        peerId: String,
        offerer: Boolean,
    ): Protocol.PeerWire? {
        val me = selfId().also { self = it }
        if (offerer) LinkHandshake.writeLaneHello(socket.output, me)
        val hello = LinkHandshake.receiveHello(socket.input)?.wire?.takeIf { it.nodeId == peerId } ?: return null
        if (!offerer) LinkHandshake.writeLaneHello(socket.output, me)
        return hello
    }

    // The link itself, keyed by peer: a newer one to the same peer replaces the older, whose own end leaves the newer.
    private fun run(
        peer: Peer,
        socket: LinkSocket,
    ) {
        val held = arrayOfNulls<FramedLink>(1)
        // Frames in, filtered in arrival order on this link's own consumer; it ends with the link's queue.
        val arrivals = Channel<InboundFrame>(Channel.UNLIMITED)
        scope.launch {
            for (frame in arrivals) {
                // One frame's failure (a lookup that threw) drops that frame, never the link's every frame after it.
                val ok =
                    runCatching { admits(frame.envelope, peer.nodeId, outbound = false) }.getOrElse { e ->
                        log("link ${peer.nodeId.take(SHORT)}: frame ${frame.envelope.id} dropped: ${e.javaClass.simpleName}")
                        false
                    }
                if (ok) {
                    crossings.firstCrossing(frame.fromNodeId, FrameKey.of(frame.wire, frame.envelope))
                    crossed(frame.fromNodeId, frame.envelope, outbound = false, author = self)
                    _inbound.emit(frame)
                }
            }
        }
        val callbacks =
            object : LinkCallbacks {
                override fun onInbound(frame: InboundFrame) {
                    arrivals.trySend(frame)
                }

                override fun onDigest(digest: ReceivedDigest) {
                    _incomingDigests.tryEmit(digest)
                }

                override fun onFile(file: ReceivedFile) {
                    scope.launch { take(file) }
                }

                override fun onLinkDown(nodeId: String) {
                    arrivals.close()
                    held[0]?.let { drop(nodeId, it) }
                }
            }
        val link =
            FramedLink(
                nodeId = peer.nodeId,
                peer = peer,
                socket = socket,
                scope = scope,
                cacheDir = cacheDir,
                partials = partials,
                metrics = metrics,
                callbacks = callbacks,
                now = now,
                // Unpaced, as the stream paces itself, but in small chunks: a frame queued mid-file waits for the chunk
                // being written to get past the stream's queue bound (`LinkArq.QUEUE_TARGET_MS`), on a path that may move
                // only a few kilobytes a second.
                pace = { LINK_PACE },
                log = log,
                landing = landing,
                proven = true,
            )
        held[0] = link
        links.put(peer.nodeId, link)?.close()
        crossings.forget(peer.nodeId) // a fresh link starts clean: the peer may have restarted with an empty seen set
        // The reset first, then the neighbour set, then the reader: the peer's first digest is answered only after the memo
        // is fresh and the composite can route the answer here (a push it dropped would still be remembered as made). What
        // is sent before the start waits on the link's queue. A hook never ends a link.
        runCatching { onLinkUp(peer.nodeId) }.onFailure { log("link ${peer.nodeId.take(SHORT)}: link-up hook failed: $it") }
        // Proven by its lane: counted, and every radio closes an unproven link claiming this peer (R3), when the peer's build
        // proves its radio links too. After the put, so a plane reading the claim finds this link proven, and ahead of the
        // neighbour set, so a peer seen linked here is already proof-capable everywhere.
        if (peer.capabilities and Protocol.CAP_LINK_PROOF != 0L) claims?.proven(peer.nodeId, PLANE)
        publish()
        link.start()
        log("link ${peer.nodeId.take(SHORT)}: framed")
    }

    // A file in from a link: an attachment addressed to the pair, or an avatar; anything else is refused and deleted, so a
    // contact cannot fill this phone's storage, or its data plan, with blobs it never asked for.
    private suspend fun take(file: ReceivedFile) {
        // A lookup that throws refuses the file, as a refusal would: its landing mark and its bytes go with it.
        val ok =
            runCatching { file.kind == FileKind.AVATAR || view(file.fromNodeId)?.admitsBlob(file.key) == true }.getOrElse { e ->
                log("link ${file.fromNodeId.take(SHORT)}: file ${file.key.take(SHORT)} refused: ${e.javaClass.simpleName}")
                false
            }
        if (!ok) {
            refused.incrementAndGet()
            landing.take(file.fromNodeId, file.key)
            runCatching { File(file.path).delete() }
            return
        }
        onUse(file.fromNodeId)
        // Dropped — a full buffer, or no collector: nothing will take it.
        if (_incomingFiles.subscriptionCount.value == 0 || !_incomingFiles.tryEmit(file)) landing.take(file.fromNodeId, file.key)
    }

    private fun drop(
        nodeId: String,
        link: FramedLink,
    ) {
        if (!links.remove(nodeId, link)) return
        crossings.forget(nodeId)
        link.close()
        publish()
    }

    private fun publish() {
        _neighbors.value = links.values.mapTo(HashSet()) { it.peer }
        _freeLinked.value = links.keys.filterTo(HashSet(), freeLink)
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        // A copy that tolerates a link dropping meanwhile (`toList()` does not: see [stop]).
        val targets = if (to == null) ArrayList(links.values) else listOfNotNull(links[to.nodeId])
        if (targets.isEmpty()) return
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        val bytes = WireCodec.encodeWire(wire)
        val key = FrameKey.of(wire, env)
        targets.forEach {
            if (!admits(env, it.nodeId, outbound = true)) return@forEach
            if (!crossings.firstCrossing(it.nodeId, key)) return@forEach // it crossed already, either way
            it.send(bytes)
            crossed(it.nodeId, env, outbound = true, author = it.nodeId)
        }
    }

    // [LinkPairFilter] for one frame to or from [peer], through the link's [view]; counted when it refuses. A refusal by
    // groups read more than [GROUPS_REREAD_MS] ago is decided again on a fresh read: a group made, or a member added, since
    // the last read must not cost its first frames a re-offer round, while a stranger's frames read the database at most
    // once per [GROUPS_REREAD_MS].
    private suspend fun admits(
        env: RelayEnvelope,
        peer: String,
        outbound: Boolean,
    ): Boolean =
        (
            view(peer)?.admitsFrame(env, outbound) == true ||
                (groupsReadBefore(peer, now() - GROUPS_REREAD_MS) && view(peer, fresh = true)?.admitsFrame(env, outbound) == true)
        ).also {
            if (!it) {
                refused.incrementAndGet()
                refusedRecent.addLast(env.id)
                while (refusedRecent.size > REFUSED_KEPT) refusedRecent.pollFirst()
            }
        }

    /**
     * The rule for the link to [peer], over its shared groups as read in the last [GROUPS_TTL_MS] (or read now, when
     * [fresh]); null before a HELLO.
     */
    suspend fun view(
        peer: String,
        fresh: Boolean = false,
    ): LinkView? {
        val me = self ?: return null
        val t = now()
        val groups =
            groupsSeen[peer]?.takeIf { (at, _) -> !fresh && t - at < GROUPS_TTL_MS }?.second
                ?: linkScope.sharedGroups(peer).also { groupsSeen[peer] = t to it }
        return LinkView(me, peer, groups, linkScope)
    }

    // Whether [peer]'s shared groups were last read before [at] (never read: a view reads them anyway).
    private fun groupsReadBefore(
        peer: String,
        at: Long,
    ): Boolean = groupsSeen[peer]?.let { (readAt, _) -> readAt < at } ?: false

    override suspend fun pairView(nodeId: String): PairView? = if (links.containsKey(nodeId)) view(nodeId) else null

    // A link to send on, never a radio.
    override fun radioLinked(nodeId: String): Boolean = false

    // A walkie's hold (docs/VOICE_CALLS.md C-3.11-8): the link held through the call's pauses, or raised, while the plane
    // runs; the radios' own holds are their children's, asked beside this one.
    override fun holdLink(nodeId: String): Boolean = running && onHold(nodeId)

    // An attachment beside a radio (L-7-11): a free link, held or raised, only for a blob the link would carry to [nodeId]
    // — one addressed to it, as [sendFile] admits — so no link is raised for a copy it would refuse.
    override suspend fun raiseFreeLink(
        nodeId: String,
        blobKey: String,
    ): Boolean = running && blobView(nodeId)?.admitsBlob(blobKey) == true && onBulk(nodeId)

    // Read whether the plane runs or not: an asker in low-power mode is still asked over a link it will not answer, and
    // waits as long as the holder does (L-7-11).
    override suspend fun freeLinkMay(
        nodeId: String,
        blobKey: String,
    ): Boolean = blobView(nodeId)?.admitsBlob(blobKey) == true && bulkMay(nodeId)

    override fun freeLinkRaising(nodeId: String): Boolean = bulkRaising(nodeId)

    // The links' own read of a free link, true before this plane's handshake publishes it, whichever end offered: each end's
    // HELLO goes out after its own [adopt], so a peer that has published proves the punch is up here too (#295). Never
    // [bulkRaising], which also covers an attempt not yet up, nor [pairView], null until the link is in [links].
    override suspend fun freeLinkComing(
        nodeId: String,
        blobKey: String,
    ): Boolean = running && freeLink(nodeId) && blobView(nodeId)?.admitsBlob(blobKey) == true

    // The pair's rule for [peer] whether or not a link is up: what a link to it would carry.
    private suspend fun blobView(peer: String): LinkView? {
        if (self == null) self = selfId()
        return view(peer)
    }

    // A frame crossed the link to or from [peerId]. Only a content frame is use. A blob control frame is the exchange's
    // bookkeeping, which re-asks on its own rounds and would keep a link open for a blob nobody can supply: neither use nor
    // logged. Nor is a frame handed back to its [author] use: custody re-serves a sender's frames its full bucket has
    // evicted, and the conversation a link exists for is never a phone's own sends (defence in depth beside
    // `LinkPushRounds`' not-kept memory, #235: the first round after each link-up and each lapse, and a far end on an older
    // build). Logged by type and the id's tail, never its content, so a link that outlived its idle wait names what kept
    // it, and a trial sees custody's re-serves as `(not use)`; a sealed tick reads as `chat`, as on the wire, and the tail
    // because a profile's id opens with `profile-`.
    private fun crossed(
        peerId: String,
        env: RelayEnvelope,
        outbound: Boolean,
        author: String?,
    ) {
        if (LinkPairFilter.blobHashes(env) != null) return
        val use = env.senderId != author
        if (use) onUse(peerId)
        val way = if (outbound) "tx" else "rx"
        log("link ${peerId.take(SHORT)}: $way ${env.type} …${env.id.takeLast(SHORT)}${if (use) "" else " (not use)"}")
    }

    /** Whether the link to [peer] is moving a file either way: a link mid-file is in use, frame or no frame. */
    fun busy(peer: String): Boolean = links[peer]?.let { it.txInProgress || it.rxInProgress } ?: false

    // fastFanout keeps the interface's no-op: the room never crosses a link, and nothing here is a coordination plane.

    // A filtered send to a peer already linked; the filter may read the database, so it runs off the caller.
    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (!links.containsKey(to.nodeId)) return
        scope.launch { send(wire, to) }
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val link = links[to.nodeId] ?: return false
        // An attachment addressed to the peer, or our own avatar; nothing else this phone holds.
        val allowed =
            when (meta.kind) {
                FileKind.ATTACHMENT -> view(to.nodeId)?.admitsBlob(meta.key) == true
                FileKind.AVATAR -> linkScope.ownAvatarKey() == meta.key
            }
        if (!allowed) {
            refused.incrementAndGet()
            return false
        }
        val accepted = link.sendFile(file, meta)
        if (accepted) {
            metrics.onFileSent(TransportKind.DirectLink)
            onUse(to.nodeId)
        }
        return accepted
    }

    // Filtered here, by the link the digest crosses: the custody ids of the pair's own frames only, so a contact never
    // learns what else this phone carries, whatever the radios do between the digest's making and its send.
    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        val link = links[to.nodeId] ?: return
        val view = view(to.nodeId) ?: return
        val envelopes = linkScope.custodyEnvelopes(ids)
        link.sendDigest(ids.filter { id -> envelopes[id]?.let(view::admits) == true })
    }

    // Streaming, then landing, as the radio planes read them (#138).
    override fun arrivingFiles(): Map<String, ArrivingFile> = (links.values.mapNotNull { it.rxFile } + landing.all).furthestByKey()

    override fun fileTaken(
        fromNodeId: String,
        key: String,
    ) = landing.take(fromNodeId, key)

    override fun fileArrivingFrom(nodeId: String): Boolean = links[nodeId]?.rxInProgress ?: false

    override fun fileArrivingEtaMs(nodeId: String): Long? = links[nodeId]?.rxEtaMs()

    override fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.cancelQueued(key) ?: false

    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = links[nodeId]?.hasPendingFile(key) ?: false

    override fun feedingLoad(nodeId: String): Int = links.values.count { it.txInProgress }

    companion object {
        /** How long a link's two-way HELLO may take before the link is closed. */
        const val HANDSHAKE_MS = 15_000L

        /** This plane's name in [LinkClaims]'s log lines. */
        const val PLANE = "link"

        /** How many refused frames' ids [refusedIds] keeps. */
        const val REFUSED_KEPT = 64

        /** How long a peer's shared groups are read from memory before the database is asked again. */
        const val GROUPS_TTL_MS = 10_000L

        /** How old a peer's shared groups may be when a frame they refuse is refused without reading them again. */
        const val GROUPS_REREAD_MS = 1_000L

        /** The most file bytes one chunk carries over a link: about a second of a slow cellular uplink's. */
        const val LINK_CHUNK_BYTES = 4 * 1024

        private val LINK_PACE = PaceConfig(chunkBytes = LINK_CHUNK_BYTES)

        private const val INBOUND_BUFFER = 256
        private const val FILE_BUFFER = 32
        private const val SHORT = 6
    }
}
