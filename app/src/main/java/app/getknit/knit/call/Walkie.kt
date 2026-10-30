package app.getknit.knit.call

import java.io.ByteArrayOutputStream
import java.util.TreeMap
import kotlin.math.abs

/** How [CallManager.start] places a call (§3.11): [AUTO] picks; [LIVE] and [WALKIE] force one (the debug bridge). */
enum class CallKind { AUTO, LIVE, WALKIE }

/**
 * The walkie-talkie's seam into [CallManager] (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): whether this build runs
 * one at all, whether a peer does (`Protocol.CAP_WALKIE`), and whether a live call to it looks possible. Late-bound
 * over the mesh like [CallDirect]; [None] is a build without the walkie, whose calls behave exactly as before.
 */
interface CallWalkie {
    val enabled: Boolean

    suspend fun capable(peerId: String): Boolean

    /**
     * Whether a live call to [peerId] could connect: an Internet route here, or a Wi-Fi Direct leg to a neighbour. A
     * guess either way — a live call that turns out to have no path still drops to a walkie (§3.11).
     */
    suspend fun liveLikely(peerId: String): Boolean

    /**
     * Whether this phone holds a cast ticket on a spool it is connected to (§2.7) — what a walkie OFFER or ANSWER tells
     * the peer in `CallPayload.ct`. False by default: a phone that cannot tell only leaves a free peer casting less.
     */
    fun castTicketed(): Boolean = false

    object None : CallWalkie {
        override val enabled = false

        override suspend fun capable(peerId: String) = false

        override suspend fun liveLikely(peerId: String) = true
    }
}

/**
 * Where a walkie chunk to a peer would go now (§3.11), for the call screen's notice: over a live link of this phone's
 * own ([DIRECT]); through other phones, with fresh evidence a radio path exists ([MESH]: the peer's own frames reached
 * us through a link we still hold, within `TalkRadioPaths.WINDOW_MS`); with no radio path, through a spool that casts it
 * (§2.7) and the peer was lately seen on ([SPOOL]); or nowhere this phone can see ([NONE]) — a relay the peer was never
 * seen on fans a cast out to nobody we know of.
 */
enum class TalkLink {
    DIRECT,
    MESH,
    SPOOL,
    NONE,
    ;

    companion object {
        /**
         * [peerId]'s link, given this phone's live [neighbours], whether the radios have a path to the peer through one
         * of them ([radioPath], `MeshManager.talkRadioPath`), and whether a connected spool the peer was lately seen on
         * would [cast] to it (`MeshManager.castCoversTalk`). The order is `MeshManager.sendCallTalk`'s: the spool only
         * where the radios have no path.
         */
        fun of(
            peerId: String,
            neighbours: Set<String>,
            radioPath: Boolean,
            cast: Boolean = false,
        ): TalkLink =
            when {
                peerId in neighbours -> DIRECT
                radioPath -> MESH
                cast -> SPOOL
                else -> NONE
            }
    }
}

/**
 * How a burst this phone spoke ended, as the speaker feels and hears it (C-3.11-5): [RELEASED], a light tick as the
 * button is let go; [CAPPED], the burst ran to [WalkiePolicy.MAX_BURST_MS] with the button still held, so a firmer
 * buzz and a short double beep tell a speaker who may still be talking that the rest was not sent. Nothing reaches the
 * peer.
 */
enum class TalkCue { RELEASED, CAPPED }

/** A walkie session's numbers (§3.11). */
object WalkiePolicy {
    /** Opus's rate for a burst: speech at about 1 KB/s, so a burst fits Coded PHY's 2.1 KB/s with its framing. */
    const val BIT_RATE = 8_000

    /** What the microphone is captured at: Opus's wideband, enough for speech. */
    const val SAMPLE_RATE = 16_000

    /**
     * A held button is released for the user after this long, with a cue that says so ([TalkCue.CAPPED]). Thirty
     * seconds keeps a burst one line of the transcript's model (C-3.11-9) and one turn of a conversation.
     */
    const val MAX_BURST_MS = 30_000L

