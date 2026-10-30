package app.getknit.knit.ui.call.stt

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.Transcript
import app.getknit.knit.call.Heard
import app.getknit.knit.call.SpeechStream
import app.getknit.knit.call.SpeechToText
import app.getknit.knit.call.SttArch
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.SttPolicy
import app.getknit.knit.moderation.ModelLease
import app.getknit.knit.moderation.ModelLoadGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The walkie transcript's speech model (docs/VOICE_CALLS.md C-3.11-9): Moonshine v2 Small Streaming, through
 * Moonshine's own runtime (`moonshine-voice`, its ONNX Runtime inside), over the files [SttModelStore] holds (a
 * reinstall is a new generation, so a new lease). It is called through the library's `JNI`
 * class alone: its `Transcriber` wrapper would add a thread of its own and its own cadence, and the cadence here is
 * [app.getknit.knit.call.PassGate]'s, under the transcriber's lock.
 *
 * The loaded model lives on a [ModelLease] like the moderation models (ADR 2026-09.cq9z) — built on the first burst,
 * released when the call ends ([release]), when the model is removed, or after [SttPolicy.IDLE_UNLOAD_MS] without a
 * burst — and every build, a probe stream included, runs under [ModelLoadGuard] (ADR 037), so a runtime that faults
 * natively on this phone is latched off instead of taking the process down on every burst. A lease whose load failed
 * stays spent until the call ends: [release] then drops it, so the next call tries again (through the guard, which
 * still says no to a latched model), and a model installed afresh gets a fresh lease at once. Every call a stream makes
 * holds the lease without ever loading it ([ModelLease.useResident]), so the reaper never frees the model under a
 * burst, and a stream whose model was freed or replaced meanwhile fails rather than load a model only to find its
 * handle gone.
 */
