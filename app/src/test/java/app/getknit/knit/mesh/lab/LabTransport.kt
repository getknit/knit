package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.bluetooth.BleFastRoutePolicy
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.LinkCrossings
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** The side channel's air this node advertises on and listens to, or null for a node without one. */
    private val pages: LabPages? = null,
) : MeshTransport {
    init {
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
     * The link set, with the `StateFlow` contract kept (the current value on subscribe, distinct-until-changed,
     * conflated) — only the hand-off to each collector is recorded on the way through.
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
                        if (published.peers != last) {
                            last = published.peers
                            collector.emit(published.peers)
                        }
                    }
                } finally {
                    handed.remove(token)
                }
            }
        }

    override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = BUFFER)
    override val inbound = _inbound.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = BUFFER)
    override val incomingFiles = _incomingFiles.asSharedFlow()

    private val _incomingDigests = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = BUFFER)
    override val incomingDigests = _incomingDigests.asSharedFlow()

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
        @Volatile
        var holding = false
        val held = mutableListOf<WireEnvelope>()

        @Volatile
        var lossy: (WireEnvelope) -> Boolean = { false }
    }

    /** Every frame a lossy pipe dropped, for a scenario that asserts on what the air ate. */
    val lost = CopyOnWriteArrayList<WireEnvelope>()

    /** Every frame this transport handed a peer, as `to type id` in send order — the diagnosis of "who served that". */
    val sent = CopyOnWriteArrayList<String>()

    /** Every write [crossings] skipped, as `to key via`: the second copy of a frame for one pipe, or an echo. */
    val dupSkipped = CopyOnWriteArrayList<String>()

    /** One write per pipe per frame, either way, as `BluetoothMeshTransport` keeps it. */
    private val crossings = LinkCrossings()

    /**
     * Links both ways so the two become neighbors, as a data path coming up does. With [publish] false the
     * pipes exist but neither side's `neighbors` moves yet — [publishNeighbors] does that — so a topology of
     * several links can come up at once (see [MeshLab.linkAll]).
     */
    fun connect(
        other: LabTransport,
        publish: Boolean = true,
    ) {
        if (other.nodeId == nodeId) return
        crossings.forget(other.nodeId)
        other.crossings.forget(nodeId)
        pipes[other.nodeId] = Pipe(other)
        other.pipes[nodeId] = Pipe(this)
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

    /** Unlinks both ways (out of range). Held frames on that link are dropped, as a torn-down link drops them. */
    fun disconnect(other: LabTransport) {
        pipes.remove(other.nodeId)
        other.pipes.remove(nodeId)
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

    /** From now on, frames this node sends [to] are parked instead of delivered — until [release]. */
    fun hold(to: LabTransport) {
        pipe(to).holding = true
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
     * Delivers everything parked for [to], in the order [reorder] returns (default: as sent), and stops holding
     * — unless [keepHolding], which parks what the far side sends back in answer to the batch as well. A
     * scenario that calls `release` and then `hold` again has a gap between the two in which a delivered frame's
     * whole answer can cross (a key request and the served key, on one slow core: `RestartLabTest` found the
     * key it meant to strand already delivered). Returns what was released, for a scenario that wants to
     * assert on the frames themselves.
     */
    suspend fun release(
        to: LabTransport,
        keepHolding: Boolean = false,
        reorder: (List<WireEnvelope>) -> List<WireEnvelope> = { it },
    ): List<WireEnvelope> {
        val pipe = pipe(to)
        val batch = synchronized(pipe.held) { pipe.held.toList().also { pipe.held.clear() } }
        pipe.holding = keepHolding
        val ordered = reorder(batch)
        ordered.forEach { pipe.target.deliver(it, nodeId, VIA_LINK) }
        return ordered
    }

    /**
     * Whether the node's router is collecting [inbound] yet. `MeshRouter.start` subscribes on
     * `Dispatchers.Default`, and a [MutableSharedFlow] with no replay drops what is emitted before that — so
     * a link brought up too early would lose the profile push. [MeshLab.node] waits on this.
     */
    val collecting: Boolean get() = _inbound.subscriptionCount.value > 0

    override fun start() = Unit

    override fun stop() = Unit

    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val targets = if (to == null) pipes.values.toList() else listOfNotNull(pipes[to.nodeId])
        val key = FrameKey.ofSigned(wire)
        targets.forEach { pipe ->
            when {
                pipe.lossy(wire) -> lost += wire
                !crosses(pipe, key, VIA_LINK) -> Unit
                pipe.holding -> synchronized(pipe.held) { pipe.held += wire }
                else -> pipe.target.deliver(wire, nodeId, VIA_LINK)
            }
        }
    }

    /**
     * The phone's `writeOnce`: true when [key] is new to this pipe (write it), false when it already crossed
     * either way (skip it, recorded in [dupSkipped]). Judged after `lossy`, which models a radio that lost the
     * packet rather than a link that took it. An unsigned frame (null key) always crosses, as on the phone.
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
        when {
            pipe.lossy(wire) -> lost += wire
            !crosses(pipe, key, VIA_FAST) -> Unit
            pipe.holding -> synchronized(pipe.held) { pipe.held += wire }
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
        emitNow(InboundFrame(wire, env, env.senderId))
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val target = pipes[to.nodeId]?.target ?: return false
        // The receiver ingests and then deletes the staged copy; it must be the receiver's own copy.
        val staged = File(target.stagingDir.apply { mkdirs() }, "${meta.key}-${UUID.randomUUID()}")
        file.copyTo(staged, overwrite = true)
        target._incomingFiles.emit(ReceivedFile(nodeId, staged.absolutePath, meta.kind, meta.key, meta.mime))
        return true
    }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        pipes[to.nodeId]?.target?._incomingDigests?.emit(ReceivedDigest(nodeId, ids))
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
        current = pipes.keys.map { Peer(it) }.toSet()
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
