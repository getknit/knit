package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto.u32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * A few MeshCore companion boards and the air between them, as `companion-v1.18.0` behaves (ADR 2026-11.gsqu, Stage 3b):
 * the command handler of `MyMesh.cpp` per board, answering **every** connected interface; contacts with routes, the
 * 8-entry circular ack table and a 16-packet pool; the offline queue a `SYNC_NEXT_MESSAGE` pops before it replies; and an
 * air of direct neighbours and repeaters on which a contact's UNKNOWN path floods, a flood is re-aired one hop by a
 * repeater, a flooded DM draws a flooded PATH return that rewrites the sender's route and pushes `0x81` on both boards
 * (bench Q13), a receiver's own UNKNOWN record floods the ack, and a byte-identical packet is dropped by every board that
 * already heard it (Q10, Q14).
 *
 * **With a [channel]** (opt-in, ADR 2026-11.gsqu's Stage 5c-2a amendment) the air is the firmware's, modelled from its
 * source: each airing takes [MeshcoreChannel.airtimeMs] and lands at its end; a receiver decodes one packet at a time, an
 * overlap corrupts both packets (counted, [FakeRadio.collisions]; radio behaviour, not the source's: a stated
 * [MeshcoreChannel.capture] alone keeps the first) and one that arrives while it transmits is lost ([FakeRadio.deaf]); a radio airs
 * one packet at a time from a priority queue, under the 50 % budget and listen-before-talk (`Dispatcher.cpp`); a repeater
 * holds 32 packets and re-airs a flood after `nextInt(0, 5·t + 1)` from the end of reception, a direct packet whose next
 * hop it is after the direct factor's draw, stripping itself (`simple_repeater/MyMesh.cpp:547-553`, `Mesh.cpp:78-108`);
 * every node keeps a 160-entry ring of SHA-256(type + payload), cleared on reboot (`SimpleMeshTables.h`); contacts keep
 * their `out_path` and a direct packet follows it (through a repeater it lands reported `0xFF`, `MyMesh.cpp:453`); a
 * flooded matched ack draws the sender's direct PATH 3 s later while its record is pinned (`BaseChatMesh.cpp:372-390`),
 * and a PATH rewrites the receiver's record and pushes `0x81` (`:353-359`); the RTC ticks with [now]; `tx_air` counts every
 * airing; a scoped flood carries its 4 B of transport codes. Without one, every Stage 3b behaviour [MeshcoreFakeVsSimTest]
 * pins stays exactly as it was.
 *
 * **Where it is not the firmware**: no capture effect unless stated; no SNR, so no score delay but the one
 * [MeshcoreChannel.score] states; a packet a lossy link loses never reaches the receiver's CAD (a real one may still show
 * its preamble); no `TRACE`, adverts or multi-acks on the channel; a repeater's flood limit and region map are not modelled.
 *
 * **One serial context.** Given a [serial] context (`limitedParallelism(1)` on a scope the caller owns, never a node's),
 * every write, delivery and timer runs on it, and [snapshot] reads there; a unit test leaves it null and runs on its
 * `TestScope`. [airings] is copy-on-write either way, so an oracle may read it from any thread.
 *
 * Each modelled behaviour is named against the simulator's frames and events (`/media/walter/data/meshcore-sim-runs/r1`)
 * in `MeshcoreFakeVsSimTest`. Everything is timed on [scope]'s clock.
 */
@Suppress("TooManyFunctions", "LargeClass") // the air and its channel engine, one model
internal class FakeMeshcoreAir(
    val scope: CoroutineScope,
    val channel: MeshcoreChannel? = null,
    val serial: CoroutineContext? = null,
    val now: () -> Long,
) {
    val boards = LinkedHashMap<String, FakeMeshcoreBoard>()
    private val links = HashSet<Pair<String, String>>()
    private val repeaters = HashSet<String>()

    /** Every node's radio, by name, when there is a [channel]: the boards' and the repeaters'. */
    val radios = LinkedHashMap<String, FakeRadio>()

    /** Every airing, in order: what the simulator's `.events.ndjson` records. Copy-on-write: read it from anywhere. */
    val airings: MutableList<Airing> = CopyOnWriteArrayList()

    /** Every repeater draw, in order ([Draw]): a failing seed replays the same delays for the same packets. */
    val draws: MutableList<Draw> = CopyOnWriteArrayList()

    /** How long a confirmation takes from the send (Q12: a 469-ms trip). Without a [channel] only. */
    var confirmDelayMs = 470L

    /** How long one packet holds the pool before it has aired. Without a [channel] only. */
    var airMs = 600L

    /** A repeater's re-airing delay (Q15: about 1.45 s). Without a [channel] only: with one, each delay is drawn. */
    var repeatDelayMs = 1_450L

    /** The `est_timeout` a `SENT` reports, flooded and direct. Without a [channel] only: with one, the firmware's formula. */
    var estFloodMs = 2_724L
    var estDirectMs = 1_890L

    /** Drops a DM attempt on the air before anyone hears it ([attempt] 0..3). */
    var loseDm: (attempt: Int) -> Boolean = { false }

    /**
     * Drops the ack [acker]'s board sends for [sender]'s DM [attempt] on its way back: keyed by both ends, so a scenario can
     * lose exactly one flight's ack (a one-shot guard in the lambda) and leave every other pair's alone. Read on the air's
     * serial context, once per ack.
     */
    var loseAck: (acker: String, sender: String, attempt: Int) -> Boolean = { _, _, _ -> false }

    private val lossy = HashMap<Pair<String, String>, (Airing) -> Boolean>()

    /** Called on each airing once its receivers have it, synchronously: a forger installs one before the author's send. */
    val listeners: MutableList<(Airing) -> Unit> = CopyOnWriteArrayList()

    internal val ctx: CoroutineContext get() = serial ?: EmptyCoroutineContext

    class Airing(
        val from: String,
        val type: String,
        val flood: Boolean,
        val hops: Int,
        /** When it began, its time on air and its bytes (0 without a channel). */
        val atMs: Long = 0,
        val airMs: Long = 0,
        val bytes: Int = 0,
        /** The node whose packet this is, and its nth of the kind: a re-air's origin, not the repeater. */
        val origin: String = from,
        val seq: Int = 0,
        /** The `path_len` byte it aired with. */
        val pathLen: Int = 0,
        /** The delay this node drew before re-airing it, or null for an own packet. */
        val drawMs: Long? = null,
        /** Channel data carried (a forger reads part 0 off it). */
        val data: ByteArray? = null,
    ) {
        /** Who decoded it whole, and who lost it (an overlap, a deaf transmitter, a lossy link). */
        val heardBy: MutableList<String> = CopyOnWriteArrayList()
        val lostAt: MutableList<String> = CopyOnWriteArrayList()

        override fun toString(): String = "$from $type ${if (flood) "flood" else "direct"} hops=$hops"
    }

    /** One repeater delay: who drew it for whose nth flood (or direct packet), and the milliseconds. */
    data class Draw(
        val repeater: String,
        val origin: String,
        val seq: Int,
        val direct: Boolean,
        val maxMs: Long,
        val ms: Long,
    )

    fun board(
        name: String,
        key: ByteArray = keyOf(name),
        maxContacts: Int = 100,
        maxChannels: Int = 4,
    ): FakeMeshcoreBoard =
        FakeMeshcoreBoard(this, name, key, maxContacts, maxChannels).also {
            boards[name] = it
            channel?.let { ch ->
                radios[name] = FakeRadio(name, key, repeater = false, pool = MeshcoreHeadroom.POOL)
                // SELF_INFO reports the preset the channel airs at, so the plane prices what the fake transmits.
                it.sf = ch.spreadFactor
                it.bwHz = ch.bandwidthHz.toLong()
                it.cr = ch.codingRate
            }
        }

    fun link(
        a: String,
        b: String,
    ) {
        links += a to b
        links += b to a
    }

    /** Takes [a] and [b] out of each other's range. */
    fun unlink(
        a: String,
        b: String,
    ) {
        links -= a to b
        links -= b to a
    }

    /** A repeater node [name], in range of [neighbours]: it re-airs every flood it hears once, one hop on. */
    fun repeater(
        name: String,
        vararg neighbours: String,
    ) {
        repeaters += name
        if (channel != null) radios.getOrPut(name) { FakeRadio(name, keyOf(name), repeater = true, pool = REPEATER_POOL) }
        neighbours.forEach { link(name, it) }
    }

    /** A repeater's or a board's hash bytes, as a path carries them. */
    fun hashOf(
        name: String,
        size: Int = 1,
    ): ByteArray = (boards[name]?.key ?: keyOf(name)).copyOf(size)

    /** Powers a repeater (or a board's radio) off and on; a repeater powered on has lost its ring and queue. */
    fun power(
        name: String,
        on: Boolean,
    ) {
        boards[name]?.let {
            it.powered = on
            return
        }
        val r = radios[name] ?: return
        if (!on) {
            r.queue.clear()
            r.forced.clear()
            r.rx = null
            r.txUntil = -1
        } else if (!r.powered) {
            r.clearRing()
        }
        r.powered = on
    }

    /** Every airing from [from] heard by [to] is lost while [drop] says so (one direction only). */
    fun lossy(
        from: String,
        to: String,
        drop: (Airing) -> Boolean = { true },
    ) {
        lossy[from to to] = drop
    }

    fun lossless(
        from: String,
        to: String,
    ) {
        lossy.remove(from to to)
    }

    /** Parks every airing of [name] (a board or a repeater) before anyone hears it, until [release]. Channel only. */
    fun hold(name: String) {
        requireNotNull(radios[name]) { "hold needs a channel and a node" }.held = true
    }

    /** What a held node has parked: its label, origin, bytes and channel data, for [release]'s pick. */
    class Held internal constructor(
        internal val queued: FakeRadio.Queued,
    ) {
        val type: String get() = queued.packet.label
        val origin: String get() = queued.packet.origin
        val bytes: Int get() = queued.packet.rawLen
        val data: ByteArray? get() = (queued.packet.body as? AirBody.Group)?.data

        override fun toString(): String = "$origin $type ${bytes}B"
    }

    /** What [name] holds parked, in queue order. */
    fun parked(name: String): List<Held> = radios[name]?.queue?.map(::Held).orEmpty()

    /**
     * Airs what [pick] chooses of [name]'s parked airings, in the order it returns them, before anything else it queues;
     * the rest stay parked when [stayHeld], else they air as the queue would. [stayHeld] keeps the node held after.
     */
    fun release(
        name: String,
        stayHeld: Boolean = false,
        pick: (List<Held>) -> List<Held> = { it },
    ) {
        val r = requireNotNull(radios[name])
        val chosen = pick(r.queue.map(::Held))
        chosen.forEach {
            r.queue.remove(it.queued)
            r.forced.addLast(it.queued)
        }
        r.held = stayHeld
        pump(r)
    }

    /** Runs [block] on the air's serial context (or here, without one): a scenario's consistent read. */
    suspend fun <T> snapshot(block: () -> T): T = if (serial != null) withContext(serial) { block() } else block()

    internal fun later(
        ms: Long,
        block: () -> Unit,
    ) {
        scope.launch(ctx) {
            delay(ms)
            confined(block)
        }
    }

    private val inside = AtomicInteger()

    /** The most bodies that ever ran at once ([confined]): 1 on a [serial] context, the probe the thread-safety test reads. */
    val maxInside = AtomicInteger()

    /** Runs [block], one of the air's mutating bodies (a write, a timer, a launch), counted for [maxInside]. */
    internal fun <T> confined(block: () -> T): T {
        maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
        try {
            return block()
        } finally {
            inside.decrementAndGet()
        }
    }

    private fun neighbours(name: String): List<String> = links.filter { it.first == name }.map { it.second }

    private fun dropped(
        from: String,
        to: String,
        airing: Airing,
    ): Boolean = lossy[from to to]?.invoke(airing) == true

    /**
     * [from] airs a packet: every board in range hears it at [hops]; a repeater in range re-airs a flood once, a hop on.
     * [deliver] is each board's reception. The Stage 3b air: no time on the air, no collision.
     */
    fun transmit(
        from: String,
        type: String,
        flood: Boolean,
        hops: Int = 0,
        seen: MutableSet<String> = HashSet(),
        deliver: (FakeMeshcoreBoard, Int) -> Unit,
    ) {
        val airing = Airing(from, type, flood, hops)
        airings += airing
        seen += from
        for (n in neighbours(from)) {
            if (dropped(from, n, airing)) continue
            // A node never takes back a packet it has aired: its own hash is in its table (Mesh::sendFlood).
            if (n !in seen) boards[n]?.takeIf { it.powered }?.let { deliver(it, hops) }
            if (flood && n in repeaters && n !in seen) {
                seen += n
                scope.launch(ctx) {
                    delay(repeatDelayMs)
                    confined { transmit(n, type, flood = true, hops = hops + 1, seen = seen, deliver = deliver) }
                }
            }
        }
    }

    // --- the channel (opt-in) ---

    private val ch: MeshcoreChannel get() = requireNotNull(channel)

    /** [node] originates [packet] (`Mesh::sendFlood` / `sendDirect`): marked seen, then queued [delayMs] on at [priority]. */
    internal fun originate(
        node: FakeRadio,
        packet: AirPacket,
        delayMs: Long,
        priority: Int,
    ) {
        node.markSeen(packet)
        queueOut(node, packet, delayMs, priority)
    }

    private fun queueOut(
        node: FakeRadio,
        packet: AirPacket,
        delayMs: Long,
        priority: Int,
    ) {
        if (!node.powered) return
        if (node.repeater && node.queue.size >= node.pool) return // `allocNew` null: the pool is full
        node.queue += FakeRadio.Queued(packet, now() + delayMs, priority)
        if (delayMs <= 0) pump(node) else later(delayMs) { pump(node) }
    }

    /** `Dispatcher::checkSend`: the next eligible packet, under the budget and listen-before-talk. */
    private fun pump(node: FakeRadio) {
        val t = now()
        // An airing whose end has not run yet (`txUntil` is reset there) still holds the radio: on the lab's wall clock a
        // lagged end timer would otherwise let the next airing start under it (5c-2b's chaos sweep). The end pumps again.
        if (!node.powered || node.busyTransmitting(t)) return
        val next =
            node.forced.firstOrNull()
                ?: if (node.held) null else node.queue.filter { it.at <= t }.minByOrNull { it.priority }
        if (next == null) {
            if (!node.held) node.queue.minOfOrNull { it.at }?.let { at -> later(at - t) { pump(node) } }
            return
        }
        val rogue = node.name in ch.rogue
        if (!rogue) {
            val elapsed = t - node.budgetAt
            node.budgetMs = minOf(MeshcoreChannel.DUTY_WINDOW_MS * MeshcoreChannel.DUTY, node.budgetMs + elapsed * MeshcoreChannel.DUTY)
            node.budgetAt = t
            val need = ch.airtimeMs(MeshcoreChannel.MAX_TRANS_UNIT) / 2.0
            if (node.budgetMs < need) {
                later(((need - node.budgetMs) / MeshcoreChannel.DUTY).toLong() + 1) { pump(node) }
                return
            }
            if (node.receiving(t)) {
                if (node.cadBusySince == 0L) node.cadBusySince = t
                if (t - node.cadBusySince <= MeshcoreChannel.CAD_MAX_MS) {
                    later(MeshcoreChannel.CAD_RETRY_MS) { pump(node) }
                    return
                }
            }
            node.cadBusySince = 0L
        }
        if (!node.forced.remove(next)) node.queue.remove(next)
        startTx(node, next.packet, drawOf(next))
    }

    private val drawn = HashMap<FakeRadio.Queued, Long>()

    private fun drawOf(q: FakeRadio.Queued): Long? = drawn.remove(q)

    private fun startTx(
        node: FakeRadio,
        p: AirPacket,
        drawMs: Long?,
    ) {
        val t = now()
        val dur = ch.airtimeMs(p.rawLen, ch.preambleFor(node.name))
        node.txUntil = t + dur
        node.rx?.let { cur ->
            // Forced past CAD (or a rogue) over a reception: half duplex loses it.
            if (!cur.corrupted && cur.endsAt > t) {
                node.collisions++
                cur.airing.lostAt += node.name
            }
            node.rx = null
        }
        val airing =
            Airing(
                node.name,
                p.label,
                p.flood,
                p.hops,
                atMs = t,
                airMs = dur,
                bytes = p.rawLen,
                origin = p.origin,
                seq = p.seq,
                pathLen = p.pathLenByte,
                drawMs = drawMs,
                data = (p.body as? AirBody.Group)?.data,
            )
        airings += airing
        val receivers = neighbours(node.name).mapNotNull { n -> radios[n]?.takeIf { lockOn(it, node.name, p, airing, t, dur) } }
        later(dur) {
            node.txUntil = -1
            node.txAirMs += dur
            node.budgetMs = (node.budgetMs - dur).coerceAtLeast(0.0)
            boards[node.name]?.let { it.sent++ }
            for (r in receivers) endReception(r, p, node.name, airing, dur)
            listeners.forEach { it(airing) }
            pump(node)
            receivers.forEach { pump(it) }
        }
    }

    /**
     * Whether [r] begins decoding [p] from [from] at [t]: not when it has no power, the link loses it or it is transmitting
     * (half duplex, [FakeRadio.deaf]). One that overlaps a reception corrupts both ([FakeRadio.collisions] counts each),
     * and the radio stays busy to the later end, which its CAD sees; only a stated [MeshcoreChannel.capture] keeps the
     * locked one.
     */
    @Suppress("LongParameterList") // one reception's facts
    private fun lockOn(
        r: FakeRadio,
        from: String,
        p: AirPacket,
        airing: Airing,
        t: Long,
        dur: Long,
    ): Boolean {
        if (!r.powered) return false
        // A reception past its end whose end timer has not run (a lagged timer on the wall clock): ended first, so a newcomer
        // never overwrites it unseen (5c-2b's chaos sweep). Under virtual time it is a packet that ends as this one starts.
        r.rx?.takeIf { it.endsAt <= t }?.let { endReception(r, it.packet, it.from, it.airing, it.airing.airMs) }
        val cur = r.rx?.takeIf { it.endsAt > t }
        val lost =
            when {
                p.lost || dropped(from, r.name, airing) -> {
                    true
                }

                r.transmitting(t) -> {
                    true.also { r.deaf++ }
                }

                cur != null -> {
                    true.also { overlap(r, cur, from, t + dur) }
                }

                // Inside a captured packet's overlapping tail: the channel is still another's.
                r.busyUntil > t -> {
                    true.also {
                        r.collisions++
                        r.busyUntil = maxOf(r.busyUntil, t + dur)
                    }
                }

                else -> {
                    false
                }
            }
        if (lost) {
            airing.lostAt += r.name
            return false
        }
        r.rx = FakeRadio.Reception(p, t + dur, from, airing)
        return true
    }

    /** A packet from [from] ending at [endsAt] overlaps [cur] at [r]: both lost unless [r] captures [cur]. */
    private fun overlap(
        r: FakeRadio,
        cur: FakeRadio.Reception,
        from: String,
        endsAt: Long,
    ) {
        r.collisions++
        // The newcomer's air keeps the channel busy to its end whatever is decoded: the radio's CAD sees the tail.
        r.busyUntil = maxOf(r.busyUntil, endsAt)
        if (cur.corrupted || ch.capture(r.name, cur.from, from)) {
            if (cur.corrupted) cur.endsAt = maxOf(cur.endsAt, endsAt)
            return
        }
        cur.corrupted = true
        cur.endsAt = maxOf(cur.endsAt, endsAt)
        r.collisions++
        cur.airing.lostAt += r.name
    }

    /** [p]'s airing ended at [r]: decoded whole unless an overlap corrupted it (then the radio stays busy to the later end). */
    private fun endReception(
        r: FakeRadio,
        p: AirPacket,
        from: String,
        airing: Airing,
        dur: Long,
    ) {
        val rx = r.rx
        if (rx?.packet !== p) return
        if (rx.corrupted) {
            if (rx.endsAt <= now()) r.rx = null
            return
        }
        r.rx = null
        r.rxAirMs += dur
        airing.heardBy += r.name
        received(r, p, from, dur)
    }

    /** `Dispatcher::checkRecv`: a flood waits its score delay when `rx_delay_base` asks for one. */
    private fun received(
        r: FakeRadio,
        p: AirPacket,
        from: String,
        airMs: Long,
    ) {
        val d = if (p.flood) ch.rxDelayMs(ch.score(from, r.name), airMs) else 0L
        if (d < MeshcoreChannel.RX_DELAY_THRESHOLD_MS) process(r, p) else later(minOf(d, MeshcoreChannel.MAX_RX_DELAY_MS)) { process(r, p) }
    }

    /** `Mesh::onRecvPacket`, for the payload types the fake carries. */
    private fun process(
        r: FakeRadio,
        p: AirPacket,
    ) {
        if (!r.powered) return
        val board = boards[r.name]
        if (!p.flood && p.path.isNotEmpty()) {
            // An early-received ack: offered to whoever hears it on its way (`Mesh.cpp:79-87`).
            if (p.type == MeshcoreChannel.TYPE_ACK) board?.onAirAck(p)
            if (r.repeater && r.isNext(p.path, p.hashSize) && !r.wasSeen(p)) {
                r.markSeen(p)
                val on = p.withPath(p.path.copyOfRange(p.hashSize, p.path.size))
                // Priced before the strip, as `Mesh.cpp:90` asks `getDirectRetransmitDelay` ahead of `removeSelfFromPath`.
                retransmit(r, on, direct = true, priority = 0, pricedAs = p)
            }
            return // not the next hop, or already forwarded (`Mesh.cpp:107`)
        }
        if (r.wasSeen(p)) {
            r.dups++
            return
        }
        r.markSeen(p)
        val mine = board?.onAir(p) == true
        val forwards = r.repeater && p.flood && p.path.size + p.hashSize <= MeshcoreChannel.MAX_PATH_SIZE
        if (forwards && !mine) {
            val on = p.withPath(p.path + r.key.copyOf(p.hashSize))
            retransmit(r, on, direct = false, priority = on.hops)
        }
    }

    /** `getRetransmitDelay` / `getDirectRetransmitDelay` (`simple_repeater/MyMesh.cpp:547-553`), drawn per packet. */
    private fun retransmit(
        r: FakeRadio,
        p: AirPacket,
        direct: Boolean,
        priority: Int,
        pricedAs: AirPacket = p,
    ) {
        val factor = if (direct) ch.directTxDelayFactor else ch.txDelayFactor
        val t = (ch.airtimeMs(pricedAs.path.size + pricedAs.payloadLen + 2) * factor).toLong()
        val maxMs = 5 * t
        val seeded = Random(MeshcoreChannel.mix(ch.runSeed, r.name, p.origin, p.seq, direct))
        val ms = ch.pinDraw?.invoke(maxMs) ?: seeded.nextLong(0, maxMs + 1)
        draws += Draw(r.name, p.origin, p.seq, direct, maxMs, ms)
        if (!r.powered || r.queue.size >= r.pool) return
        val q = FakeRadio.Queued(p, now() + ms, priority)
        drawn[q] = ms
        r.queue += q
        if (ms <= 0) pump(r) else later(ms) { pump(r) }
    }

    /** Evicts every hash from [name]'s ring, as 160 newer packets would (the replay case's knob). */
    fun evictRing(name: String) {
        radios[name]?.clearRing()
    }

    /** A radio's packets so far, by [FakeRadio] counter: for oracles. */
    fun radio(name: String): FakeRadio = requireNotNull(radios[name]) { "no radio $name (no channel?)" }

    companion object {
        /** The repeater's pool (`simple_repeater/MyMesh.cpp:864`). */
        const val REPEATER_POOL = 32

        private val pairs = java.util.concurrent.ConcurrentHashMap<String, com.google.crypto.tink.subtle.Ed25519Sign.KeyPair>()

        /**
         * A board key: a real Ed25519 public key whose private half is seeded from [name] (so a fake board signs, as a
         * companion does, `MyMesh.cpp:1801`), never starting with the reserved `0x00` / `0xFF`.
         */
        fun keyOf(name: String): ByteArray = pairOf(name).publicKey.copyOf()

        private fun pairOf(name: String): com.google.crypto.tink.subtle.Ed25519Sign.KeyPair =
            pairs.getOrPut(name) {
                generateSequence(0) { it + 1 }
                    .map { n ->
                        val seed = MessageDigest.getInstance("SHA-256").digest(("meshcore-fake:$name:$n").encodeToByteArray())
                        com.google.crypto.tink.subtle.Ed25519Sign.KeyPair
                            .newKeyPairFromSeed(seed)
                    }.first { it.publicKey[0].toInt() != 0 && it.publicKey[0].toInt() != -1 }
            }

        /** The signer for [key] when it is a [keyOf] key; null for any other (a clone that reports a key it does not hold). */
        fun signerOf(key: ByteArray): com.google.crypto.tink.subtle.Ed25519Sign? =
            pairs.values.firstOrNull { it.publicKey.contentEquals(key) }?.let {
                com.google.crypto.tink.subtle
                    .Ed25519Sign(it.privateKey)
            }

        /** `PUBLIC_GROUP_PSK` (`MyMesh.cpp:114`). */
        val PUBLIC_PSK: ByteArray = Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")
    }
}

/**
 * The Stage 3b air with its figures compressed in proportion (÷ [factor]), for the lab's ordinary cases on the wall clock:
 * the confirmation, the pool hold, the repeat and the advertised `est_timeout`, never under the session's 500-ms floor.
 * No channel: a case that needs the air's physics runs as a virtual-time JVM test over [MeshcoreChannel] instead.
 */
internal fun quickMeshcoreAir(
    scope: CoroutineScope,
    now: () -> Long,
    serial: CoroutineContext? = null,
    factor: Long = 10,
): FakeMeshcoreAir =
    FakeMeshcoreAir(scope, serial = serial, now = now).apply {
        confirmDelayMs /= factor
        airMs /= factor
        repeatDelayMs /= factor
        estFloodMs = (estFloodMs / factor).coerceAtLeast(MIN_EST_TIMEOUT_MS)
        estDirectMs = (estDirectMs / factor).coerceAtLeast(MIN_EST_TIMEOUT_MS)
    }

/** The session's floor on an advertised `est_timeout` (`MeshcoreSession.MIN_EST_TIMEOUT_MS`). */
private const val MIN_EST_TIMEOUT_MS = 500L

/** One interface a board writes every answer and push to: our NUS channel, or another client. */
internal interface FakeIface {
    val connected: Boolean

    fun deliver(frame: ByteArray)
}

/** One contact on a fake board, as `ContactInfo` keeps it. */
internal class FakeContact(
    val key: ByteArray,
    var type: Int,
    var flags: Int,
    var outPathLen: Int,
    var name: ByteArray,
    var lastmod: Long,
    /** The route's hashes (`out_path`), [outPathLen]'s count of them; empty for a zero-hop or unknown route. */
    var outPath: ByteArray = ByteArray(0),
)

/** One companion board running the `MyMesh.cpp` command handler (see [FakeMeshcoreAir]). */
@Suppress("TooManyFunctions", "LargeClass") // one handler per command, as the firmware's handleCmdFrame is one function
internal class FakeMeshcoreBoard(
    internal val air: FakeMeshcoreAir,
    val name: String,
    val key: ByteArray,
    var maxContacts: Int,
    val maxChannels: Int,
) {
    var versionCode = 14
    var advertName: ByteArray = name.encodeToByteArray()
    var manualAdd = 0
    var autoadd = 0
    var autoaddMaxHops = 0
    var multiAcks = 0
    var defaultScope = false
    var scopeOverride = false
    private var clockBase = 1_790_000_000L
    private var clockSetAt = air.now()

    /**
     * The board's RTC (`RTCClock`), in seconds. Without a channel it moves only when set (Stage 3b); with one it ticks
     * with the air's clock, so a jump of [FakeMeshcoreAir.now] moves it as a real RTC would.
     */
    var clockSecs: Long
        get() = if (air.channel != null) clockBase + (air.now() - clockSetAt) / 1_000 else clockBase
        set(value) {
            clockBase = value
            clockSetAt = air.now()
        }
    var batteryMv = 4_100

    /** `advert_loc_policy`, the telemetry byte (`env << 4 | loc << 2 | base`) and the live position, as `SELF_INFO` reports them. */
    var advertLocPolicy = 0
    var telemetryModes = 0
    var latE6 = 0
    var lonE6 = 0
    var freqKhz = 869_525L
    var bwHz = 250_000L
    var sf = 11
    var cr = 5
    var appTargetVersion = 0
    var uptimeBaseMs = 0L

    val channels = Array(maxChannels) { ByteArray(0) to ByteArray(MeshcoreProto.SECRET_BYTES) }

    /** The board's sign buffer (`sign_data`), null when none is open. */
    var signBuffer: ByteArray? = null

    /** False: a clone at the address that reports this board's key (it is in the board's adverts) but cannot sign with it. */
    var canSign = true

    /** `SIGN_FINISH`es left unanswered (a lost notify): each one drops its answer. */
    var dropSignFinish = 0

    /** A key `SELF_INFO` reports instead of [key] (another client imported one): signing stays [key]'s. */
    var reportedKey: ByteArray? = null

    /** From which `APP_START` answer (1-based, counted over the board's life) [reportedKey] applies; null: from the first. */
    var reportedKeyFrom: Int? = null
    private var appStarts = 0
    val contacts = mutableListOf<FakeContact>()
    val offline = ArrayDeque<ByteArray>()

    /**
     * The offline queue's cap (`OFFLINE_QUEUE_SIZE`): 256 on the measured board (Q6) without a channel; with one, the
     * source default of 16 (`MyMesh.h:61-62`, which 4 board variants keep). Settable.
     */
    var offlineCap = if (air.channel != null) SOURCE_OFFLINE_CAP else 256
    private val ackTable = IntArray(MeshcoreAckTable.EXPECTED_ACK_TABLE_SIZE)
    private val ackFor = arrayOfNulls<ByteArray>(MeshcoreAckTable.EXPECTED_ACK_TABLE_SIZE)
    private val ackAt = LongArray(MeshcoreAckTable.EXPECTED_ACK_TABLE_SIZE)
    private var nextAck = 0
    var outbound = 0
        get() = air.radios[name]?.let { it.queue.size + it.forced.size } ?: field
    var sent = 0L
    var txAirSecs = 0L
        get() = air.radios[name]?.let { it.txAirMs / 1_000 } ?: field
    var rxAirSecs = 0L
        get() = air.radios[name]?.let { it.rxAirMs / 1_000 } ?: field

    /** Whether this board's floods go scoped (`sendFloodScoped`): its scope override, or a default scope. */
    private val scoped: Boolean get() = scopeOverride || defaultScope

    /** `path_hash_mode` (the top two bits of `path_len`): a flood this board originates carries hashes of this size − 1. */
    var pathHashMode = 0

    /**
     * Whether the board has power. Off, it is deaf and mute and its BLE link drops; on again it [reboot]s, which keeps the
     * contacts (they are in flash) and loses everything in RAM.
     */
    var powered = true
        set(value) {
            if (field == value) return
            field = value
            val r = air.radios[name]
            if (!value) {
                r?.let {
                    it.queue.clear()
                    it.forced.clear()
                    it.rx = null
                    it.txUntil = -1
                    it.powered = false
                }
                ifaces.filterIsInstance<FakeNusChannel>().forEach { it.disconnect() }
            } else {
                r?.powered = true
                reboot()
            }
        }
    private val heard = HashSet<String>()
    val ifaces = mutableListOf<FakeIface>()

    /** Commands this board answered, by code (the firmware has no such count; the tests read it). */
    val commands = mutableListOf<Int>()

    /**
     * Contact DMs addressed to this board that matched none of its contacts and were dropped unread, unanswered — the
     * firmware's `recv matches no peers` (`Mesh.cpp:186`); the firmware has no such count, the tests read it.
     */
    var noPeerDrops = 0

    /** DMs its app was handed, as the receive frames' text. */
    val acksAired = mutableListOf<Boolean>()

    init {
        channels[0] = "Public".encodeToByteArray() to FakeMeshcoreAir.PUBLIC_PSK
    }

    /** The air's scope, on which a delayed notify lands. */
    val scope: CoroutineScope get() = air.scope

    val uptimeSecs: Long get() = (air.now() - uptimeBaseMs) / 1_000

    /**
     * The board reboots: its uptime restarts and its RAM state is gone — the ack table, the scope override, the offline
     * queue (`offline_queue_len = 0`, `MyMesh.cpp:906`), the seen ring and the radio's queue. Contacts stay (flash).
     */
    fun reboot() {
        uptimeBaseMs = air.now()
        ackTable.fill(0)
        ackFor.fill(null)
        scopeOverride = false
        outbound = 0
        offline.clear()
        heard.clear()
        air.radios[name]?.let {
            it.clearRing()
            it.queue.clear()
            it.forced.clear()
        }
    }

    fun contact(key: ByteArray): FakeContact? = contacts.firstOrNull { it.key.contentEquals(key) }

    /** Adds [other] as a contact with route [outPathLen] (`0` pinned, `0xFF` unknown), favourite like Knit's. */
    fun addContact(
        other: FakeMeshcoreBoard,
        outPathLen: Int = 0,
        name: String = MeshcoreNames.forKey(other.key),
        flags: Int = MeshcoreProto.CONTACT_FLAG_FAVOURITE,
    ): FakeContact =
        FakeContact(other.key, MeshcoreProto.ADV_TYPE_CHAT, flags, outPathLen, name.encodeToByteArray(), clockSecs).also { contacts += it }

    fun setChannel(
        index: Int,
        name: String,
        secret: ByteArray,
    ) {
        channels[index] = name.encodeToByteArray() to secret
    }

    /** Another client (or the firmware) drifts a route to UNKNOWN, pushing nothing (`RESET_PATH`, Q13). */
    fun driftPath(other: FakeMeshcoreBoard) {
        contact(other.key)?.let {
            it.outPathLen = MeshcoreProto.PATH_UNKNOWN
            it.outPath = ByteArray(0)
        }
    }

    /**
     * The route to [other] drifts, silently, to a path through the named repeaters [via] (one name: a 1-hop route) — as
     * another client's `ADD_UPDATE_CONTACT`, or a PATH whose `0x81` the phone never read, would leave it.
     */
    fun driftPath(
        other: FakeMeshcoreBoard,
        vararg via: String,
    ) {
        contact(other.key)?.let {
            it.outPath = via.fold(ByteArray(0)) { acc, n -> acc + air.hashOf(n) }
            it.outPathLen = via.size
        }
    }

    /** Writes [frame] to every connected interface (`MultiSerialInterface`). */
    fun writeAll(frame: ByteArray) {
        ifaces.filter { it.connected }.forEach { it.deliver(frame.copyOf()) }
    }

    private fun ok() = writeAll(byteArrayOf(0))

    private fun err(code: Int) = writeAll(byteArrayOf(1, code.toByte()))

    /** `handleCmdFrame` (`MyMesh.cpp:1055`). */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun handle(frame: ByteArray) {
        if (frame.isEmpty() || !powered) return
        val code = frame[0].toInt() and 0xFF
        commands += code
        when (code) {
            MeshcoreProto.CMD_DEVICE_QUERY -> {
                deviceInfo(frame)
            }

            MeshcoreProto.CMD_APP_START -> {
                selfInfo()
            }

            MeshcoreProto.CMD_GET_CHANNEL -> {
                getChannel(frame[1].toInt() and 0xFF)
            }

            MeshcoreProto.CMD_SET_CHANNEL -> {
                setChannelCmd(frame)
            }

            MeshcoreProto.CMD_SET_DEVICE_TIME -> {
                setTime(MeshcoreProto.u32(frame, 1))
            }

            MeshcoreProto.CMD_GET_DEVICE_TIME -> {
                writeAll(byteArrayOf(MeshcoreProto.RESP_CURR_TIME.toByte()) + u32(clockSecs))
            }

            MeshcoreProto.CMD_SET_FLOOD_SCOPE_KEY -> {
                scopeOverride = frame.size >= 18
                ok()
            }

            MeshcoreProto.CMD_GET_DEFAULT_FLOOD_SCOPE -> {
                writeAll(
                    if (defaultScope) byteArrayOf(MeshcoreProto.RESP_DEFAULT_FLOOD_SCOPE.toByte()) + ByteArray(47) else byteArrayOf(28),
                )
            }

            MeshcoreProto.CMD_GET_STATS -> {
                stats(frame[1].toInt())
            }

            MeshcoreProto.CMD_GET_BATT_AND_STORAGE -> {
                writeAll(byteArrayOf(12, batteryMv.toByte(), (batteryMv shr 8).toByte()) + ByteArray(8))
            }

            MeshcoreProto.CMD_SET_OTHER_PARAMS -> {
                manualAdd = frame[1].toInt() and 0xFF
                ok()
            }

            MeshcoreProto.CMD_GET_AUTOADD_CONFIG -> {
                writeAll(byteArrayOf(25, autoadd.toByte(), autoaddMaxHops.toByte()))
            }

            MeshcoreProto.CMD_SET_AUTOADD_CONFIG -> {
                autoadd = frame[1].toInt() and 0xFF
                if (frame.size >= 3) autoaddMaxHops = frame[2].toInt()
                ok()
            }

            MeshcoreProto.CMD_SET_ADVERT_NAME -> {
                advertName = frame.copyOfRange(1, minOf(frame.size, 32))
                ok()
            }

            MeshcoreProto.CMD_SYNC_NEXT_MESSAGE -> {
                val next = offline.removeFirstOrNull()
                writeAll(next ?: byteArrayOf(MeshcoreProto.RESP_NO_MORE_MESSAGES.toByte()))
            }

            MeshcoreProto.CMD_GET_CONTACT_BY_KEY -> {
                val c = contact(frame.copyOfRange(1, 33))
                if (c == null) err(MeshcoreProto.ERR_NOT_FOUND) else writeAll(contactFrame(MeshcoreProto.RESP_CONTACT, c))
            }

            MeshcoreProto.CMD_ADD_UPDATE_CONTACT -> {
                addUpdate(frame)
            }

            MeshcoreProto.CMD_REMOVE_CONTACT -> {
                val c = contact(frame.copyOfRange(1, 33))
                if (c == null) err(MeshcoreProto.ERR_NOT_FOUND) else ok().also { contacts.remove(c) }
            }

            MeshcoreProto.CMD_RESET_PATH -> {
                val c = contact(frame.copyOfRange(1, 33))
                if (c == null) {
                    err(MeshcoreProto.ERR_NOT_FOUND)
                } else {
                    c.outPathLen = MeshcoreProto.PATH_UNKNOWN
                    ok()
                }
            }

            MeshcoreProto.CMD_GET_CONTACTS -> {
                val since = if (frame.size >= 5) MeshcoreProto.u32(frame, 1) else 0L
                writeAll(byteArrayOf(2) + u32(contacts.size.toLong()))
                contacts.filter { it.lastmod > since }.forEach { writeAll(contactFrame(MeshcoreProto.RESP_CONTACT, it)) }
                writeAll(byteArrayOf(4) + u32(contacts.maxOfOrNull { it.lastmod } ?: 0))
            }

            MeshcoreProto.CMD_SEND_TXT_MSG -> {
                sendText(frame)
            }

            // `MyMesh.cpp:1781-1815`: one buffer for every client; SIGN_FINISH signs it with the board's own key.
            MeshcoreProto.CMD_SIGN_START -> {
                signBuffer = ByteArray(0)
                writeAll(byteArrayOf(MeshcoreProto.RESP_SIGN_START.toByte(), 0) + u32(8 * 1024L))
            }

            MeshcoreProto.CMD_SIGN_DATA -> {
                val buf = signBuffer
                if (buf == null) err(MeshcoreProto.ERR_BAD_STATE) else ok().also { signBuffer = buf + frame.copyOfRange(1, frame.size) }
            }

            MeshcoreProto.CMD_SIGN_FINISH -> {
                if (dropSignFinish > 0) {
                    dropSignFinish--
                    return
                }
                val buf = signBuffer
                val signer = if (canSign) FakeMeshcoreAir.signerOf(key) else null
                signBuffer = null
                when {
                    buf == null -> {
                        err(MeshcoreProto.ERR_BAD_STATE)
                    }

                    // A clone at the address reporting a key it does not hold: whatever it answers, it is no signature by it.
                    signer == null -> {
                        writeAll(
                            byteArrayOf(MeshcoreProto.RESP_SIGNATURE.toByte()) + ByteArray(MeshcoreProto.SIGNATURE_BYTES),
                        )
                    }

                    else -> {
                        writeAll(byteArrayOf(MeshcoreProto.RESP_SIGNATURE.toByte()) + signer.sign(buf))
                    }
                }
            }

            MeshcoreProto.CMD_SEND_CHANNEL_DATA -> {
                sendChannelData(frame)
            }

            MeshcoreProto.CMD_SEND_CHANNEL_TXT_MSG -> {
                sendChannelText(frame)
            }

            else -> {
                err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
            }
        }
    }

    private fun deviceInfo(frame: ByteArray) {
        if (frame.size >= 2) appTargetVersion = frame[1].toInt()
        val out = ByteArray(82)
        out[0] = MeshcoreProto.RESP_DEVICE_INFO.toByte()
        out[1] = versionCode.toByte()
        out[2] = (maxContacts / 2).toByte()
        out[3] = maxChannels.toByte()
        "fake-v1.18".encodeToByteArray().copyInto(out, 60)
        writeAll(out)
    }

    private fun selfInfo() {
        val out = ByteArray(58)
        out[0] = MeshcoreProto.RESP_SELF_INFO.toByte()
        out[1] = 1
        out[2] = 20
        out[3] = 20
        appStarts++
        val rekeyed = reportedKey?.takeIf { reportedKeyFrom?.let { from -> appStarts >= from } ?: true }
        (rekeyed ?: key).copyInto(out, 4)
        java.nio.ByteBuffer
            .wrap(out, 36, 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(latE6)
            .putInt(lonE6)
        out[44] = multiAcks.toByte()
        out[45] = advertLocPolicy.toByte()
        out[46] = telemetryModes.toByte()
        out[47] = manualAdd.toByte()
        u32(freqKhz).copyInto(out, 48)
        u32(bwHz).copyInto(out, 52)
        out[56] = sf.toByte()
        out[57] = cr.toByte()
        writeAll(out + advertName)
    }

    private fun getChannel(index: Int) {
        if (index >= maxChannels) return err(MeshcoreProto.ERR_NOT_FOUND)
        val (n, secret) = channels[index]
        writeAll(byteArrayOf(MeshcoreProto.RESP_CHANNEL_INFO.toByte(), index.toByte()) + n.copyOf(32) + secret)
    }

    private fun setChannelCmd(frame: ByteArray) {
        if (frame.size >= 2 + 32 + 32) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val index = frame[1].toInt() and 0xFF
        if (frame.size < 2 + 32 + 16 || index >= maxChannels) return err(MeshcoreProto.ERR_NOT_FOUND)
        channels[index] = MeshcoreProto.cBytes(frame, 2, 32) to frame.copyOfRange(34, 50)
        ok()
    }

    private fun setTime(secs: Long) {
        if (secs >= clockSecs) {
            clockSecs = secs
            ok()
        } else {
            err(MeshcoreProto.ERR_ILLEGAL_ARG)
        }
    }

    private fun stats(type: Int) {
        when (type) {
            MeshcoreProto.STATS_CORE -> {
                writeAll(
                    byteArrayOf(24, 0, batteryMv.toByte(), (batteryMv shr 8).toByte()) + u32(uptimeSecs) +
                        byteArrayOf(0, 0, outbound.toByte()),
                )
            }

            MeshcoreProto.STATS_RADIO -> {
                writeAll(byteArrayOf(24, 1, 0x9C.toByte(), 0xFF.toByte(), (-80).toByte(), 32) + u32(txAirSecs) + u32(rxAirSecs))
            }

            MeshcoreProto.STATS_PACKETS -> {
                writeAll(byteArrayOf(24, 2) + u32(0) + u32(sent) + ByteArray(20))
            }

            else -> {
                err(MeshcoreProto.ERR_ILLEGAL_ARG)
            }
        }
    }

    private fun contactFrame(
        code: Int,
        c: FakeContact,
    ): ByteArray {
        val out = ByteArray(MeshcoreProto.CONTACT_FULL_SIZE)
        out[0] = code.toByte()
        c.key.copyInto(out, 1)
        out[33] = c.type.toByte()
        out[34] = c.flags.toByte()
        out[35] = c.outPathLen.toByte()
        c.outPath.copyOf(minOf(c.outPath.size, OUT_PATH_BYTES)).copyInto(out, 36)
        c.name.copyOf(32).copyInto(out, 100)
        u32(c.lastmod).copyInto(out, 144)
        return out
    }

    /** `CMD_ADD_UPDATE_CONTACT` (`MyMesh.cpp:1328`), with `allocateContactSlot`'s overwrite-oldest (`BaseChatMesh.cpp:72`). */
    private fun addUpdate(frame: ByteArray) {
        if (frame.size < 36) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val k = frame.copyOfRange(1, 33)
        val type = frame[33].toInt() and 0xFF
        val flags = frame[34].toInt() and 0xFF
        val opl = frame[35].toInt() and 0xFF
        val n = if (frame.size >= 132) frame.copyOfRange(100, 132) else ByteArray(32)
        val path = pathOf(frame, opl)
        val existing = contact(k)
        if (existing != null) {
            existing.type = type
            existing.flags = flags
            existing.outPathLen = opl
            existing.outPath = path
            existing.name = MeshcoreProto.cBytes(n, 0, 32)
            existing.lastmod = clockSecs
            return ok()
        }
        if (contacts.size >= maxContacts) {
            if (autoadd and MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST == 0) return err(MeshcoreProto.ERR_TABLE_FULL)
            val victim =
                contacts.filter { it.flags and MeshcoreProto.CONTACT_FLAG_FAVOURITE == 0 }.minByOrNull { it.lastmod }
                    ?: return err(MeshcoreProto.ERR_TABLE_FULL)
            contacts.remove(victim)
            writeAll(byteArrayOf(MeshcoreProto.PUSH_CONTACT_DELETED.toByte()) + victim.key)
        }
        contacts += FakeContact(k, type, flags, opl, MeshcoreProto.cBytes(n, 0, 32), clockSecs, path)
        ok()
    }

    /** The `out_path` bytes an `ADD_UPDATE_CONTACT` carries for [opl]: none for zero hops or UNKNOWN. */
    private fun pathOf(
        frame: ByteArray,
        opl: Int,
    ): ByteArray {
        if (opl == MeshcoreProto.PATH_UNKNOWN) return ByteArray(0)
        val bytes = (opl and MeshcoreProto.PATH_HOPS_MASK) * ((opl shr 6) + 1)
        return if (frame.size >= 36 + bytes) frame.copyOfRange(36, 36 + bytes) else ByteArray(0)
    }

    /** Another board's advert reaches this one (`onAdvertRecv`): added or not by the auto-add rules, always pushed. */
    fun hearAdvert(other: FakeMeshcoreBoard) {
        val autoAdds = manualAdd and 1 == 0 || autoadd and MeshcoreProto.AUTO_ADD_CHAT != 0
        if (contact(other.key) == null && autoAdds) {
            if (contacts.size >= maxContacts && autoadd and MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST != 0) {
                contacts.filter { it.flags and 1 == 0 }.minByOrNull { it.lastmod }?.let { victim ->
                    contacts.remove(victim)
                    writeAll(byteArrayOf(MeshcoreProto.PUSH_CONTACT_DELETED.toByte()) + victim.key)
                }
            }
            if (contacts.size < maxContacts) addContact(other, MeshcoreProto.PATH_UNKNOWN, flags = 0)
        }
        val c = FakeContact(other.key, 1, 0, MeshcoreProto.PATH_UNKNOWN, other.advertName, clockSecs)
        writeAll(contactFrame(MeshcoreProto.PUSH_NEW_ADVERT, c))
    }

    /**
     * Whether a send now would find a free packet — [takePacket]'s own test: the radio's queue on a channel air, the
     * [outbound] count without one. What a stock client waiting on the last send's answer reads.
     */
    fun hasFreePacket(): Boolean =
        air.radios[name]?.let { it.queue.size + it.forced.size < MeshcoreHeadroom.POOL } ?: (outbound < MeshcoreHeadroom.POOL)

    /** One packet into the pool for [airMs]; false when the pool is full (`obtainNewPacket` null). */
    private fun takePacket(): Boolean {
        if (!hasFreePacket()) return false
        if (air.radios[name] != null) return true
        outbound++
        air.scope.launch(air.ctx) {
            delay(air.airMs)
            air.confined {
                outbound--
                sent++
                txAirSecs++
            }
        }
        return true
    }

    /** `CMD_SEND_TXT_MSG` (`MyMesh.cpp:1132`, `composeMsgPacket`, `sendMessage`). */
    private fun sendText(frame: ByteArray) {
        if (frame.size < 14) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val attempt = frame[2].toInt() and 0xFF
        val ts = MeshcoreProto.u32(frame, 3)
        val prefix = frame.copyOfRange(7, 13)
        val recipient = contacts.firstOrNull { it.key.copyOf(6).contentEquals(prefix) } ?: return err(MeshcoreProto.ERR_NOT_FOUND)
        // strlen: a zero byte cuts the text, and the cut text is what is sent and acked (Q11).
        val raw = frame.copyOfRange(13, frame.size)
        val text = raw.indexOf(0).let { if (it < 0) raw else raw.copyOf(it) }
        if (text.size > MeshcoreProto.MAX_TEXT_LEN || !takePacket()) return err(MeshcoreProto.ERR_TABLE_FULL)
        val code = MeshcoreProto.expectedAck(ts, attempt, text, key)
        ackTable[nextAck] = code
        ackFor[nextAck] = recipient.key
        ackAt[nextAck] = air.now()
        nextAck = (nextAck + 1) % ackTable.size
        val flood = recipient.outPathLen == MeshcoreProto.PATH_UNKNOWN
        air.radios[name]?.let { return airText(it, recipient, ts, attempt, text, code, flood) }
        val est = if (flood) air.estFloodMs else air.estDirectMs
        writeAll(byteArrayOf(MeshcoreProto.RESP_SENT.toByte(), if (flood) 1 else 0) + u32(code.toLong()) + u32(est))
        if (air.loseDm(attempt)) {
            air.airings += FakeMeshcoreAir.Airing(name, "msg", flood, 0)
            return
        }
        air.transmit(name, "msg", flood) { rx, hops ->
            if (rx.key.contentEquals(recipient.key)) rx.receiveText(this, ts, attempt, text, flood, hops)
        }
    }

    /**
     * A contact text reaches this board (`onPeerDataRecv`): dropped unless the sender is a contact (`Mesh.cpp:147-191`) or
     * a byte-identical copy was already heard; else queued for the phone and acked — direct when this board has a route
     * to the sender, flooded otherwise; a flooded DM draws a PATH return that rewrites the sender's route (Q13).
     */
    fun receiveText(
        from: FakeMeshcoreBoard,
        ts: Long,
        attempt: Int,
        text: ByteArray,
        flood: Boolean,
        hops: Int,
    ) {
        rxAirSecs++
        val sender =
            contact(from.key) ?: run {
                noPeerDrops++
                return
            }
        val hash = "msg:${from.name}:$ts:$attempt:${text.toList()}"
        if (!heard.add(hash)) return
        val head =
            if (appTargetVersion >= 3) {
                byteArrayOf(MeshcoreProto.RESP_CONTACT_MSG_RECV_V3.toByte(), 32, 0, 0)
            } else {
                byteArrayOf(MeshcoreProto.RESP_CONTACT_MSG_RECV.toByte())
            }
        val frame = head + from.key.copyOf(6) + byteArrayOf((if (flood) hops else 0xFF).toByte(), 0) + u32(ts) + text
        queue(frame)
        if (air.loseAck(name, from.name, attempt)) return
        val code = MeshcoreProto.expectedAck(ts, attempt, text, from.key)
        if (flood) {
            // The PATH return carries the ack, and both ends learn the route (Q13): 0x81 on both boards.
            sender.outPathLen = hops
            writeAll(byteArrayOf(MeshcoreProto.PUSH_PATH_UPDATED.toByte()) + from.key)
            acksAired += true
            air.transmit(name, "path", flood = true) { rx, _ ->
                if (rx === from) rx.receiveAck(code, this, pathHops = hops)
            }
        } else {
            val ackFloods = sender.outPathLen == MeshcoreProto.PATH_UNKNOWN
            acksAired += ackFloods
            air.transmit(name, "ack", flood = ackFloods) { rx, _ -> if (rx === from) rx.receiveAck(code, this, pathHops = null) }
        }
    }

    /** An ack for one of this board's sends (`processAck`): pushed once per table entry, after the trip. */
    fun receiveAck(
        code: Int,
        from: FakeMeshcoreBoard,
        pathHops: Int?,
    ) {
        // One ack per code: a copy re-aired by a repeater is the same packet, dropped as heard.
        if (!heard.add("ack:$code:${pathHops != null}")) return
        air.scope.launch(air.ctx) {
            delay(air.confirmDelayMs)
            air.confined { confirm(code, from, pathHops) }
        }
    }

    private fun confirm(
        code: Int,
        from: FakeMeshcoreBoard,
        pathHops: Int?,
    ) {
        if (pathHops != null) {
            contact(from.key)?.outPathLen = pathHops
            writeAll(byteArrayOf(MeshcoreProto.PUSH_PATH_UPDATED.toByte()) + from.key)
            // The sender answers a PATH return with one direct PATH of its own (Q13's fifth airing).
            air.transmit(name, "path", flood = false) { _, _ -> }
        }
        val i = ackTable.indexOfFirst { it == code && it != 0 }
        if (i < 0) return
        ackTable[i] = 0
        writeAll(byteArrayOf(MeshcoreProto.PUSH_SEND_CONFIRMED.toByte()) + u32(code.toLong()) + u32(air.confirmDelayMs))
    }

    /**
     * The channel's `sendMessage` (`BaseChatMesh.cpp:473-490`): `SENT` with the firmware's own `est_timeout`, then the
     * packet flooded or sent direct along the stored `out_path`.
     */
    @Suppress("LongParameterList") // one send's facts
    private fun airText(
        radio: FakeRadio,
        recipient: FakeContact,
        ts: Long,
        attempt: Int,
        text: ByteArray,
        code: Int,
        flood: Boolean,
    ) {
        val ch = requireNotNull(air.channel)
        val hashSize = if (flood) pathHashMode + 1 else (recipient.outPathLen shr 6) + 1
        val path = if (flood) ByteArray(0) else recipient.outPath
        val identity = byteArrayOf(recipient.key[0], key[0]) + u32(ts) + byteArrayOf(attempt.toByte()) + text + key + recipient.key
        val packet =
            AirPacket(
                MeshcoreChannel.TYPE_TXT_MSG,
                "msg",
                identity,
                4 + MeshcoreChannel.aes(5 + text.size),
                flood,
                path,
                hashSize,
                name,
                if (flood) radio.floods++ else radio.directs++,
                AirBody.Msg(this, recipient.key, ts, attempt, text),
                lost = air.loseDm(attempt),
                transportCodes = flood && scoped,
            )
        val t = ch.airtimeMs(packet.rawLen)
        val est =
            if (flood) {
                SEND_TIMEOUT_BASE_MS + FLOOD_TIMEOUT_FACTOR * t
            } else {
                SEND_TIMEOUT_BASE_MS + (t * DIRECT_PERHOP_FACTOR + DIRECT_PERHOP_EXTRA_MS) * ((recipient.outPathLen and 63) + 1)
            }
        writeAll(byteArrayOf(MeshcoreProto.RESP_SENT.toByte(), if (flood) 1 else 0) + u32(code.toLong()) + u32(est))
        air.originate(radio, packet, 0, if (flood) 1 else 0)
    }

    /** Sends [body] to [to] as a PATH return (`createPathReturn`): flooded, or direct along [route]. */
    @Suppress("LongParameterList") // one PATH's facts
    private fun airPath(
        to: FakeContact,
        outPath: ByteArray,
        outPathLen: Int,
        extraAck: Int?,
        flood: Boolean,
        route: ByteArray,
        routeLen: Int,
        delayMs: Long,
    ) {
        val radio = air.radios[name] ?: return
        val hashSize = if (flood) pathHashMode + 1 else (routeLen shr 6) + 1
        val identity = byteArrayOf(to.key[0], key[0]) + byteArrayOf(outPathLen.toByte()) + outPath + u32((extraAck ?: 0).toLong()) + key
        val packet =
            AirPacket(
                MeshcoreChannel.TYPE_PATH,
                "path",
                identity,
                4 + MeshcoreChannel.aes(1 + outPath.size + 1 + (if (extraAck != null) ACK_BYTES else 0)),
                flood,
                if (flood) ByteArray(0) else route,
                hashSize,
                name,
                if (flood) radio.floods++ else radio.directs++,
                AirBody.Path(this, to.key, outPath, outPathLen, extraAck),
                transportCodes = flood && scoped,
            )
        air.originate(radio, packet, delayMs, if (flood) 2 else 0)
    }

    /** `sendAckTo` (`BaseChatMesh.cpp:43-57`): flooded when this board's record of [to] is UNKNOWN, else direct along it. */
    private fun airAck(
        to: FakeContact,
        code: Int,
        attempt: Int,
    ) {
        val radio = air.radios[name] ?: return
        val flood = to.outPathLen == MeshcoreProto.PATH_UNKNOWN
        acksAired += flood
        val packet =
            AirPacket(
                MeshcoreChannel.TYPE_ACK,
                "ack",
                u32(code.toLong()) + byteArrayOf(attempt.toByte()),
                ACK_BYTES,
                flood,
                if (flood) ByteArray(0) else to.outPath,
                if (flood) pathHashMode + 1 else (to.outPathLen shr 6) + 1,
                name,
                if (flood) radio.floods++ else radio.directs++,
                AirBody.Ack(code),
                lost =
                    air.loseAck(
                        name,
                        air.boards.values
                            .firstOrNull { it.key.contentEquals(to.key) }
                            ?.name
                            .orEmpty(),
                        attempt,
                    ),
                transportCodes = flood && scoped,
            )
        air.originate(radio, packet, MeshcoreChannel.ACK_DELAY_MS, if (flood) 1 else 0)
    }

    /**
     * A packet whose route ends here, through the ring already (`Mesh::onRecvPacket`'s payload switch): true when it was
     * this board's (so a repeater would not re-air it, `markDoNotRetransmit`).
     */
    fun onAir(p: AirPacket): Boolean =
        when (val b = p.body) {
            is AirBody.Msg -> {
                b.to.contentEquals(key) && onAirText(p, b)
            }

            is AirBody.Ack -> {
                onAirAck(p)
            }

            is AirBody.Path -> {
                b.to.contentEquals(key) && onAirPath(p, b)
            }

            is AirBody.Group -> {
                receiveGroup(b.secret, b.data, b.dataType, p.flood, p.pathLenByte, dedup = false)
                false
            }

            is AirBody.GroupText -> {
                receiveGroupText(b.secret, b.ts, b.text, if (p.flood) p.pathLenByte else MeshcoreProto.PATH_UNKNOWN, dedup = false)
                false
            }
        }

    /** `onPeerDataRecv` for a plain text (`BaseChatMesh.cpp:230-262`): queued for the phone, then acked or PATH-returned. */
    private fun onAirText(
        p: AirPacket,
        b: AirBody.Msg,
    ): Boolean {
        // No contact matches the source hash: nothing decrypts it.
        val sender =
            contact(b.from.key) ?: run {
                noPeerDrops++
                return false
            }
        val head =
            if (appTargetVersion >= 3) {
                byteArrayOf(MeshcoreProto.RESP_CONTACT_MSG_RECV_V3.toByte(), 32, 0, 0)
            } else {
                byteArrayOf(MeshcoreProto.RESP_CONTACT_MSG_RECV.toByte())
            }
        sender.lastmod = clockSecs
        queue(head + b.from.key.copyOf(6) + byteArrayOf((if (p.flood) p.pathLenByte else 0xFF).toByte(), 0) + u32(b.ts) + b.text)
        val code = MeshcoreProto.expectedAck(b.ts, b.attempt, b.text, b.from.key)
        if (p.flood) {
            // The PATH return tells the sender the route here, and carries the ack (`:251-255`).
            acksAired += true
            airPath(
                sender,
                p.path,
                p.pathLenByte,
                code,
                flood = true,
                route = ByteArray(0),
                routeLen = 0,
                delayMs = MeshcoreChannel.ACK_DELAY_MS,
            )
        } else {
            airAck(sender, code, b.attempt)
        }
        return true
    }

    /**
     * An ack heard on the air (`onAckRecv`, `BaseChatMesh.cpp:372-383`): one of this board's own pushes `SEND_CONFIRMED`
     * once; one that came flooded while the record of its sender holds a path draws one direct PATH 3 s later
     * (`handleReturnPathRetry`, `:385-390`) — the Q13 PATH, modelled from the source.
     */
    fun onAirAck(p: AirPacket): Boolean {
        val code = (p.body as AirBody.Ack).code
        val i = ackTable.indexOfFirst { it == code && it != 0 }
        if (i < 0) return false
        val to = ackFor[i]
        ackTable[i] = 0
        ackFor[i] = null
        writeAll(byteArrayOf(MeshcoreProto.PUSH_SEND_CONFIRMED.toByte()) + u32(code.toLong()) + u32(air.now() - ackAt[i]))
        val c = to?.let(::contact)
        if (p.flood && c != null && c.outPathLen != MeshcoreProto.PATH_UNKNOWN) {
            airPath(
                c,
                p.path,
                p.pathLenByte,
                extraAck = null,
                flood = false,
                route = c.outPath,
                routeLen = c.outPathLen,
                delayMs = MeshcoreChannel.RETURN_PATH_RETRY_DELAY_MS,
            )
        }
        return true
    }

    /**
     * A PATH for this board (`onContactPathRecv`, `BaseChatMesh.cpp:353-370`): the sender's record rewritten to the route
     * it carries and `0x81` pushed; a carried ack processed; a flooded one answered by a reciprocal direct PATH along the
     * new route (`Mesh.cpp:178-184`).
     */
    private fun onAirPath(
        p: AirPacket,
        b: AirBody.Path,
    ): Boolean {
        val c = contact(b.from.key) ?: return false
        c.outPath = b.outPath
        c.outPathLen = b.outPathLen
        c.lastmod = clockSecs
        writeAll(byteArrayOf(MeshcoreProto.PUSH_PATH_UPDATED.toByte()) + b.from.key)
        b.extraAck?.let { code ->
            val i = ackTable.indexOfFirst { it == code && it != 0 }
            if (i >= 0) {
                ackTable[i] = 0
                ackFor[i] = null
                writeAll(byteArrayOf(MeshcoreProto.PUSH_SEND_CONFIRMED.toByte()) + u32(code.toLong()) + u32(air.now() - ackAt[i]))
            }
        }
        if (p.flood) {
            airPath(
                c,
                p.path,
                p.pathLenByte,
                extraAck = null,
                flood = false,
                route = b.outPath,
                routeLen = b.outPathLen,
                delayMs = MeshcoreChannel.RECIPROCAL_PATH_DELAY_MS,
            )
        }
        return true
    }

    /** `CMD_SEND_CHANNEL_DATA` (`MyMesh.cpp:1198`). */
    private fun sendChannelData(frame: ByteArray) {
        if (frame.size < 4) return err(MeshcoreProto.ERR_ILLEGAL_ARG)
        val slot = frame[1].toInt() and 0xFF
        val pathLen = frame[2].toInt() and 0xFF
        val dataType = MeshcoreProto.u16(frame, 3)
        val data = frame.copyOfRange(5, frame.size)
        if (slot >= maxChannels) return err(MeshcoreProto.ERR_NOT_FOUND)
        if (dataType == 0 || data.size > 165) return err(MeshcoreProto.ERR_ILLEGAL_ARG)
        if (!takePacket()) return err(MeshcoreProto.ERR_TABLE_FULL)
        ok()
        val secret = channels[slot].second
        val flood = pathLen == MeshcoreProto.PATH_FLOOD
        air.radios[name]?.let { radio ->
            val packet =
                AirPacket(
                    MeshcoreChannel.TYPE_GRP_DATA,
                    "grp_data",
                    byteArrayOf(secret[0]) + secret + byteArrayOf(dataType.toByte(), (dataType shr 8).toByte()) + data,
                    3 + MeshcoreChannel.aes(3 + data.size),
                    flood,
                    ByteArray(0),
                    pathHashMode + 1,
                    name,
                    if (flood) radio.floods++ else radio.directs++,
                    AirBody.Group(secret, dataType, data),
                    transportCodes = flood && scoped,
                )
            return air.originate(radio, packet, 0, 1)
        }
        air.transmit(name, "grp_data", flood) { rx, hops -> rx.receiveGroup(secret, data, dataType, flood, hops) }
    }

    /**
     * `CMD_SEND_CHANNEL_TXT_MSG` (`MyMesh.cpp:1178-1197`): a type other than plain is `UNSUPPORTED_CMD` before anything else;
     * any slot under the count is taken, an empty one too (`getChannel`, `BaseChatMesh.cpp:926-931`), and the post airs under
     * its key; no free packet is `NOT_FOUND`. The text is the board's name, `": "` and the bytes as written — a NUL included
     * (`sendGroupMessage` copies `len - i` bytes, `BaseChatMesh.cpp:518-531`), which every receiver then cuts at.
     */
    private fun sendChannelText(frame: ByteArray) {
        if (frame[1].toInt() != MeshcoreProto.TXT_TYPE_PLAIN) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val slot = frame[2].toInt() and 0xFF
        if (slot >= maxChannels || !takePacket()) return err(MeshcoreProto.ERR_NOT_FOUND)
        val ts = MeshcoreProto.u32(frame, 3)
        val body = advertName + ": ".encodeToByteArray() + frame.copyOfRange(7, frame.size)
        val text = body.copyOf(minOf(body.size, MeshcoreProto.MAX_TEXT_LEN))
        ok()
        val secret = channels[slot].second
        air.radios[name]?.let { radio ->
            val packet =
                AirPacket(
                    MeshcoreChannel.TYPE_GRP_TXT,
                    "grp_txt",
                    byteArrayOf(secret[0]) + secret + u32(ts) + text,
                    3 + MeshcoreChannel.aes(5 + text.size),
                    true,
                    ByteArray(0),
                    pathHashMode + 1,
                    name,
                    radio.floods++,
                    AirBody.GroupText(secret, ts, text),
                    transportCodes = scoped,
                )
            return air.originate(radio, packet, 0, 1)
        }
        air.transmit(name, "grp_txt", flood = true) { rx, hops -> rx.receiveGroupText(secret, ts, text, hops) }
    }

    /** Channel data on a secret this board holds (`onChannelDataRecv`); a byte-identical copy is dropped (Q1, Q10). */
    fun receiveGroup(
        secret: ByteArray,
        data: ByteArray,
        dataType: Int,
        flood: Boolean,
        hops: Int,
        dedup: Boolean = true,
    ) {
        if (dedup) rxAirSecs++
        val slot = channels.indexOfFirst { it.first.isNotEmpty() && it.second.contentEquals(secret) }
        if (slot < 0 || (dedup && !heard.add("grp:${secret.toList()}:$dataType:${data.toList()}"))) return
        queue(
            byteArrayOf(
                27,
                32,
                0,
                0,
                slot.toByte(),
                (if (flood) hops else 0xFF).toByte(),
                dataType.toByte(),
                (dataType shr 8).toByte(),
                data.size.toByte(),
            ) +
                data,
        )
    }

    /**
     * A channel text on a secret this board holds (`onGroupDataRecv` → `onChannelMessageRecv`): an unnamed slot is skipped
     * (`BaseChatMesh.cpp:401`), a byte-identical copy dropped; the text handed on up to its first NUL (`strlen`) and cut to
     * `MAX_FRAME_SIZE` less the V3 frame's 11-B header (`MyMesh.cpp:577-580`).
     */
    fun receiveGroupText(
        secret: ByteArray,
        ts: Long,
        text: ByteArray,
        hops: Int,
        dedup: Boolean = true,
    ) {
        val slot = channels.indexOfFirst { it.first.isNotEmpty() && it.second.contentEquals(secret) }
        if (slot < 0 || (dedup && !heard.add("txt:${secret.toList()}:$ts:${text.toList()}"))) return
        val nul = text.indexOf(0.toByte()).takeIf { it >= 0 } ?: text.size
        val drained = text.copyOf(minOf(nul, MAX_FRAME_SIZE - CHANNEL_MSG_V3_HEADER))
        queue(byteArrayOf(17, 32, 0, 0, slot.toByte(), hops.toByte(), 0) + u32(ts) + drained)
    }

    /** Frames the offline queue dropped (full, and no channel message left to evict). */
    var offlineDropped = 0

    /** `addToOfflineQueue`: full, the oldest channel message goes (`MyMesh.cpp:224`); a tickle to whoever is connected. */
    fun queue(frame: ByteArray) {
        if (offline.size >= offlineCap) {
            val i = offline.indexOfFirst { it[0].toInt() in listOf(8, 17, 27) }
            offlineDropped++
            if (i < 0) return
            offline.removeAt(i)
        }
        offline.addLast(frame)
        writeAll(byteArrayOf(MeshcoreProto.PUSH_MSG_WAITING.toByte()))
    }

    private companion object {
        /** `MAX_FRAME_SIZE` (`BaseSerialInterface.h:5`): what a frame to the phone holds, its header included. */
        const val MAX_FRAME_SIZE = 176

        /** `RESP_CODE_CHANNEL_MSG_RECV_V3`'s header: code, SNR, two reserved, slot, `path_len`, type, the 4-B time. */
        const val CHANNEL_MSG_V3_HEADER = 11

        /** `OFFLINE_QUEUE_SIZE`'s source default (`MyMesh.h:61-62`). */
        const val SOURCE_OFFLINE_CAP = 16

        /** `out_path` in a contact record (`MAX_PATH_SIZE`). */
        const val OUT_PATH_BYTES = 64

        /** A plain text's ack: 6 bytes (`sendAckTo(from, ack_hash, 6)`, `BaseChatMesh.cpp:256`). */
        const val ACK_BYTES = 6

        /** `MyMesh.cpp:108-111`: the companion's send timeouts. */
        const val SEND_TIMEOUT_BASE_MS = 500L
        const val FLOOD_TIMEOUT_FACTOR = 16L
        const val DIRECT_PERHOP_FACTOR = 6L
        const val DIRECT_PERHOP_EXTRA_MS = 250L
    }
}

/**
 * Our NUS channel to a fake board: a write is handled by the board at once, so its answer can be in [frames] before
 * the write returns (binding rule "GATT" 4). [dropFrame] loses a notify to us alone (a reply lost to the board's
 * four-frame BLE queue, Q3); [writeResult] fails a write; [swallow] has the board never see a command.
 */
internal class FakeNusChannel(
    val board: FakeMeshcoreBoard,
) : NusChannel,
    FakeIface {
    private val events = Channel<NusEvent>(Channel.UNLIMITED)
    override val frames: ReceiveChannel<NusEvent> = events
    val writes = mutableListOf<ByteArray>()
    var writeResult: (ByteArray) -> GattResult<Unit> = { GattResult.Ok(Unit) }
    var swallow: (ByteArray) -> Boolean = { false }
    var dropFrame: (ByteArray) -> Boolean = { false }
    var closed = false
        private set

    override val connected: Boolean get() = !closed

    init {
        board.ifaces += this
    }

    /** How long each notify takes to reach us: 0 delivers at once, as the board writes it. */
    var latencyMs = 0L

    /** A further delay for one notify: a slow answer of one kind ([latencyMs] slows every one). */
    var delayFrame: (ByteArray) -> Long = { 0L }

    /** Every frame the board handed this interface, by its first byte, in order (a diagnostic; dropped ones too). */
    val delivered: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()

    override fun deliver(frame: ByteArray) {
        delivered += frame[0].toInt() and 0xFF
        if (dropFrame(frame)) return
        val wait = latencyMs + delayFrame(frame)
        if (wait == 0L) {
            events.trySend(NusEvent.Frame(frame))
        } else {
            board.air.later(wait) { events.trySend(NusEvent.Frame(frame)) }
        }
    }

    /** Pushes a raw frame as the board would, to us alone (a foreign answer, a garbled notify). */
    fun inject(frame: ByteArray) {
        events.trySend(NusEvent.Frame(frame))
    }

    override suspend fun write(
        frame: ByteArray,
        timeoutMs: Long,
    ): GattResult<Unit> {
        val serial = board.air.serial ?: return writeHere(frame)
        return withContext(serial) { writeHere(frame) }
    }

    private fun writeHere(frame: ByteArray): GattResult<Unit> = board.air.confined { writeConfined(frame) }

    private fun writeConfined(frame: ByteArray): GattResult<Unit> {
        if (closed) return GattResult.Closed
        val result = writeResult(frame)
        if (result !is GattResult.Ok) return result
        writes += frame.copyOf()
        if (!swallow(frame)) board.handle(frame)
        return result
    }

    fun disconnect(status: Int = 8) {
        events.trySend(NusEvent.Disconnected(status))
    }

    override fun close() {
        closed = true
        board.ifaces -= this
    }

    /** The command codes written, in order. */
    val codes: List<Int> get() = writes.map { it[0].toInt() and 0xFF }
}

/** The user's own MeshCore app on the same board: it issues commands and receives every answer too. */
internal class FakeForeignClient(
    private val board: FakeMeshcoreBoard,
) : FakeIface {
    val received = mutableListOf<ByteArray>()
    override val connected: Boolean = true

    init {
        board.ifaces += this
    }

    override fun deliver(frame: ByteArray) {
        received += frame
    }

    fun send(frame: ByteArray) = board.handle(frame)
}

/** Dials a fresh [FakeNusChannel] on [board] each time, at MTU 176. */
internal class FakeMeshcoreDialer(
    var board: FakeMeshcoreBoard,
    var bond: BondState = BondState.BONDED,
) : MeshcoreDialer {
    val adapter = MutableStateFlow(true)
    override val adapterOn: StateFlow<Boolean> = adapter
    val channels = mutableListOf<FakeNusChannel>()
    var dialResult: (() -> NusDialResult)? = null
    val modes = mutableListOf<DialMode>()
    var onOpen: (FakeNusChannel) -> Unit = {}

    val channel: FakeNusChannel get() = channels.last()

    override fun bondState(address: String): BondState = bond

    override suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): NusDialResult {
        modes += mode
        dialResult?.let { return it() }
        // A board with no power answers no dial: it times out as a real one does, after the dial's own window (the
        // session's background mode redials at once on a timeout, trusting the wait), or connects once powered inside it.
        if (!board.powered) {
            val deadline = board.air.now() + timeoutMs
            while (!board.powered && board.air.now() < deadline) delay(minOf(POWER_POLL_MS, deadline - board.air.now()))
            if (!board.powered) return NusDialResult.Timeout
        }
        val ch = FakeNusChannel(board).also(onOpen)
        channels += ch
        return NusDialResult.Opened(ch, MeshcoreProto.MAX_FRAME)
    }
}

/** How often a dial to a board with no power looks again. */
private const val POWER_POLL_MS = 1_000L

/** A random key, for a test that needs one nobody else holds. */
internal fun randomKey(rng: Random = Random(1)): ByteArray =
    ByteArray(MeshcoreProto.KEY_BYTES) { rng.nextInt().toByte() }.also {
        it[0] =
            0x42
    }