internal class MoonshineSpeech(
    private val files: SttModelFiles,
    private val guard: ModelLoadGuard,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val runtime: MoonshineRuntime = JniMoonshine,
) : SpeechToText {
    /**
     * One loaded model: the runtime's handle for it, and the key terms it was last given. Compared by identity, since a
     * handle is reused once freed.
     */
    private class Engine(
        val handle: Int,
    ) {
        var keyterms: List<String> = emptyList()
    }

    private val lock = Any()
    private var lease: ModelLease<Engine>? = null

    // What the lease was built over: the store's generation, and how a burst becomes lines.
    private var leaseKey: Pair<Int, LineMode>? = null

    /**
     * How a walkie burst becomes lines ([LineMode]). Read when the model loads, so a change makes the next burst reload
     * it; the debug bridge sets it for an A/B. A live call's captions always split at pauses (C-3.7-5), whatever it says.
     */
    @Volatile
    var lines = LineMode.WHOLE

    /**
     * The names the next burst listens for (`sttKeyterms`): given to the model as a burst opens, when they differ from
     * what it has, since a load starts it with none. Never logged.
     */
    @Volatile
    var keyterms: List<String> = emptyList()

    init {
        // A model removed from the phone is let go now, not at its idle timeout: its native heap is the cost.
        scope.launch { files.state.collect { if (it != SttModelState.Ready) unload() } }
    }

    override suspend fun open(continuous: Boolean): SpeechStream? {
        val current = current(if (continuous) LineMode.SPLIT else lines) ?: return null
        return current.use { engine ->
            engine ?: return@use null
            listen(engine)
            val stream = runtime.createStream(engine.handle)
            if (stream < 0 || runtime.startStream(engine.handle, stream) != MoonshineRuntime.OK) {
                if (stream >= 0) runtime.freeStream(engine.handle, stream)
                log("walkie stt: no stream: $stream")
                return@use null
            }
            Stream(current, engine, stream)
        }
    }

    // Inside the lease: [keyterms] to [engine], if it has others. A list the runtime refuses is logged by its size and
    // not tried again until it changes; the burst goes on without it.
    @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes
    private fun listen(engine: Engine) {
        val wanted = keyterms
        if (wanted == engine.keyterms) return
        engine.keyterms = wanted
        val result =
            try {
                runtime.setKeyterms(engine.handle, wanted.joinToString(","))
            } catch (e: Exception) {
                log("walkie stt: key terms not set: ${e.javaClass.simpleName}")
                return
            }
        if (result != MoonshineRuntime.OK) log("walkie stt: ${wanted.size} key terms refused: ${runtime.describe(result)}")
    }

    /**
     * Releases the model now; the next burst builds it again. A lease whose load failed is dropped, so that build is a
     * fresh try rather than the spent lease's refusal for the rest of the process.
     */
    suspend fun unload() {
        val held = synchronized(lock) { lease } ?: return
        held.unload()
        synchronized(lock) {
            if (lease === held && held.isLoaded && !held.isResident) {
                lease = null
                leaseKey = null
            }
        }
    }

    override suspend fun release() = unload()

    /** Whether a model is in memory now (the debug bridge). */
    val resident: Boolean get() = synchronized(lock) { lease }?.isResident == true

    /**
     * One burst's stream. Its own calls never overlap (the transcriber's lock), but they share the model with the other
     * side's burst, so each runs inside the lease's [ModelLease.use].
     */
    private inner class Stream(
        private val lease: ModelLease<Engine>,
        private val engine: Engine,
        private val stream: Int,
    ) : SpeechStream {
        private var closed = false

        override suspend fun add(samples: FloatArray): Boolean =
            call { runtime.addAudio(engine.handle, stream, samples) == MoonshineRuntime.OK } ?: false

        override suspend fun look(): Heard? = call { heardOf(runtime.transcribe(engine.handle, stream, final = false), final = false) }

        override suspend fun finish(): Heard? {
            val heard =
                call {
                    runtime.stopStream(engine.handle, stream)
                    // What is left in the stream since the last look, as the library's own stop does.
                    heardOf(runtime.transcribe(engine.handle, stream, final = true), final = true)
                }
            close()
            return heard
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            // A model freed meanwhile took its streams with it; one built since never gave this stream out.
            lease.useResident { current -> if (current === engine) runtime.freeStream(engine.handle, stream) }
        }

        // [block] on the model this stream was opened on, or null (and the stream closed) when that model is gone — never
        // loaded again for it — the runtime refused, or it threw.
        @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes
        private suspend fun <R : Any> call(block: () -> R?): R? {
            if (closed) return null
            val result =
                lease.useResident { current ->
                    if (current !== engine) return@useResident null
                    try {
                        block()
                    } catch (e: Exception) {
                        log("walkie stt: stream call failed: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                }
            if (result == null || result == false) close()
            return result
        }
    }

    // The lease over the model on disk now for [mode], made afresh when the store's files or the mode changed (a walkie
    // and a live call build it differently); null with no model there.
    private fun current(mode: LineMode): ModelLease<Engine>? {
        val key = files.generation to mode
        val paths = files.paths() ?: return null
        val model = files.model
        val dir = paths.values.firstOrNull()?.parentFile ?: return null
        val (fresh, stale) =
            synchronized(lock) {
                val held = lease
                if (held != null && leaseKey == key) return held
                val made =
                    ModelLease(
                        scope = scope,
                        idleMs = SttPolicy.IDLE_UNLOAD_MS,
                        load = { guard.guard(ModelLoadGuard.STT) { build(model, dir, key.second == LineMode.SPLIT) } },
                        close = { runtime.free(it.handle) },
                    )
                lease = made
                leaseKey = key
                made to held
            }
        stale?.let { scope.launch { it.unload() } }
        return fresh
    }

    // Under the guard: the model and one probe stream over silence, which is where a runtime that faults on this phone
    // faults.
    @Suppress("TooGenericExceptionCaught") // the native runtime throws what it likes, a missing .so as an Error
    private fun build(
        model: SttModel,
        dir: File,
        split: Boolean,
    ): Engine? {
        val started = System.currentTimeMillis()
        return try {
            val handle = runtime.load(dir.path, model.arch, optionsFor(split))
            if (handle < 0) {
                log("walkie stt: ${model.id} not loaded: ${runtime.describe(handle)}")
                return null
            }
            if (!probe(handle)) {
                runtime.free(handle)
                log("walkie stt: ${model.id} failed its probe")
                return null
            }
            val lines = if (split) "split at pauses" else "one line a burst"
            log("walkie stt: ${model.id} loaded in ${System.currentTimeMillis() - started} ms, $lines")
            Engine(handle)
        } catch (e: Throwable) {
            log("walkie stt: ${model.id} not loaded: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun probe(handle: Int): Boolean {
        val stream = runtime.createStream(handle)
        if (stream < 0) return false
        try {
            if (runtime.startStream(handle, stream) != MoonshineRuntime.OK) return false
            runtime.addAudio(handle, stream, FloatArray(PROBE_SAMPLES))
            runtime.stopStream(handle, stream)
            return runtime.transcribe(handle, stream, final = true) != null
        } finally {
            runtime.freeStream(handle, stream)
        }
    }

    internal companion object {
        // Half a second of silence: enough to run every graph once.
        private const val PROBE_SAMPLES = SttPolicy.SAMPLE_RATE / 2

        // The longest stretch Moonshine advises for one line, in seconds, and what the detector is told. With the
        // detector off it ends no line at all: its fade only scales a voice score of 1, which stays above a threshold of 0,
        // so a 44 s clip came back as one whole line (desktop bench, 2026-10-30). A walkie burst is held to this length at
        // the sender instead (WalkiePolicy.MAX_BURST_MS), where `MoonshineSpeechTest` pins it.
        const val MAX_LINE_S = 30

        // The text is never logged, and no line carries its audio back: the transcript wants the words alone. Unless
        // [split], the voice detector is off (threshold 0), so a burst is one line, as Moonshine scores itself.
        fun optionsFor(split: Boolean): List<TranscriberOption> =
            listOfNotNull(
                TranscriberOption("log_output_text", "false"),
                TranscriberOption("return_audio_data", "false"),
                TranscriberOption("vad_threshold", "0").takeUnless { split },
                TranscriberOption("vad_max_segment_duration", "$MAX_LINE_S").takeUnless { split },
            )

        // The lines the model has closed at a pause are settled; the one still open is its live guess — unless this is
        // the stream's last look, which closes them all.
        fun heardOf(
            transcript: Transcript?,
            final: Boolean,
        ): Heard? {
            val lines = transcript?.lines ?: return null

            fun words(of: List<ai.moonshine.voice.TranscriptLine>) =
                of.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            if (final) return Heard(words(lines))
            val (done, open) = lines.partition { it.isComplete }
            return Heard(words(done), words(open))
        }
    }
}

/**
 * How the transcript turns a burst into lines, the model's own voice detector being what splits one at a pause.
 * [WHOLE] keeps it off, a burst being one line of up to 30 s: the talk button already bounds the speech, and a split
 * decodes each half without the other, which Moonshine measured at 1.5–2 points of word error on clean speech (16.7 %
 * against 0 on the lab's noisy clip), though every look re-decodes the burst so far. [SPLIT] cuts at pauses: its looks
 * stay cheap and its text is final the moment the burst ends, and the seams cost words. A third mode, live words from a
 * split transcriber and the text from a second whole-burst one, read no better than [WHOLE] and cost more on every count
 * (ADR 2026-10.nz3g).
 */
internal enum class LineMode { WHOLE, SPLIT }

/**
 * Where [MoonshineSpeech] finds its model: the files on the phone ([SttModelStore]), and whether they are there now. A
 * new [generation] is new files, so a new engine.
 */
internal interface SttModelFiles {
    val model: SttModel
    val generation: Int
    val state: StateFlow<SttModelState>

    /** The model's files by name, while it is [SttModelState.Ready]. */
    fun paths(): Map<String, File>?
}

/**
 * Moonshine's runtime as [MoonshineSpeech] calls it: its `JNI` class on the phone ([JniMoonshine]), a fake in
 * `MoonshineSpeechTest`. Handles and streams are the runtime's numbers; a negative one, or a status other than [OK], is
 * a refusal that [describe] puts in words. Any call may throw what the native side likes.
 */
internal interface MoonshineRuntime {
    /** A transcriber over the files in [dir], built as [options] say: its handle. */
    fun load(
        dir: String,
        arch: SttArch,
        options: List<TranscriberOption>,
    ): Int

    fun free(handle: Int)

    fun describe(error: Int): String

    /** The names the model listens for, comma-separated; an empty string clears them. */
    fun setKeyterms(
        handle: Int,
        terms: String,
    ): Int

    fun createStream(handle: Int): Int

    fun startStream(
        handle: Int,
        stream: Int,
    ): Int

    fun stopStream(
        handle: Int,
        stream: Int,
    ): Int

    fun freeStream(
        handle: Int,
        stream: Int,
    )

    /** More of the stream's audio, at [SttPolicy.SAMPLE_RATE]. */
    fun addAudio(
        handle: Int,
        stream: Int,
        samples: FloatArray,
    ): Int

    /** What the stream has heard so far; [final], everything left in it too, as after a stop. Null when it failed. */
    fun transcribe(
        handle: Int,
        stream: Int,
        final: Boolean,
    ): Transcript?

    companion object {
        const val OK = JNI.MOONSHINE_ERROR_NONE
    }
}

/** [MoonshineRuntime] on the phone: the library's `JNI` class, its native library loaded on the first [load]. */
internal object JniMoonshine : MoonshineRuntime {
    override fun load(
        dir: String,
        arch: SttArch,
        options: List<TranscriberOption>,
    ): Int {
        JNI.ensureLibraryLoaded()
        return JNI.moonshineLoadTranscriberFromFiles(dir, archOf(arch), options.toTypedArray<Any>())
    }

    override fun free(handle: Int) = JNI.moonshineFreeTranscriber(handle)

    override fun describe(error: Int): String = JNI.moonshineErrorToString(error)

    override fun setKeyterms(
        handle: Int,
        terms: String,
    ): Int = JNI.moonshineTranscriberSetKeyterms(handle, terms)

    override fun createStream(handle: Int): Int = JNI.moonshineCreateStream(handle, 0)

    override fun startStream(
        handle: Int,
        stream: Int,
    ): Int = JNI.moonshineStartStream(handle, stream)

    override fun stopStream(
        handle: Int,
        stream: Int,
    ): Int = JNI.moonshineStopStream(handle, stream)

    override fun freeStream(
        handle: Int,
        stream: Int,
    ) {
        JNI.moonshineFreeStream(handle, stream)
    }

    override fun addAudio(
        handle: Int,
        stream: Int,
        samples: FloatArray,
    ): Int = JNI.moonshineAddAudioToStream(handle, stream, samples, SttPolicy.SAMPLE_RATE, 0)

    override fun transcribe(
        handle: Int,
        stream: Int,
        final: Boolean,
    ): Transcript? = JNI.moonshineTranscribeStream(handle, stream, if (final) JNI.MOONSHINE_FLAG_FORCE_UPDATE else 0)

    private fun archOf(arch: SttArch): Int =
        when (arch) {
            SttArch.TINY_STREAMING -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            SttArch.SMALL_STREAMING -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
            SttArch.MEDIUM_STREAMING -> JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
        }
}
