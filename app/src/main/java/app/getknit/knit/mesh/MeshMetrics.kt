package app.getknit.knit.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe counters for mesh transmission, so the effect of flood suppression and the CBOR wire
 * format is measurable in the field. Pure JVM (no Android dependencies) so it can live in [mesh] and
 * be asserted from the same unit tests as [MeshRouter].
 *
 * The ratio of [Snapshot.framesSuppressed] to [Snapshot.framesRelayed] shows how much redundant
 * rebroadcasting the overhear suppression eliminates; [Snapshot.bytesSent] tracks the CBOR win.
 */
class MeshMetrics {
    private val framesOriginated = AtomicLong()
    private val framesDelivered = AtomicLong()
    private val framesRelayed = AtomicLong()
    private val framesSuppressed = AtomicLong()
    private val framesDeduped = AtomicLong()
    private val bytesSent = AtomicLong()

    /** A frame this device authored and injected into the mesh. */
    fun onOriginated() { framesOriginated.incrementAndGet() }

    /** A newly-seen frame delivered to the app layer. */
    fun onDelivered() { framesDelivered.incrementAndGet() }

    /** A pending relay that fired (we forwarded the frame onward). */
    fun onRelayed() { framesRelayed.incrementAndGet() }

    /** A pending relay we cancelled because a neighbor was overheard relaying the same frame. */
    fun onSuppressed() { framesSuppressed.incrementAndGet() }

    /** A duplicate of an already-seen frame, dropped without re-delivery. */
    fun onDeduped() { framesDeduped.incrementAndGet() }

    /** [bytes] put on the wire (counted once per target endpoint a payload is sent to). */
    fun onBytesSent(bytes: Long) { bytesSent.addAndGet(bytes) }

    fun snapshot(): Snapshot = Snapshot(
        framesOriginated = framesOriginated.get(),
        framesDelivered = framesDelivered.get(),
        framesRelayed = framesRelayed.get(),
        framesSuppressed = framesSuppressed.get(),
        framesDeduped = framesDeduped.get(),
        bytesSent = bytesSent.get(),
    )

    data class Snapshot(
        val framesOriginated: Long,
        val framesDelivered: Long,
        val framesRelayed: Long,
        val framesSuppressed: Long,
        val framesDeduped: Long,
        val bytesSent: Long,
    )
}
