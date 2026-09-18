package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * What the BLE side channel has on air, and in what order the rest goes up — the outbound half of
 * [BleSideChannel], kept pure so the schedule is asserted in a JVM test with a fake clock.
 *
 * A page is one advertising-set payload: exactly one coordination-plane unit (a complete `0x03`/`0x05` frame,
 * or one `0x04` fragment of one), because an extended advert that fits one AUX PDU is caught or missed whole
 * inside a scanner's window, while a chained one is lost if any PDU is. The carousel owns [Config.slots]
 * such pages — one per advertising set — and rotates the queue through them:
 *
 * - A part **dwells** at least [Config.dwellMs] once on air (long enough for a screen-off scanner's 10.24 s
 *   interval to land one window on it), then yields its slot only when something is waiting; otherwise it
 *   keeps airing up to [Config.lingerMs] longer, so an idle channel over-airs its last frames (free — every
 *   receiver dedups) rather than stopping and restarting a set per message.
 * - A frame's parts go up together when slots allow (both halves of a two-part post air at once); a started
 *   frame's remaining parts go before any new frame, then **fewer-part frames first**, FIFO within — a
 *   one-packet tick never waits behind a three-part profile.
 * - A frame not yet started is dropped once older than its freshness ([Config.freshMs]; a typing cue
 *   [Config.typingFreshMs]); a started frame always finishes. A typing cue **coalesces** per
 *   [coalesceKey]: a queued one is replaced, one already on air makes the new one redundant.
 * - Bounded: [Config.capacity] frames; past it the oldest *unstarted* frame is shed, never one on air. A
 *   sender-side [SeenSet] on the frame key drops a frame offered twice inside the window (the composite
 *   re-fans what it already originated).
 *
 * Single-writer by contract ([BleSideChannel] serialises its callers under one lock); `@Synchronized` guards
 * the reads its diag line takes from other threads.
 */
