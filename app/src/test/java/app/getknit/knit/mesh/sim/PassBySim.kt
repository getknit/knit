package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.LabLimits
import app.getknit.knit.mesh.lab.LabNode
import app.getknit.knit.mesh.lab.MeshLab
import app.getknit.knit.mesh.sim.SimSeeds.Purpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.robolectric.shadows.ShadowLog

/** A sidewalk of passing pairs ([PassBy]) and what each walker carries into its pass. */
data class PassByScenario(
    val seed: Long,
    val pairs: Int = DEFAULT_PAIRS,
    val speedMps: Double = PassBy.DEFAULT_SPEED_MPS,
    val lateralM: Double = PassBy.DEFAULT_LATERAL_M,
    /** DMs each walker queues before its pass, half to the walker it passes and half to a contact who stayed home. */
    val dms: Int = DEFAULT_DMS,
    /** Photos each walker queues the same way. */
    val pictures: Int = 0,
    val pictureBytes: Int = DEFAULT_PICTURE_KB * BYTES_PER_KB,
    val radio: String = BleRadio.NAME,
    val connectM: Double = DEFAULT_RANGE_M,
    val dropM: Double = DEFAULT_RANGE_M * SimProperties.DROP_PER_RANGE,
    /** Under `ble1m+nan` ([NanRadio]): Aware's reach, the Bluetooth range when null, and its failed data paths' share. */
    val nanRangeM: Double? = null,
    val nanFailShare: Double? = null,
    val nanDiscoverMs: Long? = null,
    /** The `disc` radio's dial ([DiscRadio]); the others draw their own. */
    val dialMs: Long = DEFAULT_DIAL_MS,
    val tickMs: Long = DEFAULT_TICK_MS,
    val startWindowMs: Long = PassBy.DEFAULT_START_WINDOW_MS,
    /** How far beyond the widest reach each walker starts ([PassBy.of]). */
    val startMarginM: Double = PassBy.START_MARGIN_M,
    /** How long a pair is watched once it is out of reach again: what was on the air lands, nothing else can. */
    val watchAfterMs: Long = DEFAULT_WATCH_AFTER_MS,
) {
    /** The widest any link reaches: Bluetooth's drop, or Aware's under `ble1m+nan`, held as far past its reach. */
    val reachM: Double
        get() = if (radio == NanRadio.NAME) maxOf(dropM, (nanRangeM ?: connectM) * dropM / connectM) else dropM

    /** Where a pair counts as in range: the nearest a plane dials or finds a peer from. */
    val inRangeM: Double
        get() = if (radio == NanRadio.NAME) maxOf(connectM, nanRangeM ?: connectM) else connectM

    /** The outing a [Radio] is built from: only its radio, range, tick and seed matter. */
    val radioScenario: SimScenario
        get() =
            SimScenario(
                seed = seed,
                walkers = pairs * 2,
                durationMs = 0,
                fieldM = 0.0,
                radio = radio,
                connectM = connectM,
                dropM = dropM,
                dialMs = dialMs,
                nanRangeM = nanRangeM,
                nanFailShare = nanFailShare,
                nanDiscoverMs = nanDiscoverMs,
                tickMs = tickMs,
            )

    fun summaryValues(): List<Pair<String, Any?>> =
        listOf(
            "seed" to seed,
            "pairs" to pairs,
            "speed_mps" to speedMps,
            "lateral_m" to lateralM,
            "dms_each" to dms,
            "pictures_each" to pictures,
            "picture_kb" to pictureBytes / BYTES_PER_KB,
        )

    companion object {
        const val DEFAULT_PAIRS = 20
        const val DEFAULT_DMS = 20
        const val DEFAULT_PICTURE_KB = 200
        const val DEFAULT_RANGE_M = 15.0
        const val DEFAULT_TICK_MS = 500L
        const val DEFAULT_DIAL_MS = 2_000L
        const val DEFAULT_WATCH_AFTER_MS = 30_000L
        const val BYTES_PER_KB = 1_024
    }
}

