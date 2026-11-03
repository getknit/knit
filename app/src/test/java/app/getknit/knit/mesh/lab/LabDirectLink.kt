package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.inet.DirectLinkTransport
import app.getknit.knit.mesh.inet.LinkArq
import app.getknit.knit.mesh.inet.LinkSession
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * One direct link [MeshLab.directLink] made between [a] and [b] (ADR 2026-10.a5yj): the two in-memory streams, a path
 * that can park what one end sends ([hold]) and a cut ([MeshLab.cutDirectLink]). A held direction is a path that goes
 * quiet one way — the peer's acks, answers and asks never arrive, as on a NAT mapping that stopped passing them — and
 * what it parked is lost with the cut, as datagrams in flight are. There is no release: a scenario that means the link
 * to come back cuts it and links again, the way `DirectLinks` re-punches.
 */
class LabDirectLink internal constructor(
    val a: LabNode,
    val b: LabNode,
) {
    private val lock = Any()

    // Sender node id -> the deliveries parked from it, in send order.
    private val parked = HashMap<String, MutableList<() -> Unit>>()

    private var cut = false

    internal lateinit var aStream: LinkArq
    internal lateinit var bStream: LinkArq

    /** From now on, every datagram [from] sends on this link is parked until the link is cut, which drops it. */
    fun hold(from: LabNode) {
        require(from === a || from === b) { "${from.name} is not an end of this link" }
        synchronized(lock) { parked.getOrPut(from.nodeId) { mutableListOf() } }
    }

    /** How many datagrams [from] sent that this link parked. */
    fun heldFrom(from: LabNode): Int = synchronized(lock) { parked[from.nodeId]?.size ?: 0 }

    /** One datagram [from] sent: delivered at once, parked while [from] is held, dropped once the link is cut. */
    internal fun carry(
        from: String,
        deliver: () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (cut) return true
            parked[from]?.let {
                it += deliver
                return true
            }
        }
        deliver()
        return true
    }

    /** Drops what is parked and ends both streams: each end's `FramedLink` reads to its end and drops the link. */
    internal fun cut() {
        synchronized(lock) {
            cut = true
            parked.clear()
        }
        aStream.close()
        bStream.close()
    }
}

/**
 * A node's file hand-off from its direct-link plane to the manager, holdable: from [hold] on, a file the link landed waits
 * here, still landing on the plane (`arrivingFiles`, until the manager takes it, #138), until [release] — a phone whose
 * consumer is slow to store what its link already received. Kept across restarts, like [LabCustodyGate]; a file parked
 * here when the node stops is gone with that session's collector, and the gate stays held until [release], so a scenario
 * that stops a node mid-hand-off releases it in the restart's `whileDown`. `MeshLab.close` releases it as a net.
 */
class LabFileGate {
    @Volatile
    private var opened = CompletableDeferred<Unit>().apply { complete(Unit) }

    private val waiting = AtomicInteger()

    /** How many files are waiting at the gate right now. */
    val parked: Int get() = waiting.get()

    /** From now on, every file the link plane hands the manager waits for [release]. */
    fun hold() {
        check(opened.isCompleted) { "the gate is already holding: release it first" }
        opened = CompletableDeferred()
    }

    /** Lets every parked file through, and every later one. */
    fun release() {
        opened.complete(Unit)
    }

    internal suspend fun pass() {
        val gate = opened
        if (gate.isCompleted) return
        waiting.incrementAndGet()
        try {
            gate.await()
        } finally {
            waiting.decrementAndGet()
        }
    }
}

/**
 * What [LabLinkPlane] records of the frames and digests its plane carries: [LabNode.linkFrames], [LabNode.linkDigestsFrom],
 * the node's link-up count ([LabNode.linkUps]) each frame line is stamped with, and the blob asks it sent and handed up
 * ([LabNode.linkAsksOut], [LabNode.linkAsksIn]).
 */
internal class LabLinkRecord(
    val frames: MutableList<String>,
    val digestsFrom: MutableList<String>,
    val linkUp: () -> Int,
    val asksOut: MutableList<String>,
    val asksIn: MutableList<String>,
)

/**
 * A node's direct-link plane as its composite sees it: the real [DirectLinkTransport], with every file it accepts to send
 * recorded in [files] as `to kind key` — `LabTransport.files`' line, the oracle for one copy per (hash, link) — every
 * frame handed to it for a peer it holds a link to, and every digest it took, in [record]; and its file hand-off to the
 * manager run through [handOff]. Everything else is the plane's own, as the production switch around it
 * (`SwitchableTransport`) leaves it.
 */
