package app.getknit.knit.mesh.sim

import app.getknit.knit.data.forward.ForwardRepository
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
import java.util.Locale

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
    /**
     * Frames each walker carries into its pass for others ([load]): sealed DMs from [loadAuthors] identities of their own
     * to recipients who never appear ([CarriedLoad]), taken into its custody before the walk, between its own DMs. None is
     * for the walker it passes, and no two walkers carry the same author.
     */
    val load: Int = 0,
    val loadAuthors: Int = DEFAULT_LOAD_AUTHORS,
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
    init {
        require(load == 0 || loadAuthors >= 1) { "a load needs an author: $loadAuthors" }
        // A carrier holds one author's frames and its profile under one sender quota: past it, the oldest go.
        require(load == 0 || (load + loadAuthors - 1) / loadAuthors < ForwardRepository.DEFAULT_MAX_PER_SENDER) {
            "$load frames from $loadAuthors authors is past the ${ForwardRepository.DEFAULT_MAX_PER_SENDER}-a-sender quota"
        }
    }

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
            "load_each" to load,
            "load_authors" to loadAuthors,
        )

    companion object {
        const val DEFAULT_PAIRS = 20
        const val DEFAULT_DMS = 20
        const val DEFAULT_LOAD_AUTHORS = 5
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
    /**
     * The DMs each brought for the other: sent, still in the sender's custody when the walk began, crossed when the world
     * loop read the pair just before its cut (stricter than the verdict, which counts what lands within a tick after),
     * and crossed by the end.
     */
    val dms: Int = 0,
    val dmsHeldAtBoot: Int = 0,
    val dmsAtDrop: Int? = null,
    val dmsCrossed: Int = 0,
    /**
     * The frames each carried for the other's home, as its custody held them at the walk's start: crossed just before the
     * cut and by the end.
     */
    val carried: Int = 0,
    val carriedAtDrop: Int? = null,
    val carriedCrossed: Int = 0,
    /**
     * The frames each carried in for others ([PassByScenario.load]) as its custody held them at the walk's start; how
     * many of the other's each held just before the cut and at the end, how many of its own it still held then (an
     * arrival past the 1,000-row cap evicts the largest sender's oldest), and whether the two custodies held the same set.
     */
    val load: Int = 0,
    val loadAtDrop: Int? = null,
    val loadCrossed: Int = 0,
    val loadKeptAtDrop: Int? = null,
    val loadKept: Int = 0,
    val parityAtDrop: Boolean? = null,
    val parity: Boolean = false,
    /** What the pair's air links aired both ways, and lost to a cut: frames, digests and receipts alike. */
    val airedRecords: Long = 0,
    val airedBytes: Long = 0,
    val cutRecords: Long = 0,
    /** What the pair's digests asked and answered over its links ([ExchangeTally]). */
    val exchange: ExchangeCounts = ExchangeCounts(),
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
 * its home, within a tick of the link dropping. Past the custody quota a sender's custody has already evicted its oldest
 * sends before the walk, and nothing else carries them: there a pass is measured against what its custody still held.
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

    // What the walk's air links carried for custody, from the walk's start ([play]).
    private lateinit var exchanges: ExchangeTally
    private val passes = List(scenario.pairs) { Pass() }

    // What one walker brought into its pass, for the walker it passes and for its home contact.
    private class Walker(
        val node: LabNode,
        val home: LabNode,
    ) {
        val forOther = mutableListOf<String>()
        val forOtherIds = mutableListOf<String>()

        // Of [forOther], the ones its custody still held once everything was queued: a quota keeps the newest, and an
        // evicted DM has no carrier, so these are all a pass can bring. Convergence is measured against them.
        var heldForOther: List<String> = emptyList()
        val picturesForOther = mutableListOf<Pair<String, ByteArray>>()
        var carriedIds: Set<String> = emptySet()
        val carriedPictureHashes = mutableListOf<String>()

        // What it carries for others ([PassByScenario.load]): the load, the DMs it was given, and those its custody held
        // once all were in. The authors' profiles ride in too, uncounted.
        var load: CarriedLoad? = null
        val loadSent = HashSet<String>()
        var loadIds: Set<String> = emptySet()
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

        // What had crossed when the world loop read the pair just before its cut: records already on the air land after.
        @Volatile var atDrop: Crossed? = null
    }

    // What one pair holds of what the other brought: DMs for it, frames carried for its home, the other's load; how much
    // of its own load each still holds, and whether the two custodies are one set.
    private data class Crossed(
        val dms: Int,
        val carried: Int,
        val load: Int,
        val loadKept: Int,
        val parity: Boolean,
    )

    suspend fun play(): PassByReport {
        val boot = boot()
        progress(boot)
        val endMs = (0 until scenario.pairs).maxOf { sidewalk.apartAfterMs(it, scenario.reachM) } + scenario.watchAfterMs
        exchanges = ExchangeTally.start(walkers.map { it.node })
        try {
            walk(endMs)
            return report(boot, endMs)
        } finally {
            exchanges.close()
        }
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
        if (scenario.load > 0) giveLoads(limits)
        for ((w, walker) in walkers.withIndex()) queue(w, walker, walkers[partner(w)])
        checkNothingCrossed()
        return bootLine()
    }

    // Each walker's load, its own authors' DMs sized as a DM between two of the run's nodes is.
    private suspend fun giveLoads(limits: LabLimits) {
        val signedBytes = CarriedLoad.dmSignedBytes(lab, limits, "w00 dm 0 to w01: ${FILLER.take(DM_FILLER_CHARS)}")
        val at = lab.clock.now()
        for ((w, walker) in walkers.withIndex()) {
            walker.load =
                CarriedLoad(scenario.seed, key = w.toLong(), authors = scenario.loadAuthors, profileAt = at, signedBytes = signedBytes)
        }
    }

    // Nothing crossed before the walk: every pair still lacks what the other brought, its load too.
    private suspend fun checkNothingCrossed() {
        for (pair in 0 until scenario.pairs) {
            val (a, b) = sidewalk.walkers(pair).let { (x, y) -> walkers[x] to walkers[y] }
            if (scenario.dms + scenario.pictures > 0) {
                check(!holds(b, a, pictures = false) && !holds(a, b, pictures = false)) { "pair $pair crossed before the walk" }
            }
            check(b.node.custodyIds().none { it in a.loadIds } && a.node.custodyIds().none { it in b.loadIds }) {
                "pair $pair shares a load before the walk"
            }
        }
    }

    private suspend fun bootLine(): String {
        val frames = walkers.sumOf { it.forOther.size + it.picturesForOther.size + it.carriedIds.size }
        val sent = walkers.size * (scenario.dms + scenario.pictures)
        val held = walkers.sumOf { it.heldForOther.size + it.carriedIds.size }
        val nodes = walkers.size * 2 + if (scenario.load > 0) CarriedLoad.SIZER_NODES else 0
        val custody = walkers.map { it.node.custodyIds().size }
        return "boot: ${scenario.pairs} pairs and their homes, $nodes nodes; $frames frames queued out of reach" +
            (if (held < sent) "; the senders' custody kept $held of the $sent they sent" else "") +
            (
                if (scenario.load > 0) {
                    "; each walker carries in ${scenario.load} frames from ${scenario.loadAuthors} authors, custody holding " +
                        "${walkers.minOf { it.loadIds.size }} to ${walkers.maxOf { it.loadIds.size }} of them and " +
                        "${custody.min()} to ${custody.max()} frames in all"
                } else {
                    ""
                }
            )
    }

    private fun partner(walker: Int): Int = walker xor 1

    // Out of reach, [walker] queues its DMs and photos, every other one for [other] and the rest for its home contact.
    private suspend fun queue(
        w: Int,
        walker: Walker,
        other: Walker,
    ) {
        val me = walker.node
        // Its own DMs go between its authors' loads, so custody's newest-received-first serve order mixes the two.
        var nextAuthor = 0
        val authors = walker.load?.authorCount ?: 0
        for (i in 0 until scenario.dms) {
            // The authors due before DM i: one a slice, the slices even across the DMs.
            val due = if (authors == 0) 0 else i * authors / scenario.dms + 1
            while (nextAuthor < due) carryShare(walker, nextAuthor++)
            val to = if (i % 2 == 0) other.node else walker.home
            val body = "${me.name} dm $i to ${to.name}: ${FILLER.take(DM_FILLER_CHARS)}"
            check(me.sendDm(to, body)) { "${me.name} could not send \"$body\"" }
            check(me.ownMessageId(me.dmWith(to), body) in me.custodyIds()) { "${me.name}'s DM \"$body\" is not in its custody" }
            if (to === other.node) {
                walker.forOther += body
                walker.forOtherIds += me.ownMessageId(me.dmWith(to), body)
            }
        }
        // Any author's load the DMs did not reach (fewer DMs than authors, or none).
        while (nextAuthor < authors) carryShare(walker, nextAuthor++)
        if (authors > 0) {
            walker.loadIds = me.custodyIds().filterTo(HashSet()) { it in walker.loadSent }
            check(walker.loadIds.isNotEmpty()) { "${me.name}'s custody took none of its load" }
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
        val custody = me.custodyIds()
        walker.heldForOther = walker.forOther.filterIndexed { i, _ -> walker.forOtherIds[i] in custody }
        // Custody keeps a sender's newest frames ([ForwardRepository.DEFAULT_MAX_PER_SENDER]), its own sends too: past
        // the quota it holds fewer than were queued, never none. Photos are measured under the quota only: an evicted
        // photo's frame would need its own reckoning, and its carried copy too.
        val forHome = scenario.dms / 2 + scenario.pictures / 2
        val quota = ForwardRepository.DEFAULT_MAX_PER_SENDER
        if (scenario.dms + scenario.pictures < quota) {
            check(walker.carriedIds.size >= forHome) {
                "${me.name}'s custody holds ${walker.carriedIds.size} frames for its home contact, of the $forHome it sent"
            }
            check(walker.heldForOther.size == walker.forOther.size) { "${me.name}'s custody lost a DM under its quota" }
        } else {
            check(
                scenario.pictures == 0,
            ) { "photos past the custody quota are not measured: ${scenario.pictures} with ${scenario.dms} DMs" }
            check(walker.carriedIds.isNotEmpty() && walker.heldForOther.isNotEmpty()) { "${me.name}'s custody kept none of its sends" }
        }
    }

    // [walker] takes author [index]'s share of its load into custody. Past the 1,000-row cap custody evicts as it does for
    // any arrival, so a share can go in whole and stay in part; [queue] reads what stayed.
    private suspend fun carryShare(
        walker: Walker,
        index: Int,
    ) {
        val load = checkNotNull(walker.load)
        val share = scenario.load / load.authorCount + if (index < scenario.load % load.authorCount) 1 else 0
        walker.loadSent += load.fill(walker.node, index, share, lab.clock::now)
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
        for (link in changes.dropped) {
            val pair = link.a / 2
            settle(pair, nowMs)
            if (passes[pair].atDrop == null) passes[pair].atDrop = crossed(pair)
        }
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
        if (!held.keys.containsAll(from.heldForOther + from.picturesForOther.map { it.first })) return false
        if (!holder.node.custodyIds().containsAll(from.carriedIds)) return false
        return !pictures || picturesLanded(holder, from) == from.picturesForOther.size
    }

    // What each walker of [pair] holds of what the other brought.
    private suspend fun crossed(pair: Int): Crossed {
        val (x, y) = sidewalk.walkers(pair)
        val (a, b) = walkers[x] to walkers[y]
        val one = crossedTo(b, a)
        val two = crossedTo(a, b)
        val parity = a.node.custodyIds() == b.node.custodyIds()
        return Crossed(one.dms + two.dms, one.carried + two.carried, one.load + two.load, one.loadKept + two.loadKept, parity)
    }

    private suspend fun crossedTo(
        holder: Walker,
        from: Walker,
    ): Crossed {
        val held = rows(holder.node, holder.node.dmWith(from.node))
        val custody = holder.node.custodyIds()
        return Crossed(
            dms = from.forOther.count { it in held },
            carried = from.carriedIds.count { it in custody },
            load = from.loadIds.count { it in custody },
            loadKept = holder.loadIds.count { it in custody },
            parity = false,
        )
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
        val total = exchanges.counts()
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
                    ExchangeTally.line("in the passes", total),
                    "converged in range: ${percent(outcomes.count { it.verdict == PassVerdict.CONVERGED }, outcomes.size)} " +
                        "($tally)" +
                        (
                            if (outcomes.any { it.dmsHeldAtBoot < it.dms }) {
                                "; past the custody quota, converged means every DM its sender's custody still held at the start"
                            } else {
                                ""
                            }
                        ),
                    "time to converge after the link: ${spread(converge)}; link held p50 ${seconds(percentile(links, P50))} " +
                        "p10 ${seconds(percentile(links, P10))}; dial p50 ${seconds(percentile(dials, P50))} " +
                        "p90 ${seconds(percentile(dials, P90))}; text alone ${spread(texts)}",
                ) +
                summaryOf(outcomes, converge, links, dials, texts).also { it.putAll(ExchangeTally.values(total, "pass")) }.lines()
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
        val end = crossed(pair)
        val ids = setOf(walkers[x].node.nodeId, walkers[y].node.nodeId)
        val exchange = exchanges.counts { from, to -> from in ids && to in ids }
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
            dms = walkers[x].forOther.size + walkers[y].forOther.size,
            dmsHeldAtBoot = walkers[x].heldForOther.size + walkers[y].heldForOther.size,
            dmsAtDrop = pass.atDrop?.dms,
            dmsCrossed = end.dms,
            carried = walkers[x].carriedIds.size + walkers[y].carriedIds.size,
            carriedAtDrop = pass.atDrop?.carried,
            carriedCrossed = end.carried,
            load = walkers[x].loadIds.size + walkers[y].loadIds.size,
            loadAtDrop = pass.atDrop?.load,
            loadCrossed = end.load,
            loadKeptAtDrop = pass.atDrop?.loadKept,
            loadKept = end.loadKept,
            parityAtDrop = pass.atDrop?.parity,
            parity = end.parity,
            airedRecords =
                listOf(x, y).sumOf {
                    walkers[it]
                        .node.transport.aired.records
                        .get()
                },
            airedBytes =
                listOf(x, y).sumOf {
                    walkers[it]
                        .node.transport.aired.bytes
                        .get()
                },
            cutRecords =
                listOf(x, y).sumOf {
                    walkers[it]
                        .node.transport.aired.cut
                        .get()
                },
            exchange = exchange,
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
        summary["dms_for_other"] = outcomes.sumOf { it.dms }
        summary["dms_held_at_boot"] = outcomes.sumOf { it.dmsHeldAtBoot }
        summary["dms_crossed_at_drop"] = outcomes.sumOf { it.dmsAtDrop ?: 0 }
        summary["dms_crossed"] = outcomes.sumOf { it.dmsCrossed }
        summary["carried_frames"] = outcomes.sumOf { it.carried }
        summary["carried_crossed_at_drop"] = outcomes.sumOf { it.carriedAtDrop ?: 0 }
        summary["carried_crossed"] = outcomes.sumOf { it.carriedCrossed }
        summary["load_frames"] = outcomes.sumOf { it.load }
        summary["load_crossed_at_drop"] = outcomes.sumOf { it.loadAtDrop ?: 0 }
        summary["load_crossed"] = outcomes.sumOf { it.loadCrossed }
        summary["load_kept_at_drop"] = outcomes.sumOf { it.loadKeptAtDrop ?: 0 }
        summary["load_kept"] = outcomes.sumOf { it.loadKept }
        summary["parity_at_drop_pairs"] = outcomes.count { it.parityAtDrop == true }
        summary["parity_pairs"] = outcomes.count { it.parity }
        summary["aired_records"] = outcomes.sumOf { it.airedRecords }
        summary["aired_kb"] = outcomes.sumOf { it.airedBytes } / PassByScenario.BYTES_PER_KB
        summary["cut_records"] = outcomes.sumOf { it.cutRecords }
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
            (
                if (o.verdict == PassVerdict.CUT_SHORT || o.dmsHeldAtBoot < o.dms) {
                    ", before the cut ${o.dmsAtDrop ?: 0} of ${o.dms} DMs (${o.dmsHeldAtBoot} still in custody) and " +
                        "${o.carriedAtDrop ?: 0} of ${o.carried} carried frames, by the end ${o.dmsCrossed} and ${o.carriedCrossed}"
                } else {
                    ""
                }
            ) +
            (
                if (o.load > 0) {
                    "; load: before the cut ${o.loadAtDrop ?: 0} of the other's crossed, ${o.loadKeptAtDrop ?: o.load} " +
                        "of ${o.load} own kept, parity ${o.parityAtDrop ?: false}; by the end ${o.loadCrossed}, ${o.loadKept}, " +
                        "${o.parity}; aired ${o.airedRecords} records, ${o.airedBytes / PassByScenario.BYTES_PER_KB} KB, " +
                        "${o.cutRecords} cut"
                } else {
                    ""
                }
            ) +
            (if (o.carriedPictures > 0) ", carrier holds ${o.carriedPicturesHeld} of ${o.carriedPictures} home photos" else "") +
            exchangePart(o.exchange)

    // The pair's custody exchanges, once it has aired anything.
    private fun exchangePart(e: ExchangeCounts): String =
        if (e.bytes == 0L) {
            ""
        } else {
            "; exchanges: ${e.digests} digests (${e.digestSharePct?.let { "%.0f".format(Locale.ROOT, it) }}% of the air), " +
                "${e.exchanges} answered, ${e.emptyExchanges} moving nothing new, ${e.resent} frames re-sent"
        }

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