    /**
     * The longest burst a receiver plays and transcribes in full: [MAX_BURST_MS] before 2026-10-30. An older sender
     * still holds a button that long, and it is heard whole.
     */
    const val MAX_HEARD_BURST_MS = 60_000L

    /** A session with no burst either way for this long hangs up on its own. */
    const val IDLE_END_MS = 10 * 60_000L

    /**
     * How often a walkie call asks for a Wi-Fi Aware link to its peer, from the ring to the hang-up: inside the radio's
     * 25 s bulk-want window, so the ask never lapses while the call lasts.
     */
    const val LINK_ASK_MS = 10_000L

    /** A chunk goes out once this much audio is in hand: 24 Opus packets, about 0.5 KB before its framing. */
    const val CHUNK_MS = 480L

    /** The player starts a burst once it holds two chunks, or its last, or its first has waited this long. */
    const val PREBUFFER_MS = 400L

    /** A missing chunk is waited for this long while a later one is held, then skipped. */
    const val GAP_MS = 500L

    /** A burst with nothing held and no new chunk for this long is over: its last chunk was lost. */
    const val END_MS = 4_000L

    /**
     * The receiver reports a burst this long after its last chunk landed, so the slower plane's copy of it is counted
     * too (C-3.11-10); one whose last chunk never came is reported [END_MS] after its newest.
     */
    const val REPORT_SETTLE_MS = 500L

    /**
     * A burst sent with no report of it back this long after its last chunk went is taken as lost on every path:
     * [END_MS] for a receiver that never saw its end, and a round trip through a relay on top.
     */
    const val REPORT_WAIT_MS = END_MS + 3_000L

    /**
     * The share of a burst's chunks the radios may lose before its speaker casts the bursts after it through a relay as
     * well: one in five. A three-chunk burst that loses one is past it.
     */
    const val RADIO_LOSS_MAX = 0.2

    /** Bursts in a row whose report shows the radios whole before the speaker stops casting beside them. */
    const val RADIO_WHOLE_TO_RETURN = 2
}

/** One chunk of a burst: chunk [q] (from 0) of the sender's burst [b], Opus packets [a] ([OpusPackets]), [e] on its last. */
class TalkChunk(
    val b: Int,
    val q: Int,
    val a: ByteArray,
    val e: Boolean = false,
)

/**
 * What the receiver of burst [b] heard of it (C-3.11-10): the distinct chunks that came over the radios ([radio]) and
 * those a relay cast to it ([relay]). A chunk that came both ways counts in both.
 */
class TalkReport(
    val b: Int,
    val radio: Int,
    val relay: Int,
)

/**
 * A chunk's plaintext, sealed into a `talk` frame by the mesh (§3.11). Binary, because every byte rides Coded PHY twice
 * a second:
 *
 * ```
 * flags (bit 0: the burst's last) · call tag (2 bytes, big-endian) · varint b · varint q · packets
 * ```
 *
 * The tag ([tagOf]) ties a chunk to its session, so a late one from a session just ended never plays in the next.
 * Pure (`WalkieStreamTest`).
 */
object TalkWire {
    private const val END = 0x01
    private const val REPORT = 0x02
    private const val TAG_BYTES = 2
    private const val VARINT_BITS = 7
    private const val VARINT_MORE = 0x80
    private const val VARINT_MASK = 0x7F
    private const val VARINT_MAX_BYTES = 5
    private const val BYTE = 0xFF
    private const val BYTE_BITS = 8
    private const val TAG_MASK = 0xFFFF

    /** The 16-bit tag of the call [callId]: the same on both phones, since `String.hashCode` is specified. */
    fun tagOf(callId: String): Int = callId.hashCode() and TAG_MASK

    fun encode(
        tag: Int,
        chunk: TalkChunk,
    ): ByteArray {
        val out = ByteArrayOutputStream(1 + TAG_BYTES + 2 * VARINT_MAX_BYTES + chunk.a.size)
        out.write(if (chunk.e) END else 0)
        out.write((tag shr BYTE_BITS) and BYTE)
        out.write(tag and BYTE)
        writeVarint(out, chunk.b)
        writeVarint(out, chunk.q)
        out.write(chunk.a)
        return out.toByteArray()
    }

