package app.getknit.knit.ui.devview

import app.getknit.knit.mesh.lora.LoraTrace
import app.getknit.knit.mesh.lora.LoraTraceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * What the book holds about one message row, copied out for a reader: the [events] it kept, oldest first, and what it
 * folded away. [heardMore] counts the Heards past the cap; [differentPayload] the Heards whose room-post payload differs
 * from the first one's under the same id; [requeues] the requeues after the first; [dropped] the other events past their
 * kind's cap; [heardOnOwn] the Heards that named this id after this phone sent under it, never kept (a frame heard under
 * our own id is someone else's claim). [originated] says the plane noted a send of ours under it.
 */
class LoraTraceRecord(
    val events: List<LoraTraceEvent>,
    val heardMore: Int,
    val differentPayload: Int,
    val requeues: Int,
    val dropped: Int,
    val originated: Boolean,
    val heardOnOwn: Int = 0,
)

/**
 * What the book says of itself, for a reader whose row it does not hold: when it was switched on ([onSince], this
 * phone's wall clock; null while off) and how many ids it has evicted since. Facts, never a guessed cause.
 */
class LoraTraceBookFacts(
    val onSince: Long?,
    val evicted: Int,
)

/**
 * The developer view's LoRa trace (ADR 2026-10.s7wg, its slice-3 amendment): what the LoRa plane noted about each message
 * row, in memory only, while the view is on. It keeps nothing while the view is off, and switching it off empties it.
 * Nothing here is written, logged, shared or exported; it ends with the process.
 *
 * Bounded against whoever is in radio range (the security reviews):
 * - **a Heard never lands on an id this phone sent under**: the plane notes our own echo nowhere, so a Heard there is
 *   another radio's claim (a forged frame, a contact's compact frame naming our row). It is counted, never kept, and the
 *   Heards an id held before this phone sent under it are dropped into the same count;
 * - **per id**, the first event of each kind always stays: at most [HEARD_CAP] Heards (the rest counted), the first
 *   Requeued and a count of the rest, and at most [KIND_CAP] of every other kind, a newer one replacing the second-oldest.
 *   A flood of copies cannot push the first Heard, Queued, Aired or outcome out;
 * - **book-wide**, ids this phone originated (any event but a Heard: the plane notes those only about our own sends) are
 *   kept apart from heard-only ids, each bounded on its
 *   own and evicted least recently noted first, so a busy public channel cannot push out the message under trial;
 *   evictions are counted;
 * - **by handle**, the packet handles every Aired named, newest [handleCapacity], so a broadcast's late NAK or implicit ack
 *   finds its row.
 *
 * Called from the radio's coroutines: every method takes one short monitor and calls nothing while it holds it.
 */
