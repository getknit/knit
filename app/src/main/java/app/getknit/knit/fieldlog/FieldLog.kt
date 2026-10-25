package app.getknit.knit.fieldlog

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.power.LowPowerPosture
import app.getknit.knit.mesh.power.PowerState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the mesh reports what its radios did. [NONE] records nothing — the default every constructor takes,
 * so a test or a rig without a field log is unchanged.
 */
fun interface FieldRecorder {
    fun record(event: FieldEvent)

    /**
     * Registers (or, with null, removes) a [sample] the log takes every [FieldLog.SAMPLE_EVERY_MS] while the mesh
     * runs — a radio's state that moves too often to be events. Called from the log's own tick; keep it a read.
     */
    fun sampler(
        name: String,
        sample: (() -> List<FieldEvent>)?,
    ) = Unit

    /**
     * Registers (or, with null, removes) a stream of [edges] the log follows for each mesh session — a plane's state
     * changes that some other component already publishes, turned into events by a pure follower. [edges] is called at
     * each session's start, so whatever it resolves may be built after the log; a follower registered mid-session
     * starts with the next one.
     */
    fun follower(
        name: String,
        edges: (() -> Flow<FieldEvent>)?,
    ) = Unit

    companion object {
        val NONE = FieldRecorder { }
    }
}

/**
 * The field log (ADR 2026-10.a239): a bounded record of how this phone's radios behaved, kept on the phone and
 * handed over only by the user, from Diagnostics.
 *
 * [record] is cheap and never blocks — it stamps the event, hands it to the [tap], and queues it. Rendering
 * (pseudonyms) and the disk write happen on [flush], which the session's minute tick runs, and on [stop]. A process
 * death loses at most the last minute. While the user has the log switched off ([enabled]) nothing is queued, and
 * switching it off deletes what was kept ([setEnabled]). It is off until the user turns it on
 * (`SettingsStore.fieldLogEnabled`). The tap sees every event either way (ADR 2026-10.s7wg): it keeps nothing on disk.
 *
 * The session half ([start] / [stop]) is driven by `MeshManager`: it writes `mesh_on` / `mesh_off`, follows the
 * power state and each radio's status, and every [COUNTERS_EVERY_MS] writes what the mesh's counters moved by.
 */