internal class LabLinkPlane(
    private val plane: DirectLinkTransport,
    private val files: MutableList<String>,
    handOff: LabFileGate,
    private val record: LabLinkRecord,
) : MeshTransport by plane {
    private val collectors = LabCollectors()

    /** Whether the manager collects the plane's frames, files and digests yet ([LabCollectors]). */
    val collecting: Boolean
        get() = collectors.collecting

    override val incomingFiles: Flow<ReceivedFile> = collectors.watch(plane.incomingFiles).onEach { handOff.pass() }

    // Counted as the plane hands it up, ahead of the composite's merge: that it arrived, never that it was answered.
    override val incomingDigests: Flow<ReceivedDigest> =
        collectors.watch(plane.incomingDigests).onEach { record.digestsFrom += it.fromNodeId }

    // Counted as the plane hands it up, ahead of the router: that it arrived, never that it was answered.
    override val inbound: Flow<InboundFrame> =
        collectors.watch(plane.inbound).onEach {
            if (it.envelope.type == FrameType.BLOB_REQ) record.asksIn += "${it.fromNodeId} ${it.envelope.id}"
        }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        if (to != null && plane.neighbors.value.any { it.nodeId == to.nodeId }) {
            WireCodec.decodeEnvelope(wire.signed)?.let {
                record.frames += "${record.linkUp()} ${to.nodeId} ${it.id}"
                if (it.type == FrameType.BLOB_REQ) record.asksOut += "${record.linkUp()} ${to.nodeId} ${it.id}"
            }
        }
        plane.send(wire, to)
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean =
        plane.sendFile(file, to, meta).also { accepted ->
            if (accepted) files += "${to.nodeId} ${meta.kind.wire} ${meta.key}" + if (meta.offset > 0) " from ${meta.offset}" else ""
        }
}

/**
 * The direct link between [a] and [b], with nothing left to carry, idles out on the link's clock and both ends' links
 * end. Each jump of [LinkSession.IDLE_MS] ends in both links down or a use after it; a use moves the idle close a whole
 * period on, which only the lab's jump makes that long. What may land late, and nothing else:
 *  - the last exchange's own use: a sender notes it once the send returns, after the far end already stored the frame
 *    the scenario saw, so its frame was handed to the link before the first jump;
 *  - the link-up's own frames, each end's, sent once the manager acted on the link: its profile push (`FrameId`'s
 *    `profile-<sender>-…`) and its presence-key ctl 11 ([LabNode.presenceKeyFrames]), noted on each end.
 *
 * So another period only for such a use, at most [latePeriods] of them, and after the first jump each end may hand the
 * link at most one profile of its own and one presence key of its own, nothing else, and no file: a frame re-sent after
 * the exchange ended would keep the link up inside the period bound, and this is what catches it. A link that stayed up
 * with no use fails at once.
 */
internal suspend fun MeshLab.awaitLinkIdledOut(
    a: LabNode,
    b: LabNode,
    latePeriods: Int = LATE_USE_PERIODS,
) {
    val bothDown = { checkNotNull(a.links).links.value.isEmpty() && checkNotNull(b.links).links.value.isEmpty() }
    val uses = { a.linkUses.get() + b.linkUses.get() }
    val framesAt = a.linkFrames.size to b.linkFrames.size
    val filesAt = a.linkFiles.size to b.linkFiles.size
    val frames = { "${a.name} link frames: ${a.linkFrames.takeLast(20)}\n${b.name} link frames: ${b.linkFrames.takeLast(20)}" }
    var periods = 0
    while (true) {
        val atJump = uses()
        clock.advance(LinkSession.IDLE_MS)
        assertTrue(
            "the link neither idled out nor carried a use\n${a.linkLog}\n${b.linkLog}\n${frames()}\n${report(listOf(a, b))}",
            tryAwait(1) { if (bothDown() || uses() > atJump) 1 else 0 },
        )
        if (bothDown()) break
        periods++
        assertTrue(
            "the link kept carrying uses past $latePeriods idle periods\n${a.linkLog}\n${b.linkLog}\n${frames()}",
            periods <= latePeriods,
        )
    }
    for ((node, at) in listOf(a to framesAt.first, b to framesAt.second)) {
        val late = node.linkFrames.drop(at).map { it.substringAfterLast(' ') }
        val keys = late.filter { it in node.presenceKeyFrames }
        // By id: the plane records a frame where it is handed, before the link's own once-per-link rule, so the link-up's
        // push and a digest answer serving the same profile are one profile.
        val profiles = late.filter { it.startsWith("profile-${node.nodeId}-") }
        assertTrue(
            "${node.name} handed the idle link more than its link-up's own profile and presence key after the first " +
                "jump: $late (its presence-key frames: ${node.presenceKeyFrames})\n${frames()}",
            late.size == keys.size + profiles.size && keys.size <= 1 && profiles.distinct().size <= 1,
        )
    }
    assertEquals(
        "a file crossed the idle link after the first jump: ${a.linkFiles.drop(filesAt.first) + b.linkFiles.drop(filesAt.second)}",
        filesAt,
        a.linkFiles.size to b.linkFiles.size,
    )
}

// Idle periods past the first a late use may cost ([awaitLinkIdledOut]), each late use its own period at worst: the last
// exchange's (its sender's), and the sender's and receiver's of each end's link-up profile push and presence-key ctl 11.
// Loose by design: it only ends the loop; what may cross is the frame check's.
internal const val LATE_USE_PERIODS = 1 + 2 * 2 * 2
