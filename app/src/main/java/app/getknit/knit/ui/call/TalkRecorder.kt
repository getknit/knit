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
import app.getknit.knit.call.WalkiePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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
 * coroutine, which alone feeds and drains the encoder and releases both at its end.
 *
 * [nextFrom] is the debug bridge's stand-in for a voice: the next burst reads that file of raw PCM, paced as the
 * microphone would deliver it, so a trial can send the same known sentence every time (the transcript, C-3.11-9).
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
    private var job: Job? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var dropping = false

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

    /** The source the last burst's microphone opened with; null before one, or for a burst read from [nextFrom]. */
    @Volatile
    var lastSource: Int? = null
        private set

    /**
     * What the last burst's microphone had: `on`, `off` (not asked for), `call path` (its source runs the phone's own),
     * `unavailable` or `refused`; null before one.
     */
    @Volatile
    var suppressor: String? = null
        private set

    /** Raw 16 kHz mono 16-bit little-endian PCM the next burst reads in place of the microphone, once. */
    @Volatile
    var nextFrom: File? = null

    /** Milliseconds since [start], or 0 when not recording. */
    fun elapsedMs(): Long = startedAt.let { if (it == 0L) 0L else System.currentTimeMillis() - it }

    /** The loudest sample since the last call, 0f..1f, for the live meter. */
    fun amplitude(): Float {
        val p = peak
        peak = 0
        return (p / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    /**
     * Begins a burst, handing each Opus packet to [onPacket] on the capture thread; false when the microphone is busy.
     * [headset]: a headset has the call's audio (§3.13), so the burst is spoken into its microphone.
     */
    fun start(
        headset: Boolean,
        onPacket: (ByteArray) -> Unit,
    ): Boolean {
        if (job != null) return false
        lastSource = null
        val record = nextFrom?.also { nextFrom = null }?.let(::openFile) ?: openRecord(headset) ?: return false
        val codec = openEncoder()
        if (codec == null) {
            record.close()
            return false
        }
        stopping = false
        dropping = false
        peak = 0
        startedAt = System.currentTimeMillis()
        val gain = gains.getValue(headset).also { it.newBurst() }
        job = scope.launch(Dispatchers.IO) { pump(record, codec, gain, headset, onPacket) }
        return true
    }

    /** Ends the burst; its last packets are flushed out of the encoder and handed on before this returns. */
    suspend fun stop() = end(drop = false)

    /** Ends the burst without its tail. */
    suspend fun cancel() = end(drop = true)

    private suspend fun end(drop: Boolean) {
        dropping = drop
        stopping = true
        job?.join()
        job = null
        startedAt = 0L
    }

    // The capture loop: 20 ms of PCM in, whatever packets the encoder has out, until a stop; then the flush.
    @Suppress("LoopWithTooManyJumpStatements") // a failed read or a refused queue ends the burst, an empty read skips
    private fun pump(
        record: Capture,
        codec: MediaCodec,
        gain: TalkGain,
        headset: Boolean,
        onPacket: (ByteArray) -> Unit,
    ) {
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        val level = BurstLevel()
        try {
            while (!stopping) {
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) break
                if (n == 0) continue
                level.read(pcm, n, gain)
                tapped(pcm, n)
                if (!queue(codec, pcm, n, ptsUs, 0)) break
                ptsUs += n / PCM_BYTES * US_PER_S / WalkiePolicy.SAMPLE_RATE
                drain(codec, info, onPacket, untilEnd = false)
            }
            if (!dropping && queue(codec, pcm, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                drain(codec, info, onPacket, untilEnd = true)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The platform codec and the capture throw what they like; either way the burst ends with what went out.
            Log.w(TAG, "walkie capture ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // What the gain made of the burst, for tuning it against a call's level on the phones.
            val heard = gain.speechDb?.let { "speech at %.0f dBFS".format(Locale.ROOT, it) } ?: "no speech"
            val off = if (applyGain) "" else " (off, not applied)"
            Log.i(TAG, "walkie gain: $heard, gain %+.1f dB%s, %d frame(s) limited".format(Locale.ROOT, gain.gainDb, off, gain.limited))
            deadRoute(record, headset, level.samples, level.peak)
            record.close()
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // What the microphone gave over a burst, before the gain, for a dead call path; and the gain, applied or only read.
    private inner class BurstLevel {
        var samples = 0L
            private set
        var peak = 0
            private set

        fun read(
            pcm: ByteArray,
            size: Int,
            gain: TalkGain,
        ) {
            val loudest = peakOf(pcm, size)
            peak = maxOf(peak, loudest)
            samples += size / PCM_BYTES
            // Off, the gain still reads the burst (on a copy) so its log line says what the microphone gave.
            if (applyGain) gain.apply(pcm, size) else gain.apply(pcm.copyOf(size), size)
            this@TalkRecorder.peak = maxOf(this@TalkRecorder.peak, if (applyGain) peakOf(pcm, size) else loudest)
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
                        maxOf(min, FRAME_BYTES * BUFFER_FRAMES),
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