    /**
     * A [TalkReport] for the session [tag] (C-3.11-10): `flags (bit 1) · call tag · varint b · varint radio ·
     * varint relay`. Sent only to a peer whose walkie signal set `CallPayload.hr`, since a build before it would read
     * the counts as a chunk.
     */
    fun encodeReport(
        tag: Int,
        report: TalkReport,
    ): ByteArray {
        val out = ByteArrayOutputStream(1 + TAG_BYTES + 3 * VARINT_MAX_BYTES)
        out.write(REPORT)
        out.write((tag shr BYTE_BITS) and BYTE)
        out.write(tag and BYTE)
        writeVarint(out, report.b)
        writeVarint(out, report.radio)
        writeVarint(out, report.relay)
        return out.toByteArray()
    }

    /** The tag and report [bytes] carries, or null when they are not one. */
    fun decodeReport(bytes: ByteArray): Pair<Int, TalkReport>? {
        if (bytes.size < 1 + TAG_BYTES || bytes[0].toInt() and REPORT == 0) return null
        val tag = ((bytes[1].toInt() and BYTE) shl BYTE_BITS) or (bytes[2].toInt() and BYTE)
        val (b, afterB) = readVarint(bytes, 1 + TAG_BYTES) ?: return null
        val (radio, afterRadio) = readVarint(bytes, afterB) ?: return null
        val (relay, end) = readVarint(bytes, afterRadio) ?: return null
        return if (end == bytes.size) tag to TalkReport(b = b, radio = radio, relay = relay) else null
    }

    /** The tag and chunk [bytes] carries, or null when they are not one (a report among them). */
    fun decode(bytes: ByteArray): Pair<Int, TalkChunk>? {
        if (bytes.size < 1 + TAG_BYTES) return null
        val flags = bytes[0].toInt() and BYTE
        if (flags and REPORT != 0) return null
        val tag = ((bytes[1].toInt() and BYTE) shl BYTE_BITS) or (bytes[2].toInt() and BYTE)
        val (b, afterB) = readVarint(bytes, 1 + TAG_BYTES) ?: return null
        val (q, afterQ) = readVarint(bytes, afterB) ?: return null
        return tag to TalkChunk(b = b, q = q, a = bytes.copyOfRange(afterQ, bytes.size), e = flags and END != 0)
    }

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        var v = value
        while (v >= VARINT_MORE) {
            out.write((v and VARINT_MASK) or VARINT_MORE)
            v = v ushr VARINT_BITS
        }
        out.write(v)
    }

    private fun readVarint(
        bytes: ByteArray,
        from: Int,
    ): Pair<Int, Int>? {
        var value = 0L
        var at = from
        for (i in 0 until VARINT_MAX_BYTES) {
            if (at >= bytes.size) return null
            val b = bytes[at++].toInt() and BYTE
            value = value or ((b and VARINT_MASK).toLong() shl (VARINT_BITS * i))
            if (b and VARINT_MORE == 0) return value.takeIf { it <= Int.MAX_VALUE }?.let { it.toInt() to at }
        }
        return null
    }
}

/**
 * Opus packets as a burst's chunks carry them (§3.11): each behind a one-byte length, so a chunk is self-delimiting
 * without a container. A packet past 255 bytes is not one a speech rate makes, and is dropped at the join. Pure
 * (`WalkieStreamTest`).
 */
@Suppress("MagicNumber") // the RFC 6716 TOC table
object OpusPackets {
    private const val MAX_PACKET = 255

    /** [packets] behind their lengths, those too long for one dropped. */
    fun join(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in packets) {
            if (p.isEmpty() || p.size > MAX_PACKET) continue
            out.write(p.size)
            out.write(p)
        }
        return out.toByteArray()
    }

    /** The packets [bytes] holds, up to the first length that runs off its end. */
    fun split(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val size = bytes[at].toInt() and 0xFF
            if (size == 0 || at + 1 + size > bytes.size) break
            out += bytes.copyOfRange(at + 1, at + 1 + size)
            at += 1 + size
        }
        return out
    }

    /** How long [packet] plays, in microseconds, from its TOC byte (RFC 6716 §3.1); 0 for one too short to say. */
    fun durationUs(packet: ByteArray): Long {
        if (packet.isEmpty()) return 0L
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameUs =
            when {
                config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
                config < 16 -> longArrayOf(10_000, 20_000)[config and 1]
                else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
            }
        val frames =
            when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0
            }
        return frameUs * frames
    }
}

