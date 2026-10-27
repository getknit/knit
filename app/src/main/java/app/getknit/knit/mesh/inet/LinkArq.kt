package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.link.LinkSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap

/**
 * The byte stream over one direct link (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` §6): ordered, acknowledged,
 * retransmitted and paced by a congestion window, so `FramedLink` runs over a link as it runs over a radio's socket.
 * Each segment rides one DATA datagram of the link ([send]); each DATA body that opens comes in through [take]. The
 * lane under it already authenticates every datagram and refuses a replay, so nothing here is a security boundary: a
 * segment is trusted as far as its bounds are sane.
 *
 * **Sending.** What is written is cut into segments of at most [MAX_PAYLOAD] bytes — a part-filled one goes on a flush —
 * numbered from 0. Every datagram carries the receiver's state (the next segment it lacks, the segments it holds above
 * that, and how far it will take), so an ack rides on data whenever there is any. A segment is lost once three
 * transmissions sent after it were acknowledged (QUIC's packet threshold, counted in transmissions so a retransmission
 * is judged against what went after it), or when the retransmission timer (RFC 6298, seeded from the punch's round
 * trip) expires, which presumes every segment in flight lost. The congestion window starts at [INITIAL_CWND] segments,
 * grows by one per segment acknowledged until the first loss and by one per window after, halves once per round trip of
 * losses and falls to [MIN_CWND] on a timeout (NewReno's AIMD over segments). Sends are **paced** across the round trip
 * rather than fired as a window at once (RFC 9002 §7.7): a bucket of at most [PACE_BURST] segments refills at the window
 * per smoothed round trip, doubled while the window is still growing from its start and a quarter over it after, so a
 * shallow queue on the path — an access point holding frames for a phone in power save — sees a stream, not a burst.
 *
 * **Receiving.** Segments are taken below the advertised limit ([window] past the last one read), held out of order
 * until the gap fills, and read in order. An ack goes at once for a segment out of order, a duplicate or an ASK, else
 * for every second segment or [ACK_DELAY_MS] after the first unacknowledged one. Reading a quarter of the window opens
 * it with an ack, and a sender stopped at the limit asks again each timeout, so a lost window update cannot wedge it.
 *
 * **Ending.** The stream is the link's: [end] (the link closed) ends reads once what arrived is read and fails writes;
 * [close] ends it here and closes the link ([onClose]). There is no half-close.
 *
 * The suspending [read], [write] and [flush] are the core; [input] and [output] block on them for `FramedLink`'s IO
 * threads. Thread-safe: one lock over the state, never held across a send or a suspension.
 */
