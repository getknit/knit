package app.getknit.knit.mesh.sim

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.shadows.ShadowLog
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cbrt
import kotlin.math.ceil

/** One simulated outing: who walks where, what the radio reaches, and what they send. */
data class SimScenario(
    val seed: Long,
    val walkers: Int,
    val durationMs: Long,
    /** The field is a square this many metres on a side. */
    val fieldM: Double,
    /** Friends arrive together, [groupSize] at a time, and DM only each other. */
    val groupSize: Int = 3,
    /** How long a group stands together at its gate before its walkers set off. */
    val departMs: Long = 10_000,
    val connectM: Double = 25.0,
    val dropM: Double = 35.0,
    val dialMs: Long = 2_000,
    val tickMs: Long = 500,
    val dmMeanMs: Long = 20_000,
    val roomMeanMs: Long = 60_000,
)

/**
 * What a run found. [text] is the whole report; the rest is what the harness asserts on: that the workload was
 * sent ([dmsSent], and [unsent], the sends an overloaded machine let fall past the end of the run), that no DM is
 * still missing after the gather ([neverArrived]), and that the lab's oracle then [converged].
 */
class SimReport(
    val text: String,
    val dmsSent: Int,
    val unsent: Int,
    val neverArrived: List<String>,
    val converged: Boolean,
)

/**
 * A crowd of real Knit stacks ([MeshLab] nodes) whose links are made and cut by where the walkers are, not by a
 * script: [Mobility] moves them, [DiscRadio] turns distance into links, and each step's changes are applied to
 * the lab's transports as a radio would apply them: at once, with no settling. A scripted scenario waits for the
 * mesh between its steps; here nothing waits, which is the point.
 *
 * **Time is real.** One simulated second is one second of wall clock: every protocol timer in the stacks is a
 * real `delay`, so walking faster than the timers run would change the very ratio under test (how long a
 * contact lasts against how long the mesh needs to use it).
 *
 * **A run has three parts.** Friends arrive in groups, already linked, and are acquainted the scripted way
 * ([boot]). Then the walk: links follow the walkers while each sends DMs to its friends and posts in the room.
 * Last, everyone gathers ([gather]), and what never arrived by then never will.
 */
