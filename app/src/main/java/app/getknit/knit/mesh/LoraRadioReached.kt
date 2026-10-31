package app.getknit.knit.mesh

/**
 * The one piece of evidence between our DM's ✓ and its ✓✓ over LoRa (ADR 040's 2026-10-31 amendment): the recipient's
 * own board acked the compact copy, so their **radio** has the packet. Never their phone — it may be away from its
 * board, and the board's to-phone queue drops a packet when full after it has acked it — so the chat says "reached their
 * LoRa radio" and keeps waiting for the ✓✓, which always wins once it lands.
 *
 * [atMs] is this phone's wall clock when the ack reached it. [late] is an ack that arrived after the plane had stopped
 * waiting — replayed from our board's to-phone queue after a Bluetooth drop, possibly minutes after their board took the
 * packet — so its time says nothing and the details screen shows none.
 *
 * Held in memory only ([MeshManager.loraRadioReached]): no column and no migration. A restart loses it, and a DM still
 * without its ✓✓ falls back to a plain ✓, which is honest — the evidence went with the process.
 */
data class LoraRadioReached(
    val atMs: Long,
    val late: Boolean,
) {
    companion object {
        /**
         * How many DMs are remembered, oldest dropped first. A DM's ✓✓ normally lands within a minute of its board's ack,
         * and a dropped entry only takes the middle state back to a plain ✓.
         */
        const val MAX_HELD = 256

        /**
         * [held] with [frameId] recorded as [reach], unless it is already there — the first ack wins: a resend or a
         * re-probe of the same DM carries the same id, and its ack says nothing the first did not. The one exception is a
         * [late] entry, which an on-time ack replaces: a re-probe acked on time gives the details screen a time the late
         * replay could not. Past [max] entries the oldest goes. Pure, so the caller can run it inside a lock-free
         * `MutableStateFlow.update`.
         */
        fun record(
            held: Map<String, LoraRadioReached>,
            frameId: String,
            reach: LoraRadioReached,
            max: Int = MAX_HELD,
        ): Map<String, LoraRadioReached> {
            val existing = held[frameId]
            if (existing != null && !(existing.late && !reach.late)) return held
            val next = LinkedHashMap(held)
            next[frameId] = reach
            while (next.size > max) next.remove(next.keys.first())
            return next
        }
    }
}