/** What a pass-by run found: [text] is the whole report, the rest is what a test asserts on. */
class PassByReport(
    val text: String,
    val passes: List<PassOutcome>,
) {
    val converged: Int get() = passes.count { it.verdict == PassVerdict.CONVERGED }
}

/** How one pass ended. */
enum class PassVerdict {
    /** Everything each walker brought for the other, and every frame it carried for the other's home, crossed in range. */
    CONVERGED,

    /** Linked, and the link dropped with something still to cross; what landed after the drop's tick is in [LATE]. */
    CUT_SHORT,

    /** Converged, but only after the drop's tick: on records already on the air, or not before the watch ended. */
    LATE,

    /** In range, never linked: the dial outlasted the pass. */
    NEVER_LINKED,
}

/** One pair's pass, its times ms into the run. */
data class PassOutcome(
    val pair: Int,
    val verdict: PassVerdict,
    val inRangeAtMs: Long?,
    val linkedAtMs: Long?,
    val droppedAtMs: Long?,
    val convergedAtMs: Long?,
    /** When both had every DM and carried frame the other brought, photos aside. */
    val textAtMs: Long?,
    /** The photos each brought for the other that the other holds whole at the end, of those sent. */
    val picturesLanded: Int,
    val pictures: Int,
    /** The links the pair raised in its pass: more than one is a flap. */
    val contacts: Int,
    /** The photos carried for the other's home whose bytes the carrier holds at the end, of those sent. */
    val carriedPicturesHeld: Int,
    val carriedPictures: Int,
) {
    val dialMs: Long? get() = if (inRangeAtMs != null && linkedAtMs != null) linkedAtMs - inRangeAtMs else null
    val linkMs: Long? get() = if (linkedAtMs != null && droppedAtMs != null) droppedAtMs - linkedAtMs else null
    val convergeMs: Long? get() = if (linkedAtMs != null && convergedAtMs != null) convergedAtMs - linkedAtMs else null
    val textMs: Long? get() = if (linkedAtMs != null && textAtMs != null) textAtMs - linkedAtMs else null
}

/**
 * Pairs of real Knit stacks walking past each other on a sidewalk ([PassBy]), each walker carrying what the other lacks:
 * how often a pass is long enough to converge.
 *
 * **Before the walk** each pair meets, and each walker meets a contact who stays home: linked, acquainted and accepted,
 * then cut. Out of everyone's reach, each walker queues [PassByScenario.dms] DMs and [PassByScenario.pictures] photos,
 * every other one to the walker it will pass and the rest to its home contact, so custody holds them and the other
 * walker can carry the home ones on.
 *
 * **The walk** is [MeshSim]'s: real time, a radio step every tick applied to the lab as it comes ([applyLinks]). Each
 * pair is watched from its first link until it is [PassByScenario.watchAfterMs] past reach. It converged when each
 * walker holds every DM and photo the other brought for it, and each custody holds every frame the other brought for
 * its home, within a tick of the link dropping.
 */
