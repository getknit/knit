package app.getknit.knit.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import app.getknit.knit.call.BurstPcm
import app.getknit.knit.call.SttPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.Base64

/**
 * Everything the app needs to *describe* a voice note's bytes: how long it plays, and the bar heights the
 * bubble draws. Both are derived from the audio itself and stored only locally (`MessageEntity.voiceDurationMs`
 * / `voicePeaks`) — neither crosses the wire, so a voice note costs no new wire field (see
 * `docs/WIRE_COMPAT.md`) and an old build simply renders one as an ordinary attachment it can't decode.
 *
 * The sender derives from the bytes it just recorded; the receiver derives from the same bytes once the blob
 * lands (`InboundPipeline.onObtained`). Deriving on *both* sides from one implementation is deliberate: the
 * two ends then agree by construction rather than by a field one of them has to trust.
 *
 * Voice notes are AAC-LC in an **ADTS** stream ([MIME]), not MPEG-4. That choice is forced by ADR 029 —
 * attachment bytes must never touch disk in plaintext, `MediaRecorder`'s MPEG-4 muxer needs a *seekable*
 * sink to write its `moov` atom and so cannot target a pipe, and ADTS is a pure stream that can. The happy
 * consequence is [durationMs]: ADTS frame headers are self-describing, so the duration is exact arithmetic
 * over the frame headers with no decoder, no Android dependency, and no container metadata to trust — which
 * is also what gives [peaks] a time base, since an ADTS stream states no duration of its own.
 */
@Suppress("MagicNumber") // ADTS header bit offsets/masks and the PCM scale are format constants.
object VoiceAudio {
    /** The MIME every voice note is stored and transmitted under. */
    const val MIME = "audio/aac"

    /** Bars in a stored waveform. Enough resolution for the bubble's width, and [PEAK_COUNT] bytes on disk. */
    const val PEAK_COUNT = 64

    /** Samples one AAC-LC frame decodes to — fixed by the format, and what makes [durationMs] arithmetic. */
    private const val AAC_SAMPLES_PER_FRAME = 1024

    /** An ADTS header is 7 bytes, or 9 when the (optional) CRC is present; the length field covers both. */
    private const val ADTS_HEADER_BYTES = 7

