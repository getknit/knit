package app.getknit.knit.call

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The walkie's transcript (docs/VOICE_CALLS.md §3.11, C-3.11-9): each burst this phone hears is written out as text on
 * the call screen by a streaming speech model run here, on the receiver, fed as the burst plays — so its words show
 * while the other phone is still talking, and are final a moment after the burst ends — and each burst this phone
 * speaks, from the microphone's PCM before Opus, so the two phones' lines side by side show what the codec cost the
 * model. Nothing about it is on the wire: the sender sends the same frames, and the text never leaves the phone or
 * outlives the session.
 */
object SttPolicy {
    /** What the model takes: 16 kHz mono, as the burst was captured before Opus. */
    const val SAMPLE_RATE = 16_000

    /** A burst shorter than this is a slipped thumb, not speech: its text, if any, is dropped. */
    const val MIN_SPEECH_MS = 300L

    /**
     * How much new audio a stream takes before the model looks at it again ([PassGate]): the live text's cadence.
     * Moonshine's own default; the streaming encoder has cached what came before, so a look costs the new audio and
     * a decode, not the whole burst.
     */
    const val UPDATE_MS = 500L

    /** The most a slow phone stretches the cadence to ([PassGate]), as a multiple of [UPDATE_MS]. */
    const val MAX_UPDATE_FACTOR = 10

    /** The transcript keeps this many bursts; older ones fall off the top. */
    const val MAX_LINES = 50

    /** The engine is released after this long without a burst to transcribe: its native heap is the cost. */
    const val IDLE_UNLOAD_MS = 2 * 60_000L
}

/**
 * PCM on its way through the walkie — the player's decoded audio as it goes to the speaker, or the recorder's as it
 * goes to the encoder: 16-bit native-order samples in [pcm] between its position and its limit, at [rate] Hz over
 * [channels] interleaved channels. Called on the player's or the recorder's thread; the buffer is the receiver's to
 * read, never to keep.
 */
fun interface TalkPcmTap {
    fun pcm(
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    )
}

/**
 * A streaming speech model: one [SpeechStream] per burst, fed [SttPolicy.SAMPLE_RATE] mono samples in −1..1 as they
 * arrive. [open] answers null when the model could not run (it would not load, or faulted). A [continuous] stream — a
 * live call's captions (C-3.7-5) — always has the model's voice detector split it at pauses; a walkie burst's follows
 * the transcript's line mode.
 */
fun interface SpeechToText {
    suspend fun open(continuous: Boolean): SpeechStream?

    /** Lets the model go now, with no stream open on it; the next [open] loads it again. */
    suspend fun release() {}
}

/**
 * One burst inside the model. The transcriber calls it from one coroutine at a time, never two at once; every call may
 * run the model, so each suspends, and none is made on a thread that must not wait. A call that answers null or false is the model
 * failing on this burst: the stream is closed by then, and every later call is a no-op that fails again.
 */
interface SpeechStream {
    /** Takes more of the burst's audio; false when the model failed on it. */
    suspend fun add(samples: FloatArray): Boolean

    /** Looks at everything taken so far: what it has heard, or null when the model failed. */
    suspend fun look(): Heard?

    /** The burst has ended: the last look, final, and the stream closed. Null when the model failed. */
    suspend fun finish(): Heard?

    /** Drops the stream with no last look. Idempotent, and a no-op once [finish] has run. */
    suspend fun close()
}

/**
 * What a stream has heard so far: [settled], the text of the stretches the model has closed at a pause and will not
 * change again, and [live], its current guess at the stretch still open. After [SpeechStream.finish] nothing is live.
 */