class PassBySim(
    private val lab: MeshLab,
    private val scenario: PassByScenario,
    private val progress: (String) -> Unit = {},
) {
    private val radio = Radio.of(scenario.radioScenario, emptySet())
    private val sidewalk =
        PassBy.of(
            seed = scenario.seed,
            pairs = scenario.pairs,
            dropM = scenario.reachM,
            lateralM = scenario.lateralM,
            speedMps = scenario.speedMps,
            startWindowMs = scenario.startWindowMs,
            startMarginM = scenario.startMarginM,
        )
    private lateinit var walkers: List<Walker>

    @Volatile private var worldLateMs = 0L
    private val passes = List(scenario.pairs) { Pass() }

    // What one walker brought into its pass, for the walker it passes and for its home contact.
    private class Walker(
        val node: LabNode,
        val home: LabNode,
    ) {
        val forOther = mutableListOf<String>()
        val picturesForOther = mutableListOf<Pair<String, ByteArray>>()
        var carriedIds: Set<String> = emptySet()
        val carriedPictureHashes = mutableListOf<String>()
    }

    // One pair's times, on the wall clock from the walk's start: the radio's written by the world loop, the convergence's
    // by [settle] under [lock].
    private class Pass {
        val lock = Mutex()

        @Volatile var inRangeAtMs: Long? = null

        @Volatile var linkedAtMs: Long? = null

        @Volatile var droppedAtMs: Long? = null

        @Volatile var convergedAtMs: Long? = null

        @Volatile var textAtMs: Long? = null

        @Volatile var contacts = 0
    }

    suspend fun play(): PassByReport {
        val boot = boot()
        progress(boot)
        val endMs = (0 until scenario.pairs).maxOf { sidewalk.apartAfterMs(it, scenario.reachM) } + scenario.watchAfterMs
        walk(endMs)
        return report(boot, endMs)
    }

    // Every pair met, and every walker its home contact, then cut; then each walker queues what it brings, out of reach.
    private suspend fun boot(): String {
        val limits = LabLimits(nodeAirBytesPerSecond = radio.nodeAirBytesPerSecond)
        walkers =
            (0 until scenario.pairs * 2).map { w ->
                val node = lab.node("w$w", limits).apply { setDisplayName("Walker $w") }
                val home = lab.node("h$w", limits).apply { setDisplayName("Home $w") }
                Walker(node, home)
            }
        for (pair in 0 until scenario.pairs) {
            val (a, b) = sidewalk.walkers(pair).let { (x, y) -> walkers[x] to walkers[y] }
            for ((x, y) in listOf(a.node to b.node, a.node to a.home, b.node to b.home)) {
                lab.link(x, y)
                lab.awaitAcquainted(x, y)
                lab.befriend(x, y)
                lab.unlink(x, y)
            }
        }
        for ((w, walker) in walkers.withIndex()) queue(w, walker, walkers[partner(w)])
        // Nothing crossed before the walk: every pair still lacks what the other brought.
        if (scenario.dms + scenario.pictures > 0) {
            for (pair in 0 until scenario.pairs) {
                val (a, b) = sidewalk.walkers(pair).let { (x, y) -> walkers[x] to walkers[y] }
                check(!holds(b, a, pictures = false) && !holds(a, b, pictures = false)) { "pair $pair crossed before the walk" }
            }
        }
        val frames = walkers.sumOf { it.forOther.size + it.picturesForOther.size + it.carriedIds.size }
        return "boot: ${scenario.pairs} pairs and their homes, ${walkers.size * 2} nodes; $frames frames queued out of reach"
    }

    private fun partner(walker: Int): Int = walker xor 1

    // Out of reach, [walker] queues its DMs and photos, every other one for [other] and the rest for its home contact.
    private suspend fun queue(
        w: Int,
        walker: Walker,
        other: Walker,
    ) {
        val me = walker.node
        for (i in 0 until scenario.dms) {
            val to = if (i % 2 == 0) other.node else walker.home
            val body = "${me.name} dm $i to ${to.name}: ${FILLER.take(DM_FILLER_CHARS)}"
            check(me.sendDm(to, body)) { "${me.name} could not send \"$body\"" }
            check(me.ownMessageId(me.dmWith(to), body) in me.custodyIds()) { "${me.name}'s DM \"$body\" is not in its custody" }
            if (to === other.node) walker.forOther += body
        }
        for (j in 0 until scenario.pictures) {
            val to = if (j % 2 == 0) other.node else walker.home
            val text = "${me.name} photo $j to ${to.name}"
            val bytes = SimSeeds.stream(scenario.seed, Purpose.PASS_PICTURE, w.toLong(), j.toLong()).nextBytes(scenario.pictureBytes)
            check(me.sendImage(bytes, text, to = to)) { "${me.name} could not send \"$text\"" }
            val id = me.ownMessageId(me.dmWith(to), text)
            check(id in me.custodyIds()) { "${me.name}'s photo \"$text\" is not in its custody" }
            if (to === other.node) {
                walker.picturesForOther += text to bytes
            } else {
                walker.carriedPictureHashes += checkNotNull(me.attachmentHash(me.dmWith(to), id)) { "no hash for \"$text\"" }
            }
        }
        walker.carriedIds = me.custodyIdsTo(walker.home)
        val forHome = scenario.dms / 2 + scenario.pictures / 2
        check(walker.carriedIds.size >= forHome) {
            "${me.name}'s custody holds ${walker.carriedIds.size} frames for its home contact, of the $forHome it sent"
        }
    }

    // The world loop on the wall clock beside one watcher a pair, until every pass is over.
    private suspend fun walk(endMs: Long) =
        coroutineScope {
            val origin = System.nanoTime()
            launch(Dispatchers.Default) {
                var atMs = 0L
                while (atMs <= endMs) {
                    val lateMs = wallMs(origin) - atMs
                    if (lateMs < 0) delay(-lateMs) else worldLateMs = maxOf(worldLateMs, lateMs)
                    step(atMs, origin)
                    atMs += scenario.tickMs
                }
            }
            for (pair in 0 until scenario.pairs) {
                launch(Dispatchers.Default) { watch(pair, origin, endMs) }
            }
        }

    private fun wallMs(origin: Long): Long = (System.nanoTime() - origin) / NANOS_PER_MS

    // One radio step. Every time a pass records is the wall clock's at the step that saw it, so a world loop running late
    // moves the link, the drop and the convergence alike. A pair about to be cut is read once more before the cut: what
    // crossed while its link was up counts, however late the loop or its watcher.
    private suspend fun step(
        atMs: Long,
        origin: Long,
    ) {
        val positions = walkers.indices.map { sidewalk.position(it, atMs) }
        val changes = radio.step(atMs, positions)
        val nowMs = wallMs(origin)
        for (link in changes.raised + changes.dropped) check(link.a / 2 == link.b / 2) { "walkers of two pairs linked: $link" }
        for (link in changes.dropped) settle(link.a / 2, nowMs)
        lab.applyLinks(changes, { walkers[it].node }, radio)
        for (pair in 0 until scenario.pairs) {
            val (a, b) = sidewalk.walkers(pair)
            val pass = passes[pair]
            if (pass.inRangeAtMs == null && positions[a].distanceTo(positions[b]) <= scenario.inRangeM) pass.inRangeAtMs = nowMs
        }
        for (link in changes.raised) {
            val pass = passes[link.a / 2]
            pass.contacts++
            if (pass.linkedAtMs == null) pass.linkedAtMs = nowMs
        }
        for (link in changes.dropped) {
            val pass = passes[link.a / 2]
            if (pass.droppedAtMs == null) pass.droppedAtMs = nowMs
        }
        if (atMs % LOG_CLEAR_MS == 0L) {
            ShadowLog.clear()
            val done = passes.count { it.convergedAtMs != null }
            progress("t=${atMs / MS_PER_S}s: ${radio.links.size} links up, $done of ${scenario.pairs} pairs converged")
        }
    }

    // Polls [pair] from its first link until it converges or the run ends.
    private suspend fun watch(
        pair: Int,
        origin: Long,
        endMs: Long,
    ) {
        val pass = passes[pair]
        while (wallMs(origin) <= endMs && pass.convergedAtMs == null) {
            if (pass.linkedAtMs != null) settle(pair, wallMs(origin))
            delay(WATCH_POLL_MS)
        }
    }

    // Reads [pair] once and stamps, at [nowMs], the first time its text and then everything had crossed. One reader at a
    // time: the watcher's polls and the world loop's read before a cut.
    private suspend fun settle(
        pair: Int,
        nowMs: Long,
    ) {
        val pass = passes[pair]
        val (x, y) = sidewalk.walkers(pair)
        val (a, b) = walkers[x] to walkers[y]
        pass.lock.withLock {
            if (pass.linkedAtMs == null || pass.convergedAtMs != null) return
            if (pass.textAtMs == null && holds(b, a, pictures = false) && holds(a, b, pictures = false)) pass.textAtMs = nowMs
            if (pass.textAtMs != null && holds(b, a, pictures = true) && holds(a, b, pictures = true)) pass.convergedAtMs = nowMs
        }
    }

    // Whether [holder] has everything [from] brought for it (its photos too, with [pictures]) and carries everything
    // [from] brought for its home.
    private suspend fun holds(
        holder: Walker,
        from: Walker,
        pictures: Boolean,
    ): Boolean {
        val conversation = holder.node.dmWith(from.node)
        val held = rows(holder.node, conversation)
        if (!held.keys.containsAll(from.forOther + from.picturesForOther.map { it.first })) return false
        if (!holder.node.custodyIds().containsAll(from.carriedIds)) return false
        return !pictures || picturesLanded(holder, from) == from.picturesForOther.size
    }

    // How many of the photos [from] brought for [holder] it holds whole.
    private suspend fun picturesLanded(
        holder: Walker,
        from: Walker,
    ): Int {
        val conversation = holder.node.dmWith(from.node)
        val held = rows(holder.node, conversation)
        return from.picturesForOther.count { (text, bytes) ->
            val id = held[text]?.id
            id != null && holder.node.attachmentPlain(conversation, id)?.contentEquals(bytes) == true
        }
    }

    private suspend fun report(
        boot: String,
        endMs: Long,
    ): PassByReport {
        val outcomes = passes.mapIndexed { pair, pass -> outcome(pair, pass) }
        val converge = outcomes.filter { it.verdict == PassVerdict.CONVERGED }.mapNotNull { it.convergeMs }.sorted()
        val links = outcomes.mapNotNull { it.linkMs }.sorted()
        val dials = outcomes.mapNotNull { it.dialMs }.sorted()
        val texts = outcomes.mapNotNull { it.textMs }.sorted()
        val pictureKb = scenario.pictureBytes / PassByScenario.BYTES_PER_KB
        val tally = PassVerdict.entries.joinToString { v -> "${v.name.lowercase()} ${outcomes.count { it.verdict == v }}" }
        val lines =
            listOf(
                "pass-by: ${scenario.pairs} pairs at ${scenario.speedMps} m/s, ${scenario.lateralM} m apart across the sidewalk, " +
                    "${radio.name} dialled within ${scenario.connectM} m and held to ${scenario.dropM} m",
                "each walker brings ${scenario.dms} DMs and ${scenario.pictures} photos of $pictureKb KB, " +
                    "half for the walker it passes and half for its home",
                boot,
                "watched ${seconds(endMs)}; the world loop ran $worldLateMs ms late at worst",
            ) +
                outcomes.map(::passLine) +
                listOf(
                    "converged in range: ${percent(outcomes.count { it.verdict == PassVerdict.CONVERGED }, outcomes.size)} " +
                        "($tally)",
                    "time to converge after the link: ${spread(converge)}; link held p50 ${seconds(percentile(links, P50))} " +
                        "p10 ${seconds(percentile(links, P10))}; dial p50 ${seconds(percentile(dials, P50))} " +
                        "p90 ${seconds(percentile(dials, P90))}; text alone ${spread(texts)}",
                ) +
                summaryOf(outcomes, converge, links, dials, texts).lines()
        return PassByReport(lines.joinToString("\n"), outcomes)
    }

    private suspend fun outcome(
        pair: Int,
        pass: Pass,
    ): PassOutcome {
        val (x, y) = sidewalk.walkers(pair)
        val carried =
            walkers[x].carriedPictureHashes.map { it to walkers[y] } +
                walkers[y].carriedPictureHashes.map { it to walkers[x] }
        return PassOutcome(
            pair = pair,
            verdict = verdict(pass),
            inRangeAtMs = pass.inRangeAtMs,
            linkedAtMs = pass.linkedAtMs,
            droppedAtMs = pass.droppedAtMs,
            convergedAtMs = pass.convergedAtMs,
            textAtMs = pass.textAtMs,
            picturesLanded = picturesLanded(walkers[y], walkers[x]) + picturesLanded(walkers[x], walkers[y]),
            pictures = walkers[x].picturesForOther.size + walkers[y].picturesForOther.size,
            contacts = pass.contacts,
            carriedPicturesHeld = carried.count { (hash, carrier) -> carrier.node.holdsBlob(hash) },
            carriedPictures = carried.size,
        )
    }

    private fun summaryOf(
        outcomes: List<PassOutcome>,
        converge: List<Long>,
        links: List<Long>,
        dials: List<Long>,
        texts: List<Long>,
    ): SimSummary {
        val summary = SimSummary()
        summary.putAll(scenario.summaryValues())
        summary["radio"] = radio.name
        summary.putAll(radio.summaryValues())
        summary["world_late_ms"] = worldLateMs
        summary["linked"] = outcomes.count { it.linkedAtMs != null }
        PassVerdict.entries.forEach { v -> summary["${v.name.lowercase()}_pairs"] = outcomes.count { it.verdict == v } }
        summary["converged_pct"] = share(outcomes.count { it.verdict == PassVerdict.CONVERGED }, outcomes.size)
        summary["text_in_range_pct"] = share(outcomes.count { inRange(it.textAtMs, it.droppedAtMs) }, outcomes.size)
        summary["text_p50_s"] = tenths(percentile(texts, P50))
        summary["text_p90_s"] = tenths(percentile(texts, P90))
        summary["pictures_landed"] = outcomes.sumOf { it.picturesLanded }
        summary["pictures_sent"] = outcomes.sumOf { it.pictures }
        summary["converge_p50_s"] = tenths(percentile(converge, P50))
        summary["converge_p90_s"] = tenths(percentile(converge, P90))
        summary["converge_max_s"] = tenths(converge.lastOrNull())
        summary["link_p50_s"] = tenths(percentile(links, P50))
        summary["link_p10_s"] = tenths(percentile(links, P10))
        summary["dial_p50_s"] = tenths(percentile(dials, P50))
        summary["dial_p90_s"] = tenths(percentile(dials, P90))
        summary["flapped_pairs"] = outcomes.count { it.contacts > 1 }
        summary["carried_pictures"] = outcomes.sumOf { it.carriedPictures }
        summary["carried_pictures_held"] = outcomes.sumOf { it.carriedPicturesHeld }
        return summary
    }

    // Whether [atMs] came while the link was up, or within a tick of its drop: what was already on the air.
    private fun inRange(
        atMs: Long?,
        droppedAtMs: Long?,
    ): Boolean = atMs != null && (droppedAtMs == null || atMs <= droppedAtMs + scenario.tickMs)

    // p50, p90 and the most of [sorted].
    private fun spread(sorted: List<Long>): String =
        "p50 ${seconds(percentile(sorted, P50))} p90 ${seconds(percentile(sorted, P90))} max ${seconds(sorted.lastOrNull())}"

    private fun verdict(pass: Pass): PassVerdict =
        when {
            pass.linkedAtMs == null -> PassVerdict.NEVER_LINKED
            pass.convergedAtMs == null -> PassVerdict.CUT_SHORT
            inRange(pass.convergedAtMs, pass.droppedAtMs) -> PassVerdict.CONVERGED
            else -> PassVerdict.LATE
        }

    private fun passLine(o: PassOutcome): String =
        "  pair ${o.pair.toString().padStart(2)}: ${o.verdict.name.lowercase().padEnd(VERDICT_WIDTH)} " +
            "in range ${seconds(o.inRangeAtMs)}, dial ${seconds(o.dialMs)}, link held ${seconds(o.linkMs)}, " +
            "text ${seconds(o.textMs)} and all ${seconds(o.convergeMs)} after the link" +
            (if (o.pictures > 0) ", ${o.picturesLanded} of ${o.pictures} photos landed" else "") +
            (if (o.contacts > 1) ", ${o.contacts} links" else "") +
            (if (o.carriedPictures > 0) ", carrier holds ${o.carriedPicturesHeld} of ${o.carriedPictures} home photos" else "")

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
        const val LOG_CLEAR_MS = 10_000L
        const val WATCH_POLL_MS = 250L
        const val P10 = 10
        const val VERDICT_WIDTH = 12
        const val DM_FILLER_CHARS = 60
        const val FILLER = "on my way, see you at the corner by the bakery in ten minutes or so, bring the map"
    }
}
