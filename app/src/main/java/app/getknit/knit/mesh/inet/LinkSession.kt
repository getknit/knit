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
 * **Liveness.** A keepalive goes out after [keepaliveMs] with nothing sent, which holds the NAT mappings on both
 * paths open; a link that hears nothing that opens for [silenceMs] ends [EndReason.SILENT]. A BYE ends it at once.
 * Every datagram is one [LinkLane] seal, so the counter is shared by every path and nothing is ever sealed twice.
 */
class LinkSession(
    val peerId: String,
    private val port: LinkPort,
    private val lane: LinkLane,
    private val targets: List<LinkCandidate>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val keepaliveMs: Long = KEEPALIVE_MS,
    private val silenceMs: Long = SILENCE_MS,
    private val onData: (ByteArray) -> Unit = {},
    private val random: java.util.Random = SecureRandom(),
) {
    /** Where a session stands. */
    enum class State { PUNCHING, UP, CLOSED }

    /** Why a session closed. */
    enum class EndReason { NO_PATH, SILENT, BYE, LOCAL }

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

    // Peer-reflexive targets: sources of PROBEs that opened but match no listed target. Bounded like the list itself.
    private val learnt = java.util.concurrent.CopyOnWriteArrayList<Pair<ByteArray, Int>>()

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

    /** Sends one data payload over the link; false when it is not up. */
    fun sendData(payload: ByteArray): Boolean = state.value == State.UP && sendTo(endpoint, byteArrayOf(DATA) + payload)

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
        if (_state.value == State.UP) keepAlive()
    }

    private suspend fun keepAlive() {
        while (scope.isActive && _state.value == State.UP) {
            val now = clock()
            if (now - lastHeard >= silenceMs) return close(EndReason.SILENT, sayBye = false)
            if (now - lastSent >= keepaliveMs) sendTo(endpoint, byteArrayOf(KEEPALIVE))
            delay(minOf(keepaliveMs - (clock() - lastSent), silenceMs - (clock() - lastHeard)).coerceAtLeast(MIN_TICK_MS))
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
                if (_state.value == State.UP) onData(plain.copyOfRange(1, plain.size))
            }

            // A keepalive, or a type a later version sends.
            else -> {}
        }
        // A late PROBE from a candidate the peer is still trying says nothing about the path it settled on.
        if (_state.value == State.UP && plain[0] != PROBE) endpoint = from
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
        /** A keepalive after this long with nothing sent: inside the 30 s a cellular NAT is known to hold a mapping. */
        const val KEEPALIVE_MS = 20_000L

        /** A link that hears nothing for this long is gone. */
        const val SILENCE_MS = 65_000L

        private const val RECEIVE_SLICE_MS = 1_000
        private const val MIN_TICK_MS = 250L

        private const val PROBE: Byte = 1
        private const val ACK: Byte = 2
        private const val KEEPALIVE: Byte = 3
        private const val BYE: Byte = 4
        private const val DATA: Byte = 5

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