data class Heard(
    val settled: String,
    val live: String = "",
) {
    /** Both, as the line shows them. */
    val text: String get() = listOf(settled, live).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * When a stream is worth another look: once [SttPolicy.UPDATE_MS] of audio has come in since the last, or — on a phone
 * that cannot keep up — once as much audio as the last look took to make, up to [SttPolicy.MAX_UPDATE_FACTOR] times the
 * cadence. A look's cost is mostly a fixed start-up, so a slow phone that kept the cadence would fall further behind
 * every look; earning each look turns that into larger, rarer ones that stay within a look or two of the talker.
 * Moonshine's own `isUpdateDue`, here so it is tested. Pure; not thread-safe.
 */
class PassGate(
    private val intervalMs: Long = SttPolicy.UPDATE_MS,
) {
    private var pendingMs = 0.0
    private var lastLookMs = 0L

    /** [ms] more audio has been taken; true when a look is due now, and the count starts again. */
    fun fed(ms: Double): Boolean {
        pendingMs += ms
        val needed = lastLookMs.coerceIn(intervalMs, intervalMs * SttPolicy.MAX_UPDATE_FACTOR)
        if (pendingMs < needed) return false
        pendingMs = 0.0
        return true
    }

    /** A look took [ms] of wall time. */
    fun took(ms: Long) {
        lastLookMs = ms
    }
}

/**
 * One burst's audio on its way to the model: the player's PCM, whatever its rate and layout, mixed to mono and brought
 * to [SttPolicy.SAMPLE_RATE] as it comes — by averaging whole blocks where the rate is a multiple (the decoder's 48 kHz,
 * three to one), by linear interpolation where it is not — and held until [drain] takes it. The whole burst is capped
 * at [capMs] so a stuck one cannot grow without bound. At 8 kbps Opus codes speech narrowband, so a block average's
 * soft low-pass costs nothing the model would hear. Pure (`BurstPcmTest`); not thread-safe.
 */
class BurstPcm(
    capMs: Long = WalkiePolicy.MAX_HEARD_BURST_MS + CAP_SLACK_MS,
) {
    private val cap = (capMs * SttPolicy.SAMPLE_RATE / MS_PER_S).toInt()
    private var held = FloatArray(INITIAL_SAMPLES)
    private var heldSize = 0
    private var total = 0

    // The block being averaged (an integer ratio), or the resampler's phase and last sample (any other).
    private var blockSum = 0f
    private var blockCount = 0
    private var phase = 0.0
    private var previous = 0f
    private var rate = 0

    /** How much audio the burst has had, drained or not, in milliseconds. */
    val audioMs: Long get() = total * MS_PER_S / SttPolicy.SAMPLE_RATE

    /** Takes [pcm]'s samples between its position and its limit; [pcm] itself is left as it was. */
    fun add(
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    ) {
        if (rate <= 0 || channels <= 0) return
        if (rate != this.rate) {
            this.rate = rate
            blockSum = 0f
            blockCount = 0
            phase = 0.0
        }
        val shorts = pcm.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        while (shorts.remaining() >= channels) {
            var sum = 0f
            repeat(channels) { sum += shorts.get() }
            take(sum / channels / FULL_SCALE)
        }
    }

    /** The audio held since the last drain, at [SttPolicy.SAMPLE_RATE], and no longer held here. */
    fun drain(): FloatArray {
        val out = held.copyOf(heldSize)
        heldSize = 0
        return out
    }

    private fun take(sample: Float) {
        val r = rate
        if (r % SttPolicy.SAMPLE_RATE == 0) {
            val factor = r / SttPolicy.SAMPLE_RATE
            blockSum += sample
            if (++blockCount < factor) return
            put(blockSum / factor)
            blockSum = 0f
            blockCount = 0
            return
        }
        // Any other rate: an output sample every rate/16000 inputs, interpolated between the two around it.
        val step = r.toDouble() / SttPolicy.SAMPLE_RATE
        while (phase < 1.0) {
            put((previous + (sample - previous) * phase).toFloat())
            phase += step
        }
        phase -= 1.0
        previous = sample
    }

    private fun put(sample: Float) {
        if (total >= cap) return
        if (heldSize == held.size) held = held.copyOf(minOf(cap, held.size * 2))
        held[heldSize++] = sample
        total++
    }

    private companion object {
        const val MS_PER_S = 1_000L
        const val FULL_SCALE = 32_768f
        const val INITIAL_SAMPLES = SttPolicy.SAMPLE_RATE

        // A burst ends a little after the recorder's cap: the encoder's tail and the last chunk's rounding.
        const val CAP_SLACK_MS = 2_000L
    }
}

/**
 * One burst, as the transcript shows it: [burst] is the sender's number, [heardAt] when it began to play — or, [mine],
 * a burst this phone spoke, numbered by its own count and stamped when the button went down, transcribed from the
 * microphone before Opus touched it. [live] is what the model has heard so far, while the burst is still coming
 * ([State.Listening]) or being finished ([State.Pending]).
 */
data class TranscriptLine(
    val burst: Int,
    val heardAt: Long,
    val state: State,
    val live: String = "",
    // For the diagnostics, 0 until known: the burst's audio, the time from its end to its final text, how many looks the
    // model took while it came, and the model's time over it all (its share of the phone).
    val audioMs: Long = 0,
    val tookMs: Long = 0,
    val looks: Int = 0,
    val modelMs: Long = 0,
    val mine: Boolean = false,
    // While [live] is shown: the part of the words the model has closed at a pause and will not change (a live call's
    // captions, or a burst split at pauses); [live] is then the rest.
    val settled: String = "",
) {
    /** The words so far while the line is still coming: [settled], then [live]. */
    val words: String get() = listOf(settled, live).filter { it.isNotBlank() }.joinToString(" ")

    sealed interface State {
        /** The burst is playing, or being spoken. */
        data object Listening : State

        /** It has ended; the model is finishing it. */
        data object Pending : State

        data class Text(
            val text: String,
        ) : State

        /** Too short to be speech, or the model heard none. */
        data object NoSpeech : State

        /** No speech model on this phone when the burst ended. */
        data object NoModel : State

        /** The model would not load, or failed on this burst. */
        data object Failed : State
    }
}

/** [lines] with [line] in place of the line for its burst, or after the rest; at most [max], oldest dropped. */
fun withLine(
    lines: List<TranscriptLine>,
    line: TranscriptLine,
    max: Int = SttPolicy.MAX_LINES,
): List<TranscriptLine> {
    val at = lines.indexOfFirst { it.sameBurst(line) }
    val next = if (at >= 0) lines.toMutableList().also { it[at] = line } else lines + line
    return if (next.size > max) next.takeLast(max) else next
}

// Each side numbers its own bursts, so a burst is its number and whose it is.
private fun TranscriptLine.sameBurst(other: TranscriptLine) = burst == other.burst && mine == other.mine

/**
 * Writes the peer's walkie bursts out as text (C-3.11-9), as they play. The player's loop opens a burst ([begin]) as it
 * starts to play it, its decoded PCM comes through [pcm] (this is the player's [TalkPcmTap]) and is fed to a stream of
 * the model's as it comes, the line showing what the model has heard so far each time it looks ([PassGate]), and the
 * stream is finished when the burst has played to its end ([end]). All of it runs on [dispatcher], one model call at a
 * time behind a fair lock, so a burst's audio reaches the model in order and the player's thread never waits on it.
 * [ready] says whether the model is on the phone; a burst heard while it is not is held, and fed in full should the
 * model arrive before the burst ends — else the burst is marked [TranscriptLine.State.NoModel] and its audio dropped.
 * [enabled] is the person's switch: a burst that begins while it is off gets no line and none of its audio is taken.
 *
 * The bursts this phone speaks go the same way through [mic], the recorder's tap, opened and ended with `mine = true`
 * — the microphone's PCM before the encoder, so set beside the other phone's line for the same burst it shows what
 * Opus cost the model and what the model gets wrong on its own. One of each can be open at once: talking holds the
 * playback mid-burst, it does not end it.
 *
 * A session ([reset]) starts the transcript afresh; a burst of the last one still with the model is closed unheard. The
 * text is held here and nowhere else: it is never logged, stored or sent — save to [clips], a debug build's lab switch,
 * which keeps each finished burst's audio and line while it is on. Thread-safe: [pcm] comes on the player's thread and
 * [mic] on the recorder's, the rest from the player's loop, the talk button and the call screen.
 */
class WalkieTranscriber(
    private val speech: SpeechToText,
    private val ready: () -> Boolean,
    scope: CoroutineScope,
    // The person's switch (`TranscriptStore.enabled`), asked as each burst begins: off, the burst is not taken.
    private val enabled: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val clips: WalkieClips? = null,
) : TalkPcmTap {
    private val _lines = MutableStateFlow<List<TranscriptLine>>(emptyList())

    /** This session's bursts, oldest first. */
    val lines: StateFlow<List<TranscriptLine>> = _lines.asStateFlow()

    private val workScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private val serial = Mutex()

    /** One burst on its way through: its audio under [lock], its stream under [serial]. */
    private class Burst(
        val number: Int,
        val mine: Boolean,
        val session: Int,
        // Under [lock], like [audio]: everything drained from it, while [clips] keeps this burst.
        val tape: SampleTape?,
    ) {
        // Under [lock]: the audio not yet fed, whether a feed is already on its way, and whether the burst was dropped.
        val audio = BurstPcm()
        var queued = false
        var dropped = false

        // Under [serial]: the model's stream, once open, and what the model has cost so far.
        var stream: SpeechStream? = null
        var failed = false
        val gate = PassGate()
        var looks = 0
        var modelMs = 0L
    }

    // The burst playing now, the one being spoken, and the session's number, which every piece of work must still match.
    private var heard: Burst? = null
    private var spoken: Burst? = null
    private var session = 0

    /** The recorder's tap: the microphone's PCM for the burst being spoken. */
    val mic = TalkPcmTap { pcm, rate, channels -> take(mine = true, pcm, rate, channels) }

    /** A new session: an empty transcript, and nothing from the last one still to come. */
    fun reset() {
        val gone =
            synchronized(lock) {
                session++
                listOfNotNull(heard, spoken).onEach { it.dropped = true }.also {
                    heard = null
                    spoken = null
                }
            }
        _lines.value = emptyList()
        gone.forEach(::closeLater)
    }

    /** The burst [burst] has begun to play, or ([mine]) to be spoken. */
    fun begin(
        burst: Int,
        mine: Boolean = false,
    ) {
        // Switched off, the slot is still cleared, so the burst before never takes this one's audio.
        val on = enabled()
        val replaced =
            synchronized(lock) {
                val before = if (mine) spoken else heard
                before?.dropped = true
                val next = if (on) Burst(burst, mine, session, tape = SampleTape().takeIf { clips?.on == true }) else null
                if (mine) spoken = next else heard = next
                before
            }
        replaced?.let(::closeLater)
        if (!on) return
        _lines.update { withLine(it, TranscriptLine(burst, clock(), TranscriptLine.State.Listening, mine = mine)) }
    }

    override fun pcm(
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    ) = take(mine = false, pcm, rate, channels)

    private fun take(
        mine: Boolean,
        pcm: ByteBuffer,
        rate: Int,
        channels: Int,
    ) {
        val burst =
            synchronized(lock) {
                val b = (if (mine) spoken else heard) ?: return
                b.audio.add(pcm, rate, channels)
                if (b.queued) return
                b.queued = true
                b
            }
        workScope.launch(dispatcher) { serial.withLock { feed(burst) } }
    }

    /** The burst [burst] has played, or ([mine]) been spoken, to its end: the model finishes it. */
    fun end(
        burst: Int,
        mine: Boolean = false,
    ) {
        val b =
            synchronized(lock) {
                val b = (if (mine) spoken else heard)?.takeIf { it.number == burst } ?: return
                if (mine) spoken = null else heard = null
                b
            }
        val endedAt = clock()
        val audioMs = synchronized(lock) { b.audio.audioMs }
        val state =
            when {
                audioMs < SttPolicy.MIN_SPEECH_MS -> TranscriptLine.State.NoSpeech
                !ready() -> TranscriptLine.State.NoModel
                else -> TranscriptLine.State.Pending
            }
        updateLine(b) { it.copy(state = state, audioMs = audioMs, live = if (state == TranscriptLine.State.Pending) it.live else "") }
        if (state != TranscriptLine.State.Pending) {
            synchronized(lock) { b.dropped = true }
            closeLater(b)
            // A burst heard with no model on the phone is still one to score a model on; a slipped thumb is not.
            if (state == TranscriptLine.State.NoModel) keepClip(b)
            return
        }
        workScope.launch(dispatcher) { serial.withLock { finish(b, endedAt) } }
    }

    /** The session ended with a burst still playing, or ([mine]) a spoken one was dropped: drop it, and its line with it. */
    fun abandon(mine: Boolean = false) {
        val b =
            synchronized(lock) {
                val b = (if (mine) spoken else heard) ?: return
                if (mine) spoken = null else heard = null
                b.dropped = true
                b
            }
        _lines.update { lines ->
            lines.filterNot { it.burst == b.number && it.mine == mine && it.state == TranscriptLine.State.Listening }
        }
        closeLater(b)
    }

    /**
     * The call is over: a burst still open either way is dropped, and the model is let go once the work queued for it
     * — a burst ended just before still finishes, and every dropped stream is closed — has run. Closing a stream after
     * the model has gone would load it again only to free the stream, so the release waits its turn on [serial]. The
     * lines stay until the next session's [reset].
     */
    fun release() {
        abandon()
        abandon(mine = true)
        workScope.launch(dispatcher) { serial.withLock { speech.release() } }
    }

    // Under [serial]: the audio come in since the last feed, to the model — opening its stream first, once the model is
    // on the phone — and, unless the burst is about to be finished anyway, a look when one is due.
    private suspend fun feed(
        b: Burst,
        look: Boolean = true,
    ) {
        val samples =
            synchronized(lock) {
                b.queued = false
                if (b.dropped || b.session != session || !ready()) return
                b.audio.drain().also { b.tape?.add(it) }
            }
        val stream = streamOf(b) ?: return
        if (samples.isEmpty()) return
        if (!timed(b) { stream.add(samples) }) return fail(b)
        if (!look) return
        if (!b.gate.fed(samples.size * MS_PER_S.toDouble() / SttPolicy.SAMPLE_RATE)) return
        val began = clock()
        val heard = timed(b) { stream.look() } ?: return fail(b)
        b.gate.took(clock() - began)
        b.looks++
        updateLine(b) { if (it.state == TranscriptLine.State.Listening) it.copy(live = heard.text) else it }
    }

    // Under [serial]: what is left of the burst to the model, and its last look as the line's text.
    private suspend fun finish(
        b: Burst,
        endedAt: Long,
    ) {
        if (b.session != synchronized(lock) { session }) {
            b.stream?.close()
            b.stream = null
            return
        }
        feed(b, look = false)
        val heard = streamOf(b)?.let { stream -> timed(b) { stream.finish() } }
        b.stream = null
        val took = clock() - endedAt
        val state =
            when {
                heard == null -> TranscriptLine.State.Failed
                heard.text.isBlank() -> TranscriptLine.State.NoSpeech
                else -> TranscriptLine.State.Text(heard.text)
            }
        // Timings only: the text itself is never logged.
        val audioMs = synchronized(lock) { b.audio.audioMs }
        log(
            "walkie: transcribed ${if (b.mine) "spoken" else "heard"} burst ${b.number}: $audioMs ms of audio, " +
                "${b.looks} look(s), ${b.modelMs} ms in the model, final $took ms after its end, ${state.label()}",
        )
        updateLine(b) { it.copy(state = state, live = "", tookMs = took, looks = b.looks, modelMs = b.modelMs) }
        keepClip(b)
    }

    // [b]'s whole audio and its line as it stands, to [clips] — if it kept this burst and the burst is still this
    // session's. What the model was never fed (no model on the phone) is drained here.
    private fun keepClip(b: Burst) {
        val tape = b.tape ?: return
        val samples =
            synchronized(lock) {
                if (b.session != session) return
                tape.add(b.audio.drain())
                tape.samples()
            }
        val line = _lines.value.firstOrNull { it.burst == b.number && it.mine == b.mine } ?: return
        clips?.keep(WalkieClip(line, samples))
    }

    // Under [serial]: the burst's stream, opened on first use; null once the model would not run for it.
    private suspend fun streamOf(b: Burst): SpeechStream? {
        if (b.stream != null || b.failed) return b.stream
        val opened = timed(b) { speech.open(continuous = false) }
        b.failed = opened == null
        // Dropped while the model loaded: nothing will finish it.
        if (opened != null && synchronized(lock) { b.dropped || b.session != session }) {
            opened.close()
            return null
        }
        b.stream = opened
        return opened
    }

    private suspend fun fail(b: Burst) {
        b.failed = true
        b.stream?.close()
        b.stream = null
    }

    private inline fun <R> timed(
        b: Burst,
        call: () -> R,
    ): R {
        val began = clock()
        return call().also { b.modelMs += clock() - began }
    }

    // A dropped burst's stream, closed behind whatever work for it is already queued.
    private fun closeLater(b: Burst) {
        workScope.launch(dispatcher) {
            serial.withLock {
                b.stream?.close()
                b.stream = null
            }
        }
    }

    // [change] to the line for [b], if it is still in this session's transcript: one that fell off the top stays off.
    private fun updateLine(
        b: Burst,
        change: (TranscriptLine) -> TranscriptLine,
    ) {
        _lines.update { lines ->
            if (b.session != synchronized(lock) { session }) return@update lines
            val line = lines.firstOrNull { it.burst == b.number && it.mine == b.mine } ?: return@update lines
            withLine(lines, change(line))
        }
    }

    private fun TranscriptLine.State.label(): String =
        when (this) {
            is TranscriptLine.State.Text -> "${text.length} chars"
            else -> javaClass.simpleName
        }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