@Suppress("TooManyFunctions") // one outing start to finish: its three parts, then one reader per report line
class MeshSim(
    private val lab: MeshLab,
    private val scenario: SimScenario,
    /** Told where the outing has got to, as it goes: a run that stalls says where. */
    private val progress: (String) -> Unit = {},
) {
    private val groups = (0 until scenario.walkers).chunked(scenario.groupSize)
    private val mobility =
        RandomWaypoint(
            seed = scenario.seed,
            widthM = scenario.fieldM,
            heightM = scenario.fieldM,
            starts = gates(),
            departMs = scenario.departMs,
        )
    private val radio = DiscRadio(scenario.connectM, scenario.dropM, scenario.dialMs, initial = groups.flatMap(::pairs).toSet())
    private val contacts = ContactLog(scenario.walkers)
    private val events = Workload.schedule(scenario.seed, groups, scenario.durationMs, scenario.dmMeanMs, scenario.roomMeanMs)

    private lateinit var nodes: List<LabNode>
    private val dms = CopyOnWriteArrayList<Pair<SimEvent.Dm, Reach>>()
    private val rooms = CopyOnWriteArrayList<SimEvent.Room>()
    private val refused = AtomicInteger()
    private val unsent = AtomicInteger()
    private val worldLateMs = AtomicLong()
    private val sendLateMs = AtomicLong()

    /** Boots, walks, gathers and reports. */
    suspend fun play(): SimReport {
        val lines = mutableListOf("mesh sim: park walk, $scenario")
        lines += boot().also(progress)
        val before = Counters.of(nodes)
        val cpuBefore = cpuNanos()
        val startedAt = lab.clock.now()
        walkAndTalk()
        val endedAt = lab.clock.now()
        val cores = (cpuNanos() - cpuBefore).toDouble() / ((endedAt - startedAt) * NANOS_PER_MS)
        lines += contacts.summary()
        lines += "frames in the run: ${Counters.of(nodes) - before}; room posts moderation refused ${refused.get()}"
        val custody = nodes.map { it.custodyIds().size }
        lines += "custody at the end of the run: mean ${custody.average().toInt()} rows, max ${custody.max()}"
        lines +=
            "machine: ${"%.2f".format(Locale.ROOT, cores)} cores busy, heap ${usedHeapMb()} MB after the run, " +
            "${Thread.activeCount()} threads; the world ran at most ${worldLateMs.get()} ms late, a send at most " +
            "${sendLateMs.get()} ms late, ${unsent.get()} sends fell past the end of the run"
        progress("the walk is over: ${dms.size} DMs and ${rooms.size} room posts sent, ${unsent.get()} never sent")
        val gathering = gatherLine(endedAt)
        val outcomes = outcomes(endedAt)
        lines += "DMs between friends, by how the two stood when it was sent:"
        lines += deliveryLines(outcomes)
        lines += roomLine(endedAt)
        lines += gathering
        lines += "frames refused over the whole outing: ${drops().ifEmpty { "none" }}"
        val stranded = outcomes.filter { it.latencyMs == null }.map { it.event.body }
        // With a DM missing the oracle's verdict is known, and each of its waits would run to its end to say so.
        val failure = if (stranded.isEmpty()) oracle() else "not asked, ${stranded.size} DMs never arrived"
        lines += "oracle: " + (failure ?: "every DM thread converged (messages, ticks, custody parity across the field)")
        return SimReport(lines.joinToString("\n"), outcomes.size, unsent.get(), stranded, converged = failure == null)
    }

    // The gather, and for what it left undelivered, where the two ends say it is stuck.
    private suspend fun gatherLine(endedAt: Long): String {
        gather()
        val late = outcomes(endedAt, ticks = false).filter { it.latencyMs == null }
        progress("gathered: ${late.size} DMs still missing")
        return if (late.isEmpty()) "gather: every DM arrived" else strandedLine(late)
    }

    // Each group stands at its own gate: a point drawn in the field, shared by its walkers.
    private fun gates(): List<Point> {
        val random = kotlin.random.Random(scenario.seed)
        val gates = groups.map { Point(random.nextDouble() * scenario.fieldM, random.nextDouble() * scenario.fieldM) }
        return (0 until scenario.walkers).map { gates[it / scenario.groupSize] }
    }

    private fun pairs(group: List<Int>): List<Link> = group.flatMapIndexed { i, a -> group.drop(i + 1).map { b -> Link.of(a, b) } }

    private fun ends(link: Link): Pair<LabNode, LabNode> = nodes[link.a] to nodes[link.b]

    // The nodes, then every group linked, acquainted and accepted as contacts: friends before they walk in.
    private suspend fun boot(): String {
        val heapBefore = usedHeapMb()
        val started = System.nanoTime()
        nodes = (0 until scenario.walkers).map { i -> lab.node("w$i").apply { setDisplayName("Walker $i") } }
        val bootMs = (System.nanoTime() - started) / NANOS_PER_MS / scenario.walkers
        for (group in groups) {
            val friends = group.map { nodes[it] }.toTypedArray()
            lab.linkAll(*pairs(group).map(::ends).toTypedArray())
            lab.awaitAcquainted(*friends)
            lab.befriend(*friends)
        }
        val perNode = (usedHeapMb() - heapBefore).toDouble() / scenario.walkers
        val heapEach = "%.1f".format(Locale.ROOT, perNode)
        return "boot: ${scenario.walkers} nodes, $bootMs ms each, $heapEach MB of heap each, $heapBefore MB before any"
    }

    // The walk and the workload side by side, on the wall clock from one origin. Each walker sends on its own, as each
    // phone does: one slow send holds up that walker alone. A walker so far behind that its next send falls past the
    // end of the run drops the rest, so an overloaded machine ends the run late by one send, not by the backlog.
    private suspend fun walkAndTalk() =
        coroutineScope {
            val origin = System.nanoTime()
            launch(Dispatchers.Default) {
                var atMs = 0L
                while (atMs <= scenario.durationMs) {
                    worldLateMs.accumulateAndGet(waitUntil(origin, atMs), ::maxOf)
                    step(atMs)
                    atMs += scenario.tickMs
                }
            }
            for (own in events.groupBy { it.from }.values) {
                launch(Dispatchers.Default) {
                    for ((sent, event) in own.withIndex()) {
                        val lateMs = waitUntil(origin, event.atMs)
                        if (event.atMs + lateMs > scenario.durationMs) {
                            unsent.addAndGet(own.size - sent)
                            break
                        }
                        sendLateMs.accumulateAndGet(lateMs, ::maxOf)
                        send(event)
                    }
                }
            }
        }

    /** Waits for [atMs] of the run and returns how late the caller already was for it: 0 when it had to wait. */
    private suspend fun waitUntil(
        origin: Long,
        atMs: Long,
    ): Long {
        val lateMs = (System.nanoTime() - origin) / NANOS_PER_MS - atMs
        if (lateMs < 0) delay(-lateMs)
        return lateMs.coerceAtLeast(0)
    }

    // One radio step, applied as a radio applies it: the link is gone or there, and nobody waits for the mesh.
    private fun step(atMs: Long) {
        val positions = nodes.indices.map { mobility.position(it, atMs) }
        val changes = radio.step(atMs, positions)
        changes.dropped.map(::ends).forEach { (a, b) -> a.transport.disconnect(b.transport) }
        changes.raised.map(::ends).forEach { (a, b) -> lab.link(a, b) }
        contacts.record(atMs, changes, radio.links)
        // Robolectric keeps every log line of the test: a long run would measure that, not the stacks.
        if (atMs % LOG_CLEAR_MS == 0L) {
            ShadowLog.clear()
            val late = worldLateMs.get()
            progress("t=${atMs / MS_PER_S}s: ${radio.links.size} links up, ${dms.size} DMs sent, world $late ms late at worst")
        }
    }

    private suspend fun send(event: SimEvent) {
        val from = nodes[event.from]
        when (event) {
            is SimEvent.Dm -> {
                val reach = Reach.of(hops(radio.links, event.from, event.to))
                if (from.sendDm(nodes[event.to], event.body)) dms += event to reach else refused.incrementAndGet()
            }

            is SimEvent.Room -> {
                if (from.sendRoom(event.body)) rooms += event else refused.incrementAndGet()
            }
        }
    }

    /**
     * Everyone walks to one spot: each group whole again, and every walker holding about six links, a phone's
     * budget and not the full mesh no radio would hold. They are laid as a chordal ring (one, a stride and a
     * stride squared along), so the gathered field is a few hops across however big the crowd. Waits for every DM
     * to arrive, or [GATHER_MS].
     */
    private suspend fun gather() {
        val stride = ceil(cbrt(scenario.walkers.toDouble())).toInt().coerceAtLeast(2)
        val ring =
            (0 until scenario.walkers).flatMap { a ->
                listOf(1, stride, stride * stride).map { (a + it) % scenario.walkers }.filter { it != a }.map { Link.of(a, it) }
            }
        val wanted = (groups.flatMap(::pairs) + ring).toSet() - radio.links
        lab.linkAll(*wanted.map(::ends).toTypedArray())
        awaitArrivals(GATHER_MS)
    }

    // Waits up to [timeoutMs] for every DM sent to have arrived.
    private suspend fun awaitArrivals(timeoutMs: Long) {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                while (outcomes(Long.MAX_VALUE, ticks = false).any { it.latencyMs == null }) delay(GATHER_POLL_MS)
            }
        }
    }

    // The ordinary messages [holder] has in [conversation], by body; [parked] keeps the ones still waiting for a key.
    private suspend fun rows(
        holder: LabNode,
        conversation: String,
        parked: Boolean = false,
    ): Map<String, MessageEntity> =
        holder.messages
            .observeNewestMessages(conversation, THREAD_WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL && (parked || !it.pendingKey) }
            .associateBy { it.body }

    // Where the DMs that never arrived are stuck, as far as the two ends can tell: the first thing a diagnosis asks.
    private suspend fun strandedLine(stranded: List<DmOutcome>): String {
        var parked = 0
        var held = 0
        for (dm in stranded.map { it.event }) {
            val from = nodes[dm.from]
            // The sender's own row: `pendingKey` is an outbound flag (a received row never carries it), and a frame the
            // recipient parked for a key is in its in-memory `PendingInbound`, which no row shows.
            val row = rows(from, from.dmWith(nodes[dm.to]), parked = true)[dm.body] ?: continue
            if (row.pendingKey) parked++
            if (row.id in from.custodyIds()) held++
        }
        return "gather: ${stranded.size} DMs had not arrived ${GATHER_MS / MS_PER_S} s on " +
            "($parked parked for a key at the sender, $held still in the sender's custody): " +
            stranded.joinToString { "${it.event.body} (${it.reach.name.lowercase()})" }
    }

    private fun drops(): Map<String, Long> =
        nodes
            .flatMap {
                it.metrics
                    .frames()
                    .dropsByReason.entries
            }.groupBy({ it.key.name }, { it.value })
            .mapValues { it.value.sum() }
            .filterValues { it > 0 }

    // Every DM sent, read off the recipient's own row (when it arrived) and, with [ticks], the sender's (whether its tick
    // came back: a query per message, which the gather's poll leaves out).
    private suspend fun outcomes(
        endedAt: Long,
        ticks: Boolean = true,
    ): List<DmOutcome> {
        val threads = HashMap<Pair<Int, Int>, Map<String, MessageEntity>>()
        val unticked = HashMap<Pair<Int, Int>, Set<String>>()
        return dms.map { (event, reach) ->
            val from = nodes[event.from]
            val to = nodes[event.to]
            val row = threads.getOrPut(event.to to event.from) { rows(to, to.dmWith(from)) }[event.body]
            val arrivedAt = row?.arrivedAt
            val missing = if (ticks) unticked.getOrPut(event.from to event.to) { untickedBodies(from, to) } else emptySet()
            DmOutcome(
                event = event,
                reach = reach,
                latencyMs = arrivedAt?.let { (it - row.sentAt).coerceAtLeast(0) },
                arrivedInRun = arrivedAt != null && arrivedAt <= endedAt,
                acked = ticks && event.body !in missing,
            )
        }
    }

    // The bodies of [from]'s DMs to [to] whose tick has not come back.
    private suspend fun untickedBodies(
        from: LabNode,
        to: LabNode,
    ): Set<String> = from.missingAcks(from.dmWith(to), listOf(from, to)).keys

    // How far a room post got: the share of the other walkers holding it when the run ended, and after the gather.
    private suspend fun roomLine(endedAt: Long): String {
        val held = nodes.map { rows(it, Conversations.NEARBY) }
        var inRun = 0
        var byEnd = 0
        for (post in rooms) {
            for ((walker, rows) in held.withIndex()) {
                val arrivedAt = rows[post.body]?.arrivedAt?.takeIf { walker != post.from } ?: continue
                byEnd++
                if (arrivedAt <= endedAt) inRun++
            }
        }
        val audience = rooms.size * (scenario.walkers - 1)
        return "room posts: ${rooms.size} sent, reached ${percent(inRun, audience)} of the other walkers in the run, " +
            "${percent(byEnd, audience)} by the end"
    }

    // The lab's own oracle, thread by thread: delivery, ticks, custody parity across the whole field. Returns how the
    // first thread to fail it failed, or null when every thread passed.
    private suspend fun oracle(): String? {
        val threads = dms.map { (event, _) -> Link.of(event.from, event.to) }.distinct()
        for (pair in threads) {
            val (a, b) = ends(pair)
            val count = dms.count { (event, _) -> Link.of(event.from, event.to) == pair }
            val thread = { node: LabNode -> if (node === a) a.dmWith(b) else b.dmWith(a) }
            val failure =
                runCatching { lab.assertConverged(listOf(a, b), count, nodes - a - b, timeoutMs = ORACLE_MS, conversation = thread) }
                    .exceptionOrNull() ?: continue
            val why =
                failure.message
                    .orEmpty()
                    .substringBefore('\n')
                    .take(WHY_CHARS)
            return "${a.name}–${b.name} did not converge: $why"
        }
        return null
    }

    /** The router's counters summed over the field, and the frames that crossed a link. */
    private data class Counters(
        val originated: Long,
        val relayed: Long,
        val deduped: Long,
        val suppressed: Long,
        val crossedLinks: Long,
    ) {
        operator fun minus(other: Counters) =
            Counters(
                originated - other.originated,
                relayed - other.relayed,
                deduped - other.deduped,
                suppressed - other.suppressed,
                crossedLinks - other.crossedLinks,
            )

        override fun toString(): String =
            "originated $originated, relayed $relayed, deduped $deduped, suppressed $suppressed, crossed a link $crossedLinks"

        companion object {
            fun of(nodes: List<LabNode>): Counters {
                val frames = nodes.map { it.metrics.frames() }
                return Counters(
                    originated = frames.sumOf { it.framesOriginated },
                    relayed = frames.sumOf { it.framesRelayed },
                    deduped = frames.sumOf { it.framesDeduped },
                    suppressed = frames.sumOf { it.framesSuppressed },
                    crossedLinks = nodes.sumOf { it.transport.framesReceived },
                )
            }
        }
    }

    private suspend fun usedHeapMb(): Long {
        repeat(2) {
            System.gc()
            delay(GC_PAUSE_MS)
        }
        return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / BYTES_PER_MB }
    }

    private fun cpuNanos(): Long = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
        const val BYTES_PER_MB = 1_048_576L
        const val GC_PAUSE_MS = 150L
        const val LOG_CLEAR_MS = 30_000L
        const val THREAD_WINDOW = 5_000

        /** Two rounds of the 60 s re-offer and some: what a frame left in custody needs to cross the gathered field. */
        const val GATHER_MS = 180_000L
        const val GATHER_POLL_MS = 1_000L

        /** Custody parity across a gathered field several hops wide is settled by the 60 s re-offer, not the link-up. */
        const val ORACLE_MS = 120_000L
        const val WHY_CHARS = 200
    }
}