/** Gathers one burst's Opus packets into chunks of at least [chunkMs] of audio (§3.11). Not thread-safe. */
class TalkChunker(
    private val chunkMs: Long = WalkiePolicy.CHUNK_MS,
) {
    private val held = mutableListOf<ByteArray>()
    private var heldUs = 0L
    private var totalUs = 0L

    /** Audio this burst has taken in so far, sent or not. */
    val totalMs: Long get() = totalUs / US_PER_MS

    /** Takes one packet; the chunk's audio it completes, or null while the chunk is still short. */
    fun add(packet: ByteArray): ByteArray? {
        held += packet
        val us = OpusPackets.durationUs(packet)
        heldUs += us
        totalUs += us
        return if (heldUs >= chunkMs * US_PER_MS) flush() else null
    }

    /** Whatever is held, possibly nothing: the burst's last chunk. */
    fun flush(): ByteArray =
        OpusPackets.join(held).also {
            held.clear()
            heldUs = 0L
        }

    private companion object {
        const val US_PER_MS = 1_000L
    }
}

/**
 * The peer's chunks put back in order for the player (§3.11). Bursts play one after another in the order they were
 * spoken, each in chunk order once it holds two chunks (or its last, or [prebufferMs] has passed since its first came).
 * A missing chunk is waited for [gapMs] while a later one is held, then skipped; a burst ends on its last chunk, or
 * after [endMs] with nothing held and nothing new. Pure and clocked by the caller (`WalkieStreamTest`); not thread-safe.
 */
class TalkQueue(
    private val prebufferMs: Long = WalkiePolicy.PREBUFFER_MS,
    private val gapMs: Long = WalkiePolicy.GAP_MS,
    private val endMs: Long = WalkiePolicy.END_MS,
) {
    sealed interface Step {
        /**
         * Play [audio] (joined Opus packets, maybe none) of [burst]; [first] opens it, [last] closes it, and [cutShort]
         * says it closes because its last chunk never came.
         */
        class Play(
            val burst: Int,
            val audio: ByteArray,
            val first: Boolean,
            val last: Boolean,
            val cutShort: Boolean = false,
        ) : Step

        /** Nothing to play for [ms], unless a chunk comes first. */
        data class Wait(
            val ms: Long,
        ) : Step

        /** Nothing held: wait for a chunk. */
        data object Idle : Step
    }

    private class Burst(
        val firstAt: Long,
    ) {
        val chunks = TreeMap<Int, TalkChunk>()
        var lastAt = firstAt
    }

    private val bursts = TreeMap<Int, Burst>()

    // The newest burst played to its end or given up on; nothing at or below it plays again.
    private var done = -1
    private var playing: Int? = null
    private var next = 0
    private var started = false
    private var stalledAt: Long? = null

    /** Chunks skipped over because they never came. */
    var skipped = 0
        private set

    /** Bursts given up on because their last chunk never came: each lost that chunk at least, and how many more is unknown. */
    var cutShort = 0
        private set

    /** Takes a chunk that came at [at]; false when it is too late (its burst is over or past it) or a duplicate. */
    fun offer(
        chunk: TalkChunk,
        at: Long,
    ): Boolean {
        if (chunk.b <= done || (chunk.b == playing && chunk.q < next)) return false
        val burst = bursts.getOrPut(chunk.b) { Burst(at) }
        if (burst.chunks.putIfAbsent(chunk.q, chunk) != null) return false
        burst.lastAt = maxOf(burst.lastAt, at)
        // A peer that keeps opening bursts it never ends loses the oldest it is not playing.
        while (bursts.size > MAX_BURSTS) bursts.remove(bursts.keys.first { it != playing })
        return true
    }

    /** What to do at [now]. */
    @Suppress("ReturnCount") // a ladder: start a burst, play, skip a gap, give the burst up, wait
    fun poll(now: Long): Step {
        while (true) {
            val id = playing ?: (startable(now) ?: return waitToStart(now))
            val burst = bursts.getValue(id)
            val chunk = burst.chunks.remove(next)
            if (chunk != null) {
                next++
                stalledAt = null
                val first = !started
                started = true
                if (chunk.e) finish(id)
                return Step.Play(id, chunk.a, first, chunk.e)
            }
            val later = burst.chunks.higherKey(next)
            if (later != null) {
                val since = stalledAt ?: now.also { stalledAt = it }
                if (now - since < gapMs) return Step.Wait(since + gapMs - now)
                skipped += later - next
                next = later
                stalledAt = null
                continue
            }
            if (now - burst.lastAt < endMs) return Step.Wait(burst.lastAt + endMs - now)
            // Its last chunk never came: close what played, and move on.
            val opened = started
            cutShort++
            finish(id)
            if (opened) return Step.Play(id, ByteArray(0), first = false, last = true, cutShort = true)
        }
    }

    // The oldest held burst, made the playing one, when it may start now.
    private fun startable(now: Long): Int? {
        val (id, burst) = bursts.firstEntry() ?: return null
        val ready = burst.chunks.size >= 2 || burst.chunks.values.any { it.e } || now - burst.firstAt >= prebufferMs
        if (!ready) return null
        playing = id
        next = 0
        started = false
        stalledAt = null
        return id
    }

    private fun waitToStart(now: Long): Step {
        val burst = bursts.firstEntry()?.value ?: return Step.Idle
        return Step.Wait(burst.firstAt + prebufferMs - now)
    }

    private fun finish(id: Int) {
        done = maxOf(done, id)
        playing = null
        bursts.headMap(id, true).clear()
    }

    private companion object {
        const val MAX_BURSTS = 8
    }
}

