package app.getknit.knit.mesh

import app.getknit.knit.data.forward.ForwardRepository

/**
 * The sealed receipts we minted for DMs addressed to us, so a re-delivery of a DM we already acked in this era does
 * not mint another (#176, ADR 2026-10.8hn6).
 *
 * Why it exists: the pre-decrypt exists-gate (`InboundPipeline.decryptAndDeliver`) answers every re-delivered DM, and for a
 * ratchet-capable author off the LoRa board that answer was a new sealed `CTL_RECEIPT` under a fresh id: flooded,
 * custodied mesh-wide for a day, a chain key spent. A sealed receipt purges nothing (ADR 018), so nothing stopped the
 * next copy, and one delivered DM could buy a receipt per router window, about 144 a day — a carrier whose quota
 * kept a DM the recipient evicted, or a back-fill fed by two holders that relayed one holder's copy to the recipient.
 *
 * What a re-delivery is skipped on is [heldReceipt]'s answer plus the caller's custody read, and the caller checks
 * the rest (not LoRa, an author who can read a sealed receipt):
 *
 * - **The era.** An entry names the session root the receipt was sealed under ([Entry.rootTag], taken from the seal
 *   itself under the ratchet lock, never re-read after it: a reset adopted on the other delivery plane can commit in
 *   between). A re-delivery under another root gets one fresh receipt, since the old one was sealed into an era the
 *   author may have abandoned with its receive state (ADR 2026-10.gcrd, ADR 2026-10.dmgw's re-seal).
 * - **The floor.** Held in custody is not applied at the author: it custodies a frame before it opens it, so a
 *   receipt it holds and never applied (a process death between the custody write and the ratchet commit, an open
 *   that failed and left its era unmoved) is never re-served, and its digest hides it from every carrier. So an entry
 *   re-arms [floorMs] after its last receipt, 15 min doubling per re-ack to a 4 h ceiling (ADR 053's tick backoff
 *   shape): at most 10 receipts a DM a day instead of 144 (the ADR's count), and a same-era heal kept.
 * - **Live in our own custody**, which the caller reads (`ForwardStore.frame`, not `has`, which counts an expired row
 *   the sweep has not reached yet): a receipt our quota or the TTL dropped is no longer re-served by anyone, so the
 *   DM's re-delivery is the only way back to a ✓✓ and it re-seals.
 *
 * Only `InboundPipeline.sealDmReceipt` records, for the acked DM's own id — never the room ids that ride along (AckSync
 * owns those). The LoRa coalescer's flush and the acks a reply carries inline are not recorded: the cost is one more
 * receipt on a later re-delivery off the board, once. In memory only, bounded oldest-first at [cap] and aged out
 * [ttlMs] after an entry's last receipt (by then that receipt has left custody, so it would re-seal anyway); a restart
 * re-arms one receipt per held DM, which #176 accepts. Pure: the caller passes the clock. Thread-safe, since the
 * radio's inbound collector and the spool's deliveries run concurrently.
 */
class DmReAckLedger(
    private val cap: Int = ForwardRepository.DEFAULT_MAX_ROWS,
    private val ttlMs: Long = ForwardRepository.DEFAULT_TTL_MS,
) {
    /** One acked DM: the root its last receipt was sealed under, that receipt's frame id, when, and how often re-sealed in this era. */
    class Entry(
        val rootTag: Long,
        val receiptId: String,
        val sealedAt: Long,
        val step: Int,
    )

    // Acked DM id → entry, oldest receipt first (a re-record moves an id to the end); guarded by `synchronized(this)`.
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Records that [receiptId], sealed under the root [rootTag] names at [now], acks [ackedId]. A re-seal in the same era
     * doubles the floor; one under another root starts the era's own floor afresh.
     */
    fun record(
        ackedId: String,
        rootTag: Long,
        receiptId: String,
        now: Long,
    ) = synchronized(this) {
        val previous = entries.remove(ackedId)
        val step = if (previous != null && previous.rootTag == rootTag) (previous.step + 1).coerceAtMost(MAX_STEP) else 0
        entries[ackedId] = Entry(rootTag, receiptId, now, step)
        while (entries.size > cap) entries.remove(entries.keys.first())
    }

    /**
     * The receipt that already answers a re-delivery of [ackedId] at [now] under the root [currentRootTag] names, or
     * null when the re-delivery must re-seal: never acked here (or aged out, or evicted), acked under another root (or
     * no session is held now, null), or past the floor. The caller still re-seals when the receipt this returns is no
     * longer live in its own custody.
     */
    fun heldReceipt(
        ackedId: String,
        currentRootTag: Long?,
        now: Long,
    ): String? =
        synchronized(this) {
            val entry = entries[ackedId] ?: return@synchronized null
            // A negative age is our wall clock stepped back (NITZ, a network fix after weeks offline, ADR 026): read
            // as past the floor, or the jump would hold back the held-but-unapplied re-arm by its own size.
            val age = now - entry.sealedAt
            if (age < 0 || age >= ttlMs) {
                entries.remove(ackedId)
                return@synchronized null
            }
            entry.receiptId.takeIf { currentRootTag != null && entry.rootTag == currentRootTag && age < floorMs(entry.step) }
        }

    /** The entry for [ackedId], for tests and the debug bridge. */
    fun entry(ackedId: String): Entry? = synchronized(this) { entries[ackedId] }

    /** How many acked DMs the ledger holds. */
    val size: Int get() = synchronized(this) { entries.size }

    companion object {
        /** The first re-arm floor: past the router's 10 min seen window, the heal heartbeat's 15 min (ADR 053). */
        const val FLOOR_MS = 15 * 60_000L

        /** The ceiling the doubling stops at. */
        const val MAX_FLOOR_MS = 4 * 60 * 60_000L

        // 15 min << 4 = 4 h; a step past it would only overflow the shift.
        private const val MAX_STEP = 4

        /** The re-arm floor after [step] same-era re-seals: [FLOOR_MS] doubling, capped at [MAX_FLOOR_MS]. */
        fun floorMs(step: Int): Long = (FLOOR_MS shl step.coerceIn(0, MAX_STEP)).coerceAtMost(MAX_FLOOR_MS)
    }
}

/**
 * Whether our receipt [receiptId] is still live in this store at [now] — the custody leg of the re-ack skip (#176,
 * ADR 2026-10.8hn6), the one definition production and the pipeline rig both call. `frame`, never `has`: `has` counts
 * a row the TTL expired and the sweep has not reached, and a receipt nobody re-serves any more must re-seal.
 */
suspend fun ForwardStore.holdsLiveReceipt(
    receiptId: String,
    now: Long,
): Boolean = frame(receiptId, now) != null
