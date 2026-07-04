package app.getknit.knit.data.gif

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.util.Log
import app.getknit.knit.data.downscale
import java.io.ByteArrayOutputStream

/**
 * Shrinks an animated GIF for faster mesh transmit: resamples its frames at a capped frame rate,
 * downscales them, and re-quantizes/re-encodes via the vendored [AnimatedGifEncoder] (Android has no
 * built-in animated-GIF encoder — `Bitmap.compress` writes single frames only).
 *
 * The output stays a GIF (`image/gif`), so nothing downstream changes — the wire frame's
 * `attachmentMime`, the blob store, moderation's first-frame decode, and Coil's playback all keep
 * working. Only [app.getknit.knit.data.AttachmentStore.ingest] calls this, on the send side.
 *
 * Decoding uses [android.graphics.Movie]: it renders the composited frame at any time offset, which
 * is exactly the fixed-rate resampling decimation needs. It's deprecated but the only built-in way to
 * sample GIF frames, and it's still functional on current Android. [shrink] **never regresses** — it
 * returns null (caller keeps the original bytes) whenever the GIF can't be decoded, reports unknown
 * timing, or the re-encode wouldn't actually be smaller.
 *
 * Limitation: `Movie` renders full composited frames, which is correct for the full-frame opaque GIFs
 * that dominate chat use; a partial-frame ("dirty rectangle") GIF could show minor artifacts. If that
 * ever bites in the field, swap the decode for a frame-accurate GIF decoder.
 */
object GifTranscode {

    private const val TAG = "GifTranscode"

    /** Cap on emitted frames so a very long GIF can't blow up encode time / output size. */
    private const val MAX_FRAMES = 200

    /** A successful re-encode plus the before/after stats logged for it. */
    private class Shrunk(
        val bytes: ByteArray,
        val srcW: Int,
        val srcH: Int,
        val outW: Int,
        val outH: Int,
        val frames: Int,
        val durationMs: Int,
    )

    /**
     * Re-encodes [bytes] into a smaller GIF bounded by [maxDim] (longest edge) and [maxFps]. Returns
     * the smaller GIF, or null if decode/encode fails, the timing is unknown, or the result isn't
     * smaller than the input.
     */
    fun shrink(bytes: ByteArray, maxDim: Int, maxFps: Int): ByteArray? {
        val result = try {
            shrinkInternal(bytes, maxDim, maxFps)
        } catch (_: Throwable) {
            // A codec failure must never break sending — fall back to the original bytes.
            null
        }
        if (result == null) {
            Log.i(TAG, "gif kept verbatim (${bytes.size} B) — not smaller / undecodable")
            return null
        }
        // Record the full before/after picture — bytes, dimensions, and frame rate — so a shrink
        // (or a surprising one) can be sized up from the log line alone.
        val pct = 100 - (result.bytes.size * 100L / bytes.size)
        val fps = result.frames * 1000 / result.durationMs
        Log.i(
            TAG,
            "gif shrunk ${bytes.size} B -> ${result.bytes.size} B ($pct% smaller): " +
                "${result.srcW}x${result.srcH} -> ${result.outW}x${result.outH}, " +
                "${result.frames} frames @ ~$fps fps",
        )
        return result.bytes
    }

    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private fun shrinkInternal(bytes: ByteArray, maxDim: Int, maxFps: Int): Shrunk? {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val srcW = movie.width()
        val srcH = movie.height()
        val duration = movie.duration() // 0 ⇒ unknown timing, can't resample reliably
        if (srcW <= 0 || srcH <= 0 || duration <= 0) return null

        // Keep at least 1 ms between samples; stretch the interval if a long GIF would exceed the
        // frame cap, so we preserve the full animation length at a lower effective frame rate.
        var interval = (1000 / maxFps).coerceAtLeast(1)
        if (duration / interval + 1 > MAX_FRAMES) {
            interval = (duration / MAX_FRAMES) + 1
        }

        // Movie needs a software canvas; one reusable buffer at the source size, drawn each step.
        val frameBuffer = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBuffer)
        val out = ByteArrayOutputStream()
        val encoder = AnimatedGifEncoder()
        encoder.setRepeat(0) // loop forever, like a typical GIF

        var frames = 0
        var outW = 0
        var outH = 0
        if (encoder.start(out)) {
            var t = 0
            while (t < duration) {
                frameBuffer.eraseColor(Color.TRANSPARENT)
                movie.setTime(t)
                movie.draw(canvas, 0f, 0f)

                val scaled = downscale(frameBuffer, maxDim) // same src dims ⇒ uniform frame size
                outW = scaled.width
                outH = scaled.height
                val next = t + interval
                encoder.setDelay(minOf(next, duration) - t) // this frame's share of wall-time
                encoder.addFrame(scaled)
                if (scaled !== frameBuffer) scaled.recycle()

                frames++
                t = next
            }
        }
        val encoded = if (frames > 0 && encoder.finish()) out.toByteArray() else null
        frameBuffer.recycle()

        // Never regress: only replace the original when the re-encode actually saved bytes.
        val smaller = encoded?.takeIf { it.size < bytes.size }
        return if (smaller == null) null else Shrunk(smaller, srcW, srcH, outW, outH, frames, duration)
    }
}