/**
 * A walkie session's numbers, as the call screen's diagnostics card draws them (§3.11): the walkie's answer to a live
 * call's `CallStats`. Null until measured. [path] is filled in by the caller.
 */
data class WalkieStats(
    // Arrival less the sender's stamp, the median of the latest chunks: by two phones' clocks, so their skew is in it.
    val transitMs: Double? = null,
    // RFC 3550's interarrival jitter over chunks, from the sender's stamps: no clock skew in it.
    val jitterMs: Double? = null,
    // Chunks heard lost, of the chunks this session expected: those skipped as never come, and one for each burst cut
    // short (its last chunk never came, and how many before it went too is unknown), so a floor while [burstsCutShort]
    // is above zero.
    val lossPercent: Double? = null,
    // This phone's chunks the peer's reports (C-3.11-10) say it never got, of those its reports judged, a burst with no
    // report in time counted whole; null with none judged, as with a peer that does not report. A report counts a
    // chunk that came both ways on both planes, so a burst that went over both reads a floor.
    val sentLossPercent: Double? = null,
    // Frame bytes per second of speech, over the latest burst each way.
    val sendKbps: Double? = null,
    val receiveKbps: Double? = null,
    // The latest burst heard: its first chunk's arrival to its playing.
    val bufferMs: Double? = null,
    // From a chunk's cut to the radio taking it (retries included), the median of the latest chunks.
    val handMs: Double? = null,
    // The average `talk` frame (its envelope's bytes) each way.
    val frameOut: Int? = null,
    val frameIn: Int? = null,
    val chunksOut: Int = 0,
    val chunksFailed: Int = 0,
    val chunksIn: Int = 0,
    val chunksLost: Int = 0,
    // This phone's chunks the peer's reports say it never got, and the bursts that had no report in time.
    val chunksUnheard: Int = 0,
    val burstsUnreported: Int = 0,
    val burstsOut: Int = 0,
    val burstsIn: Int = 0,
    val burstsCutShort: Int = 0,
    // Silences mid-burst: the player ran out of a burst's audio before its next chunk came, and how long they lasted.
    val gaps: Int = 0,
    val gapMs: Long = 0,
    val path: String? = null,
)

