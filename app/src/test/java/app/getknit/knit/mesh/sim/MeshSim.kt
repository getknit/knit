package app.getknit.knit.mesh.sim

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.lab.LabLimits
import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.shadows.ShadowLog
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    /** Friends arrive together, [groupSize] at a time, unless [groups] names other sizes. */
    val groupSize: Int = 3,
    /** How the walkers split into groups ([GroupSizes.of]): `fixed` keeps [groupSize]; `street` draws a street's. */
    val groups: String = GroupSizes.FIXED,
    /** How the walkers move ([Motion.of]): `rwp`, each alone once its group has left the gate, or `groups`, together. */
    val motion: String = Motion.WAYPOINT,
    /** Whom the walkers DM ([Friends.of]): `group`, the friends they came with, or `circles`, their circle's too. */
    val friends: String = Friends.Group.name,
    /** Under `circles`, the share of a walker's DMs that go to its circle's other groups ([Friends.Circle.awayShare]). */
    val awayShare: Double = Friends.Circle.DEFAULT_AWAY_SHARE,
    /** How long a group stands together at its gate before its walkers set off. */
    val departMs: Long = 10_000,
    /** Which [Radio] turns places into links ([Radio.of]); the three numbers after it are [DiscRadio]'s. */
    val radio: String = DiscRadio.NAME,
    val connectM: Double = 25.0,
    val dropM: Double = 35.0,
    val dialMs: Long = 2_000,
    val tickMs: Long = 500,
    /** The mean gap between one walker's DMs ([Workload]); 0 sends none. */
    val dmMeanMs: Long = 20_000,
    /** The mean gap between one walker's Nearby posts; 0 posts none. */
    val roomMeanMs: Long = 60_000,
    /** The mean gap between one member's posts in its party's group chat ([Workload.parties]); 0, the default, makes none. */
    val groupMeanMs: Long = 0,
    /** Messages each walker sends while the boot links its party, before the walk ([Workload.backlog]): custody's history. */
    val backlog: Int = 0,
    /** Frames a node's router may have queued before its plane drops the next ([LabLimits.inboundSlots]); null holds all. */
    val inboundSlots: Int? = null,
    /** How long after the gather to keep reading custody across the field for parity ([MeshSim.settleLine]); 0 skips it. */
    val settleMs: Long = 0,
    /**
     * Whether everyone gathers after the walk, with the settle and the oracle after it. Without it the outing ends at the
     * walk: a measurement of the walk alone, which checks nothing ([SimReport.checked]).
     */
    val gather: Boolean = true,
    /** How the gather brings everyone together ([GatherBy]): `ring`, links laid by hand, or `walk`, everyone walking in. */
    val gatherBy: String = GatherBy.RING,
    /** Under `motion=park`: units arrive at a gate over this long from the start ([ParkConfig.arrivalWindowMs]). */
    val arrivalWindowMs: Long = 0,
    /** Under `motion=park`: the exponent of its choice of places ([ParkConfig.latp]). */
    val latp: Double = ParkConfig.DEFAULT_LATP,
    /** Under `motion=park`: how often a member of a group wanders off ([ParkConfig.excursionsPerHour]). */
    val excursionsPerHour: Double = ParkConfig.DEFAULT_EXCURSIONS_PER_HOUR,
) {
    init {
        require(gatherBy in GatherBy.NAMES) { "no gather named $gatherBy; there are ${GatherBy.NAMES}" }
    }
}

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
    /** Whether the outing gathered and asked the oracle: a walk-only outing measures and is never red. */
    val checked: Boolean = true,
)

