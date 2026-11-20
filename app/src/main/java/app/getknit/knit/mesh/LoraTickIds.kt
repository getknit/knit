package app.getknit.knit.mesh

/**
 * Which ✓✓ ids each compact tick we offered the LoRa plane carries, by the tick's frame id (ADR 2026-10.x9fa's 2026-11-19
 * amendment): the plane names a tick that did not land only by its frame id (`LoraMeshTransport.onDirectUndelivered`),
 * and `MeshManager` puts its ids back in `DmAckCoalescer`'s ride-only hold. Recorded before each offer, since a tick held
 * at the offer is reported inside it.
 *
 * Bounded at [cap] ticks, oldest dropped, and aged out at [ttlMs] (the window a compact copy is taken in): a tick past
 * either is no longer worth a ride, and the author's re-probe heals it. Every entry point holds one monitor and does no
 * I/O, so the plane's coroutines may call [take]. In memory only, like the hold it feeds.
 */
internal class LoraTickIds(
    private val cap: Int = CAP,
    private val ttlMs: Long = LORA_DIRECT_SEND_WINDOW_MS,
) {
    /** One tick's author and ids, and when it was offered. */
    class Tick(
        val authorId: String,
        val ids: List<String>,
        val at: Long,
    )

    private val ticks = LinkedHashMap<String, Tick>()

    /** Records that the compact tick [frameId] to [authorId] carries [ids], offered at [now]. */
    @Synchronized
    fun record(
        frameId: String,
        authorId: String,
        ids: List<String>,
        now: Long,
    ) {
        if (ids.isEmpty()) return
        ticks.remove(frameId)
        ticks[frameId] = Tick(authorId, ids, now)
        while (ticks.size > cap) ticks.remove(ticks.keys.first())
    }

    /** The tick [frameId] names, taken once, or null: never recorded, aged out at [now], or already taken. */
    @Synchronized
    fun take(
        frameId: String,
        now: Long,
    ): Tick? {
        ticks.values.removeAll { now - it.at > ttlMs }
        return ticks.remove(frameId)
    }

    /** How many ticks are recorded (the JVM tests). */
    @Synchronized
    fun size(): Int = ticks.size

    companion object {
        /** The most ticks remembered: a tick per flush, several flushes per author, a few authors at once. */
        const val CAP = 64
    }
}
