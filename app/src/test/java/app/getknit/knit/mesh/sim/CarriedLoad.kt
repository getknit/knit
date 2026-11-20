package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabLimits
import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.lab.LabSybil
import app.getknit.knit.mesh.lab.MeshLab
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a phone carries for people it met before the run: sealed DMs from [authors] identities of their own ([LabSybil])
 * to [recipients] who never appear, so nobody in the run opens or delivers one, as most of a busy carrier's store is.
 * [fill] takes a share into a node's custody straight ([LabNode.carry]), past its router and links, so no peer hears of
 * it before a digest exchange. Each author's profile goes first, once a node, heard as its author's page
 * ([LabTransport.hearPage][app.getknit.knit.mesh.lab.LabTransport]): the node pins the author as a carrier that met it has,
 * and holds the profile, so a peer it serves pins the author before the author's frames, as custody's serve order has it.
 * A profile carried and never heard would leave the node holding frames whose author no node in the run has pinned.
 *
 * Authors and recipients are drawn from [seed] and [key] alone: two loads with the same key are the same authors, so a
 * pool shared across a field carries the same senders' frames on many phones, and loads with different keys share none.
 */
internal class CarriedLoad(
    seed: Long,
    key: Long,
    authors: Int,
    recipients: Int = DEFAULT_RECIPIENTS,
    /** When every author's profile was published: before any of its DMs, inside the custody TTL. */
    private val profileAt: Long,
    /** The size of a DM's `signed` bytes, to carry frames the size the run's own DMs are; LabSybil's smallest when null. */
    private val signedBytes: Int?,
) {
    init {
        require(authors > 0 && recipients > 0) { "a load needs an author and a recipient: $authors, $recipients" }
    }

    private val sybils =
        List(authors) { a -> LabSybil(SimSeeds.stream(seed, Purpose.LOAD_AUTHOR, key, a.toLong()).nextInt(), "Carried $key.$a") }
    private val recipientIds =
        List(recipients) { r -> LabSybil(SimSeeds.stream(seed, Purpose.LOAD_RECIPIENT, key, r.toLong()).nextInt(), "To $key.$r").nodeId }
    private val profiled = HashSet<Pair<String, Int>>()
    private var sent = 0L

    val authorCount: Int get() = sybils.size

    /** The node ids of the load's authors: what a custody row's sender is matched against. */
    val authorIds: Set<String> = sybils.mapTo(HashSet()) { it.nodeId }

    /**
     * Takes [count] new DMs from author [author] into [node]'s custody, each stamped by [now] as it is made and addressed to
     * the next recipient in turn; the author's profile first, if this node has not had it. Returns the ids of the DMs made,
     * whether or not custody kept them all: past its caps it evicts as it does for any arrival.
     */
    suspend fun fill(
        node: LabNode,
        author: Int,
        count: Int,
        now: () -> Long,
    ): List<String> {
        val sybil = sybils[author]
        if (profiled.add(node.nodeId to author)) meet(node, sybil)
        return List(count) {
            val to = recipientIds[(sent++ % recipientIds.size).toInt()]
            val dm = sybil.dm(to, sentAt = now(), signedBytes = signedBytes)
            node.carry(dm)
            checkNotNull(WireCodec.decodeEnvelope(dm.signed)).id
        }
    }

    // [node] hears [sybil]'s profile as its page, and pins it.
    private suspend fun meet(
        node: LabNode,
        sybil: LabSybil,
    ) {
        val profile = sybil.profile(profileAt)
        node.transport.hearPage(profile, checkNotNull(WireCodec.decodeEnvelope(profile.signed)))
        val pinned =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(MeshLab.AWAIT_MS) {
                    while (node.pinned(sybil.nodeId) == null) delay(MeshLab.POLL_MS)
                    true
                }
            }
        check(pinned == true) { "${node.name} never pinned ${sybil.nodeId} from its profile" }
    }

    companion object {
        const val DEFAULT_RECIPIENTS = 50

        /** What an outing's carried DM is sized after ([dmSignedBytes]): an everyday message, as the pass-by's own are. */
        const val SAMPLE_BODY = "w00 dm 0 to w01: on my way, see you at the corner by the bakery in ten minute"

        /**
         * Every one of [nodes] given [SimScenario.load] frames from one pool of [SimScenario.loadAuthors] authors shared by
         * the field, each author's share even across a walker's load, the DMs sized as a DM between two of the run's nodes
         * is ([SAMPLE_BODY]). Carried past the router: the walk's first digests move it. Puts its summary keys in
         * [summary] and returns the boot's words for it.
         */
        suspend fun fillField(
            lab: MeshLab,
            limits: LabLimits,
            nodes: List<LabNode>,
            scenario: SimScenario,
            summary: SimSummary,
        ): String {
            val signedBytes = dmSignedBytes(lab, limits, SAMPLE_BODY)
            val pool =
                CarriedLoad(scenario.seed, key = 0, authors = scenario.loadAuthors, profileAt = lab.clock.now(), signedBytes = signedBytes)
            for (node in nodes) {
                for (author in 0 until pool.authorCount) {
                    val share = scenario.load / pool.authorCount + if (author < scenario.load % pool.authorCount) 1 else 0
                    pool.fill(node, author, share, lab.clock::now)
                }
            }
            val custody = nodes.map { it.custodyIds().size }
            val line =
                "each walker carries ${scenario.load} frames from a pool of ${pool.authorCount} authors " +
                    "($signedBytes B signed each), custody holding ${custody.min()} to ${custody.max()} frames"
            summary["custody_rows_after_load_min"] = custody.min()
            summary["custody_rows_after_load_max"] = custody.max()
            return line
        }

        /** The nodes [dmSignedBytes] makes in [lab]: two that never walk. */
        const val SIZER_NODES = 2

        /**
         * The `signed` size of a DM whose body is [body], measured on two nodes of [lab] that never walk: the second DM of
         * the pair, as the first carries the session's init.
         */
        suspend fun dmSignedBytes(
            lab: MeshLab,
            limits: LabLimits,
            body: String,
        ): Int {
            val (a, b) = lab.node("sizer-a", limits) to lab.node("sizer-b", limits)
            lab.link(a, b)
            lab.awaitAcquainted(a, b)
            lab.befriend(a, b)
            lab.unlink(a, b)
            val bodies = listOf("$body.", "$body!")
            bodies.forEach { check(a.sendDm(b, it)) { "the sizer could not send \"$it\"" } }
            val id = a.ownMessageId(a.dmWith(b), bodies[1])
            return checkNotNull(a.custodySignedBytes()[id]) { "the sizer's DM is not in its custody" }
        }
    }
}