internal class SideCarousel(
    private val now: () -> Long,
    private val config: Config = Config(),
    private val onDrop: (Drop) -> Unit = {},
) {
    data class Config(
        val dwellMs: Long = DWELL_MS,
        val lingerMs: Long = LINGER_MS,
        val freshMs: Long = FRESH_MS,
        val typingFreshMs: Long = TYPING_FRESH_MS,
        val capacity: Int = CAPACITY,
        val slots: Int = SLOTS,
    )

    /** What kind of frame an offer carries — only the typing cue is scheduled differently. */
    enum class Kind { CONTENT, TYPING }

    /** What [offer] did with the frame. */
    enum class Offer {
        /** Accepted onto the queue (or straight into a free slot on the next [tick]). */
        QUEUED,

        /** Replaced a queued, not-yet-started frame carrying the same [coalesceKey]. */
        REPLACED,

        /** A frame with the same [coalesceKey] is on air right now; this one says nothing new. */
        COALESCED,

        /** The same frame (by key) was offered inside the seen window. */
        DUPLICATE,

        /** The queue is full of started frames; nothing could be shed. */
        OVERFLOW,
    }

    /** Why a queued frame was discarded (surfaced as metrics by the channel). */
    enum class Drop { STALE, OVERFLOW }

    private class Frame(
        val key: String,
        val kind: Kind,
        val coalesceKey: String?,
        val parts: List<ByteArray>,
        val offeredAt: Long,
    ) {
        /** Index of the next part to put on air. */
        var next = 0

        val started: Boolean get() = next > 0
        val exhausted: Boolean get() = next >= parts.size
    }

    private class Slot {
        var frame: Frame? = null
        var bytes: ByteArray? = null
        var onAirAt = 0L
    }

    // Frames with at least one part still to go up, in offer order.
    private val queue = ArrayList<Frame>()
    private val slots = List(config.slots) { Slot() }

    // A slot the controller refused (too many advertising sets) is skipped until the channel re-enables it.
    private val usable = BooleanArray(config.slots) { true }
    private val seen = SeenSet(maxSize = SEEN_MAX, ttlMillis = SEEN_TTL_MS, clock = now)

    /** Bumps whenever any slot's content changes, so the channel pushes only what moved. */
    @get:Synchronized
    var version = 0L
        private set

    @Synchronized
    fun offer(
        parts: List<ByteArray>,
        kind: Kind,
        coalesceKey: String?,
        key: String,
    ): Offer {
        require(parts.isNotEmpty()) { "a frame has at least one part" }
        if (!seen.add(key)) return Offer.DUPLICATE
        val frame = Frame(key, kind, coalesceKey, parts, now())
        val coalesced = coalesceKey?.let { coalesce(it, frame) }
        return coalesced ?: enqueue(frame)
    }

    /** The coalescing outcome for [frame], or null when no frame with its key is queued or on air. */
    private fun coalesce(
        coalesceKey: String,
        frame: Frame,
    ): Offer? {
        if (slots.any { it.frame?.coalesceKey == coalesceKey }) return Offer.COALESCED
        val queued = queue.indexOfFirst { it.coalesceKey == coalesceKey && !it.started }
        if (queued < 0) return null
        queue[queued] = frame
        return Offer.REPLACED
    }

    private fun enqueue(frame: Frame): Offer {
        if (queue.size >= config.capacity) {
            val victim = queue.indexOfFirst { !it.started }
            if (victim < 0) return Offer.OVERFLOW
            queue.removeAt(victim)
            onDrop(Drop.OVERFLOW)
        }
        queue.add(frame)
        return Offer.QUEUED
    }

    /**
     * Advances the schedule to [now]: sheds stale unstarted frames, retires dwelled parts whose slot is wanted
     * (or that have lingered out), and fills every free slot. True when a slot's content changed.
     */
    @Synchronized
    fun tick(): Boolean {
        val t = now()
        shedStale(t)
        val changed = retireDwelled(t) or fill(t)
        if (changed) version++
        return changed
    }

    private fun shedStale(t: Long) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (!f.started && t - f.offeredAt >= freshFor(f.kind)) {
                it.remove()
                onDrop(Drop.STALE)
            }
        }
    }

    /** A dwelled part yields its slot when something is waiting, or once it has lingered out. */
    private fun retireDwelled(t: Long): Boolean {
        var changed = false
        val wanted = queue.isNotEmpty()
        for (s in slots) {
            val onAir = if (s.frame == null) -1L else t - s.onAirAt
            if (onAir >= config.dwellMs && (wanted || onAir >= config.dwellMs + config.lingerMs)) {
                s.frame = null
                s.bytes = null
                changed = true
            }
        }
        return changed
    }

    private fun fill(t: Long): Boolean {
        var changed = false
        for ((i, s) in slots.withIndex()) {
            if (usable[i] && s.frame == null) {
                val f = pick() ?: return changed
                s.frame = f
                s.bytes = f.parts[f.next++]
                s.onAirAt = t
                if (f.exhausted) queue.remove(f)
                changed = true
            }
        }
        return changed
    }

    /**
     * Marks slot [index] usable or not. An unusable slot is never filled, and whatever it held is dropped on
     * the spot (the set never came up, so nobody heard it; the flood carries the frame regardless).
     */
    @Synchronized
    fun setSlotUsable(
        index: Int,
        on: Boolean,
    ) {
        if (usable[index] == on) return
        usable[index] = on
        val s = slots[index]
        if (!on && s.frame != null) {
            s.frame = null
            s.bytes = null
            version++
        }
    }

    /** The payload each slot should be advertising right now (null = the set should be down). */
    @Synchronized
    fun pages(): List<ByteArray?> = slots.map { it.bytes }

    /** The instant [tick] next has something to do, or null while nothing is queued or on air. */
    @Synchronized
    fun nextDeadlineMs(): Long? {
        var next: Long? = null

        fun consider(at: Long) {
            next = next?.let { minOf(it, at) } ?: at
        }
        val wanted = queue.isNotEmpty()
        for (s in slots) {
            s.frame ?: continue
            consider(s.onAirAt + config.dwellMs + if (wanted) 0L else config.lingerMs)
        }
        for (f in queue) if (!f.started) consider(f.offeredAt + freshFor(f.kind))
        return next
    }

    @Synchronized
    fun queued(): Int = queue.size

    @Synchronized
    fun onAir(): Int = slots.count { it.frame != null }

    /** Forgets the queue and empties every slot (radio down); the seen window is kept on purpose. */
    @Synchronized
    fun clear() {
        queue.clear()
        for (s in slots) {
            s.frame = null
            s.bytes = null
        }
        version++
    }

    private fun freshFor(kind: Kind): Long = if (kind == Kind.TYPING) config.typingFreshMs else config.freshMs

    // A started frame's remaining parts first (finish what a receiver may be holding half of), then the frame
    // with the fewest parts, oldest first — `minWithOrNull` keeps the first of equals, so FIFO holds on ties.
    private fun pick(): Frame? = queue.firstOrNull { it.started } ?: queue.minWithOrNull(compareBy({ it.parts.size }, { it.offeredAt }))

    companion object {
        /** A part holds its slot at least this long: one screen-off scan interval (10.24 s) plus slack. */
        const val DWELL_MS = 12_000L

        /** How much longer an unwanted slot keeps its last part airing before the set goes down. */
        const val LINGER_MS = 30_000L

        /** A frame nobody had room for in this long rides the flood alone. */
        const val FRESH_MS = 30_000L

        /** A typing cue is worthless sooner (`TypingTracker` shows one for ~12 s). */
        const val TYPING_FRESH_MS = 10_000L

        const val CAPACITY = 32

        /** Advertising sets the channel raises — one part on air each. */
        const val SLOTS = 2

        private const val SEEN_MAX = 256
        private const val SEEN_TTL_MS = 10 * 60_000L

        /**
         * The dedup key both ends of the channel use — [FrameKey], shared with the links' crossing memo: the
         * signature's first bytes (a re-seal keeps its id but carries a fresh signature, so the id would
         * suppress it), or the id under its own namespace for an unsigned frame.
         */
        fun frameKey(
            wire: WireEnvelope,
            env: RelayEnvelope,
        ): String = FrameKey.of(wire, env)
    }
}
