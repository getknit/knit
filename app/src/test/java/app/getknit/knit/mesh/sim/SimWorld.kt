package app.getknit.knit.mesh.sim

import java.util.Locale

/**
 * A scenario's world without its stacks: the groups and their gates, how everyone moves, whom they DM, the radio and the
 * workload. All of it comes from the seed and the scenario alone, and the radio's links from where the walkers stand
 * alone, never from what the mesh does: so an outing with stacks ([MeshSim]) and one without ([FieldRun]) walk the same
 * crowd and make the same links.
 *
 * A [trace] replaces the motion: its walkers are the scenario's, nobody starts linked, and nobody walks in a group or
 * DMs, since a recording knows neither.
 */
class SimWorld(
    val scenario: SimScenario,
    val trace: TraceMobility? = null,
) {
    init {
        require(trace == null || trace.walkers == scenario.walkers) { "a trace of ${trace?.walkers} walkers for ${scenario.walkers}" }
    }

    val groups: List<List<Int>> = GroupSizes.of(scenario.groups, scenario.groupSize).partition(scenario.seed, scenario.walkers)
    val mobility: Mobility = trace ?: Motion.of(scenario, groups, gates())
    val friends: Friends = Friends.of(scenario.friends, scenario.seed, groups, scenario.awayShare)

    // The groups at their gates at the start: linked before the walk, as friends who arrived together are.
    val radio: Radio =
        Radio.of(
            scenario,
            initial =
                if (trace == null && Motion.startsTogether(scenario.motion)) {
                    groups.filter { g -> g.all { mobility.present(it, 0) } }.flatMap(::pairs).toSet()
                } else {
                    emptySet()
                },
        )
    val events: List<SimEvent> =
        if (trace != null) {
            emptyList()
        } else {
            Workload.schedule(
                scenario.seed,
                groups,
                scenario.durationMs,
                scenario.dmMeanMs,
                scenario.roomMeanMs,
                friends,
                scenario.groupMeanMs,
            )
        }

    /** Everyone who knows each other before the outing ([Workload.parties]): what the boot acquaints. */
    val parties: List<List<Int>> = Workload.parties(groups, friends)

    /** What each walker sent before the outing, sent as the boot links its party ([Workload.backlog]). */
    val backlog: List<SimEvent> =
        if (trace != null) {
            emptyList()
        } else {
            Workload.backlog(scenario.seed, parties, scenario.backlog, scenario.dmMeanMs, scenario.roomMeanMs, scenario.groupMeanMs)
        }

    /** The instruments for this world's walk, before its first step. */
    fun meter(): FieldMeter =
        FieldMeter(
            walkers = scenario.walkers,
            seed = scenario.seed,
            groups = groups.takeIf { trace == null },
            maxLinks = radio.maxLinks,
            dmPairs = events.filterIsInstance<SimEvent.Dm>().mapTo(HashSet()) { Link.of(it.from, it.to) },
            initialLinks = radio.links,
        )

    // One gate a group, where it stands until it sets off: a point drawn in the field, shared by its walkers.
    private fun gates(): List<Point> {
        val random = kotlin.random.Random(scenario.seed)
        return groups.map { Point(random.nextDouble() * scenario.fieldM, random.nextDouble() * scenario.fieldM) }
    }

    companion object {
        /** Every pair in [group], lower walker first. */
        fun pairs(group: List<Int>): List<Link> = group.flatMapIndexed { i, a -> group.drop(i + 1).map { b -> Link.of(a, b) } }
    }
}

/**
 * The walk and the radio over a whole outing with no stacks behind them: [SimWorld]'s positions, links and workload,
 * stepped as [MeshSim] steps them but as fast as the machine goes, through the same [FieldMeter]. Its contact numbers are
 * the outing's own, to the link, in seconds for what an outing with stacks takes its whole length for, so a mobility
 * model is measured over hours and hundreds of walkers. What it cannot say is what the mesh did with the links: each DM
 * is only classed by how its two ends stood when it was sent ([Reach]).
 */