class FieldLog(
    private val journal: FieldJournal,
    private val metrics: () -> MeshMetrics.Snapshot,
    private val power: Flow<PowerState>,
    private val enabled: Flow<Boolean>,
    private val versionCode: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = FLUSH_EVERY_MS,
    // Where the export stages its copy for the share sheet ([FieldLogExport.stage]); [clear] empties it. Null keeps none.
    private val staged: File? = null,
    // Low-power mode's posture (`LowPowerSource.posture`, ADR 2026-10.d44b), followed for the session like [power].
    private val lowPower: Flow<LowPowerPosture>? = null,
    // The developer view's event console (ADR 2026-10.s7wg): handed each event ahead of [enabled], in memory only.
    private val tap: FieldTap = FieldTap.NONE,
) : FieldRecorder {
    private class Pending(
        val at: Long,
        val event: FieldEvent,
    )

    private val queue = ConcurrentLinkedQueue<Pending>()
    private val queued = AtomicInteger()
    private val lock = Mutex()
    private var pseudonyms: Pseudonyms? = null

    /** The last switch position seen; null until the first [flush] or [setEnabled] reads it. */
    @Volatile private var on: Boolean? = null

    /** When each peer last lost its Bluetooth link, for [FieldEvent.BtUp.relinkMs]. */
    private val downAt = ConcurrentHashMap<String, Long>()

    /** When the LoRa board's ready link last ended, for [FieldEvent.LoraUp.relinkMs]; null while it is up. */
    @Volatile private var loraDownAt: Long? = null

    /** Each radio's last status this session, for the change test and the counters' sample. */
    private val radios = ConcurrentHashMap<TransportKind, TransportStatus>()

    @Volatile private var countersAt = 0L
    private var countersBefore: Map<String, Long> = emptyMap()

    private val samplers = ConcurrentHashMap<String, () -> List<FieldEvent>>()

    private val followers = ConcurrentHashMap<String, () -> Flow<FieldEvent>>()

    @Volatile private var sampledAt = 0L

    override fun sampler(
        name: String,
        sample: (() -> List<FieldEvent>)?,
    ) {
        if (sample == null) samplers.remove(name) else samplers[name] = sample
    }

    override fun follower(
        name: String,
        edges: (() -> Flow<FieldEvent>)?,
    ) {
        if (edges == null) followers.remove(name) else followers[name] = edges
    }

    override fun record(event: FieldEvent) {
        val at = clock()
        // Ahead of the switch: the console shows what happened whether or not the person keeps a field log.
        tap.saw(at, event)
        if (on == false) return
        queue.add(Pending(at, relink(at, event)))
        // Bounded: a log nobody flushes (a wedged IO pool) drops its oldest rather than growing.
        if (queued.incrementAndGet() > MAX_QUEUED && queue.poll() != null) queued.decrementAndGet()
    }

    /**
     * Fills a [FieldEvent.BtUp]'s time without a link since the same peer's last [FieldEvent.BtDown]. Held here, not
     * in the transport, so the radio code only reports edges; the map forgets a peer once it links again. A
     * [FieldEvent.LoraUp]'s is filled the same way, from the board's last [FieldEvent.LoraDown].
     */
    private fun relink(
        at: Long,
        event: FieldEvent,
    ): FieldEvent =
        when (event) {
            is FieldEvent.BtDown -> {
                event.also {
                    // Strangers who never come back would grow it for days: past the cap the oldest go.
                    if (downAt.size >= MAX_DOWN_PEERS) downAt.entries.minByOrNull { it.value }?.let { downAt.remove(it.key) }
                    downAt[event.peer] = at
                }
            }

            is FieldEvent.BtUp -> {
                downAt.remove(event.peer)?.let { event.copy(relinkMs = at - it) } ?: event
            }

            else -> {
                loraRelink(at, event)
            }
        }

    /** [relink]'s LoRa half: the board is one link, so one time of its last down is all it needs. */
    private fun loraRelink(
        at: Long,
        event: FieldEvent,
    ): FieldEvent =
        when (event) {
            is FieldEvent.LoraDown -> {
                event.also { loraDownAt = at }
            }

            is FieldEvent.LoraUp -> {
                loraDownAt?.let { event.copy(relinkMs = at - it) }.also { loraDownAt = null } ?: event
            }

            else -> {
                event
            }
        }

    /** Writes everything queued. */
    suspend fun flush() =
        withContext(io) {
            lock.withLock { flushLocked() }
        }

    private suspend fun flushLocked() {
        val keep = enabled.first().also { on = it }
        val drained = generateSequence { queue.poll()?.also { queued.decrementAndGet() } }.toList()
        if (!keep || drained.isEmpty()) return
        val names = pseudonyms ?: Pseudonyms(journal.salt()).also { pseudonyms = it }
        runCatching { journal.append(drained.map { FieldLine.render(it.at, it.event, names) }) }
    }

    /** Every kept line, oldest first, after writing what is queued. */
    suspend fun lines(): List<String> =
        withContext(io) {
            lock.withLock {
                flushLocked()
                journal.read(clock())
            }
        }

    suspend fun size(): Long = withContext(io) { lock.withLock { journal.size() } }

    /**
     * Deletes the log, its salt and any copy staged for a share: what is recorded next reads under new pseudonyms.
     */
    suspend fun clear() =
        withContext(io) {
            lock.withLock {
                queue.clear()
                queued.set(0)
                journal.clear()
                // The copy last staged for a share is the log too: a Clear that left it would not be one.
                staged?.listFiles()?.forEach { it.delete() }
                pseudonyms = null
            }
        }

    /** The user's switch. Off also deletes what was kept — an off log keeps nothing. */
    suspend fun setEnabled(
        value: Boolean,
        persist: suspend (Boolean) -> Unit,
    ) {
        on = value
        persist(value)
        if (!value) clear()
    }

    /** The mesh session began: follows the power state and [statuses] on [session] until it is cancelled. */
    fun start(
        session: CoroutineScope,
        statuses: Flow<List<TransportStatus>>,
    ) {
        // The switch, followed for the session: off (the default) queues nothing and samples nothing.
        session.launch { enabled.collect { on = it } }
        // A board link the last session left down ended with that session (a `mesh_off` closes it): its outage is not
        // this session's, so the first `lora_up` carries no relink.
        loraDownAt = null
        record(FieldEvent.MeshOn(versionCode))
        session.launch { power.distinctUntilChanged().collect { record(it.toEvent()) } }
        lowPower?.let { posture ->
            session.launch { posture.distinctUntilChanged().collect { record(FieldEvent.LowPower(it.reason)) } }
        }
        session.launch { followRadios(statuses) }
        // One that throws costs its own lines, never the session; it is not restarted until the next one.
        followers.values.forEach { edges -> session.launch { runCatching { edges().collect(::record) } } }
        session.launch {
            while (true) {
                delay(tickMs)
                if (on == true) {
                    if (clock() - countersAt >= COUNTERS_EVERY_MS) sample()
                    if (clock() - sampledAt >= SAMPLE_EVERY_MS) sampleRadios()
                }
                flush()
            }
        }
    }

    /** The session ended: what moved since the last counters line, then everything to disk. */
    suspend fun stop() {
        sample()
        record(FieldEvent.MeshOff)
        flush()
    }

    private suspend fun followRadios(statuses: Flow<List<TransportStatus>>) {
        radios.clear()
        statuses.collect { now ->
            now.forEach { status ->
                val was = radios.put(status.kind, status)
                // Nearby is smoothed and moves all the time; it rides the other changes and the counters' sample.
                if (was == null || was.copy(nearby = status.nearby) != status) record(status.toEvent())
            }
        }
    }

    /** Writes what the counters moved by since the last sample, and each radio's status as it stands. */
    @Synchronized
    fun sample() {
        countersAt = clock()
        val now = FieldCounters.flatten(metrics())
        val moved = FieldCounters.deltas(countersBefore, now)
        countersBefore = now
        if (moved.isNotEmpty()) record(FieldEvent.Counters(moved))
        // The sample that carries each radio's smoothed nearby count, which moves too often to be an event.
        radios.values.forEach { record(it.toEvent()) }
    }

    /** Takes every registered radio sample; one that throws costs its own lines, never the tick. */
    fun sampleRadios() {
        sampledAt = clock()
        samplers.values.forEach { sample -> runCatching { sample() }.getOrNull()?.forEach(::record) }
    }

    private fun PowerState.toEvent() = FieldEvent.Power(screenOn = interactive, charging = charging, batteryLow = batteryLow)

    private fun TransportStatus.toEvent() =
        FieldEvent.Radio(
            radio = kind,
            health = health,
            linked = linked,
            nearby = nearby,
            contended = contended,
            initiatorHeld = initiatorHeld,
        )

    companion object {
        /** How often the queue goes to disk: the mesh's own metrics tick. */
        const val FLUSH_EVERY_MS = 60_000L

        /** How often the counters' movement is written. */
        const val COUNTERS_EVERY_MS = 15 * 60_000L

        /** How often each radio's sampler runs: often enough to follow a walk, sparse enough to keep three days. */
        const val SAMPLE_EVERY_MS = 5 * 60_000L

        /** Events held in memory between flushes before the oldest go. */
        const val MAX_QUEUED = 5_000

        /** Peers whose last link-down is remembered for the relink time. */
        const val MAX_DOWN_PEERS = 256
    }
}
