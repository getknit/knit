package app.getknit.knit.ui.call

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import app.getknit.knit.call.CaptureHealth
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.TalkCapture
import app.getknit.knit.call.TalkGain
import app.getknit.knit.call.TalkPcmTap
import app.getknit.knit.call.VoxGate
import app.getknit.knit.call.VoxPolicy
import app.getknit.knit.call.VoxSensitivity
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.call.take
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Records a walkie burst as Opus packets while it is spoken (docs/VOICE_CALLS.md §3.11): the microphone through
 * [AudioRecord] at 16 kHz mono, from the source [TalkCapture] picks for the live call's [CaptureProfile] (C-3.11-5), with
 * the platform's noise suppressor on its session where that source runs none of its own ([suppressNoise]), through
 * [TalkGain], into the platform Opus encoder at [WalkiePolicy.BIT_RATE], each 20 ms packet handed on the moment it
 * comes out. There is no container, no file, and nothing on disk. The chat's
 * `VoiceRecorder` writes AAC through `MediaRecorder`, which at a speech rate is about twice Opus's size and buffers
 * besides.
 *
 * One burst at a time: `WalkieTalk` calls [start], [stop] and [cancel] under its lock. The capture loop runs on one IO
 * coroutine, which alone reads the microphone and feeds, drains and releases every encoder; what other threads ask of it
 * is answered at its next 20 ms frame.
 *
 * Hands-free (C-3.11-11), [monitor] keeps the microphone open between bursts and runs every frame's level, before the
 * gain, through a [VoxGate]. A burst the gate opens starts [VoxPolicy.PRE_ROLL_MS] back, out of a ring of the latest frames,
 * and is encoded at once but handed to the walkie ([Monitor.commit]) only once it is speech enough to send; one that ends
 * before is dropped, unsent. At [WalkiePolicy.MAX_BURST_MS] still speaking, it ends and the next opens on the next frame,
 * so a long turn loses nothing. A press on the armed monitor takes its microphone, and a press during a hands-free
 * burst being sent takes the burst over ([adopt]). The frames heard while the peer's burst may be on the loudspeaker
 * ([Monitor.hearing]) teach neither the gain nor the gate, and the gain learns only while a burst is sent or a press
 * holds the microphone ([VoxGate.steersGain]). A run of exact zeros on the call path teaches the fallback as
 * a burst does, and the monitor moves to the headset's microphone, or off it, between bursts.
 *
 * [nextFrom] is the debug bridge's stand-in for a voice: the next burst, or the next monitor until the file ends, reads
 * that file of raw PCM, paced as the microphone would deliver it, so a trial can send the same known sentence every time
 * (the transcript, C-3.11-9).
 *
 * [tap] sees each read of PCM before it goes to the encoder, so the transcript can write out what was spoken as the
 * model hears it without Opus (C-3.11-9); a tap that throws costs the transcript, never the burst.
 *
 * [capture] is the phone's call-microphone verdict (ADR 2026-10.kw29): its profile picks the phone's source, and a burst
 * on the call path that hands only zeros teaches it the fallback, unless [muted]. Without one the phone's microphone
 * records as on the fallback.
 */