class FieldRun(
    private val world: SimWorld,
) {
    /** The report: what the outing was, the field's lines, the DMs' reach and a `summary:` block. */
    fun play(): String {
        val scenario = world.scenario
        val radio = world.radio
        val mobility = world.mobility
        val walkers = scenario.walkers
        val meter = world.meter()
        val dms = world.events.filterIsInstance<SimEvent.Dm>()
        val reach = IntArray(Reach.entries.size)
        var next = 0
        val started = System.nanoTime()
        var atMs = 0L
        while (atMs <= scenario.durationMs) {
            val positions = (0 until walkers).map { mobility.position(it, atMs) }
            val present = BooleanArray(walkers) { mobility.present(it, atMs) }
            val changes = radio.step(atMs, positions, present)
            meter.record(atMs, positions, present, changes, radio.links)
            // A DM is sent between this step and the next, over the links this one left: as [MeshSim] sends it.
            while (next < dms.size && dms[next].atMs < atMs + scenario.tickMs) {
                reach[Reach.of(hops(radio.links, dms[next].from, dms[next].to)).ordinal]++
                next++
            }
            atMs += scenario.tickMs
        }
        val gather = if (GatherBy.walks(scenario) && world.trace == null) walkIn() else null
        val seconds = (System.nanoTime() - started) / NANOS_PER_S
        val summary = SimSummary()
        summary.putAll(scenario.summaryValues())
        world.trace?.let { summary["motion"] = Motion.TRACE }
        summary["trace"] = world.trace?.name
        summary["radio"] = radio.name
        summary.putAll(radio.summaryValues())
        val lines = mutableListOf("mesh sim: the field alone, ${world.trace?.let { "replaying ${it.name}, " }.orEmpty()}$scenario")
        lines += meter.lines()
        summary.putAll(meter.summaryValues())
        (mobility as? ParkMobility)?.report(scenario.durationMs)?.let { (line, values) ->
            lines += line
            summary.putAll(values)
        }
        if (dms.isNotEmpty()) {
            lines += "DMs between friends by how the two stood when sent: " +
                Reach.entries.joinToString(", ") { "${it.name.lowercase()} ${reach[it.ordinal]}" } + " of ${dms.size}"
            Reach.entries.forEach { summary["dm_${it.name.lowercase()}_sent"] = reach[it.ordinal] }
            summary["dm_all_sent"] = dms.size
        }
        gather?.let { (line, values) ->
            lines += line
            summary.putAll(values)
        }
        lines += "machine: ${"%.1f".format(Locale.ROOT, seconds)} s for ${scenario.durationMs / MS_PER_S} s of field"
        summary["field_run_s"] = seconds
        lines += summary.lines()
        return lines.joinToString("\n")
    }

    // The walk to the meeting point after the walk, stepped on through the radio until the last walker has stood there
    // [GATHER_HOLD_MS]: the field is not measured, only how the radio's links joined the crowd.
    private fun walkIn(): Pair<String, List<Pair<String, Any?>>> {
        val scenario = world.scenario
        val gathering = world.mobility as Gathering
        val watch = GatherWatch(scenario.walkers, scenario.durationMs)
        watch.step(scenario.durationMs, world.radio.links)
        var atMs = scenario.durationMs + scenario.tickMs
        while (atMs <= gathering.gatheredByMs + GATHER_HOLD_MS) {
            val positions = (0 until scenario.walkers).map { world.mobility.position(it, atMs) }
            val present = BooleanArray(scenario.walkers) { world.mobility.present(it, atMs) }
            world.radio.step(atMs, positions, present)
            watch.step(atMs, world.radio.links)
            atMs += scenario.tickMs
        }
        return watch.line(gathering.gatheredByMs, world.radio.links) to watch.summaryValues(gathering.gatheredByMs, world.radio.links)
    }

    companion object {
        /** How long a walking gather goes on once the last walker has reached its spot: time for the last dials. */
        const val GATHER_HOLD_MS = 30_000L
        private const val NANOS_PER_S = 1e9
        private const val MS_PER_S = 1_000L
    }
}