class LinkArq(
    private val send: (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onClose: () -> Unit = {},
    private val window: Int = RECV_WINDOW,
) : LinkSocket {
    init {
        require(window in MIN_WINDOW..MAX_WINDOW) { "a window is $MIN_WINDOW..$MAX_WINDOW segments" }
    }

    private class Sent(
        val seq: Long,
        val payload: ByteArray,
    ) {
        var sentAt = 0L
        var txn = 0L
        var resent = false
    }

    /** One stream's counters, for the debug bridge's bulk measurement. */
    class Stats(
        val segmentsSent: Long,
        val retransmits: Long,
        val bytesAcked: Long,
        val srttMs: Long?,
        val cwnd: Int,
        // Halvings for loss, timer expiries, and segments presumed lost that were acknowledged before going again.
        val lossEvents: Long = 0,
        val timeouts: Long = 0,
        val spurious: Long = 0,
    )

    private val lock = Any()

    // Bumped on every change a reader, a writer or the timers may be waiting for.
    private val tick = MutableStateFlow(0L)

    // Sending: written but not yet a whole segment, whole but never sent, sent and outstanding, presumed lost.
    private val partial = ByteArrayOutputStream()
    private val ready = ArrayDeque<ByteArray>()
    private val inFlight = TreeMap<Long, Sent>()
    private val lost = TreeMap<Long, Sent>()
    private var nextSeq = 0L
    private var txn = 0L
    private var peerLimit = window.toLong()
    private var highestAckedTxn = -1L
    private var cwnd = INITIAL_CWND.toDouble()
    private var ssthresh = Double.MAX_VALUE
    private var recoveryStart = Long.MIN_VALUE
    private var srtt: Double? = null
    private var rttvar = 0.0
    private var backoff = 1
    private var rtoAt: Long? = null
    private var askAt: Long? = null
    private var askNow = false

    // The pacing bucket: segments that may go now, when it was last filled, and when the next one may go.
    private var paceTokens = PACE_BURST.toDouble()
    private var paceStamp = 0L
    private var paceAt: Long? = null

    // Receiving: the next segment lacked, those held above it, those read in order but not yet consumed.
    private var rcvNext = 0L
    private val ooo = TreeMap<Long, ByteArray>()
    private val inq = ArrayDeque<ByteArray>()
    private var inqOffset = 0
    private var consumed = 0L
    private var advertised = window.toLong()
    private var ackOwed = 0
    private var ackAt: Long? = null
    private var ackNow = false

    private var ended = false

    private var segmentsSent = 0L
    private var retransmits = 0L
    private var lossEvents = 0L
    private var timeouts = 0L
    private var spurious = 0L
    private var bytesAcked = 0L

    override val input: InputStream = BufferedInputStream(Input(), INPUT_BUFFER)
    override val output: OutputStream = Output()

    // Last, once every property above is set.
    init {
        scope.launch { timers() }
    }

    /** Seeds the round trip from the punch's, until the stream measures its own. */
    fun seedRtt(rttMs: Long) {
        synchronized(lock) { if (srtt == null && rttMs > 0) setRtt(rttMs) }
    }

    fun stats(): Stats =
        synchronized(lock) {
            Stats(segmentsSent, retransmits, bytesAcked, srtt?.toLong(), cwnd.toInt(), lossEvents, timeouts, spurious)
        }

    /** Suspends until everything written and flushed is acknowledged; false when the stream ended first. */
    suspend fun awaitDrained(): Boolean {
        tick.first { synchronized(lock) { ended || drained() } }
        return synchronized(lock) { drained() }
    }

    // Under the lock: nothing flushed is waiting or outstanding.
    private fun drained() = ready.isEmpty() && inFlight.isEmpty() && lost.isEmpty()

    /** One DATA body that opened on the link. */
    fun take(body: ByteArray) {
        val segment = Segment.decode(body) ?: return
        val out =
            synchronized(lock) {
                if (ended) return
                val now = clock()
                onAck(segment, now)
                segment.seq?.let { onSegment(it, segment.payload, now) }
                if (segment.ask) ackNow = true
                pump(now)
            }
        out.forEach { send(it) }
        bump()
    }

    /** The link is gone: what arrived can still be read, then reads end; writes fail. */
    fun end() {
        synchronized(lock) { ended = true }
        bump()
    }

    /** Ends the stream here and closes the link under it. Idempotent. */
    override fun close() {
        val was = synchronized(lock) { ended.also { ended = true } }
        bump()
        if (!was) onClose()
    }

    /** Reads up to [len] bytes into [b], suspending until at least one is there; -1 once the stream has ended. */
    suspend fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        tick.first { synchronized(lock) { inq.isNotEmpty() || ended } }
        val (n, out) =
            synchronized(lock) {
                if (inq.isEmpty()) return -1
                var n = 0
                while (n < len && inq.isNotEmpty()) {
                    val head = inq.first()
                    val take = minOf(len - n, head.size - inqOffset)
                    head.copyInto(b, off + n, inqOffset, inqOffset + take)
                    n += take
                    inqOffset += take
                    if (inqOffset == head.size) {
                        inq.removeFirst()
                        inqOffset = 0
                        consumed++
                    }
                }
                if (consumed + window - advertised >= window / WINDOW_UPDATE_DIVISOR) ackNow = true
                n to pump(clock())
            }
        out.forEach { send(it) }
        bump()
        return n
    }

    /** Writes [len] bytes of [b], suspending while the send buffer is full; throws once the stream has ended. */
    suspend fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        var at = off
        val end = off + len
        while (at < end) {
            tick.first { synchronized(lock) { ended || ready.size < SEND_BUFFER } }
            val out =
                synchronized(lock) {
                    if (ended) throw IOException("link stream ended")
                    while (at < end && ready.size < SEND_BUFFER) {
                        val n = minOf(end - at, MAX_PAYLOAD - partial.size())
                        partial.write(b, at, n)
                        at += n
                        if (partial.size() == MAX_PAYLOAD) cut()
                    }
                    pump(clock())
                }
            out.forEach { send(it) }
            bump()
        }
    }

    /** Sends what was written, a part-filled segment too. */
    suspend fun flush() {
        tick.first { synchronized(lock) { ended || ready.size < SEND_BUFFER } }
        val out =
            synchronized(lock) {
                if (ended) throw IOException("link stream ended")
                if (partial.size() > 0) cut()
                pump(clock())
            }
        out.forEach { send(it) }
        bump()
    }

    // Under the lock: the written bytes become the next whole segment.
    private fun cut() {
        if (nextSeq + ready.size >= MAX_SEQ) throw IOException("link stream spent")
        ready.addLast(partial.toByteArray())
        partial.reset()
    }

    // The retransmission, delayed-ack and window-probe timers, woken by every change.
    private suspend fun timers() {
        while (true) {
            val (out, wait, seen) =
                synchronized(lock) {
                    if (ended) return
                    val now = clock()
                    if (rtoAt?.let { now >= it } == true) onTimeout(now)
                    if (ackAt?.let { now >= it } == true) ackNow = true
                    if (paceAt?.let { now >= it } == true) paceAt = null
                    if (askAt?.let { now >= it } == true) {
                        ackNow = true
                        askNow = true
                        askAt = null
                    }
                    val sends = pump(now)
                    val next = listOfNotNull(rtoAt, ackAt, askAt, paceAt).minOrNull()?.let { (it - now).coerceAtLeast(1) }
                    Triple(sends, next, tick.value)
                }
            out.forEach { send(it) }
            withTimeoutOrNull(wait ?: IDLE_WAIT_MS) { tick.first { it != seen } }
        }
    }

    // Under the lock: the receiver's state in [segment] — what it holds and how far it will take.
    private fun onAck(
        segment: Segment,
        now: Long,
    ) {
        if (segment.limit > peerLimit) peerLimit = segment.limit
        val acked = acknowledged(segment)
        if (acked.isEmpty()) return
        acked.forEach { bytesAcked += it.payload.size }
        val newest = acked.maxBy { it.txn }
        if (newest.txn > highestAckedTxn) highestAckedTxn = newest.txn
        if (!newest.resent) sample(now - newest.sentAt)
        // Growth only for what was sent since the last reduction, so one loss event is answered once.
        repeat(acked.count { it.sentAt > recoveryStart }) { cwnd += if (cwnd < ssthresh) 1.0 else 1.0 / cwnd }
        cwnd = cwnd.coerceAtMost(window.toDouble())
        backoff = 1
        rtoAt = if (inFlight.isEmpty()) null else now + rto()
        detectLoss(now)
    }

    // Under the lock: the segments [segment] acknowledges, taken out of flight (or out of the lost, sent after all).
    private fun acknowledged(segment: Segment): List<Sent> {
        val acked = ArrayList<Sent>()
        if (segment.ack <= nextSeq) {
            for (held in listOf(inFlight, lost)) {
                held.headMap(segment.ack).let {
                    if (held === lost) spurious += it.size
                    acked += it.values
                    it.clear()
                }
            }
        }
        for ((start, end) in segment.ranges) {
            if (end > nextSeq) continue
            for (held in listOf(inFlight, lost)) {
                held.subMap(start, end).let {
                    if (held === lost) spurious += it.size
                    acked += it.values
                    it.clear()
                }
            }
        }
        return acked
    }

    // Under the lock: three transmissions after one were acknowledged and it was not, so it is lost; the window halves
    // once for the losses of a round.
    private fun detectLoss(now: Long) {
        val gone = inFlight.values.filter { it.txn + REORDER_THRESHOLD <= highestAckedTxn }
        if (gone.isEmpty()) return
        gone.forEach {
            inFlight.remove(it.seq)
            lost[it.seq] = it
        }
        if (gone.any { it.sentAt > recoveryStart }) {
            lossEvents++
            cwnd = (cwnd / 2).coerceAtLeast(MIN_CWND.toDouble())
            ssthresh = cwnd
            recoveryStart = now
        }
    }

    // Under the lock: one segment of data from the peer.
    private fun onSegment(
        seq: Long,
        payload: ByteArray,
        now: Long,
    ) {
        when {
            seq < rcvNext || ooo.containsKey(seq) -> {
                ackNow = true
            }

            // Past the window: dropped unacknowledged, and sent again once the window opens.
            seq >= consumed + window -> {
                return
            }

            seq == rcvNext -> {
                val filled = ooo.isNotEmpty()
                inq.addLast(payload)
                rcvNext++
                while (ooo.isNotEmpty() && ooo.firstKey() == rcvNext) {
                    inq.addLast(ooo.remove(rcvNext)!!)
                    rcvNext++
                }
                ackOwed++
                if (filled || ackOwed >= ACK_EVERY) {
                    ackNow = true
                } else if (ackAt == null) {
                    ackAt = now + ACK_DELAY_MS
                }
            }

            else -> {
                ooo[seq] = payload
                ackNow = true
            }
        }
    }

    // Under the lock: every segment presumed lost, the window down to its floor, the timer backed off.
    private fun onTimeout(now: Long) {
        timeouts++
        lost.putAll(inFlight)
        inFlight.clear()
        ssthresh = (cwnd / 2).coerceAtLeast(MIN_CWND.toDouble())
        cwnd = MIN_CWND.toDouble()
        recoveryStart = now
        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        rtoAt = null
    }

    // Under the lock: what the window lets go now — lost segments first, then new ones below the peer's limit — each
    // carrying the ack; else a bare ack when one is owed. Sent by the caller once the lock is released.
    private fun pump(now: Long): List<ByteArray> {
        val out = sendPaced(now)
        askAt = if (stalledAtLimit()) askAt ?: (now + rto()) else null
        if (out.isEmpty() && ackNow) out += segment(null, EMPTY)
        return out
    }

    // Under the lock: the segments the window and the pace let go now. When the window would let more go than the pace
    // does, the timers send it once the bucket holds a segment.
    private fun sendPaced(now: Long): MutableList<ByteArray> {
        val out = ArrayList<ByteArray>()
        refill(now)
        while (inFlight.size < cwnd.toInt() && hasToSend()) {
            if (paceTokens < 1) {
                paceAt = now + paceWaitMs()
                return out
            }
            val next = nextToSend() ?: return out
            paceTokens -= 1
            next.sentAt = now
            next.txn = txn++
            inFlight[next.seq] = next
            segmentsSent++
            out += segment(next.seq, next.payload)
            if (rtoAt == null) rtoAt = now + rto()
        }
        return out
    }

    // Under the lock: written segments wait, nothing is outstanding, and the peer's limit stops the next one.
    private fun stalledAtLimit() = ready.isNotEmpty() && inFlight.isEmpty() && lost.isEmpty() && nextSeq >= peerLimit

    // Under the lock: whether a lost segment, or a new one below the peer's limit, is waiting.
    private fun hasToSend() = lost.isNotEmpty() || (ready.isNotEmpty() && nextSeq < peerLimit)

    // Under the lock: segments per millisecond the bucket refills at, or null before a round trip is known (then the
    // window alone bounds a burst, and it starts at [INITIAL_CWND]).
    private fun paceRate(): Double? {
        val rtt = srtt ?: return null
        val gain = if (cwnd < ssthresh) PACE_GAIN_GROWING else PACE_GAIN
        return gain * cwnd / rtt.coerceAtLeast(1.0)
    }

    // Under the lock: the bucket topped up for the time since it was last; it holds [PACE_BURST], or a timer tick's worth
    // of the pace when that is more, so a fast path is not held to what one tick can send.
    private fun refill(now: Long) {
        val rate = paceRate()
        paceTokens =
            if (rate == null) {
                PACE_BURST.toDouble()
            } else {
                val cap = maxOf(PACE_BURST.toDouble(), rate * PACE_TICK_MS)
                (paceTokens + (now - paceStamp).coerceAtLeast(0) * rate).coerceAtMost(cap)
            }
        paceStamp = now
    }

    // Under the lock: how long until the bucket holds a whole segment.
    private fun paceWaitMs(): Long {
        val rate = paceRate() ?: return 1
        return Math.ceil((1 - paceTokens) / rate).toLong().coerceAtLeast(1)
    }

    // Under the lock: the lowest lost segment, else the next new one below the peer's limit, else none.
    private fun nextToSend(): Sent? {
        lost.pollFirstEntry()?.value?.let {
            it.resent = true
            retransmits++
            return it
        }
        if (ready.isEmpty() || nextSeq >= peerLimit) return null
        return Sent(nextSeq++, ready.removeFirst())
    }

    // Under the lock: one segment, carrying the ack (which it settles).
    private fun segment(
        seq: Long?,
        payload: ByteArray,
    ): ByteArray {
        advertised = consumed + window
        val ask = askNow
        ackNow = false
        askNow = false
        ackOwed = 0
        ackAt = null
        return Segment(rcvNext, advertised, ranges(), seq, payload, ask).encode()
    }

    // The ranges held above the gap, highest first, at most [MAX_RANGES].
    private fun ranges(): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var end = -1L
        var start = -1L
        for (seq in ooo.descendingKeySet()) {
            if (seq == start - 1) {
                start = seq
                continue
            }
            if (end >= 0) {
                out += start to end
                if (out.size == MAX_RANGES) return out
            }
            start = seq
            end = seq + 1
        }
        if (end >= 0) out += start to end
        return out
    }

    // RFC 6298's smoothing: alpha 1/8, beta 1/4.
    private fun sample(rttMs: Long) {
        val r = rttMs.coerceAtLeast(1).toDouble()
        val s = srtt ?: return setRtt(r.toLong())
        rttvar = (1 - RTT_BETA) * rttvar + RTT_BETA * Math.abs(s - r)
        srtt = (1 - RTT_ALPHA) * s + RTT_ALPHA * r
    }

    private fun setRtt(rttMs: Long) {
        srtt = rttMs.toDouble()
        rttvar = rttMs / 2.0
    }

    private fun rto(): Long {
        val base = srtt?.let { (it + maxOf(RTO_GRANULARITY_MS.toDouble(), RTO_VAR_FACTOR * rttvar)).toLong() } ?: INITIAL_RTO_MS
        return (base.coerceAtLeast(MIN_RTO_MS) * backoff).coerceAtMost(MAX_RTO_MS)
    }

    private fun bump() = tick.update { it + 1 }

    private inner class Input : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = runBlocking { this@LinkArq.read(b, off, len) }

        override fun close() = this@LinkArq.close()
    }

    private inner class Output : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = runBlocking { this@LinkArq.write(b, off, len) }

        override fun flush() = runBlocking { this@LinkArq.flush() }

        override fun close() = this@LinkArq.close()
    }

    /**
     * One segment as it rides a DATA body (`docs/DIRECT_LINKS.md` L-6-1): `flags(1) · ack(4) · limit(4) · n(1) ·
     * n × (start(4) · end(4)) · [seq(4) · payload]`, big-endian. [seq] null is a bare ack.
     */
    internal class Segment(
        val ack: Long,
        val limit: Long,
        val ranges: List<Pair<Long, Long>>,
        val seq: Long?,
        val payload: ByteArray,
        val ask: Boolean,
    ) {
        fun encode(): ByteArray {
            val size = FIXED_BYTES + RANGE_BYTES * ranges.size + (if (seq != null) U32_BYTES + payload.size else 0)
            val out = ByteArray(size)
            out[0] = ((if (seq != null) FLAG_SEQ else 0) or (if (ask) FLAG_ASK else 0)).toByte()
            putU32(out, 1, ack)
            putU32(out, 1 + U32_BYTES, limit)
            out[1 + 2 * U32_BYTES] = ranges.size.toByte()
            var at = FIXED_BYTES
            for ((start, end) in ranges) {
                putU32(out, at, start)
                putU32(out, at + U32_BYTES, end)
                at += RANGE_BYTES
            }
            if (seq != null) {
                putU32(out, at, seq)
                payload.copyInto(out, at + U32_BYTES)
            }
            return out
        }

        companion object {
            const val FLAG_SEQ = 0x01
            const val FLAG_ASK = 0x02
            const val FIXED_BYTES = 1 + 2 * U32_BYTES + 1
            const val RANGE_BYTES = 2 * U32_BYTES

            /** [body] as a segment, or null when it is not a well-formed one. */
            @Suppress("ReturnCount") // a ladder of refusals ahead of the parse
            fun decode(body: ByteArray): Segment? {
                if (body.size < FIXED_BYTES) return null
                val flags = body[0].toInt()
                val n = body[1 + 2 * U32_BYTES].toInt() and BYTE_MASK
                if (n > MAX_RANGES) return null
                val hasSeq = flags and FLAG_SEQ != 0
                val head = FIXED_BYTES + RANGE_BYTES * n
                if (body.size < head + (if (hasSeq) U32_BYTES + 1 else 0)) return null
                if (!hasSeq && body.size != head) return null
                if (hasSeq && body.size - head - U32_BYTES > MAX_PAYLOAD) return null
                val ranges =
                    (0 until n).map {
                        val at = FIXED_BYTES + RANGE_BYTES * it
                        u32(body, at) to u32(body, at + U32_BYTES)
                    }
                if (ranges.any { (start, end) -> start >= end }) return null
                return Segment(
                    ack = u32(body, 1),
                    limit = u32(body, 1 + U32_BYTES),
                    ranges = ranges,
                    seq = if (hasSeq) u32(body, head) else null,
                    payload = if (hasSeq) body.copyOfRange(head + U32_BYTES, body.size) else EMPTY,
                    ask = flags and FLAG_ASK != 0,
                )
            }
        }
    }

    companion object {
        /** The largest UDP payload a link sends: QUIC's floor, under any path's MTU an IPv6 path may have. */
        const val MAX_DATAGRAM = 1200

        /** The most ranges an ack lists. */
        const val MAX_RANGES = 8

        /** The most data one segment carries: a whole datagram with the lane, its type byte and a full ack around it. */
        const val MAX_PAYLOAD =
            MAX_DATAGRAM - LinkLane.OVERHEAD_BYTES - 1 - (1 + 2 * 4 + 1) - MAX_RANGES * 2 * 4 - 4

        /** Segments a receiver takes past the last one read (~560 KB): a cellular path's bandwidth-delay product. */
        const val RECV_WINDOW = 512

        /** The congestion window's start and floor, in segments. */
        const val INITIAL_CWND = 10
        const val MIN_CWND = 2

        /** Whole segments written but not yet sent before a writer waits. */
        const val SEND_BUFFER = 64

        /** The retransmission timer before a round trip is known, and its bounds. */
        const val INITIAL_RTO_MS = 1_000L
        const val MIN_RTO_MS = 200L
        const val MAX_RTO_MS = 8_000L

        /** The most segments that go back to back; the pace refills it. */
        const val PACE_BURST = 4

        /** The pace over the window per round trip: doubled while the window grows from its start, a quarter over after. */
        private const val PACE_GAIN_GROWING = 2.0
        private const val PACE_GAIN = 1.25
        private const val PACE_TICK_MS = 2

        /** An in-order segment's ack waits at most this long for a second one to share it. */
        const val ACK_DELAY_MS = 20L

        private const val ACK_EVERY = 2
        private const val REORDER_THRESHOLD = 3
        private const val WINDOW_UPDATE_DIVISOR = 4
        private const val MAX_BACKOFF = 64
        private const val RTT_ALPHA = 0.125
        private const val RTT_BETA = 0.25
        private const val RTO_VAR_FACTOR = 4
        private const val RTO_GRANULARITY_MS = 10L
        private const val IDLE_WAIT_MS = 60_000L
        private const val MIN_WINDOW = 4
        private const val MAX_WINDOW = 65_535
        private const val INPUT_BUFFER = 16 * 1024
        private const val MAX_SEQ = 1L shl 32
        private const val U32_BYTES = 4
        private const val BYTE_MASK = 0xFF
        private const val BITS = 8
        private const val U32_MASK = 0xFFFFFFFFL
        private val EMPTY = ByteArray(0)

        private fun putU32(
            out: ByteArray,
            at: Int,
            v: Long,
        ) {
            for (i in 0 until U32_BYTES) out[at + i] = (v ushr (BITS * (U32_BYTES - 1 - i))).toByte()
        }

        private fun u32(
            b: ByteArray,
            at: Int,
        ): Long {
            var v = 0L
            for (i in 0 until U32_BYTES) v = (v shl BITS) or (b[at + i].toLong() and BYTE_MASK.toLong())
            return v and U32_MASK
        }
    }
}