internal class TalkRecorder(
    private val scope: CoroutineScope,
    private val tap: TalkPcmTap? = null,
    private val capture: CaptureHealth? = null,
    private val muted: () -> Boolean = { false },
) {
    /**
     * One burst's encoding, as the walkie holds it: its Opus [packets] as the encoder makes them, closed once its last is
     * out, and [ended]: true when its tail went out, false when it was dropped. [vox]: the gate opened it (C-3.11-11).
     */
    class Burst internal constructor(
        val vox: Boolean,
    ) {
        internal val channel = Channel<ByteArray>(Channel.UNLIMITED)
        internal val done = CompletableDeferred<Boolean>()

        val packets: ReceiveChannel<ByteArray> get() = channel

        val ended: Deferred<Boolean> get() = done
    }

    /** What the walkie answers when a hands-free burst is speech enough to send (C-3.11-11). */
    enum class Take {
        /** Sent: the walkie streams its packets from now. */
        ACCEPTED,

        /** Not yet (a burst before it is still ending): asked again at the next frame. */
        LATER,

        /** Never: it is dropped unsent. */
        REFUSED,
    }

    /** The armed microphone's way to the walkie (C-3.11-11). Read on the capture thread, so nothing here suspends. */
    interface Monitor {
        /** Whether a headset has the call's audio now (§3.13): the monitor moves to its microphone between bursts. */
        fun headset(): Boolean

        fun sensitivity(): VoxSensitivity

        /** Whether the peer's burst may be on the loudspeaker now: the gate stays shut and learns nothing meanwhile. */
        fun hearing(): Boolean

        /** The gate found [burst] speech enough to send. */
        fun commit(burst: Burst): Take

        /** The microphone was lost: the monitor has ended. */
        fun lost()
    }

    // The capture loop running now: a press's alone, or the armed monitor's.
    @Volatile
    private var loop: Loop? = null

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var peak = 0

    // One gain per microphone, kept across bursts and sessions (§3.11, C-3.11-5): the phone's and a headset's differ.
    private val gains = mapOf(false to TalkGain(), true to TalkGain())

    /**
     * Whether a burst asks for the platform's noise suppressor (`android.media.audiofx.NoiseSuppressor`) on its
     * microphone. On by default; the debug bridge turns it off for an A/B. A burst read from [nextFrom] has none.
     */
    @Volatile
    var suppressNoise = true

    /**
     * A `MediaRecorder.AudioSource` the phone's own microphone tries first, for the debug bridge's source A/B; null (the
     * default) keeps [TalkCapture.sources]. In memory only.
     */
    @Volatile
    var sourceFirst: Int? = null

    /** Whether [TalkGain] is applied; the debug bridge turns it off for an A/B. Off, it still reads the level for its log line. */
    @Volatile
    var applyGain = true

    /** The source the last microphone opened with; null before one, or for a burst read from [nextFrom]. */
    @Volatile
    var lastSource: Int? = null
        private set

    /**
     * What the last microphone had: `on`, `off` (not asked for), `call path` (its source runs the phone's own),
     * `unavailable` or `refused`; null before one.
     */
    @Volatile
    var suppressor: String? = null
        private set

    /** Raw 16 kHz mono 16-bit little-endian PCM the next burst, or the next monitor, reads in place of the microphone, once. */
    @Volatile
    var nextFrom: File? = null

    /** Whether the armed monitor holds the microphone (C-3.11-11). */
    val monitoring: Boolean get() = loop?.monitor != null

    /** The hands-free gate's state now, for the debug bridge; null with no monitor. */
    val gateState: VoxGate.State? get() = loop?.gate?.state

    /** Hands-free bursts the gate opened, and of them those it sent, dropped unsent, and rolled over at the limit. */
    val voxOpened = AtomicInteger()
    val voxCommitted = AtomicInteger()
    val voxDropped = AtomicInteger()
    val voxRolled = AtomicInteger()

    /** Milliseconds since the burst being recorded began, or 0 when none is. */
    fun elapsedMs(): Long = startedAt.let { if (it == 0L) 0L else System.currentTimeMillis() - it }

    /** The loudest sample since the last call, 0f..1f, for the live meter. */
    fun amplitude(): Float {
        val p = peak
        peak = 0
        return (p / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    /**
     * Begins a pressed burst; null when the microphone is busy or the encoder would not open. [headset]: a headset has
     * the call's audio (§3.13), so the burst is spoken into its microphone. With the monitor armed, the press takes the
     * monitor's microphone, and the gate stands aside until it ends; a hands-free burst not yet sent gives way to it.
     */
    suspend fun start(headset: Boolean): Burst? {
        loop?.let { running ->
            return if (running.monitor != null) running.ask(null) { Command.Press(it) } else null
        }
        lastSource = null
        val input = nextFrom?.also { nextFrom = null }?.let(::openFile) ?: openRecord(headset) ?: return null
        return pressOn(input, headset)
    }

    // A press's own loop on [input], with its encoder; null, and [input] closed, where either is refused.
    private fun pressOn(
        input: Capture,
        headset: Boolean,
    ): Burst? {
        val codec = openEncoder()
        val press = Loop(input, headset, monitor = null)
        val burst = Burst(vox = false)
        if (codec != null) press.attach(Encoding(burst, codec, accepted = true, held = null))
        if (codec != null && launch(press)) return burst
        runCatching { codec?.release() }
        input.close()
        return null
    }

    /** Ends [burst]: its last packets are flushed out of the encoder and handed on before this returns. */
    suspend fun stop(burst: Burst) = end(burst, keep = true)

    /** Ends [burst] without its tail. */
    suspend fun cancel(burst: Burst) = end(burst, keep = false)

    /**
     * A press takes over the hands-free [burst] being sent: the gate stands aside, so the burst ends on [stop] and never on
     * a silence. False when it is no longer the one being sent.
     */
    suspend fun adopt(burst: Burst): Boolean = loop?.takeIf { it.monitor != null }?.ask(false) { Command.Adopt(burst, it) } ?: false

    /**
     * Arms the microphone for hands-free bursts (C-3.11-11): it stays open, every frame goes through a [VoxGate], and a
     * burst the gate finds speech enough to send goes to [monitor]. False when the microphone is busy or would not open.
     */
    fun monitor(monitor: Monitor): Boolean {
        if (loop != null) return false
        lastSource = null
        val headset = monitor.headset()
        val input = nextFrom?.also { nextFrom = null }?.let(::openFile) ?: openRecord(headset) ?: return false
        if (!launch(Loop(input, headset, monitor))) {
            input.close()
            return false
        }
        return true
    }

    /** Disarms the microphone; a hands-free burst being sent ends with its tail. */
    suspend fun unmonitor() {
        val running = loop?.takeIf { it.monitor != null } ?: return
        running.ask(Unit) { Command.Quit(it) }
        running.job?.join()
    }

    private suspend fun end(
        burst: Burst,
        keep: Boolean,
    ) {
        val running = loop ?: return
        running.ask(Unit) { Command.End(burst, keep, it) }
        // A press's own loop closes the microphone as it ends; the next press waits for that.
        if (running.monitor == null) running.job?.join()
    }

    // One loop at a time, claimed atomically: a press and an arming that race both find the other's.
    private fun launch(running: Loop): Boolean {
        synchronized(this) {
            if (loop != null) return false
            loop = running
        }
        running.job = scope.launch(Dispatchers.IO) { running.run() }
        return true
    }

    /** What the capture loop is asked from other threads, answered at its next frame (or at once, once it has ended). */
    private sealed interface Command {
        class Press(
            val reply: CompletableDeferred<Burst?>,
        ) : Command

        class End(
            val burst: Burst,
            val keep: Boolean,
            val reply: CompletableDeferred<Unit>,
        ) : Command

        class Adopt(
            val burst: Burst,
            val reply: CompletableDeferred<Boolean>,
        ) : Command

        class Quit(
            val reply: CompletableDeferred<Unit>,
        ) : Command
    }

    /** A burst's encoder, its timestamps, and (a hands-free burst not yet sent) whether it is speech and its PCM so far. */
    private class Encoding(
        val burst: Burst,
        val codec: MediaCodec,
        var accepted: Boolean,
        var held: MutableList<ByteArray>?,
    ) {
        var committed = false
        var ptsUs = 0L
    }

    /**
     * One capture loop on one IO coroutine, which alone reads [input] and feeds, drains and releases every encoder: a
     * press's, which ends with its burst, or the armed monitor's, which runs every frame through [gate] and attaches an
     * encoder only while a burst wants one.
     */
    private inner class Loop(
        var input: Capture,
        var headset: Boolean,
        val monitor: Monitor?,
    ) {
        val gate = monitor?.let { VoxGate() }
        var job: Job? = null
        private val commands = ArrayDeque<Command>()
        private var alive = true
        private var quit: Command.Quit? = null
        private val info = MediaCodec.BufferInfo()

        // The latest frames, for a hands-free burst to open with (VoxPolicy.PRE_ROLL_MS).
        private val ring = ArrayDeque<ByteArray>()
        private var encoding: Encoding? = null

        // An encoder made ready between bursts, so a burst opens without the encoder's start on the capture thread;
        // tried once a burst, not every frame, where the phone refuses one.
        private var spare: MediaCodec? = null
        private var spareFailed = false

        // What the microphone gave a press's loop, before the gain, for a dead call path; a monitor's run of zeros.
        private var samples = 0L
        private var burstPeak = 0
        private var zeros = 0L
        private var frames = 0

        @Volatile
        var reopen = false

        /** Asks the loop for [make]'s answer, or [dead] once it has ended. */
        suspend fun <T> ask(
            dead: T,
            make: (CompletableDeferred<T>) -> Command,
        ): T {
            val reply = CompletableDeferred<T>()
            val command = make(reply)
            synchronized(this) {
                if (!alive) return dead
                commands.addLast(command)
            }
            return reply.await()
        }

        fun attach(e: Encoding) {
            encoding = e
            gains.getValue(headset).newBurst()
            peak = 0
            startedAt = System.currentTimeMillis()
        }

        @Suppress("LoopWithTooManyJumpStatements") // a quit or an ended press, a lost microphone, or an input that ended
        fun run() {
            val pcm = ByteArray(FRAME_BYTES)
            try {
                while (true) {
                    if (!obey()) break
                    if (monitor != null && !moveMicrophone()) break
                    val n = input.read(pcm, 0, pcm.size)
                    if (n < 0 && !readOn()) break
                    if (n > 0) frame(pcm, n)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // The platform codec and the capture throw what they like; either way the burst ends with what went out.
                Log.w(TAG, "walkie capture ended: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                close()
            }
        }

        // The input ended: an armed monitor's file gives way to the microphone; anything else ends the loop.
        private fun readOn(): Boolean = input is PacedFile && monitor != null && reopenOn(headset)

        // Answers what was asked since the last frame; false once the loop is to end.
        private fun obey(): Boolean {
            while (true) {
                val command = synchronized(this) { commands.removeFirstOrNull() } ?: return true
                when (command) {
                    is Command.Press -> {
                        command.reply.complete(press())
                    }

                    is Command.Adopt -> {
                        val e = encoding?.takeIf { it.burst === command.burst && it.accepted }
                        if (e != null) gate?.hold()
                        command.reply.complete(e != null)
                    }

                    is Command.End -> {
                        val ours = encoding?.burst === command.burst
                        if (ours) {
                            end(keep = command.keep)
                            gate?.release()
                        }
                        command.reply.complete(Unit)
                        if (ours && monitor == null) return false
                    }

                    is Command.Quit -> {
                        quit = command
                        return false
                    }
                }
            }
        }

        // A press on the armed monitor: it takes the microphone; a hands-free burst not yet sent is dropped for it.
        private fun press(): Burst? {
            encoding?.let { e ->
                if (e.accepted) return null
                end(keep = false)
            }
            val codec = takeEncoder() ?: return null
            gate?.hold()
            return Burst(vox = false).also { attach(Encoding(it, codec, accepted = true, held = null)) }
        }

        // Between bursts only: onto a headset's microphone or off it (§3.13), or onto the fallback a dead path taught.
        private fun moveMicrophone(): Boolean {
            if (encoding != null) return true
            val m = monitor ?: return true
            val want = if (++frames % ROUTE_CHECK_FRAMES == 0) m.headset() else headset
            if (!reopen && want == headset) return true
            reopen = false
            return reopenOn(want)
        }

        private fun reopenOn(want: Boolean): Boolean {
            end(keep = true)
            input.close()
            // None opened: the loop ends, and its close tells the walkie.
            val next = openRecord(want) ?: return false
            input = next
            headset = want
            zeros = 0L
            ring.clear()
            gate?.release()
            return true
        }

        private fun frame(
            pcm: ByteArray,
            n: Int,
        ) {
            val raw = peakOf(pcm, n)
            val hearing = monitor?.hearing() == true
            val g = gate
            // Off, the gain still reads the frame (on a copy) so its log line says what the microphone gave.
            val level = gains.getValue(headset).take(pcm, n, g, hearing, applyGain)
            peak = maxOf(peak, if (applyGain) peakOf(pcm, n) else raw)
            val frame = pcm.copyOf(n)
            if (g == null) {
                samples += n / PCM_BYTES
                burstPeak = maxOf(burstPeak, raw)
                feed(frame)
                return
            }
            val m = monitor ?: return
            watchDead(raw, n, hearing)
            g.sensitivity = m.sensitivity()
            act(g.frame(level, hearing, muted()), frame)
            offer()
            ring.addLast(frame)
            while (ring.size > VoxPolicy.RING_FRAMES) ring.removeFirst()
            if (encoding == null && spare == null && !spareFailed) spare = openEncoder().also { spareFailed = it == null }
        }

        // What the gate said of [frame]: open a burst on it with its pre-roll, mark it speech, end it, or roll over.
        private fun act(
            action: VoxGate.Action,
            frame: ByteArray,
        ) {
            when (action) {
                is VoxGate.Action.Open -> {
                    open(committed = false)
                    ring.takeLast(action.preRoll).forEach(::feed)
                    feed(frame)
                }

                VoxGate.Action.Commit -> {
                    encoding?.committed = true
                    feed(frame)
                }

                is VoxGate.Action.Close -> {
                    end(keep = action.kept)
                }

                VoxGate.Action.Roll -> {
                    voxRolled.incrementAndGet()
                    end(keep = true)
                    open(committed = true)
                    feed(frame)
                }

                VoxGate.Action.None -> {
                    feed(frame)
                }
            }
        }

        // A hands-free burst opens: an encoder, and (for the debug build's own line) its PCM held until it is sent.
        private fun open(committed: Boolean) {
            val codec = takeEncoder() ?: return
            voxOpened.incrementAndGet()
            attach(Encoding(Burst(vox = true), codec, accepted = false, held = tap?.let { mutableListOf() }))
            encoding?.committed = committed
        }

        private fun takeEncoder(): MediaCodec? = spare?.also { spare = null } ?: openEncoder()

        // A hands-free burst that is speech asks the walkie, at every frame until it answers.
        private fun offer() {
            val e = encoding ?: return
            val m = monitor ?: return
            if (!e.burst.vox || e.accepted || !e.committed) return
            when (m.commit(e.burst)) {
                Take.ACCEPTED -> {
                    e.accepted = true
                    voxCommitted.incrementAndGet()
                    e.held?.forEach { tapped(it, it.size) }
                    e.held = null
                }

                // Asked again at the next frame.
                Take.LATER -> {}

                Take.REFUSED -> {
                    end(keep = false)
                    gate?.release()
                }
            }
        }

        // One frame into the burst being recorded, if one is.
        private fun feed(frame: ByteArray) {
            val e = encoding ?: return
            if (e.accepted) tapped(frame, frame.size) else e.held?.add(frame)
            if (!queue(e.codec, frame, frame.size, e.ptsUs, 0)) {
                end(keep = true)
                return
            }
            e.ptsUs += frame.size / PCM_BYTES * US_PER_S / WalkiePolicy.SAMPLE_RATE
            drain(e.codec, info, { e.burst.channel.trySend(it) }, untilEnd = false)
        }

        // Ends the burst being recorded: its tail flushed out where [keep] and the walkie took it, else dropped.
        private fun end(keep: Boolean) {
            val e = encoding ?: return
            encoding = null
            spareFailed = false
            startedAt = 0L
            val kept = keep && e.accepted
            if (e.burst.vox && !e.accepted) voxDropped.incrementAndGet()
            try {
                if (kept && queue(e.codec, ByteArray(0), 0, e.ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                    drain(e.codec, info, { e.burst.channel.trySend(it) }, untilEnd = true)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") x: Exception,
            ) {
                Log.w(TAG, "walkie encoder flush failed: ${x.javaClass.simpleName}: ${x.message}")
            } finally {
                runCatching { e.codec.stop() }
                runCatching { e.codec.release() }
                e.burst.channel.close()
                e.burst.done.complete(kept)
            }
            if (e.accepted) logGain()
        }

        // C-3.13-13 on the armed microphone: a second of exact zeros on the call path, not muted, silenced or the peer's.
        private fun watchDead(
            raw: Int,
            n: Int,
            hearing: Boolean,
        ) {
            val mic = input as? Microphone ?: return
            if (raw > 0 || hearing || muted()) {
                zeros = 0L
                return
            }
            zeros += n / PCM_BYTES
            if (!TalkCapture.deadMicrophone(mic.source, headset, zeros, 0) || mic.silenced()) return
            zeros = 0L
            val health = capture ?: return
            Log.w(TAG, "walkie in: armed source ${mic.source} captured only zeros; the phone's microphone takes the fallback")
            scope.launch {
                health.learnedSilent()
                reopen = true
            }
        }

        private fun logGain() {
            val gain = gains.getValue(headset)
            val heard = gain.speechDb?.let { "speech at %.0f dBFS".format(Locale.ROOT, it) } ?: "no speech"
            val off = if (applyGain) "" else " (off, not applied)"
            Log.i(TAG, "walkie gain: $heard, gain %+.1f dB%s, %d frame(s) limited".format(Locale.ROOT, gain.gainDb, off, gain.limited))
        }

        private fun close() {
            runCatching { end(keep = true) }
            runCatching { spare?.release() }
            spare = null
            if (monitor == null) deadRoute(input, headset, samples, burstPeak)
            input.close()
            synchronized(this) {
                alive = false
                for (command in commands) {
                    when (command) {
                        is Command.Press -> command.reply.complete(null)
                        is Command.Adopt -> command.reply.complete(false)
                        is Command.End -> command.reply.complete(Unit)
                        is Command.Quit -> command.reply.complete(Unit)
                    }
                }
                commands.clear()
            }
            synchronized(this@TalkRecorder) { if (loop === this) loop = null }
            // An armed microphone that ended unasked (a lost route, a failed read, a throw) disarms the walkie.
            if (quit == null) monitor?.lost()
            quit?.reply?.complete(Unit)
        }
    }

    // C-3.13-13 on a burst: the phone's call path handed only zeros, so the next bursts and calls take the fallback.
    private fun deadRoute(
        record: Capture,
        headset: Boolean,
        samples: Long,
        burstPeak: Int,
    ) {
        val mic = record as? Microphone ?: return
        val health = capture ?: return
        if (!TalkCapture.deadMicrophone(mic.source, headset, samples, burstPeak) || muted() || mic.silenced()) return
        Log.w(TAG, "walkie in: source ${mic.source} captured only zeros; the phone's microphone takes the fallback")
        scope.launch { health.learnedSilent() }
    }

    private fun tapped(
        pcm: ByteArray,
        size: Int,
    ) {
        val t = tap ?: return
        runCatching { t.pcm(ByteBuffer.wrap(pcm, 0, size).order(ByteOrder.LITTLE_ENDIAN), WalkiePolicy.SAMPLE_RATE, 1) }
            .onFailure { Log.w(TAG, "walkie transcript mic tap failed: ${it.javaClass.simpleName}") }
    }

    private fun queue(
        codec: MediaCodec,
        pcm: ByteArray,
        size: Int,
        ptsUs: Long,
        flags: Int,
    ): Boolean {
        repeat(INPUT_TRIES) {
            val index = codec.dequeueInputBuffer(DEQUEUE_US)
            if (index >= 0) {
                codec.getInputBuffer(index)?.apply {
                    clear()
                    put(pcm, 0, size)
                }
                codec.queueInputBuffer(index, 0, size, ptsUs, flags)
                return true
            }
        }
        return false
    }

    private fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        onPacket: (ByteArray) -> Unit,
        untilEnd: Boolean,
    ) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) DEQUEUE_US else 0L)
            if (index < 0) {
                if (!untilEnd || ++idle >= END_TRIES) return
                continue
            }
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
                codec.getOutputBuffer(index)?.let { out ->
                    out.position(info.offset)
                    onPacket(ByteArray(info.size).also { out.get(it) })
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** Where a burst's PCM comes from: the microphone, or [nextFrom]'s file. */
    private interface Capture {
        /** Fills [buffer] up to [size] bytes; the count read, or -1 at the end. */
        fun read(
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ): Int

        fun close()
    }

    private class Microphone(
        private val record: AudioRecord,
        private val effect: NoiseSuppressor?,
        val source: Int,
    ) : Capture {
        /** Whether the system silenced this record (a call with no foreground service): zeros that are not the route's. */
        fun silenced(): Boolean = runCatching { record.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)

        override fun read(
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ) = record.read(buffer, offset, size)

        // The effect first: it is attached to the record's session, and outliving it leaks the native effect.
        override fun close() {
            runCatching { effect?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    // A file read no faster than the microphone would deliver it, so the burst streams as a spoken one does.
    private class PacedFile(
        private val input: InputStream,
    ) : Capture {
        private val began = System.nanoTime()
        private var bytes = 0L

        override fun read(
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ): Int {
            val n = input.read(buffer, offset, size)
            if (n <= 0) return -1
            bytes += n
            val dueNs = bytes / PCM_BYTES * NS_PER_S / WalkiePolicy.SAMPLE_RATE
            val aheadMs = (dueNs - (System.nanoTime() - began)) / NS_PER_MS
            if (aheadMs > 0) Thread.sleep(aheadMs)
            return n
        }

        override fun close() {
            runCatching { input.close() }
        }
    }

    private fun openFile(file: File): Capture? =
        runCatching { PacedFile(file.inputStream().buffered()) }
            .onFailure { Log.w(TAG, "walkie test PCM would not open: ${it.javaClass.simpleName}") }
            .getOrNull()

    /**
     * The first of [TalkCapture.sources] that records: on the standard profile the phone's own call path
     * (VOICE_COMMUNICATION), on the fallback VOICE_RECOGNITION, MIC where the HAL refuses either. With a [headset] on the
     * call, VOICE_COMMUNICATION: the one source sure to follow the call's route (Telecom's, §3.13) to the headset's
     * microphone, a Bluetooth one's over its call link.
     */
    @SuppressLint("MissingPermission") // the call screen holds RECORD_AUDIO before a walkie session can connect
    private fun openRecord(headset: Boolean): Capture? {
        val min = AudioRecord.getMinBufferSize(WalkiePolicy.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val profile = capture?.choice?.value?.profile ?: CaptureProfile.FALLBACK
        for (source in TalkCapture.sources(headset, profile, sourceFirst)) {
            val record =
                runCatching {
                    AudioRecord(
                        source,
                        WalkiePolicy.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        // Half a second: the capture thread may pause while an encoder starts, and the frames wait.
                        maxOf(min, FRAME_BYTES * RECORD_FRAMES),
                    )
                }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { record.startRecording() }
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val effect = suppressorOn(record, source)
                    lastSource = source
                    Log.i(
                        TAG,
                        "walkie in: source $source, ${record.routedDevice?.type?.let(::deviceName) ?: "unrouted"}, " +
                            "noise suppressor $suppressor",
                    )
                    return Microphone(record, effect, source)
                }
            }
            runCatching { record.release() }
        }
        Log.w(TAG, "walkie microphone would not open")
        return null
    }

    /**
     * The platform's noise suppressor on [record]'s session, enabled, where [source] runs none of its own
     * ([TalkCapture.suppress]), [suppressNoise] asks for it and the phone implements one (C-3.11-5); null otherwise, with
     * [suppressor] saying why. `VOICE_RECOGNITION` carries no pre-processing, so there this is the only suppression a
     * burst gets; `VOICE_COMMUNICATION` brings the phone's call processing, and a second pass costs the transcript.
     */
    private fun suppressorOn(
        record: AudioRecord,
        source: Int,
    ): NoiseSuppressor? {
        if (!TalkCapture.suppress(source)) {
            suppressor = "call path"
            return null
        }
        if (!suppressNoise) {
            suppressor = "off"
            return null
        }
        if (!NoiseSuppressor.isAvailable()) {
            suppressor = "unavailable"
            return null
        }
        val effect = runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()
        val on = effect != null && runCatching { effect.setEnabled(true) == NoiseSuppressor.SUCCESS && effect.enabled }.getOrDefault(false)
        if (!on) runCatching { effect?.release() }
        suppressor = if (on) "on" else "refused"
        return effect.takeIf { on }
    }

    private fun openEncoder(): MediaCodec? =
        runCatching {
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, WalkiePolicy.SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, WalkiePolicy.BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * BUFFER_FRAMES)
                }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                // Constant bitrate keeps every packet one size, so a chunk's size says nothing about what was said, to
                // a radio carrier or a relay (docs/VOICE_CALLS.md §2.7). Asked for only where the encoder says it can:
                // elsewhere its default stands, and a cast's padding still keeps a relay's view to a size class.
                val caps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_OPUS).encoderCapabilities
                if (caps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.onFailure { Log.w(TAG, "walkie Opus encoder failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    private fun peakOf(
        pcm: ByteArray,
        size: Int,
    ): Int {
        var max = 0
        var i = 0
        while (i + 1 < size) {
            val sample = (pcm[i].toInt() and BYTE) or (pcm[i + 1].toInt() shl BYTE_BITS)
            max = maxOf(max, abs(sample.toShort().toInt()))
            i += PCM_BYTES
        }
        return max
    }

    private companion object {
        const val TAG = "KnitCall"
        const val PCM_BYTES = 2

        // 20 ms of 16 kHz mono PCM: one Opus frame's worth.
        const val FRAME_BYTES = WalkiePolicy.SAMPLE_RATE / 50 * PCM_BYTES
        const val BUFFER_FRAMES = 4
        const val RECORD_FRAMES = 25

        // How often the armed monitor asks whether a headset has the call: every half second.
        const val ROUTE_CHECK_FRAMES = 25
        const val US_PER_S = 1_000_000L
        const val NS_PER_S = 1_000_000_000L
        const val NS_PER_MS = 1_000_000L
        const val DEQUEUE_US = 10_000L
        const val INPUT_TRIES = 20
        const val END_TRIES = 20
        const val MAX_AMPLITUDE = 32_767f
        const val BYTE = 0xFF
        const val BYTE_BITS = 8
    }
}