/**
 * Measures a walkie session for [WalkieStats]: each chunk out as the radio takes it (or refuses it), each chunk in as
 * the player's queue takes it, each burst as it starts playing, the chunks skipped and the bursts cut short, the
 * peer's reports of this phone's bursts, and each piece of audio as the player is handed it, against the burst's audio
 * still to play, for the silences heard mid-burst. Pure and clocked by its callers (`WalkieMeterTest`); synchronized,
 * since the burst out, the player and the reports run on their own threads.
 */
class WalkieMeter {
    private val transits = ArrayDeque<Long>()
    private val hands = ArrayDeque<Long>()
    private var jitter: Double? = null
    private var lastIn: Pair<Long, Long>? = null
    private var buffer: Long? = null
    private var out = Tally()
    private var inBurst = -1
    private var inTally = Tally()
    private var framesOut = Tally()
    private var framesIn = Tally()
    private var chunksOut = 0
    private var failed = 0
    private var chunksIn = 0
    private var lost = 0
    private var cutShort = 0
    private var judged = 0
    private var unheard = 0
    private var unreported = 0
    private var burstsOut = 0
    private var burstsIn = 0
    private var gaps = 0
    private var gapMs = 0L

    // The playing burst's audio handed to the player and not yet played out (ms) as of [aheadAt], and whether this
    // phone's talking holds the player meanwhile.
    private var ahead = 0L
    private var aheadAt = 0L
    private var held = false

    // Bytes over the audio they carried (ms), or over the frames counted.
    private class Tally(
        var bytes: Long = 0,
        var count: Long = 0,
    )

    @Synchronized
    fun reset() {
        transits.clear()
        hands.clear()
        jitter = null
        lastIn = null
        buffer = null
        out = Tally()
        inBurst = -1
        inTally = Tally()
        framesOut = Tally()
        framesIn = Tally()
        chunksOut = 0
        failed = 0
        chunksIn = 0
        lost = 0
        cutShort = 0
        judged = 0
        unheard = 0
        unreported = 0
        burstsOut = 0
        burstsIn = 0
        gaps = 0
        gapMs = 0L
        ahead = 0L
        aheadAt = 0L
        held = false
    }

    /** A burst out begins: the send rate is this burst's from here. */
    @Synchronized
    fun burstOut() {
        out = Tally()
    }

    /** A chunk carrying [audio] out, cut [handMs] before the radio took it in a frame of [frameBytes], or null: refused. */
    @Synchronized
    fun chunkOut(
        audio: ByteArray,
        frameBytes: Int?,
        handMs: Long,
    ) {
        if (frameBytes == null) {
            failed++
            return
        }
        chunksOut++
        out.bytes += frameBytes
        out.count += audioMs(audio)
        framesOut.bytes += frameBytes
        framesOut.count++
        hands.keep(handMs)
    }

    /** A chunk in, taken by the player's queue: it came [at], stamped [sentAt] by its sender (0: unknown). */
    @Synchronized
    fun chunkIn(
        chunk: TalkChunk,
        at: Long,
        sentAt: Long,
        frameBytes: Int,
    ) {
        chunksIn++
        if (chunk.b != inBurst) {
            inBurst = chunk.b
            inTally = Tally()
        }
        inTally.bytes += frameBytes
        inTally.count += audioMs(chunk.a)
        framesIn.bytes += frameBytes
        framesIn.count++
        if (sentAt <= 0) return
        transits.keep(at - sentAt)
        // RFC 3550 §6.4.1, the arrivals' spacing against the stamps': J += (|D| - J) / 16.
        lastIn?.let { (r, s) ->
            val d = abs((at - r) - (sentAt - s)).toDouble()
            val j = jitter ?: 0.0
            jitter = j + (d - j) / JITTER_GAIN
        }
        lastIn = at to sentAt
    }

    /** A burst whose first chunk came [firstAt] began to play [at]. */
    @Synchronized
    fun started(
        firstAt: Long,
        at: Long,
    ) {
        buffer = at - firstAt
    }

    /** A burst out reached the radio: at least one of its chunks was taken. */
    @Synchronized
    fun burstSent() {
        burstsOut++
    }

    /** A burst in played to its end. */
    @Synchronized
    fun burstHeard() {
        burstsIn++
    }

