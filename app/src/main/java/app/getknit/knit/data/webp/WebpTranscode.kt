package app.getknit.knit.data.webp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.Build
import android.util.Log
import app.getknit.knit.data.downscale
import java.io.ByteArrayOutputStream

/**
 * Re-encodes an animated GIF into a smaller **animated WebP** for faster mesh transmit: resamples the
 * GIF's frames at a capped frame rate, downscales them, compresses each with the built-in
 * `Bitmap.compress(WEBP_LOSSY, …)` (hardware VP8 — far more efficient than GIF's LZW), and muxes them
 * into one animated WebP via [AnimatedWebpMuxer].
 *
 * The output is `image/webp`, which the app already stores, transmits, and displays (Coil's
 * `AnimatedImageDecoder` plays animated WebP, and static-WebP attachments already exist), so only the
 * attachment mime changes. Only [app.getknit.knit.data.AttachmentStore.ingest] calls this, on the send
 * side.
 *
 * Decoding uses [android.graphics.Movie]: it renders the composited frame at any time offset, exactly
 * the fixed-rate resampling decimation needs. It's deprecated but the only built-in GIF frame sampler,
 * and still functional. [shrink] **never regresses** — it returns null (caller keeps the original GIF
 * bytes) whenever the GIF can't be decoded, its timing is unknown, or the WebP wouldn't be smaller.
 *
 * Limitation: `Movie` renders full composited frames, correct for the full-frame GIFs that dominate
 * chat use; a partial-frame ("dirty rectangle") GIF could show minor artifacts.
 */
object WebpTranscode {
    private const val TAG = "WebpTranscode"

    /** Cap on emitted frames so a very long GIF can't blow up encode time / output size. */
    private const val MAX_FRAMES = 200

    /** A successful re-encode plus the before/after stats logged for it. */
    private class Encoded(
        val bytes: ByteArray,
        val srcW: Int,
        val srcH: Int,
        val outW: Int,
        val outH: Int,
        val frames: Int,
        val durationMs: Int,
    )

    /**
     * Re-encodes [bytes] (an animated GIF) into a smaller animated WebP bounded by [maxDim] (longest
     * edge) and [maxFps], at WebP [quality] (0–100). Returns the smaller WebP, or null if decode/encode
     * fails, the timing is unknown, or the result isn't smaller than the input.
     */
    fun shrink(
        bytes: ByteArray,
        maxDim: Int,
        maxFps: Int,
        quality: Int,
    ): ByteArray? {
        val result =
            try {
                shrinkInternal(bytes, maxDim, maxFps, quality)
            } catch (ignored: Throwable) {
                // A codec failure must never break sending — fall back to the original GIF bytes.
                Log.i(
                    TAG,
                    "gif kept verbatim (${bytes.size} B) — webp transcode threw " +
                        "${ignored.javaClass.simpleName}: ${ignored.message}",
                )
                null
            }
        // A null result already logged its specific reason (undecodable / not smaller / threw) below.
        if (result == null) return null
        val pct = 100 - (result.bytes.size * 100L / bytes.size)
        val fps = result.frames * 1000 / result.durationMs
        Log.i(
            TAG,
            "gif -> webp ${bytes.size} B -> ${result.bytes.size} B ($pct% smaller): " +
                "${result.srcW}x${result.srcH} -> ${result.outW}x${result.outH}, " +
                "${result.frames} frames @ ~$fps fps q$quality",
        )
        return result.bytes
    }

    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private fun shrinkInternal(bytes: ByteArray, maxDim: Int, maxFps: Int, quality: Int): Encoded? {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
        if (movie == null) {
            Log.i(TAG, "gif kept verbatim (${bytes.size} B) — Movie could not decode it")
            return null
        }
        val srcW = movie.width()
        val srcH = movie.height()
        val duration = movie.duration() // 0 ⇒ unknown timing, can't resample reliably
        if (srcW <= 0 || srcH <= 0 || duration <= 0) {
            Log.i(TAG, "gif kept verbatim (${bytes.size} B) — unknown size/timing (${srcW}x$srcH, ${duration}ms)")
            return null
        }

        // Keep at least 1 ms between samples; stretch the interval if a long GIF would exceed the
        // frame cap, so we preserve the full animation length at a lower effective frame rate.
        var interval = (1000 / maxFps).coerceAtLeast(1)
        if (duration / interval + 1 > MAX_FRAMES) {
            interval = (duration / MAX_FRAMES) + 1
        }

        val frames = sampleFrames(movie, maxDim, quality, interval, duration)
        val muxed = frames?.let { AnimatedWebpMuxer.mux(it.webp, it.durations, it.outW, it.outH) }
        // Never regress: only replace the original when the WebP actually saved bytes. Real GIFs
        // (already-optimized Tenor/Gboard ones included) reliably shrink here, but keep the guard.
        if (frames == null || muxed == null || muxed.size >= bytes.size) {
            Log.i(
                TAG,
                "gif kept verbatim (${bytes.size} B) — webp not smaller (${muxed?.size ?: 0} B, ${srcW}x$srcH" +
                    " -> ${frames?.outW ?: 0}x${frames?.outH ?: 0}, ${frames?.webp?.size ?: 0} frames)",
            )
            return null
        }
        return Encoded(muxed, srcW, srcH, frames.outW, frames.outH, frames.webp.size, duration)
    }

    /** Per-frame single-frame WebP bytes + per-frame on-screen durations + the uniform output dims. */
    private class Frames(
        val webp: List<ByteArray>,
        val durations: IntArray,
        val outW: Int,
        val outH: Int,
    )

    /**
     * Samples [movie] every [interval] ms up to [duration], downscaling each composited frame to [maxDim]
     * and compressing it to a single-frame WebP at [quality]. Returns null if a frame fails to encode or
     * none were produced (so the caller keeps the original GIF).
     */
    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private fun sampleFrames(movie: Movie, maxDim: Int, quality: Int, interval: Int, duration: Int): Frames? {
        // Movie needs a software canvas; one reusable buffer at the source size, drawn each step.
        val frameBuffer = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBuffer)
        val webp = ArrayList<ByteArray>()
        val durations = ArrayList<Int>()
        var outW = 0
        var outH = 0
        var aborted = false
        var t = 0
        while (t < duration) {
            frameBuffer.eraseColor(Color.TRANSPARENT)
            movie.setTime(t)
            movie.draw(canvas, 0f, 0f)

            val scaled = downscale(frameBuffer, maxDim) // same src dims ⇒ uniform frame size
            outW = scaled.width
            outH = scaled.height
            val fo = ByteArrayOutputStream()

            // WEBP (deprecated at API 30) is the API-29 lossy WebP format; WEBP_LOSSY is API 30.
            @Suppress("DEPRECATION")
            val webpFormat =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            val ok = scaled.compress(webpFormat, quality, fo)
            if (scaled !== frameBuffer) scaled.recycle()
            if (!ok) {
                aborted = true
                break
            }
            val next = t + interval
            durations.add(minOf(next, duration) - t) // this frame's share of wall-time
            webp.add(fo.toByteArray())
            t = next
        }
        frameBuffer.recycle()
        return if (aborted || webp.isEmpty()) null else Frames(webp, durations.toIntArray(), outW, outH)
    }
}