    /** MPEG-4 sample-rate table, indexed by an ADTS header's 4-bit `sampling_frequency_index`. */
    private val SAMPLE_RATES =
        intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /**
     * Queues one compressed sample from [extractor] into [codec], returning true once the end of the stream
     * has been queued so the caller stops feeding. A no-op when the codec has no free input buffer this
     * round — the caller simply comes back after draining an output one.
     */
    private fun feedInput(
        codec: MediaCodec,
        extractor: MediaExtractor,
    ): Boolean {
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return false
        val input = codec.getInputBuffer(index) ?: return false
        val size = extractor.readSampleData(input, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /**
     * Hands the PCM in one decoder output buffer to [onChunk] as a view of its payload, returning whether it held any.
     * Split out of [decodeChunks] so that loop stays readable, and because the offset handling here is the subtle part:
     * a codec may return a buffer whose payload starts at `info.offset`, and a decoder that reuses one large buffer per
     * output would otherwise replay the previous frame's samples.
     */
    private fun drainOutput(
        output: java.nio.ByteBuffer?,
        info: MediaCodec.BufferInfo,
        layout: PcmLayout,
        onChunk: (pcm: java.nio.ByteBuffer, timeUs: Long, rate: Int, channels: Int) -> Unit,
    ): Boolean {
        if (output == null || info.size <= 0) return false
        output.position(info.offset)
        output.limit(info.offset + info.size)
        onChunk(output.slice().order(ByteOrder.nativeOrder()), info.presentationTimeUs, layout.rate, layout.channels)
        return true
    }

    /** The decoder's output layout: what its format says, updated when the codec announces a change. */
    private class PcmLayout(
        var rate: Int,
        var channels: Int,
    ) {
        fun from(format: MediaFormat) {
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
    }

    /** See [scaleFor]: the percentile the waveform normalises to, so one transient can't flatten it. */
    private const val NORMALISE_PERCENTILE = 95

    /**
     * See [scaleFor]: a 16-bit PCM magnitude below which a recording is treated as silent rather than quiet
     * speech — about 1% of full scale. Absolute, because that is the only thing that actually distinguishes
     * a dead room from speech with a transient in it.
     */
    private const val SILENCE_FLOOR = 328L

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** [speechPcm]'s cap past the stated length: the decoder's priming and tail. */
    private const val SPEECH_CAP_SLACK_MS = 1_000L

    /** [speechPcm]'s cap for audio that states no length at all: a little past the recorder's five minutes. */
    private const val SPEECH_FALLBACK_CAP_MS = 6 * 60_000L

    private const val TAG = "VoiceAudio"

    /**
     * True when [mime] names audio this app renders as a voice note. The single place that rule lives —
     * the bubble, the chat-list preview, the reply label, the screening gates and the inbound derivation
     * all ask here rather than each spelling out a prefix test that could drift.
     */
    fun isVoice(mime: String?): Boolean = mime != null && mime.startsWith("audio/")

    /**
     * Playing time of the ADTS stream in [bytes], by walking its frame headers, or null when [bytes] does
     * not start with a well-formed ADTS frame. Pure arithmetic — no decoder is instantiated, so this is
     * cheap enough to run on any thread and is unit-testable off-device.
     *
     * Each frame header states its own total length, so the walk is a chain: read the length, step forward,
     * repeat. A frame whose length is nonsense (shorter than a header, or running past the end) ends the
     * walk rather than throwing, so a truncated transfer reports the duration of the part that *is* intact
     * instead of nothing at all.
     */
    fun durationMs(bytes: ByteArray): Int? {
        val frames = countFrames(bytes) ?: return null
        return (frames.frames * AAC_SAMPLES_PER_FRAME * 1000L / frames.sampleRate).toInt()
    }

    /**
     * [PEAK_COUNT] bar heights (0..255) for the audio in [bytes], or null when it can't be decoded. Unlike
     * [durationMs] this needs a real decoder, so it runs on [Dispatchers.IO] and is why the receive side
     * derives asynchronously once the blob lands rather than at row-insert time.
     *
     * Bars are peak (not mean) amplitude over equal *time* slices, which is what makes a waveform legible:
     * the mean of a speech envelope is nearly flat, while its peak tracks syllables. The result is scaled so
     * the loudest bar is full height — a quiet recording still draws a readable waveform rather than a line.
     *
     * The time base comes from [playingMs]: [durationMs] for an ADTS stream, which carries no duration of its own,
     * else the container's — a picked file (Ogg, MP3, M4A) has no ADTS headers to count.
     */
    suspend fun peaks(bytes: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            val totalUs = playingMs(bytes)?.toLong()?.times(1000L)?.takeIf { it > 0 } ?: return@withContext null
            val buckets = LongArray(PEAK_COUNT)
            var any = false
            val ok =
                decodeChunks(bytes) { pcm, timeUs, _, _ ->
                    val bucket = ((timeUs * PEAK_COUNT) / totalUs).toInt().coerceIn(0, PEAK_COUNT - 1)
                    val shorts = pcm.asShortBuffer()
                    while (shorts.hasRemaining()) {
                        any = true
                        val sample = shorts.get()
                        // Guard against Short.MIN_VALUE, whose negation overflows a Short.
                        val magnitude = if (sample < 0) -sample.toLong() else sample.toLong()
                        if (magnitude > buckets[bucket]) buckets[bucket] = magnitude
                    }
                }
            if (!ok || !any) return@withContext null
            ByteArray(PEAK_COUNT) { i -> ((buckets[i] * 255 / scaleFor(buckets)).coerceAtMost(255L)).toInt().toByte() }
        }

    /** What a voice note's bytes say about themselves — the pair stored on the message row. */
    data class Description(
        val durationMs: Int?,
        val peaks: String?,
    ) {
        /** False when the bytes yielded nothing worth storing, so callers can skip a pointless write. */
        val isEmpty: Boolean get() = durationMs == null && peaks == null
    }

    /**
     * [durationMs] and encoded [peaks] for [bytes], in one call. The single entry point both sides use — the
     * sender on the bytes it just recorded, the recipient on the bytes it just pulled — so the two ends
     * agree by construction instead of by trusting a value one of them put on the wire.
     */
    suspend fun describe(bytes: ByteArray): Description =
        Description(
            durationMs = playingMs(bytes),
            peaks = peaks(bytes)?.let { encodePeaks(it) },
        )

    /**
     * Encodes [peaks] for the `messages.voicePeaks` column. Base64 rather than a `BLOB` so `MessageEntity`
     * stays a plain `data class` — a `ByteArray` property would give it reference-identity `equals`, which
     * the chat's list diffing depends on not having.
     */
    fun encodePeaks(peaks: ByteArray): String = Base64.getEncoder().encodeToString(peaks)

    /**
     * Decodes a stored `voicePeaks` column back to bar heights, or null when the column is absent or
     * malformed. Bars come back as unsigned 0..255 in a [FloatArray] normalised to 0f..1f, which is what the
     * waveform actually draws — the caller should never have to remember that a Kotlin [Byte] is signed.
     */
    fun decodePeaks(encoded: String?): FloatArray? {
        if (encoded == null) return null
        val raw = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        return FloatArray(raw.size) { i -> (raw[i].toInt() and 0xFF) / 255f }
    }

    /**
     * The divisor that maps bucket peaks onto 0..255.
     *
     * Not simply the loudest bucket: a single transient — a knock on the phone, a door, the button itself —
     * is often several times louder than speech, and dividing by it flattens every real syllable to nothing.
     * So the scale is normally the [NORMALISE_PERCENTILE]th percentile, which clips the transient (harmless:
     * it is one bar, already full height) and lets the rest of the envelope fill the space.
     *
     * The exception is a genuinely silent recording, which must not be amplified into a confident-looking
     * waveform of nothing. What separates the two cases is **absolute** loudness, not a ratio: in both, the
     * percentile sits far below the peak, so no fraction-of-the-max floor can tell them apart — it would
     * only re-flatten the speech it was meant to rescue. A percentile under [SILENCE_FLOOR] means the room
     * really was dead, and the honest rendering is to scale by the true peak: the one click shows, and
     * everything else stays flat.
     *
     * `internal` rather than private so the rule can be unit-tested without a decoder; nothing else calls it.
     */
    internal fun scaleFor(buckets: LongArray): Long {
        val sorted = buckets.sortedArray()
        val percentile = sorted[((sorted.size - 1) * NORMALISE_PERCENTILE / 100)]
        val loudest = sorted.last()
        return (if (percentile >= SILENCE_FLOOR) percentile else loudest).coerceAtLeast(1L)
    }

    /**
     * Playing time of the audio in [bytes]: [durationMs] for a recorded note's ADTS stream, else what the container
     * states (`MediaFormat.KEY_DURATION`), which is how a picked Ogg, MP3 or M4A file gets a length and a waveform.
     * Null when neither says. Opens an extractor, so not for the main thread.
     */
    internal fun playingMs(bytes: ByteArray): Int? = durationMs(bytes) ?: containerDurationMs(bytes)

    /** The duration the container in [bytes] states for its first audio track, or null when it states none. */
    private fun containerDurationMs(bytes: ByteArray): Int? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ByteArrayMediaSource(bytes))
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.div(1000L)
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?.toInt()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // As in [decodeChunks]: the extractor's failures vary by device, and all mean "not audio we can read".
            Log.w(TAG, "voice duration read failed: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** What [countFrames] recovers from an ADTS stream: how many frames it holds, and at what rate. */
    private class FrameCount(
        val frames: Long,
        val sampleRate: Int,
    )

    /**
     * Walks the ADTS headers, counting frames and recovering the sample rate. Suppressed for multiple jumps
     * deliberately: a bad syncword and a bad frame length are distinct malformed-input cases, and folding
     * them into one condition would hide which check actually stopped the walk.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun countFrames(bytes: ByteArray): FrameCount? {
        var offset = 0
        var frames = 0L
        var sampleRate = 0
        while (offset + ADTS_HEADER_BYTES <= bytes.size) {
            // Syncword: 12 set bits. Anything else means we are not looking at a frame boundary.
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) break
            if (sampleRate == 0) {
                val index = (bytes[offset + 2].toInt() and 0x3C) shr 2
                sampleRate = SAMPLE_RATES.getOrNull(index) ?: return null
            }
            // aac_frame_length is 13 bits straddling bytes 3, 4 and 5, and counts the header itself.
            val length =
                ((bytes[offset + 3].toInt() and 0x03) shl 11) or
                    ((bytes[offset + 4].toInt() and 0xFF) shl 3) or
                    ((bytes[offset + 5].toInt() and 0xFF) shr 5)
            if (length < ADTS_HEADER_BYTES || offset + length > bytes.size) break
            frames++
            offset += length
        }
        if (frames == 0L || sampleRate == 0) return null
        return FrameCount(frames, sampleRate)
    }

    /**
     * The note in [bytes] as the speech model takes it (ADR 2026-10.am8d): mono, at [SttPolicy.SAMPLE_RATE], in
     * −1..1, or null when it can't be decoded or holds no audio. Mixed down and resampled by the walkie's own
     * [BurstPcm] as the decoder hands each buffer over, so no 16-bit copy of the whole note is held; the result is
     * about 19 MB for the recorder's five-minute cap, and is the caller's to drop once the model has had it. Capped
     * at the length the audio states ([playingMs]), so a stream that decodes past its own claim cannot grow
     * the buffer without bound.
     */
    suspend fun speechPcm(bytes: ByteArray): FloatArray? =
        withContext(Dispatchers.IO) {
            val claimed = playingMs(bytes)?.toLong() ?: SPEECH_FALLBACK_CAP_MS
            val audio = BurstPcm(capMs = claimed + SPEECH_CAP_SLACK_MS)
            val ok = decodeChunks(bytes) { pcm, _, rate, channels -> audio.add(pcm, rate, channels) }
            if (!ok) return@withContext null
            audio.drain().takeIf { it.isNotEmpty() }
        }

    /**
     * Streams [bytes] through the platform AAC decoder, handing each output buffer's PCM (16-bit, native order,
     * [channels] interleaved at [rate] Hz) to [onChunk] with the presentation time of the buffer it came from.
     * The buffer is the callee's to read, never to keep. Returns false when the bytes carry no decodable audio
     * track. Deliberately materializes no PCM buffer of its own: a 5-minute note is ~13 M samples, and holding
     * them all to pick [PEAK_COUNT] peaks would be pure waste.
     */
    private fun decodeChunks(
        bytes: ByteArray,
        onChunk: (pcm: java.nio.ByteBuffer, timeUs: Long, rate: Int, channels: Int) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decoded = false
        try {
            extractor.setDataSource(ByteArrayMediaSource(bytes))
            val track =
                (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val layout = PcmLayout(rate = 0, channels = 1).apply { from(format) }

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            while (!sawOutputEnd) {
                if (!sawInputEnd) sawInputEnd = feedInput(codec, extractor)
                val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                // HE-AAC and some decoders only state the real rate and layout here, before the first buffer.
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) layout.from(codec.outputFormat)
                if (outIndex >= 0) {
                    if (drainOutput(codec.getOutputBuffer(outIndex), info, layout, onChunk)) decoded = true
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }
            return decoded
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Broad on purpose: the platform codec throws IllegalStateException, IllegalArgumentException,
            // MediaCodec.CodecException or IOException depending on the device, and every one of them means
            // the same thing here — these bytes aren't decodable audio. A corrupt transfer, an unexpected
            // codec, a device missing the decoder. The bubble draws a flat waveform and still plays;
            // nothing else in the app changes. Deriving a waveform must never take a chat down.
            Log.w(TAG, "voice waveform decode failed: ${e.javaClass.simpleName}")
            return decoded
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