class LoraTraceBook(
    private val originatedCapacity: Int = ORIGINATED_CAPACITY,
    private val heardCapacity: Int = HEARD_CAPACITY,
    private val handleCapacity: Int = HANDLE_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : LoraTrace {
    private val lock = Any()

    @Volatile private var active = false
    private var onSince: Long? = null
    private var evicted = 0

    // In the order each id was last noted (a note moves it to the end), so the eldest is the least recently noted. Insertion
    // ordered, never access ordered: a reader's lookup must not change what is evicted next.
    private val originated = LinkedHashMap<String, Slot>()
    private val heardOnly = LinkedHashMap<String, Slot>()
    private val handles = LinkedHashMap<Long, String>()

    override val on: Boolean get() = active

    /** Follows the developer view's switch on [scope] for the process. */
    fun follow(
        on: Flow<Boolean>,
        scope: CoroutineScope,
    ) {
        scope.launch { on.distinctUntilChanged().collect(::setActive) }
    }

    /** On keeps what comes next; off empties what was kept and keeps nothing more. */
    fun setActive(on: Boolean) {
        synchronized(lock) {
            if (on == active) return
            originated.clear()
            heardOnly.clear()
            handles.clear()
            evicted = 0
            onSince = if (on) clock() else null
            active = on
        }
    }

    override fun note(
        id: String,
        event: LoraTraceEvent,
    ) {
        synchronized(lock) {
            if (!active) return
            noteLocked(id, event)
        }
    }

    override fun noteByHandle(
        handle: Long,
        event: LoraTraceEvent,
    ) {
        synchronized(lock) {
            if (!active) return
            val id = handles[handle] ?: return
            noteLocked(id, event)
        }
    }

    /** What the book holds about row [id], or null when it holds nothing. A copy. */
    fun of(id: String): LoraTraceRecord? =
        synchronized(lock) {
            originated[id]?.record(originated = true) ?: heardOnly[id]?.record(originated = false)
        }

    fun facts(): LoraTraceBookFacts = synchronized(lock) { LoraTraceBookFacts(onSince, evicted) }

    private fun noteLocked(
        id: String,
        event: LoraTraceEvent,
    ) {
        // Every event but a Heard is about a send of ours: a refusal before the queue too, which is exactly the row a trial
        // asks about, so it is kept on the protected side.
        val originates = event !is LoraTraceEvent.Heard
        if (!originates) {
            // Counted, never kept, and it moves nothing: another radio's claim must not keep our row alive either.
            originated[id]?.let { slot ->
                slot.heardOnOwn++
                return
            }
        }
        val slot = slotFor(id, originates)
        slot.add(event)
        if (event is LoraTraceEvent.Aired) {
            // Every airing's handles, not only the kept ones': a later by-handle answer may name any of them.
            event.handles.forEach { handle ->
                handles.remove(handle)
                handles[handle] = id
            }
            while (handles.size > handleCapacity) handles.remove(handles.keys.first())
        }
    }

    /**
     * The slot for [id], moved to its side's newest end: made, or promoted to the originated side when [originates]. Evicts
     * past either bound.
     */
    private fun slotFor(
        id: String,
        originates: Boolean,
    ): Slot {
        originated.remove(id)?.let { return put(originated, originatedCapacity, id, it) }
        val heard = heardOnly.remove(id)
        return if (originates) {
            put(originated, originatedCapacity, id, heard?.apply { dropHeards() } ?: Slot())
        } else {
            put(heardOnly, heardCapacity, id, heard ?: Slot())
        }
    }

    private fun put(
        map: LinkedHashMap<String, Slot>,
        capacity: Int,
        id: String,
        slot: Slot,
    ): Slot {
        map[id] = slot
        while (map.size > capacity) {
            map.remove(map.keys.first())
            evicted++
        }
        return slot
    }

    /** One row's events, bounded per kind. Touched only under the book's monitor. */
    private class Slot {
        private val heards = ArrayList<LoraTraceEvent.Heard>(HEARD_CAP)
        private val others = HashMap<Class<*>, ArrayList<LoraTraceEvent>>()
        private var firstRequeue: LoraTraceEvent.Requeued? = null
        private var heardMore = 0
        private var differentPayload = 0
        private var requeues = 0
        private var dropped = 0
        var heardOnOwn = 0

        fun add(event: LoraTraceEvent) {
            when (event) {
                is LoraTraceEvent.Heard -> addHeard(event)
                is LoraTraceEvent.Requeued -> if (firstRequeue == null) firstRequeue = event else requeues++
                else -> addOther(event)
            }
        }

        private fun addHeard(event: LoraTraceEvent.Heard) {
            val firstTag = heards.firstOrNull()?.payloadTag
            if (firstTag != null && event.payloadTag != null && event.payloadTag != firstTag) differentPayload++
            if (heards.size < HEARD_CAP) heards += event else heardMore++
        }

        /** The Heards an id held before this phone sent under it: another radio's claims, counted and dropped. */
        fun dropHeards() {
            heardOnOwn += heards.size + heardMore
            heards.clear()
            heardMore = 0
            differentPayload = 0
        }

        private fun addOther(event: LoraTraceEvent) {
            val kept = others.getOrPut(event.javaClass) { ArrayList(KIND_CAP) }
            if (kept.size >= KIND_CAP) {
                // The first stays; the second-oldest makes room, so the newest is always there too.
                kept.removeAt(1)
                dropped++
            }
            kept += event
        }

        fun record(originated: Boolean): LoraTraceRecord {
            val all = ArrayList<LoraTraceEvent>(heards.size + others.values.sumOf { it.size } + 1)
            all += heards
            others.values.forEach { all += it }
            firstRequeue?.let { all += it }
            // Stable: events noted in the same millisecond keep the order of their kind's list.
            all.sortBy { it.atMs }
            return LoraTraceRecord(all, heardMore, differentPayload, requeues, dropped, originated, heardOnOwn)
        }
    }

    companion object {
        const val ORIGINATED_CAPACITY = 128
        const val HEARD_CAPACITY = 256
        const val HANDLE_CAPACITY = 1_024
        const val HEARD_CAP = 8
        const val KIND_CAP = 6
    }
}