    /** [n] chunks skipped over as never come. */
    @Synchronized
    fun skipped(n: Int) {
        lost += n
    }

    /** [n] bursts given up on because their last chunk never came: each lost that chunk at least. */
    @Synchronized
    fun cutShort(n: Int) {
        cutShort += n
    }

    /**
     * The peer's word on a burst of [chunks] this phone sent (C-3.11-10): [heard] of them came, counted over both
     * planes, or null when no report came in time, so none did as far as this phone can tell.
     */
    @Synchronized
    fun judged(
        chunks: Int,
        heard: Int?,
    ) {
        if (chunks <= 0) return
        judged += chunks
        unheard += chunks - (heard ?: 0).coerceIn(0, chunks)
        if (heard == null) unreported++
    }

    /** This phone's talking holds the player from [at] ([on]), or lets it go on: held, no audio plays out. */
    @Synchronized
    fun held(
        on: Boolean,
        at: Long,
    ) {
        drain(at)
        held = on
    }

    /**
     * [audio] of a burst handed to the player [at]; [first] opens the burst. Audio that comes once the player has played
     * out all it had of the burst ends a silence the listener heard mid-burst, counted from [GAP_MIN_MS]. An empty piece
     * (a burst given up on, or a cancelled one's end) ends none: nothing plays after it.
     */
    @Synchronized
    fun played(
        audio: ByteArray,
        first: Boolean,
        at: Long,
    ) {
        val ms = audioMs(audio)
        if (first) {
            ahead = 0L
            aheadAt = at
        } else {
            val dry = drain(at)
            if (dry >= GAP_MIN_MS && ms > 0) {
                gaps++
                gapMs += dry
            }
        }
        ahead += ms
    }

    // Plays the burst's audio out up to [at], unless held: how long, of the time since, the player had none left.
    private fun drain(at: Long): Long {
        val elapsed = (at - aheadAt).coerceAtLeast(0L)
        aheadAt = at
        if (held) return 0L
        val dry = elapsed - ahead
        ahead = (ahead - elapsed).coerceAtLeast(0L)
        return dry.coerceAtLeast(0L)
    }

    @Synchronized
    fun snapshot(): WalkieStats =
        WalkieStats(
            transitMs = transits.median(),
            jitterMs = jitter,
            lossPercent = (chunksIn + lost + cutShort).takeIf { it > 0 }?.let { (lost + cutShort) * PERCENT / it },
            sentLossPercent = judged.takeIf { it > 0 }?.let { unheard * PERCENT / it },
            sendKbps = out.rate(),
            receiveKbps = inTally.rate(),
            bufferMs = buffer?.toDouble(),
            handMs = hands.median(),
            frameOut = framesOut.mean(),
            frameIn = framesIn.mean(),
            chunksOut = chunksOut,
            chunksFailed = failed,
            chunksIn = chunksIn,
            chunksLost = lost,
            chunksUnheard = unheard,
            burstsUnreported = unreported,
            burstsOut = burstsOut,
            burstsIn = burstsIn,
            burstsCutShort = cutShort,
            gaps = gaps,
            gapMs = gapMs,
        )

    // Bits per millisecond are kilobits per second.
    private fun Tally.rate(): Double? = count.takeIf { it > 0 }?.let { bytes * BITS / it.toDouble() }

    private fun Tally.mean(): Int? = count.takeIf { it > 0 }?.let { (bytes / it).toInt() }

    private fun ArrayDeque<Long>.keep(value: Long) {
        addLast(value)
        if (size > WINDOW) removeFirst()
    }

    private fun ArrayDeque<Long>.median(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun audioMs(audio: ByteArray): Long = OpusPackets.split(audio).sumOf(OpusPackets::durationUs) / US_PER_MS

    private companion object {
        // The latest chunks a median reads: about eight seconds of speech.
        const val WINDOW = 16

        // A silence shorter than this is the track's own output latency, which the reckoning does not see, more than one
        // the listener heard: about two of a chunk's 20 ms packets.
        const val GAP_MIN_MS = 50L
        const val JITTER_GAIN = 16.0
        const val PERCENT = 100.0
        const val BITS = 8.0
        const val US_PER_MS = 1_000L
    }
}
