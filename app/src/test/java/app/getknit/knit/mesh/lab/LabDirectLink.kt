package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.inet.DirectLinkTransport
import app.getknit.knit.mesh.inet.LinkArq
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
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
 * and the node's link-up count ([LabNode.linkUps]) each frame line is stamped with.
 */
internal class LabLinkRecord(
    val frames: MutableList<String>,
    val digestsFrom: MutableList<String>,
    val linkUp: () -> Int,
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
    private val handOff: LabFileGate,
    private val record: LabLinkRecord,
) : MeshTransport by plane {
    override val incomingFiles: Flow<ReceivedFile> = plane.incomingFiles.onEach { handOff.pass() }

    // Counted as the plane hands it up, ahead of the composite's merge: that it arrived, never that it was answered.
    override val incomingDigests: Flow<ReceivedDigest> = plane.incomingDigests.onEach { record.digestsFrom += it.fromNodeId }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        if (to != null && plane.neighbors.value.any { it.nodeId == to.nodeId }) {
            WireCodec.decodeEnvelope(wire.signed)?.let { record.frames += "${record.linkUp()} ${to.nodeId} ${it.id}" }
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
