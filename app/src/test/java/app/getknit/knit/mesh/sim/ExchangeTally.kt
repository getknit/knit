package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.AiredRecord
import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One record an air link delivered, as the exchange ledger reads it: a frame by id, a digest, or a file. A frame custody
 * would never hold ([custodial] false: a typing cue, a blob request, a key ask) counts for its bytes alone. [hops] is the
 * envelope's as aired: 0 for a frame served fresh (a custody answer, an author's own send), 1 or more for a router relay
 * (`WireEnvelope.relayed`); null when not known.
 */
internal data class AirEvent(
    val atNanos: Long,
    val from: String,
    val to: String,
    val kind: AiredRecord.Kind,
    val bytes: Long,
    val frameId: String? = null,
    val recipientId: String? = null,
    val senderId: String? = null,
    val custodial: Boolean = true,
    val hops: Int? = null,
)

/** What a set of air links did with custody, from [ExchangeLedger.counts]. */
data class ExchangeCounts(
    val digests: Int = 0,
    val digestBytes: Long = 0,
    val frameBytes: Long = 0,
    val fileBytes: Long = 0,
    /** Bytes of frames custody never holds: typing cues, blob traffic, key asks. */
    val otherBytes: Long = 0,
    /** Frame records aired, new and re-sent. */
    val frames: Int = 0,
    /**
     * Frames aired to a node that already had them: in its custody when the tally began, off the air since, or its own
     * frame echoed back. What a re-offer, a second carrier or a flood sent for nothing.
     */
    val resent: Int = 0,
    /**
     * The [resent] frames that were router relays (hops 1 or more): the flood's share. The rest were served at hop 0, by
     * a custody answer, a key served (`KeyExchange.serve`) or an author's own send (a profile pushed on link-up): not the
     * flood's.
     */
    val resentRelayed: Int = 0,
    /** Digests answered: one opens when it lands and closes at the asker's next digest to the same peer or the end. */
    val exchanges: Int = 0,
    /** Exchanges in which the answering peer aired nothing new to the asker. */
    val emptyExchanges: Int = 0,
    /** The frames each receiver got for the first time: addressed to it, still in its custody at the end, or neither. */
    val toRecipient: Int = 0,
    val heldAtEnd: Int = 0,
    val notKept: Int = 0,
    /** After each exchange that moved nothing new, how long until the asker's next digest to the same peer, sorted. */
    val reaskAfterEmptyMs: List<Long> = emptyList(),
) {
    val newFrames: Int get() = frames - resent

    /** The [resent] frames served at hop 0: custody's re-sends. */
    val resentServed: Int get() = resent - resentRelayed
    val bytes: Long get() = digestBytes + frameBytes + fileBytes + otherBytes

    /** The digests' share of every byte aired, in percent; null when nothing aired. */
    val digestSharePct: Double? get() = if (bytes == 0L) null else digestBytes * PERCENT / bytes

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * Reads custody's exchanges off the air ([AirEvent]s, in delivery order): who asked whom with a digest, what the peer
 * answered with, which frames a receiver already had, and where each new frame ended up. Pure: [ExchangeTally] feeds it.
 *
 * An exchange is a digest landing at a peer: the asker said what it holds, and the peer's frames to it until the asker's
 * next digest to that peer, or the end, are the answer. A frame aired to a node that already had it, from any peer, is
 * re-sent: held when the tally began ([heldAtStart]), had off the air since, or written by the node itself. A frame a
 * node gets for the first time ends up delivered (the node is its addressee), held (custody
 * has it at the end) or not kept (refused, evicted on arrival, or evicted since).
 */
internal object ExchangeLedger {
    fun counts(
        events: List<AirEvent>,
        heldAtStart: (String) -> Set<String> = { emptySet() },
        heldAtEnd: (String) -> Set<String>,
    ): ExchangeCounts {
        val reader = Reader(heldAtStart)
        events.sortedBy { it.atNanos }.forEach(reader::read)
        return reader.finish(heldAtEnd)
    }

    // One pass over the events in delivery order.
    private class Reader(
        private val heldAtStart: (String) -> Set<String>,
    ) {
        private val received = HashMap<String, HashSet<String>>()
        private val firsts = ArrayList<AirEvent>()

        // Open answers, by (asker, peer): when the asker's digest landed, and how many new frames the peer has aired to the
        // asker since.
        private val open = HashMap<Pair<String, String>, Answer>()
        private val reasks = ArrayList<Long>()
        private var digests = 0
        private var digestBytes = 0L
        private var frameBytes = 0L
        private var fileBytes = 0L
        private var otherBytes = 0L
        private var frames = 0
        private var resent = 0
        private var resentRelayed = 0
        private var exchanges = 0
        private var empty = 0

        fun read(e: AirEvent) {
            when (e.kind) {
                AiredRecord.Kind.DIGEST -> digest(e)
                AiredRecord.Kind.FILE -> fileBytes += e.bytes
                AiredRecord.Kind.FRAME -> frame(e)
            }
        }

        private fun digest(e: AirEvent) {
            digests++
            digestBytes += e.bytes
            val key = e.from to e.to
            close(key)?.takeIf { it.moved == 0 }?.let { reasks += (e.atNanos - it.atNanos) / NANOS_PER_MS }
            open[key] = Answer(e.atNanos)
        }

        private fun frame(e: AirEvent) {
            if (!e.custodial) {
                otherBytes += e.bytes
                return
            }
            frames++
            frameBytes += e.bytes
            val id = e.frameId ?: return
            val had = received.getOrPut(e.to) { HashSet(heldAtStart(e.to)) }
            if (e.senderId == e.to || !had.add(id)) {
                resent++
                if ((e.hops ?: 0) > 0) resentRelayed++
                return
            }
            firsts += e
            open[e.to to e.from]?.let { it.moved++ }
        }

        private fun close(key: Pair<String, String>): Answer? {
            val answer = open.remove(key) ?: return null
            exchanges++
            if (answer.moved == 0) empty++
            return answer
        }

        fun finish(heldAtEnd: (String) -> Set<String>): ExchangeCounts {
            open.keys.toList().forEach { close(it) }
            val held = HashMap<String, Set<String>>()
            val toRecipient = firsts.count { it.recipientId == it.to }
            val kept = firsts.count { it.recipientId != it.to && it.frameId in held.getOrPut(it.to) { heldAtEnd(it.to) } }
            return ExchangeCounts(
                digests = digests,
                digestBytes = digestBytes,
                frameBytes = frameBytes,
                fileBytes = fileBytes,
                otherBytes = otherBytes,
                frames = frames,
                resent = resent,
                resentRelayed = resentRelayed,
                exchanges = exchanges,
                emptyExchanges = empty,
                toRecipient = toRecipient,
                heldAtEnd = kept,
                notKept = firsts.size - toRecipient - kept,
                reaskAfterEmptyMs = reasks.sorted(),
            )
        }
    }

    private class Answer(
        val atNanos: Long,
    ) {
        var moved = 0
    }

    private const val NANOS_PER_MS = 1_000_000L
}

/**
 * The exchanges [nodes]' air links carry from the moment [start] makes this until [close] ([ExchangeLedger]). Each
 * node's [LabTransport.airTaps][app.getknit.knit.mesh.lab.LabTransport.airTaps] gets a listener that only queues the
 * record; frames are decoded when counted. Only air links are seen: a boot's plain links are not. What each node's
 * custody held at the start ([heldAtStart]) is what it already had.
 */
internal class ExchangeTally private constructor(
    private val nodes: List<LabNode>,
    private val heldAtStart: Map<String, Set<String>>,
) {
    private class Seen(
        val from: String,
        val record: AiredRecord,
    )

    private val seen = ConcurrentLinkedQueue<Seen>()
    private val taps = nodes.map { node -> { r: AiredRecord -> seen += Seen(node.nodeId, r) }.also { node.transport.airTaps += it } }

    /** Stops listening: what aired from here is not counted. */
    fun close() = nodes.zip(taps).forEach { (node, tap) -> node.transport.airTaps -= tap }

    /** Every record so far, decoded, with [include] choosing by (from, to). */
    fun events(include: (String, String) -> Boolean = { _, _ -> true }): List<AirEvent> =
        seen.filter { include(it.from, it.record.to) }.map { s ->
            val env = s.record.wire?.let { WireCodec.decodeEnvelope(it.signed) }
            AirEvent(
                atNanos = s.record.atNanos,
                from = s.from,
                to = s.record.to,
                kind = s.record.kind,
                bytes = s.record.bytes,
                frameId = env?.id,
                recipientId = env?.recipientId,
                senderId = env?.senderId,
                custodial = env == null || FrameType.isCustodial(env.type),
                hops = s.record.wire?.hops,
            )
        }

    /** The counts over [include]'s links, each receiver's custody read now. */
    suspend fun counts(include: (String, String) -> Boolean = { _, _ -> true }): ExchangeCounts {
        val byId = nodes.associateBy { it.nodeId }
        val events = events(include)
        val custody = HashMap<String, Set<String>>()
        for (id in events.map { it.to }.toSet()) custody[id] = byId[id]?.custodyIds().orEmpty()
        return ExchangeLedger.counts(events, heldAtStart = { heldAtStart[it].orEmpty() }) { custody[it].orEmpty() }
    }

    /** The line [phase] reads, and its [SimSummary] keys, each ending in `_`[key]. */
    suspend fun report(
        phase: String,
        key: String,
    ): Pair<String, List<Pair<String, Any?>>> {
        val c = counts()
        return line(phase, c) to values(c, key)
    }

    companion object {
        /** A tally of [nodes]' air links from now, each node's custody read as what it already has. */
        suspend fun start(nodes: List<LabNode>): ExchangeTally = ExchangeTally(nodes, nodes.associate { it.nodeId to it.custodyIds() })

        fun line(
            phase: String,
            c: ExchangeCounts,
        ): String =
            "custody exchanges $phase: ${c.digests} digests (${c.digestBytes / BYTES_PER_KB} KB, " +
                "${c.digestSharePct?.let { "%.1f".format(Locale.ROOT, it) } ?: "n/a"}% of the air), ${c.exchanges} answered, " +
                "${c.emptyExchanges} moving nothing new; ${c.frames} frames aired, ${c.resent} to a node that already had them " +
                "(${c.resentServed} served at hop 0, ${c.resentRelayed} relayed); " +
                "of the ${c.newFrames} new, ${c.toRecipient} reached their addressee, ${c.heldAtEnd} were held at the end, " +
                "${c.notKept} were not kept" +
                reaskPart(c)

        private fun reaskPart(c: ExchangeCounts): String {
            val gaps = c.reaskAfterEmptyMs
            if (gaps.isEmpty()) return ""
            return "; after an exchange that moved nothing, the next digest to that peer came p50 ${gaps[gaps.size / 2] / MS_PER_S} s, " +
                "max ${gaps.last() / MS_PER_S} s later (${gaps.size} re-asks)"
        }

        fun values(
            c: ExchangeCounts,
            key: String,
        ): List<Pair<String, Any?>> =
            listOf(
                "digests_$key" to c.digests,
                "digest_kb_$key" to c.digestBytes / BYTES_PER_KB,
                "digest_air_pct_$key" to c.digestSharePct,
                "exchanges_$key" to c.exchanges,
                "exchanges_empty_$key" to c.emptyExchanges,
                "frames_aired_$key" to c.frames,
                "frames_resent_$key" to c.resent,
                "frames_resent_served_$key" to c.resentServed,
                "frames_resent_relayed_$key" to c.resentRelayed,
                "frames_new_$key" to c.newFrames,
                "frames_to_recipient_$key" to c.toRecipient,
                "frames_held_$key" to c.heldAtEnd,
                "frames_not_kept_$key" to c.notKept,
                "reasks_after_empty_$key" to c.reaskAfterEmptyMs.size,
                "reask_after_empty_p50_s_$key" to c.reaskAfterEmptyMs.getOrNull(c.reaskAfterEmptyMs.size / 2)?.div(MS_PER_S),
                "reask_after_empty_max_s_$key" to c.reaskAfterEmptyMs.lastOrNull()?.div(MS_PER_S),
            )

        private const val BYTES_PER_KB = 1_024
        private const val MS_PER_S = 1_000L
    }
}
