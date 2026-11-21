package app.getknit.knit.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio made on the device by its own AAC encoder, so a voice test needs no asset: [adts] is what `VoiceRecorder`'s
 * `MediaRecorder` writes, [m4a] what a picked recording from another app is.
 */
object AudioFixtures {
    /**
     * [totalMs] of 16-bit PCM at [rate] Hz, [channels] interleaved: a [toneHz] sine at [level] of full scale for the
     * first [toneMs], then digital silence.
     */
    @Suppress("LongParameterList") // the tone's four facts and the stream's two
    fun tone(
        rate: Int,
        channels: Int = 1,
        toneHz: Double = TONE_HZ,
        toneMs: Int,
        totalMs: Int,
        level: Double = 0.5,
    ): ShortArray {
        val frames = rate * totalMs / MS_PER_S
        val toneFrames = rate * toneMs / MS_PER_S
        val out = ShortArray(frames * channels)
        for (i in 0 until toneFrames) {
            val sample = (level * Short.MAX_VALUE * sin(2 * PI * toneHz * i / rate)).toInt().toShort()
            for (c in 0 until channels) out[i * channels + c] = sample
        }
        return out
    }

    /** [pcm] through the platform's AAC-LC encoder, each access unit behind its own ADTS header. */
    fun adts(
        pcm: ShortArray,
        rate: Int,
        channels: Int = 1,
    ): ByteArray {
        val index = ADTS_RATES.indexOf(rate)
        require(index >= 0) { "no ADTS index for $rate Hz" }
        val out = ByteArrayOutputStream()
        encode(pcm, rate, channels, onFormat = {}) { unit, _ ->
            out.write(adtsHeader(unit.size, index, channels))
            out.write(unit)
        }
        return out.toByteArray()
    }

    /** [pcm] through the platform's AAC-LC encoder into an MPEG-4 file at [out], returned as bytes. */
    fun m4a(
        pcm: ShortArray,
        rate: Int,
        channels: Int,
        out: File,
    ): ByteArray {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        try {
            encode(pcm, rate, channels, onFormat = { format ->
                track = muxer.addTrack(format)
                muxer.start()
            }) { unit, info ->
                // The unit is a copy of its own, so its offset is 0, not the codec buffer's.
                val sample = MediaCodec.BufferInfo().apply { set(0, unit.size, info.presentationTimeUs, 0) }
                muxer.writeSampleData(track, ByteBuffer.wrap(unit), sample)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return out.readBytes()
    }

    /** The root mean square of [samples] in −1..1 between [fromMs] and [toMs], at [rate] Hz mono. */
    fun rms(
        samples: FloatArray,
        rate: Int,
        fromMs: Int,
        toMs: Int,
    ): Double {
        val from = rate * fromMs / MS_PER_S
        val to = minOf(samples.size, rate * toMs / MS_PER_S)
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    /** How often [samples] cross zero per second between [fromMs] and [toMs], at [rate] Hz mono: twice the tone's pitch. */
    fun crossingsPerSecond(
        samples: FloatArray,
        rate: Int,
        fromMs: Int,
        toMs: Int,
    ): Double {
        val from = rate * fromMs / MS_PER_S
        val to = minOf(samples.size, rate * toMs / MS_PER_S)
        var crossings = 0
        for (i in from + 1 until to) if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        return crossings * MS_PER_S.toDouble() / (toMs - fromMs)
    }

    /**
     * Feeds [pcm] to the platform AAC encoder and hands each access unit (no codec-config buffer) to [onUnit] with its
     * buffer info, after [onFormat] has seen the encoder's output format.
     */
    @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun encode(
        pcm: ShortArray,
        rate: Int,
        channels: Int,
        onFormat: (MediaFormat) -> Unit,
        onUnit: (ByteArray, MediaCodec.BufferInfo) -> Unit,
    ) {
        val raw = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
        raw.asShortBuffer().put(pcm)
        val bytes = raw.array()
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE * channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
            }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + ENCODE_BUDGET_NS
        var fed = 0
        var inputDone = false
        try {
            while (true) {
                assertTrue("the AAC encoder finished the fixture", System.nanoTime() < deadline)
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(DEQUEUE_US)
                    if (i >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(i)).apply { clear() }
                        val n = minOf(buffer.remaining() / (2 * channels) * (2 * channels), bytes.size - fed)
                        buffer.put(bytes, fed, n)
                        val ptsUs = (fed / (2 * channels)).toLong() * US_PER_S / rate
                        fed += n
                        inputDone = fed >= bytes.size
                        codec.queueInputBuffer(i, 0, n, ptsUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val o = codec.dequeueOutputBuffer(info, DEQUEUE_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) onFormat(codec.outputFormat)
                if (o < 0) continue
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!config && info.size > 0) {
                    val out = checkNotNull(codec.getOutputBuffer(o))
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    onUnit(ByteArray(info.size).also { out.get(it) }, info)
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** The 7-byte ADTS header (no CRC) in front of an AAC-LC access unit of [payload] bytes. */
    @Suppress("MagicNumber") // ADTS bit layout (ISO/IEC 13818-7 §6.2)
    private fun adtsHeader(
        payload: Int,
        rateIndex: Int,
        channels: Int,
    ): ByteArray {
        val length = payload + ADTS_HEADER_BYTES
        val lcProfile = 1 // AAC LC, as audio object type 2 minus one
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((lcProfile shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    const val TONE_HZ = 440.0
    private const val MS_PER_S = 1_000
    private const val US_PER_S = 1_000_000L
    private const val BIT_RATE = 32_000
    private const val INPUT_BYTES = 16 * 1024
    private const val DEQUEUE_US = 10_000L
    private const val ENCODE_BUDGET_NS = 60_000_000_000L
    private const val ADTS_HEADER_BYTES = 7
    private val ADTS_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
}
