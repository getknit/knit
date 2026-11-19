package app.getknit.knit.ui.redact

import android.graphics.Bitmap
import app.getknit.knit.redact.FaceDetector
import app.getknit.knit.redact.FaceRedaction
import app.getknit.knit.redact.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One photo on the review screen at a time, from the moment it is opened to the moment its covered bytes are handed
 * back (ADR 2026-10.whkk). The chat owns one; the screen draws [pending] and reports edits here.
 *
 * Nothing leaves without [finish]: a cancelled review hands nothing back, and a photo is only ever handed back covered
 * as the person last saw it. [open] takes a software bitmap, already bounded and upright, because the screen and
 * [FaceRedaction] read its pixels.
 */
class PhotoReview(
    private val scope: CoroutineScope,
    private val faces: FaceDetector?,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Where a reviewed photo goes once it is covered. */
    sealed interface Purpose {
        /** Staged in the composer, as any photo attached in the chat. */
        data object Attach : Purpose

        /** Offered over a direct Wi-Fi transfer under [name], the name the peer is shown. */
        data class Transfer(
            val name: String,
        ) : Purpose
    }

    /** The photo under review, what will be covered, how the search stands, and whether its bytes are being made. */
    data class Pending(
        val photo: Bitmap,
        val redaction: Redaction,
        val search: FaceSearch,
        val sending: Boolean = false,
        val purpose: Purpose = Purpose.Attach,
    ) {
        /** Whether the person may change the covers: the search is over and nothing is being sent. */
        val editable: Boolean get() = search != FaceSearch.Looking && !sending
    }

    /** A finished review: the covered photo's bytes as [FaceRedaction.encode] made them, and where they go. */
    class Finished(
        val mime: String,
        val bytes: ByteArray,
        val purpose: Purpose,
    )

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The review on screen, or null when there is none. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var search: Job? = null

    /**
     * Puts [photo] up for review, for [purpose], replacing any review open, and looks for its faces. With no detector,
     * or one that cannot run, the search is [FaceSearch.Unavailable] and every face is the person's to cover.
     */
    fun open(
        photo: Bitmap,
        purpose: Purpose = Purpose.Attach,
    ) {
        search?.cancel()
        val bare = Redaction(photo.width, photo.height)
        val looking = Pending(photo, bare, FaceSearch.Looking, purpose = purpose)
        _pending.value = looking
        search =
            scope.launch {
                // A detector that throws is one that could not run: the person covers by hand, the chat does not crash.
                val found =
                    try {
                        faces?.detect(photo)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                val searched =
                    if (found == null) {
                        looking.copy(search = FaceSearch.Unavailable)
                    } else {
                        looking.copy(redaction = Redaction.of(photo.width, photo.height, found), search = FaceSearch.Done)
                    }
                // Only over the review this search was for: a cancel, or another photo opened since, wins.
                _pending.compareAndSet(looking, searched)
            }
    }

    fun tap(
        x: Float,
        y: Float,
        slop: Float,
    ) = edit { it.tap(x, y, slop) }

    fun draw(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) = edit { it.draw(x0, y0, x1, y1) }

    fun toggle(id: Int) = edit { it.toggle(id) }

    /** Closes the review and hands nothing back. */
    fun cancel() {
        search?.cancel()
        _pending.value = null
    }

    /**
     * The photo as the person last saw it, covered and encoded to send ([FaceRedaction.encode]), and the review closed;
     * null when there is none to finish (no review, the search still running, already sending) or it was cancelled
     * while the bytes were being made.
     */
    suspend fun finish(): Finished? {
        val shown = _pending.value?.takeIf { it.editable } ?: return null
        val sending = shown.copy(sending = true)
        if (!_pending.compareAndSet(shown, sending)) return null
        val (mime, bytes) = withContext(work) { FaceRedaction.encode(FaceRedaction.covered(shown.photo, shown.redaction)) }
        return Finished(mime, bytes, shown.purpose).takeIf { _pending.compareAndSet(sending, null) }
    }

    private fun edit(change: (Redaction) -> Redaction) {
        _pending.update { pending ->
            if (pending != null &&
                pending.editable
            ) {
                pending.copy(redaction = change(pending.redaction))
            } else {
                pending
            }
        }
    }
}