/**
 * A crowd of real Knit stacks ([MeshLab] nodes) whose links are made and cut by where the walkers are, not by a
 * script: [Mobility] moves them, a [Radio] turns where they stand into links, and each step's changes are applied to
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
    private val world = SimWorld(scenario)
    private val groups = world.groups
    private val mobility = world.mobility
    private val radio = world.radio
    private val field = world.meter()
    private val events = world.events

    /** The walkers' nodes, once [play] has booted them: what a test reads the links they were given off. */
    internal lateinit var nodes: List<LabNode>
        private set
    private val dms = CopyOnWriteArrayList<Pair<SimEvent.Dm, Reach>>()
    private val rooms = CopyOnWriteArrayList<SimEvent.Room>()
    private val refused = AtomicInteger()
    private val unsent = AtomicInteger()
    private val worldLateMs = AtomicLong()
    private val followed = mutableListOf<Followed>()

    @Volatile private var gatheredAt = 0L
    private val sendLateMs = AtomicLong()
    private val heldPeakWalk = AtomicLong()
    private val heldPeak = AtomicLong()

    @Volatile private var heldPeakWhere = ""

    // Set as the gather begins, before its links come up: the sampler's line between the walk and the gather.
    @Volatile private var walkOver = false

    // The walking gather's line, once it has walked in ([walkIn]).
    private var walkInLine: String? = null

    // Set once the walk's last step is applied: a sample after it, with no gather, is labelled after the walk.
    @Volatile private var walkEnded = false
    private val probes = GatherProbes(nodes = { nodes }, groups = world.groups)
    private val chats = GroupChats(lab, world.parties)

    // Each workload DM's frame id, read off its sender's row as it is sent: what a custody row is matched against.
    private val dmIds = ConcurrentHashMap<SimEvent.Dm, String>()
    private val custodyPressure = CustodyPressure(ForwardRepository.DEFAULT_MAX_ROWS)
    private val custodyLock = Mutex()
    private val slowestSampleMs = AtomicLong()
    private var outingStart = 0L

    @Volatile private var settling = false

    // Every router counter as the walk began: what booting the nodes did, taken off what the report says moved.
    private var countersAtWalk: Map<String, Long> = emptyMap()

    // The report's headline numbers, put as each part of it is read, and written last ([SimSummary]).
    private val summary = SimSummary()

    // What the air links did from the boot's end: taken once the nodes exist ([boot]).
    private lateinit var airOuting: AirTally

    /** Boots, walks, gathers and reports. */
    suspend fun play(): SimReport {
        val lines = mutableListOf("mesh sim: park walk, $scenario")
        summary.putAll(scenario.summaryValues())
        summary["radio"] = radio.name
        summary.putAll(radio.summaryValues())
        lines += boot().also(progress)
        airOuting = AirTally(nodes)
        outingStart = lab.clock.now()
        // Launched once the nodes exist, so they never read them before boot, and sample nothing of the boot.
        return coroutineScope {
            val samplers = listOf(launch(Dispatchers.Default) { sampleHeld() }, launch(Dispatchers.Default) { sampleCustody() })
            try {
                walkAndReport(lines)
            } finally {
                samplers.forEach { it.cancel() }
            }
        }
    }

    private suspend fun sampleCustody() {
        while (true) {
            delay(CUSTODY_POLL_MS)
            readCustody()
        }
    }

    private suspend fun readCustody() = custodyLock.withLock { takeCustodySample() }

    // One reading of every store, each row classed by what it carries; only under [custodyLock]. The stores are read
    // before what has arrived, so a DM delivered while they were read counts as delivered, never as a copy custody lost.
    private suspend fun takeCustodySample() {
        val started = System.nanoTime()
        val now = lab.clock.now()
        val frames = nodes.map { it.custodyFrames() }
        val delivered = outcomes(Long.MAX_VALUE, ticks = false).filter { it.latencyMs != null }.mapTo(HashSet()) { it.event }
        val ids = dmIds.toMap()
        val pending = ids.entries.associate { (event, id) -> id to (event !in delivered) }
        val undelivered = ids.filterKeys { it !in delivered }.entries.associate { (event, id) -> id to event.from }
        val posts = chats.frameIds.toSet()
        val stores = frames.map { rows -> rows.map { heldRow(it, pending, posts) } }
        val phase =
            when {
                settling -> "settle"
                walkOver -> "gather"
                walkEnded -> "after the walk"
                else -> "walk"
            }
        // Into the progress file as it is taken: a run cut short keeps what it measured.
        progress(custodyPressure.line(custodyPressure.sample((now - outingStart) / MS_PER_S, phase, now, stores, undelivered)))
        slowestSampleMs.accumulateAndGet((System.nanoTime() - started) / NANOS_PER_MS, ::maxOf)
    }

    // The walk alone, measured and not judged: what had arrived when it ended, and custody under it.
    private suspend fun walkOnlyReport(
        lines: MutableList<String>,
        endedAt: Long,
    ): SimReport {
        // Every link the walk left up goes down first, so nothing crosses while the report reads: "by the end" is then
        // the walk's end, give or take what was already in flight.
        radio.links.map(::ends).forEach { (a, b) -> a.transport.disconnect(b.transport) }
        val outcomes = outcomes(endedAt)
        lines += "DMs between friends, by how the two stood when it was sent (no gather: the end is the walk's end):"
        lines += deliveryLines(outcomes)
        summary.putAll(deliverySummary(outcomes))
        lines += roomLine(nodes, rooms, endedAt, summary)
        chats.line(nodes, endedAt, summary)?.let { lines += it }
        lines += "frames refused since boot: ${drops(nodes).ifEmpty { "none" }}"
        airLines(lines, airOuting, "over the whole outing", key = "outing")
        val totals = counterTotals(nodes)
        lines += movedCounters(totals, since = countersAtWalk)
        summary.putAll(headlineCounters(totals, since = countersAtWalk))
        lines += custodyLines()
        lines += "oracle: not asked, the outing had no gather"
        summary["oracle"] = "skipped"
        lines += summary.lines()
        return SimReport(lines.joinToString("\n"), outcomes.size, unsent.get(), emptyList(), converged = false, checked = false)
    }

    // The last sample and the report read from it, both under the lock the sampler takes: no sample lands while the
    // report reads them.
    private suspend fun custodyLines(): List<String> =
        custodyLock.withLock {
            takeCustodySample()
            val missing = outcomes(Long.MAX_VALUE, ticks = false).filter { it.latencyMs == null }
            val neverArrived = missing.mapNotNullTo(HashSet()) { dmIds[it.event] }
            summary.putAll(custodyPressure.summaryValues(neverArrived))
            custodyPressure.lines(
                neverArrived = neverArrived,
                untallied = dms.size - dmIds.size,
                slowestSampleMs = slowestSampleMs.get(),
            )
        }

    private suspend fun walkAndReport(lines: MutableList<String>): SimReport {
        val endedAt = walkLines(lines)
        progress("the walk is over: ${dms.size} DMs and ${rooms.size} room posts sent, ${unsent.get()} never sent")
        if (!scenario.gather) return walkOnlyReport(lines, endedAt)
        val gathering = gatherLine(endedAt)
        return gatherReport(lines, endedAt, gathering)
    }

    // The walk itself, then its lines: the field's shape, the frames, the queues, custody and the machine. Returns when
    // it ended.
    private suspend fun walkLines(lines: MutableList<String>): Long {
        val before = RouterCounters.of(nodes)
        countersAtWalk = counterTotals(nodes)
        val inboundBefore = nodes.map { it.transport.inboundWaited to it.transport.inboundRefused }
        val airWalk = AirTally(nodes)
        val cpuBefore = cpuNanos()
        val atWalk = nodes.map { it.custodyIds().size }.sorted()
        summary["custody_rows_at_walk_min"] = atWalk.firstOrNull()
        summary["custody_rows_at_walk_p50"] = atWalk.getOrNull(atWalk.size / 2)
        summary["custody_rows_at_walk_max"] = atWalk.lastOrNull()
        val startedAt = lab.clock.now()
        walkAndTalk()
        val endedAt = lab.clock.now()
        walkEnded = true
        val cores = (cpuNanos() - cpuBefore).toDouble() / ((endedAt - startedAt) * NANOS_PER_MS)
        lines += field.lines()
        summary.putAll(field.summaryValues())
        (mobility as? ParkMobility)?.report(scenario.durationMs)?.let { (line, values) ->
            lines += line
            summary.putAll(values)
        }
        val walked = RouterCounters.of(nodes) - before
        lines += "frames in the run: $walked; room posts moderation refused ${refused.get()}"
        summary.putAll(walked.summaryValues(suffix = "walk"))
        summary["room_refused"] = refused.get()
        lines += inboundLine("in the run", since = inboundBefore, peak = heldPeakWalk.get(), key = "walk")
        airLines(lines, airWalk, "in the run", key = "walk")
        val custody = nodes.map { it.custodyIds().size }
        lines += "custody at the end of the run: mean ${custody.average().toInt()} rows, max ${custody.max()}"
        summary["custody_rows_walk_end_mean"] = custody.average().toInt()
        val heapMb = usedHeapMb()
        lines +=
            "machine: ${"%.2f".format(Locale.ROOT, cores)} cores busy, heap $heapMb MB after the run, " +
            "${Thread.activeCount()} threads; the world ran at most ${worldLateMs.get()} ms late, a send at most " +
            "${sendLateMs.get()} ms late, ${unsent.get()} sends fell past the end of the run"
        summary.putAll(
            listOf(
                "cores_busy_walk" to cores,
                "heap_mb_walk_end" to heapMb,
                "world_late_ms" to worldLateMs.get(),
                "send_late_ms" to sendLateMs.get(),
                "unsent" to unsent.get(),
            ),
        )
        return endedAt
    }

    // The gathered outing's report, after the walk's lines: delivery, the gather, the settle, and the oracle's verdict.
    private suspend fun gatherReport(
        lines: MutableList<String>,
        endedAt: Long,
        gathering: String,
    ): SimReport {
        val outcomes = outcomes(endedAt)
        lines += "DMs between friends, by how the two stood when it was sent:"
        lines += deliveryLines(outcomes)
        summary.putAll(deliverySummary(outcomes))
        lines += roomLine(nodes, rooms, endedAt, summary)
        chats.line(nodes, endedAt, summary)?.let { lines += it }
        lines += gathering
        val stranded = outcomes.filter { it.latencyMs == null }.map { it.event.body }
        summary["gather_dms_missing"] = stranded.size
        val groupStranded = chats.stranded(nodes, summary)
        val settled = settleLine()
        settled?.let { lines += it.text }
        lines += probes.line(after = if (settled == null) "the gather" else "the settle", walked = GatherBy.walks(scenario), summary)
        sampleFollowed()
        lines += lostLines()
        if (settled != null) {
            val still = if (stranded.isEmpty()) 0 else outcomes(Long.MAX_VALUE, ticks = false).count { it.latencyMs == null }
            if (stranded.isNotEmpty()) {
                lines += "settle: ${stranded.size - still} of the ${stranded.size} DMs missing after the gather arrived during it"
            }
            summary["settle_dms_still_missing"] = still
        }
        lines += "frames refused over the whole outing: ${drops(nodes).ifEmpty { "none" }}"
        val totals = counterTotals(nodes)
        lines += movedCounters(totals, since = countersAtWalk)
        summary.putAll(headlineCounters(totals, since = countersAtWalk))
        lines += inboundLine("over the whole outing", peak = heldPeak.get(), where = heldPeakWhere, key = "outing")
        airLines(lines, airOuting, "over the whole outing", key = "outing")
        lines += custodyLines()
        // With a DM missing the oracle's verdict is known, and each of its waits would run to its end to say so; so it
        // is with custody the settle watched not converge for at least as long as the oracle would wait. A shorter settle
        // would call a late field stuck, so then the oracle decides.
        val unsettled = settled?.parity == false && scenario.settleMs >= ORACLE_MS
        val failure = verdict(stranded, groupStranded, unsettled)
        lines += "oracle: " + (failure ?: "every DM and group thread converged (messages, ticks, custody parity across the field)")
        val strandedAll = stranded + groupStranded
        summary["oracle"] = oracleWord(failure, stranded = strandedAll.isNotEmpty(), unsettled = unsettled)
        lines += summary.lines()
        return SimReport(lines.joinToString("\n"), outcomes.size, unsent.get(), strandedAll, converged = failure == null)
    }

    // The gather, and for what it left undelivered, where the two ends say it is stuck.
    private suspend fun gatherLine(endedAt: Long): String {
        gather()
        walkInLine?.let(progress)
        val late = outcomes(endedAt, ticks = false).filter { it.latencyMs == null }
        progress("gathered: ${late.size} DMs still missing")
        val arrived = if (late.isEmpty()) "gather: every DM arrived" else strandedLine(late)
        return listOfNotNull(walkInLine, arrived).joinToString("\n")
    }

    private fun pairs(group: List<Int>): List<Link> = SimWorld.pairs(group)

    private fun ends(link: Link): Pair<LabNode, LabNode> = nodes[link.a] to nodes[link.b]

    // The nodes, then every group linked, acquainted and accepted as contacts: friends before they walk in.
    private suspend fun boot(): String {
        val heapBefore = usedHeapMb()
        val started = System.nanoTime()
        val limits = LabLimits(inboundSlots = scenario.inboundSlots, nodeAirBytesPerSecond = radio.nodeAirBytesPerSecond)
        nodes = (0 until scenario.walkers).map { i -> lab.node("w$i", limits).apply { setDisplayName("Walker $i") } }
        val bootMs = (System.nanoTime() - started) / NANOS_PER_MS / scenario.walkers
        // Everyone who DMs each other meets first: a group, or under circles its whole circle. Friends who came separately
        // already know each other, so only the links the radio starts with stay up once they have. While linked, a party
        // opens its group chat and sends what it sent before the outing (its backlog), and its stores settle on it.
        val backlog = world.backlog.groupBy { it.from }
        for ((party, members) in world.parties.withIndex()) {
            val friends = members.map { nodes[it] }.toTypedArray()
            lab.linkAll(*pairs(members).map(::ends).toTypedArray(), air = radio.air)
            lab.awaitAcquainted(*friends)
            lab.befriend(*friends)
            if (scenario.groupMeanMs > 0) chats.open(party, friends.toList())
            val history = members.flatMap { backlog[it].orEmpty() }
            if (history.isNotEmpty()) {
                history.forEach { if (!sendBacklog(nodes, chats, it)) refused.incrementAndGet() }
                lab.awaitCustodyParity(*friends)
            }
            pairs(members).filterNot { it in radio.links }.map(::ends).forEach { (a, b) -> a.transport.disconnect(b.transport) }
        }
        val perNode = (usedHeapMb() - heapBefore).toDouble() / scenario.walkers
        val heapEach = "%.1f".format(Locale.ROOT, perNode)
        summary["boot_ms_each"] = bootMs
        summary["heap_mb_each"] = perNode
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
        val present = BooleanArray(nodes.size) { mobility.present(it, atMs) }
        val changes = radio.step(atMs, positions, present)
        changes.dropped.map(::ends).forEach { (a, b) -> a.transport.disconnect(b.transport) }
        changes.raised.map(::ends).forEach { (a, b) -> lab.link(a, b, air = radio.air) }
        // The field is measured over the walk; a walking gather's walk-in is the gather's, not the walk's.
        if (atMs <= scenario.durationMs) field.record(atMs, positions, present, changes, radio.links)
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
                if (from.sendDm(nodes[event.to], event.body)) {
                    dms += event to reach
                    rows(from, from.dmWith(nodes[event.to]), parked = true)[event.body]?.id?.let { dmIds[event] = it }
                } else {
                    refused.incrementAndGet()
                }
            }

            is SimEvent.Room -> {
                if (from.sendRoom(event.body)) rooms += event else refused.incrementAndGet()
            }

            is SimEvent.Group -> {
                if (!chats.send(from, event)) refused.incrementAndGet()
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
        walkOver = true
        if (GatherBy.walks(scenario)) {
            walkIn()
            awaitArrivals(GATHER_MS)
            return
        }
        val stride = ceil(cbrt(scenario.walkers.toDouble())).toInt().coerceAtLeast(2)
        val ring =
            (0 until scenario.walkers).flatMap { a ->
                listOf(1, stride, stride * stride).map { (a + it) % scenario.walkers }.filter { it != a }.map { Link.of(a, it) }
            }
        val wanted = (groups.flatMap(::pairs) + ring).toSet() - radio.links
        lab.linkAll(*wanted.map(::ends).toTypedArray(), air = radio.air)
        probes.send(wanted)
        follow()
        awaitArrivals(GATHER_MS)
    }

    /**
     * The walking gather ([GatherBy.WALK]): the walk goes on to the meeting point on the wall clock, the radio raising
     * and cutting its own links as it does in the walk, until the last walker has stood there [FieldRun.GATHER_HOLD_MS].
     * Then the probes go over the links the walk-in raised. The field's instruments stop at the walk's end.
     */
    private suspend fun walkIn() {
        val gathering = mobility as Gathering
        val before = radio.links.toSet()
        val watch = GatherWatch(nodes.size, scenario.durationMs)
        watch.step(scenario.durationMs, radio.links)
        follow()
        val origin = System.nanoTime()
        var atMs = scenario.durationMs + scenario.tickMs
        while (atMs <= gathering.gatheredByMs + FieldRun.GATHER_HOLD_MS) {
            worldLateMs.accumulateAndGet(waitUntil(origin, atMs - scenario.durationMs), ::maxOf)
            step(atMs)
            watch.step(atMs, radio.links)
            atMs += scenario.tickMs
        }
        walkInLine = watch.line(gathering.gatheredByMs, radio.links)
        summary.putAll(watch.summaryValues(gathering.gatheredByMs, radio.links))
        probes.send(radio.links - before, friends = events.filterIsInstance<SimEvent.Dm>().mapTo(HashSet()) { Link.of(it.from, it.to) })
    }

    // The most link frames one node's readers held back at once, sampled through the outing: how deep a phone's backlog
    // runs once its readers wait for room (#331) instead of dropping.
    private suspend fun sampleHeld() {
        while (true) {
            delay(HELD_POLL_MS)
            val most = nodes.maxBy { it.transport.inboundHeld }
            val held = most.transport.inboundHeld
            val gathering = walkOver
            val at = gatheredAt
            if (!gathering) heldPeakWalk.accumulateAndGet(held, ::maxOf)
            if (held > heldPeak.get()) {
                heldPeak.set(held)
                heldPeakWhere = "on ${most.name} " +
                    when {
                        !gathering -> "in the walk"
                        at == 0L -> "as the gather's links came up"
                        else -> "+${(lab.clock.now() - at) / MS_PER_S}s after the gather's links came up"
                    }
            }
        }
    }

    // Waits up to [timeoutMs] for every DM sent to have arrived, reading where the missing ones are as it goes. The
    // probes are read too, but a late one does not hold the gather open: the settle would start later than it says.
    private suspend fun awaitArrivals(timeoutMs: Long) {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                var polls = 0L
                // Every DM and every group post: a post still riding custody toward one member is late, not lost.
                while (outcomes(Long.MAX_VALUE, ticks = false).any { it.latencyMs == null } || chats.missing(nodes).isNotEmpty()) {
                    probes.missing()
                    delay(GATHER_POLL_MS)
                    if (++polls % (FOLLOW_POLL_MS / GATHER_POLL_MS) == 0L) sampleFollowed()
                }
            }
        }
    }

    /**
     * A DM still missing when the gather began, followed to the end of the outing: its frame id, the crossing key of
     * every seal of it the sender held at a sample ([keys]: a re-seal keeps the id and signs anew), and [trail], seconds
     * since the gather's links came up → how many stores held it then.
     */
    private class Followed(
        val event: SimEvent.Dm,
        val id: String?,
    ) {
        val keys = HashSet<String>()
        val trail = sortedMapOf<Long, String>()
        var last: String? = null
    }

    // Starts following every DM not yet arrived as the gather's links come up.
    private suspend fun follow() {
        gatheredAt = lab.clock.now()
        for (dm in outcomes(Long.MAX_VALUE, ticks = false).filter { it.latencyMs == null }) {
            val from = nodes[dm.event.from]
            val id = rows(from, from.dmWith(nodes[dm.event.to]), parked = true)[dm.event.body]?.id
            followed += Followed(dm.event, id)
        }
        sampleFollowed()
    }

    // Notes, for each followed DM, how many stores hold it, whether the sender is one, and whether it has arrived.
    private suspend fun sampleFollowed() {
        if (followed.isEmpty()) return
        val held = nodes.map { it.custodyIds() }
        val atS = (lab.clock.now() - gatheredAt) / MS_PER_S
        for (f in followed) {
            val id = f.id ?: continue
            val from = nodes[f.event.from]
            val to = nodes[f.event.to]
            from.crossingKey(id)?.let { f.keys += it }
            val sender = if (id in held[f.event.from]) ", the sender among them" else ""
            val arrived = if (rows(to, to.dmWith(from))[f.event.body] != null) ", arrived" else ""
            val state = "held by ${held.count { id in it }}$sender$arrived"
            if (state != f.last) {
                f.trail[atS] = state
                f.last = state
            }
        }
    }

    // For each followed DM still missing: who held it and when, how often it crossed to its recipient, and what met it.
    private suspend fun lostLines(): List<String> {
        val lines = mutableListOf<String>()
        for (f in followed) {
            val from = nodes[f.event.from]
            val to = nodes[f.event.to]
            if (rows(to, to.dmWith(from))[f.event.body] == null) lines += lostLine(f, from, to)
        }
        return lines
    }

    private suspend fun lostLine(
        f: Followed,
        from: LabNode,
        to: LabNode,
    ): String {
        val id = f.id ?: return "lost: ${f.event.body}: the sender has no row for it"
        val via = HashMap<String, Int>()
        for (node in nodes) {
            for (line in node.transport.sent) {
                val parts = line.split(' ', limit = SENT_PARTS)
                val toRecipient = parts.size == SENT_PARTS && parts[SENT_ID] == id && to.nodeId.startsWith(parts[1])
                if (toRecipient) via.merge(node.name, 1, Int::plus)
            }
        }
        val skipped = f.keys.sumOf { key -> nodes.sumOf { it.transport.skipsToward(to.nodeId, key) } }
        val row = rows(from, from.dmWith(to), parked = true)[f.event.body]
        val ticked = f.event.body !in untickedBodies(from, to)
        return "lost: ${f.event.body} (${from.name}→${to.name}, frame ${id.take(FRAME_ID_CHARS)}): since the gather's links came up " +
            f.trail.entries.joinToString { "+${it.key}s ${it.value}" } + "; crossed to ${to.name} ${via.values.sum()} times " +
            "(${via.entries.joinToString { "${it.key} ${it.value}" }}), ${to.transport.inboundRefused(id)} page copies dropped at " +
            "its full queue, ${to.transport.inboundHeld} frames held on its links now; $skipped writes toward it skipped as " +
            "already crossed, over ${f.keys.size} seals; the sender's row " +
            if (row == null) "is gone" else "pendingKey=${row.pendingKey}, ticked=$ticked"
    }

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

    // The air links' line and summary keys for [phase], on a radio whose links have airtime ([Radio.air]).
    private fun airLines(
        lines: MutableList<String>,
        tally: AirTally,
        phase: String,
        key: String,
    ) {
        if (radio.air == null) return
        val (line, values) = tally.report(phase, key)
        lines += line
        summary.putAll(values)
    }

    // What the nodes' full inbound queues held back, [phase], which only a bounded run has: a link's reader waits for a
    // slot (#331), a page that finds its lane full is dropped. [since] holds each node's (waits, pages) at the start of
    // [phase]; [peak] is the most link frames one node's readers held at once.
    private fun inboundLine(
        phase: String,
        since: List<Pair<Long, Long>> = nodes.map { 0L to 0L },
        peak: Long,
        where: String = "",
        key: String,
    ): String {
        val slots = scenario.inboundSlots ?: return "inbound queues: no bound, nothing held back $phase"
        val waits = nodes.mapIndexed { i, node -> node.transport.inboundWaited - since[i].first }
        val pages = nodes.indices.sumOf { nodes[it].transport.inboundRefused - since[it].second }
        val most = waits.withIndex().maxBy { it.value }
        summary["inbound_waits_$key"] = waits.sum()
        summary["inbound_held_peak_$key"] = peak
        summary["inbound_pages_dropped_$key"] = pages
        return "inbound queues of $slots: link readers waited for a slot ${waits.sum()} times $phase, on " +
            "${waits.count { it > 0 }} of ${nodes.size} nodes, at most ${most.value} on ${nodes[most.index].name}; at most " +
            "$peak frames held on one node's links at once ${where.ifEmpty { "(sampled)" }}; $pages pages dropped"
    }

    /** How custody across the field stood once the settle ended, and whether every store held the same ids. */
    private class Settled(
        val text: String,
        val parity: Boolean,
    )

    /**
     * After the gather, whether the stores come to hold the same live ids, and how fast the gap closes: a gap that
     * shrinks is late, one that stops shrinking is stuck. Reads every store each [SETTLE_POLL_MS] until they match or
     * [SimScenario.settleMs] has passed, then names what is still odd: its type, author, who holds it, and the author's
     * share of the stores that lack it (the per-sender quota's edge reads as a full bucket there).
     */
    private suspend fun settleLine(): Settled? {
        if (scenario.settleMs <= 0) return null
        settling = true
        val startedAt = lab.clock.now()
        var gap = CustodyGap(nodes.map { it.custodyIds() })
        // Seconds after the gather → rows missing: the first reading, the first of each minute after, and the last.
        val trail = sortedMapOf(0L to gap.missing)
        var tookS = 0L
        withContext(Dispatchers.Default) {
            while (!gap.parity && lab.clock.now() - startedAt < scenario.settleMs) {
                delay(SETTLE_POLL_MS)
                gap = CustodyGap(nodes.map { it.custodyIds() })
                tookS = (lab.clock.now() - startedAt) / MS_PER_S
                sampleFollowed()
                probes.missing()
                progress("settle +${tookS}s: ${gap.odd.size} ids odd, ${gap.missing} rows missing on ${gap.short} stores")
                if (trail.keys.none { it / SECONDS_PER_MINUTE == tookS / SECONDS_PER_MINUTE }) trail[tookS] = gap.missing
            }
        }
        trail[tookS] = gap.missing
        val missingTrail = "rows missing: ${trail.entries.joinToString { "+${it.key}s ${it.value}" }}"
        val described = described(nodes)
        val kinds =
            gap.union
                .groupingBy { described[it]?.get(1) ?: "?" }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
        // Matching full stores may each have evicted an odd id rather than all received it: the ids gone everywhere
        // leave no trace here.
        val cap = if (gap.held.any { it.size >= ForwardRepository.DEFAULT_MAX_ROWS }) ", stores at custody's cap" else ""
        val held = "the field's ${gap.union.size} ids by type$cap: ${kinds.joinToString { "${it.key} ${it.value}" }}"
        summary["settle_parity_s"] = if (gap.parity) tookS else null
        summary["settle_rows_missing_end"] = gap.missing
        if (gap.parity) {
            return Settled("settle: every store held the same ids ${tookS}s after the gather ($missingTrail); $held", true)
        }
        return Settled(
            "settle: no parity ${tookS}s after the gather: ${gap.odd.size} ids odd, ${gap.missing} rows missing on " +
                "${gap.short} of ${nodes.size} stores ($missingTrail); $held\n" + oddLines(gap, described, nodes),
            false,
        )
    }

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

    // Why the outing failed, or null: what is still missing decides it before the oracle is asked.
    private suspend fun verdict(
        stranded: List<String>,
        groupStranded: List<String>,
        unsettled: Boolean,
    ): String? =
        when {
            stranded.isNotEmpty() -> "not asked, ${stranded.size} DMs never arrived"
            groupStranded.isNotEmpty() -> "not asked, ${groupStranded.size} group posts never reached every member"
            unsettled -> "not asked, custody never matched in the settle"
            else -> oracle() ?: chats.oracle(nodes, ORACLE_MS)
        }

    // The lab's own oracle, thread by thread: delivery, ticks, custody parity across the whole field. Returns how the
    // first thread to fail it failed, or null when every thread passed.
    // Every thread a DM was sent in, probes included: a lost probe is a strand like any other DM.
    private suspend fun oracle(): String? {
        val threads = (dms.map { (event, _) -> Link.of(event.from, event.to) } + probes.pairs).distinct()
        for (pair in threads) {
            val (a, b) = ends(pair)
            val count =
                dms.count { (event, _) -> Link.of(event.from, event.to) == pair } +
                    probes.pairs.count { it == pair }
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

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
        const val LOG_CLEAR_MS = 30_000L

        /** Two rounds of the 60 s re-offer and some: what a frame left in custody needs to cross the gathered field. */
        const val GATHER_MS = 180_000L
        const val GATHER_POLL_MS = 1_000L

        const val HELD_POLL_MS = 250L

        /** Every store read once a minute: a 33-minute outing reports in about 33 lines. */
        const val CUSTODY_POLL_MS = 60_000L

        /** Custody parity across a gathered field several hops wide is settled by the 60 s re-offer, not the link-up. */
        const val ORACLE_MS = 120_000L
        const val WHY_CHARS = 200
        const val SETTLE_POLL_MS = 15_000L
        const val FOLLOW_POLL_MS = 15_000L

        /** `#seq receiver type id relay=… via=…`, as [app.getknit.knit.mesh.lab.LabTransport.sent] logs a crossing. */
        const val SENT_PARTS = 5
        const val SENT_ID = 3
        const val FRAME_ID_CHARS = 8
        const val SECONDS_PER_MINUTE = 60L
    }
}
