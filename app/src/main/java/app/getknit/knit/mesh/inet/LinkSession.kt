package app.getknit.knit.mesh.inet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * One direct link attempt and, once it answers, the link (ADR 2026-10.a5yj): a [LinkPort], the attempt's [LinkLane]
 * and the peer's [targets], from the punch to the close. Pure above the port seam, so `LinkSessionTest` runs it on
 * virtual time over `FakeLinkNet`.
 *
 * **The punch.** Both ends send a PROBE to every target at [PunchPolicy]'s pace, and answer every PROBE that opens with
 * an ACK to the address it came from. The link is up on the first ACK that opens: the peer heard us and we hear it, on
 * the address it answered from. A PROBE that opens from an address the peer never listed — a symmetric NAT's fresh
 * mapping toward us — makes that address a target too (ICE's peer-reflexive candidate), so the end behind the gentler
 * NAT can reach back through the mapping the other end's probe just opened. Nothing answered in
 * [PunchPolicy.GIVE_UP_MS] and the attempt ends [EndReason.NO_PATH].
 *
 * **The path.** This end sends to the source of the last datagram that opened ([endpoint]) — not to a candidate it was
 * told about — so a NAT that rebinds the peer's port, or a peer that answered from an address it never listed, is
 * followed at once, and only an authenticated datagram can move it.
 *
 * **Liveness.** A link exists to move something, so it sends no keepalive: one that has carried no DATA either way
 * (under a transport, no frame or file: [noteUse]) for [idleMs] closes with a BYE ([EndReason.IDLE]), well inside the
 * shortest idle-path life measured (2–4 min behind a home router's IPv6 firewall, ADR 2026-10.a5yj's keepalive
 * amendment), and one that hears nothing that opens for [silenceMs] while it sends ends [EndReason.SILENT]. A **held**
 * link ([hold], [holdFor]: a live session, such as a walkie, that must outlast its pauses) does not idle out: it sends a
 * keepalive after [holdKeepaliveMs] with nothing sent and ends after [holdSilenceMs] hearing nothing; once its hold ends
 * it idles out as any link does, its idle and its silence counted from that end. A BYE ends either at once.
 * Every datagram is one [LinkLane] seal, so the counter is shared by every path and nothing is ever sealed twice.
 */
class LinkSession(
    val peerId: String,
    private val port: LinkPort,
    private val lane: LinkLane,
    private val targets: List<LinkCandidate>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val idleMs: Long = IDLE_MS,
    private val silenceMs: Long = SILENCE_MS,
    private val holdKeepaliveMs: Long = HOLD_KEEPALIVE_MS,
    private val holdSilenceMs: Long = HOLD_SILENCE_MS,
    // Whether every DATA datagram is use (the idle close counts from it, and [carriedData] reads it). False once a
    // transport runs over the link's stream: its handshake, digests and acks are DATA too, so only [noteUse] — a frame or
    // a file it moved — counts, and a link that only keeps itself in sync idles out.
    private val dataIsUse: Boolean = true,
    // Whether the transport over the link is moving a file now: a link mid-file is in use, though no frame crosses it for
    // the file's whole length, so it never idles out under one.
    private val busy: () -> Boolean = { false },
    onData: (ByteArray) -> Unit = {},
    private val random: java.util.Random = SecureRandom(),
) {
    /** Where a session stands. */
    enum class State { PUNCHING, UP, CLOSED }

    /** Why a session closed. */
    enum class EndReason { NO_PATH, IDLE, SILENT, BYE, LOCAL }

    /** How the punch went: up through a target of [kind] (null: an address the peer never listed), or not. */
    sealed interface Outcome {
        val ms: Long

        class Up(
            val kind: LinkCandidate.Kind?,
            override val ms: Long,
            val rttMs: Long,
        ) : Outcome

        class Failed(
            override val ms: Long,
        ) : Outcome
    }

    // The reader, the punch/keepalive loop and a caller's close run on the mesh's pool: every state change takes this.
    private val stateLock = Any()
    private val _state = MutableStateFlow(State.PUNCHING)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Why it closed, once [state] is [State.CLOSED]. */
    @Volatile
    var endReason: EndReason? = null
        private set

    private val started = clock()
    private val outcome = CompletableDeferred<Outcome>()
    private val probes = HashMap<Long, Long>() // nonce → sent at
    private val probesLock = Any()

    @Volatile private var endpoint: Pair<ByteArray, Int>? = null

    @Volatile private var lastHeard = started

    @Volatile private var lastSent = started

    // The last DATA either way, or the moment the link came up: what an idle close counts from.
    @Volatile private var lastData = started

    // Until when the link is held ([hold], [holdFor]): past it, the idle close and the silence bound count from it.
    @Volatile private var heldUntil = 0L

    // Where a DATA body that opened goes: the constructor's, until a stream [attach]es.
    @Volatile private var sink = onData

    /** Whether any DATA crossed either way: a link that closes without it was answered for nothing. */
    @Volatile
    var carriedData = false
        private set

    // Peer-reflexive targets: sources of PROBEs that opened but match no listed target. Bounded like the list itself.
    private val learnt = java.util.concurrent.CopyOnWriteArrayList<Pair<ByteArray, Int>>()

    // The gap measurement's pending echoes: replies to ours by nonce, and the peer's asked-for echoes by nonce.
    private val echoes = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Long>>()
    private val asked = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Pair<Long, Boolean>>>()

    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()
    private var jobs: List<Job> = emptyList()

    /** Bytes of datagrams that opened, and of every datagram sent, so far. */
    fun bytes(): Pair<Long, Long> = bytesIn.get() to bytesOut.get()

    /** Starts the reader and the punch; [awaitOutcome] then says how the punch went. */
    fun start() {
        jobs = listOf(scope.launch { read() }, scope.launch { punch() })
    }

    /** How the punch went: [Outcome.Up] on the first ACK, [Outcome.Failed] once it gave up or the session closed. */
    suspend fun awaitOutcome(): Outcome = outcome.await()

    /** Hands every DATA body that opens from now on to [sink] — the stream over the link ([LinkStream]). */
    fun attach(sink: (ByteArray) -> Unit) {
        this.sink = sink
    }

    /** Sends one data payload over the link; false when it is not up. */
    fun sendData(payload: ByteArray): Boolean {
        if (state.value != State.UP) return false
        if (dataIsUse) lastData = clock()
        return sendTo(endpoint, byteArrayOf(DATA) + payload).also { if (it && dataIsUse) carriedData = true }
    }

    /**
     * Real traffic moved over the link, in either direction (a frame or a file a transport sent or took): the idle close
     * counts from now, and the link carried data. What counts when [dataIsUse] is false.
     */
    fun noteUse() {
        if (state.value != State.UP) return
        lastData = clock()
        carriedData = true
    }

    /**
     * Holds the link up through idle pauses, or ([on] false) lets it idle out again — counted from now, so a released
     * link gets its whole [idleMs]. For a live session that cannot wait out a fresh punch after each pause.
     */
    fun hold(on: Boolean) {
        val now = clock()
        synchronized(stateLock) { heldUntil = if (on) Long.MAX_VALUE else minOf(heldUntil, now) }
    }

    /**
     * Holds the link up for [ms] from now, never shortening a longer hold: a live session's hold, renewed while it lasts
     * (`DirectLinks.raise`), which ends on its own once the renewals stop.
     */
    fun holdFor(ms: Long) {
        val until = clock() + ms
        synchronized(stateLock) { if (until > heldUntil) heldUntil = until }
    }

    /** Closes the session, telling the peer with a BYE when [sayBye] and the link is up. Idempotent. */
    fun close(
        reason: EndReason = EndReason.LOCAL,
        sayBye: Boolean = true,
    ) {
        val was =
            synchronized(stateLock) {
                val prior = _state.value
                if (prior == State.CLOSED) return
                endReason = reason
                _state.value = State.CLOSED
                prior
            }
        if (sayBye && was == State.UP) sendTo(endpoint, byteArrayOf(BYE))
        outcome.complete(Outcome.Failed(clock() - started))
        jobs.forEach { it.cancel() }
        runCatching { port.close() }
    }

    private suspend fun punch() {
        while (scope.isActive && _state.value == State.PUNCHING) {
            val elapsed = clock() - started
            if (PunchPolicy.givesUp(elapsed)) return close(EndReason.NO_PATH, sayBye = false)
            targets.forEach { probe(it.address to it.port) }
            learnt.forEach { probe(it) }
            delay(PunchPolicy.probeIntervalMs(elapsed))
        }
        if (_state.value == State.UP) watch()
    }

    // The link's liveness while it is up: an idle close, or a held link's keepalive, and the silence bound of each.
    private suspend fun watch() {
        while (scope.isActive && _state.value == State.UP) {
            val now = clock()
            val until = heldUntil
            val next =
                if (now < until) {
                    if (now - lastHeard >= holdSilenceMs) return close(EndReason.SILENT, sayBye = false)
                    if (now - lastSent >= holdKeepaliveMs) sendTo(endpoint, byteArrayOf(KEEPALIVE))
                    minOf(holdKeepaliveMs - (clock() - lastSent), holdSilenceMs - (clock() - lastHeard))
                } else {
                    if (busy()) lastData = now
                    // A hold that ended counts as data and as heard up to its end: a released link gets its whole idle,
                    // and a held one that heard a keepalive a minute ago is not taken for gone the moment it is let go.
                    val dataAt = maxOf(lastData, until)
                    val heardAt = maxOf(lastHeard, until)
                    if (now - dataAt >= idleMs) return close(EndReason.IDLE)
                    if (now - heardAt >= silenceMs) return close(EndReason.SILENT, sayBye = false)
                    minOf(idleMs - (now - dataAt), silenceMs - (now - heardAt))
                }
            // Never past [MAX_TICK_MS], so a hold taken or released mid-wait is acted on promptly.
            delay(next.coerceIn(MIN_TICK_MS, MAX_TICK_MS))
        }
    }

    private suspend fun read() {
        while (scope.isActive && _state.value != State.CLOSED) {
            port.receive(RECEIVE_SLICE_MS)?.let(::take)
        }
    }

    // One datagram in: dropped unless it opens, else acted on by its type.
    private fun take(datagram: Datagram) {
        val plain = lane.open(datagram.data)?.takeIf { it.isNotEmpty() } ?: return
        bytesIn.addAndGet(datagram.data.size.toLong())
        lastHeard = clock()
        val from = datagram.address to datagram.port
        val nonced = plain.size == 1 + Long.SIZE_BYTES
        when (plain[0]) {
            PROBE -> {
                if (nonced) {
                    sendTo(from, byteArrayOf(ACK) + plain.copyOfRange(1, plain.size))
                    learn(from)
                }
            }

            ACK -> {
                if (nonced) onAck(from, bytesLong(plain, 1))
            }

            BYE -> {
                return close(EndReason.BYE, sayBye = false)
            }

            DATA -> {
                if (_state.value == State.UP) {
                    if (dataIsUse) {
                        lastData = clock()
                        carriedData = true
                    }
                    sink(plain.copyOfRange(1, plain.size))
                }
            }

            // An echo of the gap measurement, a keepalive, or a type a later version sends.
            else -> {
                takeEcho(plain, from)
            }
        }
        // A late PROBE from a candidate the peer is still trying says nothing about the path it settled on.
        if (_state.value == State.UP && plain[0] != PROBE) endpoint = from
    }

    // The gap measurement's three types (debug builds only drive them; any build answers an ECHO). An ECHO from the peer
    // is answered at once and, when this end asked for it, noted with whether it came from another address than the
    // path's — a NAT that rebound the peer's port while it was silent.
    private fun takeEcho(
        plain: ByteArray,
        from: Pair<ByteArray, Int>,
    ) {
        if (_state.value != State.UP) return
        when (plain[0]) {
            ECHO -> {
                if (plain.size != 1 + Long.SIZE_BYTES) return
                val nonce = bytesLong(plain, 1)
                val roamed = endpoint?.let { !it.first.contentEquals(from.first) || it.second != from.second } ?: false
                sendTo(from, byteArrayOf(ECHO_REPLY) + longBytes(nonce))
                asked.remove(nonce)?.complete(clock() to roamed)
            }

            ECHO_REPLY -> {
                if (plain.size == 1 + Long.SIZE_BYTES) echoes.remove(bytesLong(plain, 1))?.complete(clock())
            }

            ECHO_AFTER -> {
                if (plain.size != 1 + 2 * Long.SIZE_BYTES) return
                val delayMs = bytesLong(plain, 1).coerceIn(0L, MAX_ECHO_DELAY_MS)
                val nonce = bytesLong(plain, 1 + Long.SIZE_BYTES)
                scope.launch {
                    delay(delayMs)
                    if (_state.value == State.UP) sendTo(endpoint, byteArrayOf(ECHO) + longBytes(nonce))
                }
            }
        }
    }

    /**
     * The gap measurement's outbound half: one ECHO to the peer now, and the round trip of its reply, or null when none
     * came within [timeoutMs]. After an idle gap, a reply says the peer's side still admits this end's datagrams.
     */
    suspend fun echo(timeoutMs: Long): Long? {
        val nonce = random.nextLong()
        val reply = CompletableDeferred<Long>()
        echoes[nonce] = reply
        val sentAt = clock()
        if (!sendTo(endpoint, byteArrayOf(ECHO) + longBytes(nonce))) return null.also { echoes.remove(nonce) }
        return withTimeoutOrNull(timeoutMs) { reply.await() - sentAt }.also { echoes.remove(nonce) }
    }

    /**
     * The gap measurement's inbound half: asks the peer to stay silent [delayMs] and then send an ECHO, and waits up to
     * [delayMs] + [graceMs] for it. Its arrival (when, and whether from a new address) says this end's side still admitted
     * the peer after [delayMs] in which this end sent nothing — the caller must send nothing meanwhile either.
     */
    suspend fun echoAfter(
        delayMs: Long,
        graceMs: Long,
    ): Pair<Long, Boolean>? {
        val nonce = random.nextLong()
        val heard = CompletableDeferred<Pair<Long, Boolean>>()
        asked[nonce] = heard
        val sentAt = clock()
        if (!sendTo(endpoint, byteArrayOf(ECHO_AFTER) + longBytes(delayMs) + longBytes(nonce))) return null.also { asked.remove(nonce) }
        val got = withTimeoutOrNull(delayMs + graceMs) { heard.await() }
        asked.remove(nonce)
        return got?.let { (at, roamed) -> (at - sentAt) to roamed }
    }

    private fun probe(to: Pair<ByteArray, Int>) {
        val nonce = random.nextLong()
        synchronized(probesLock) { probes[nonce] = clock() }
        sendTo(to, byteArrayOf(PROBE) + longBytes(nonce))
    }

    // A listed target or one already learnt is nothing new; a new one is probed at once and every round after.
    private fun learn(from: Pair<ByteArray, Int>) {
        if (_state.value != State.PUNCHING || learnt.size >= PunchPolicy.MAX_TARGETS) return
        val known = targets.any { it.address.contentEquals(from.first) && it.port == from.second }
        if (known || learnt.any { it.first.contentEquals(from.first) && it.second == from.second }) return
        learnt += from
        probe(from)
    }

    private fun onAck(
        from: Pair<ByteArray, Int>,
        nonce: Long,
    ) {
        val sentAt = synchronized(probesLock) { probes[nonce] } ?: return
        // Under the state lock: a give-up closing the session in this instant must not see it come back UP.
        synchronized(stateLock) {
            if (_state.value != State.PUNCHING) return
            endpoint = from
            lastData = clock()
            _state.value = State.UP
        }
        val kind = targets.firstOrNull { it.address.contentEquals(from.first) && it.port == from.second }?.kind
        outcome.complete(Outcome.Up(kind, clock() - started, clock() - sentAt))
        synchronized(probesLock) { probes.clear() }
    }

    private fun sendTo(
        to: Pair<ByteArray, Int>?,
        plain: ByteArray,
    ): Boolean {
        val (address, p) = to ?: return false
        val sealed = runCatching { lane.seal(plain) }.getOrNull() ?: return false
        lastSent = clock()
        bytesOut.addAndGet(sealed.size.toLong())
        return port.send(address, p, sealed)
    }

    companion object {
        /** A link that has carried no DATA either way for this long closes: well inside the 2 min an idle path lived. */
        const val IDLE_MS = 60_000L

        /** A link that sends but hears nothing for this long is gone. */
        const val SILENCE_MS = 65_000L

        /** A held link's keepalive after this long with nothing sent: under the 2–4 min a home router held an idle path. */
        const val HOLD_KEEPALIVE_MS = 90_000L

        /** A held link that hears nothing for this long — two of the peer's keepalives missed — is gone. */
        const val HOLD_SILENCE_MS = 200_000L

        private const val RECEIVE_SLICE_MS = 1_000
        private const val MIN_TICK_MS = 250L
        private const val MAX_TICK_MS = 5_000L

        private const val PROBE: Byte = 1
        private const val ACK: Byte = 2
        private const val KEEPALIVE: Byte = 3
        private const val BYE: Byte = 4
        private const val DATA: Byte = 5

        // The gap measurement (debug builds drive it; `DirectLinks.measureGaps`): an echo, its reply, and an echo asked
        // for after a silence. The longest silence one may ask for, so a stray request cannot park a sender for long.
        private const val ECHO: Byte = 6
        private const val ECHO_REPLY: Byte = 7
        private const val ECHO_AFTER: Byte = 8
        private const val MAX_ECHO_DELAY_MS = 10 * 60_000L

        private const val BITS = 8
        private const val BYTE_MASK = 0xFFL

        private fun longBytes(v: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { (v ushr (BITS * (Long.SIZE_BYTES - 1 - it))).toByte() }

        private fun bytesLong(
            b: ByteArray,
            at: Int,
        ): Long {
            var v = 0L
            for (i in 0 until Long.SIZE_BYTES) v = (v shl BITS) or (b[at + i].toLong() and BYTE_MASK)
            return v
        }
    }
}
