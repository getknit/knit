package app.getknit.knit.data.video

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * The MP4 muxer a short-video encode writes through: Media3's own `Mp4Muxer`, into a [MemoryMuxerOutput] (no
 * plaintext file), with no padding and its metadata on an allow-list.
 *
 * Transformer hands the muxer every metadata entry the *source* carried: the place it was recorded
 * (`Mp4LocationData`), when (`Mp4TimestampData`), the phone's make, model and capture settings (`mdta` keys) and any
 * XMP. Sending those would break "no GPS leaves the phone", so only the one entry a player needs to draw the picture
 * upright, `Mp4OrientationData`, is written, and everything else is dropped: anything not understood is left out, the
 * same rule `ImageStrip` keeps for pictures (ADR 2026-10.unqn). JVM-tested in `MemoryMp4MuxerTest`.
 */
@OptIn(UnstableApi::class)
class MemoryMp4Muxer private constructor(
    output: MemoryMuxerOutput,
) : Muxer {
    /** The latest presentation time of a sample written whole, in µs: how far into the clip the output got. */
    var writtenUs = 0L
        private set

    // Not streamable: moov goes at the end. Streamable output reserves space for moov ahead of mdat and pads what it
    // does not use with a `free` box — 373 KB of a 2.3 MB encode on the first trailer tried, 16 % of the file. A Knit
    // video is fetched whole and played from memory, so nothing gains from moov first.
    private val muxer = Mp4Muxer.Builder(output).setAttemptStreamableOutputEnabled(false).build()

    override fun addTrack(format: Format): Int {
        val track = muxer.addTrack(format)
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            muxer.addMetadataEntry(Mp4OrientationData(format.rotationDegrees))
        }
        return track
    }

    override fun writeSampleData(
        trackId: Int,
        byteBuffer: ByteBuffer,
        bufferInfo: BufferInfo,
    ) {
        muxer.writeSampleData(trackId, byteBuffer, bufferInfo)
        writtenUs = maxOf(writtenUs, bufferInfo.presentationTimeUs)
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
        // The orientation is written from the video track's own format in addTrack; nothing the source carried is.
    }

    override fun close() = muxer.close()

    /**
     * Makes one [MemoryMp4Muxer] per export and keeps its output, which [bytes] reads once the export completes. The
     * path Transformer passes is ignored: nothing is written there.
     */
    class Factory(
        private val maxBytes: Int,
    ) : Muxer.Factory {
        private var output: MemoryMuxerOutput? = null
        private var muxer: MemoryMp4Muxer? = null

        override fun create(path: String): Muxer = MemoryMp4Muxer(MemoryMuxerOutput(maxBytes).also { output = it }).also { muxer = it }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            when (trackType) {
                C.TRACK_TYPE_VIDEO -> Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES
                C.TRACK_TYPE_AUDIO -> Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES
                else -> ImmutableList.of()
            }

        /** The finished MP4, or null when no muxer was made. */
        fun bytes(): ByteArray? = output?.toByteArray()

        /** Whether the export ran past [maxBytes] (see [MemoryMuxerOutput.overflowed]). */
        fun overflowed(): Boolean = output?.overflowed == true

        /** How far into the clip the output got before it stopped, in µs (see [MemoryMp4Muxer.writtenUs]). */
        fun writtenUs(): Long = muxer?.writtenUs ?: 0L
    }
}
