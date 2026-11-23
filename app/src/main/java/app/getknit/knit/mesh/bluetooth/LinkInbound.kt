package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.link.LinkCrossings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * How one Bluetooth link hands the frames its reader decodes to the plane's router (#331, ADR 2026-11.43f7). Pure of
 * Android: the clock, the log and the counter are the caller's, so `LinkInboundTest` drives it over a real flow.
 *
 * **The reader waits for room; it never drops.** The far end marked each frame crossed when it wrote it, and for the
 * router `SeenSet`'s ten minutes it skips re-serving that frame over this link ([LinkCrossings], ADR 2026-09.6nmy). A
 * frame a full queue refused was therefore withheld from us for ten minutes, and custody's 1,000-row cap could evict it
 * everywhere meanwhile: a DM lost for good. So a frame that finds [inbound] (the links' lane, [BleInbound.links]) full
 * suspends its link's reader until the router makes room, as the iPhone's radio stops reading a channel past 256 KiB
 * unprocessed (knit-ios ADR 2026-10.f5zs, rule 6). A [MutableSharedFlow] resumes its waiting emitters in order, so
 * every parked link advances at its share of the router's rate. Whether Android's stack then withholds the channel's LE
 * credits from the sender (pushback), buffers without a bound, or drops is not known: the device trial's first question.
 *
 * **The wait is a suspension inside the link's `readerJob`**, so `FramedLink.close()` cancels it: a torn-down link's
 * reader never stays blocked on a router that is behind, and never hands the router a frame after its link ended. A
 * blocking wait (`runBlocking`, a blocking channel send) is out of `close()`'s reach.
 *
 * **A crossing is marked after the hand-off, and only while this link is the plane's ([current]).** After: a frame
 * counts as crossed once the router's queue holds it, never while it waits, so a hand-off cancelled by `close()` leaves
 * no mark the far end's re-serve would be skipped for. The cost is a frame the router re-fans back toward this link in
 * the instant between the two, which the far end's `SeenSet` drops. Only while current: a link replaced under the same
 * node id, or torn down, while its reader waited must not mark the replacement's memo, which would withhold the frame
 * on the new link for ten minutes. [LinkCrossings.firstCrossingIf] reads [current] under the lock its `forget` takes.
 * A point-to-point frame marks nothing ([LinkCrossings.marksInbound], #356).
 *
 * Each wait is counted ([onWaited]) and the reader's spell of waits is logged at its start and its end: `bt inbound
 * wait <id>` and `bt inbound waited <id> frames=<n> ms=<total> (<why>)`. A spell ends at the first frame that goes
 * straight in (`room`), at a wait `close()` cancelled (`closed`), at the first frame read once the link is no longer
 * current (`replaced`), or when the reader's loop ends ([end], `ended`), so every `wait` line is followed by one `waited`.
 *
 * The reader's alone: one instance per link, called only on its reader.
 */
class LinkInbound(
    private val nodeId: String,
    private val inbound: MutableSharedFlow<InboundFrame>,
    private val crossings: LinkCrossings,
    // Whether this link is the one the plane holds for [nodeId] now (`links[nodeId] === link`).
    private val current: () -> Boolean,
    // elapsedRealtime: a measure, never a wait, so time asleep counts as time the reader waited.
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val onWaited: (ms: Long) -> Unit = {},
) {
    // The spell of waits in progress: frames that waited, and their total ms. Zero frames: none.
    private var spellFrames = 0
    private var spellMs = 0L

    /**
     * Hands [frame] to the router, waiting for room if its queue is full. False, and nothing handed, for a frame read off
     * a link that is no longer the plane's. Throws [CancellationException] when the reader is cancelled while it waits.
     */
    suspend fun hand(frame: InboundFrame): Boolean {
        if (!current()) {
            endSpell("replaced")
            return false // read off a link already torn down or replaced: it dies with that link
        }
        if (inbound.tryEmit(frame)) {
            endSpell("room")
        } else {
            if (spellFrames == 0) log("bt inbound wait $nodeId: the router's queue is full")
            val from = now()
            try {
                inbound.emit(frame) // the wait: room, in order behind every other link waiting
            } catch (e: CancellationException) {
                noteWait(now() - from)
                endSpell("closed")
                throw e
            }
            noteWait(now() - from)
        }
        if (LinkCrossings.marksInbound(frame.wire)) {
            crossings.firstCrossingIf(nodeId, FrameKey.of(frame.wire, frame.envelope), current)
        }
        return true
    }

    /** The reader's loop ended (`FramedLink`'s `onReadEnd`): a spell still open ends with the link. */
    fun end() = endSpell("ended")

    private fun noteWait(ms: Long) {
        spellFrames++
        spellMs += ms
        onWaited(ms)
    }

    private fun endSpell(why: String) {
        if (spellFrames == 0) return
        log("bt inbound waited $nodeId frames=$spellFrames ms=$spellMs ($why)")
        spellFrames = 0
        spellMs = 0L
    }
}
