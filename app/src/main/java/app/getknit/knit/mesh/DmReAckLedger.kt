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
 * What a copy is skipped on is [heldReceipt]'s answer plus the caller's custody read, and the caller checks that the
 * author can read a sealed receipt. A LoRa copy is asked at the coalescer's flush, not at its re-ack (#300).
 *
 * The author chooses a DM's id, so an id names a DM only together with its author: every entry and every claim here is
 * keyed by both, and one author's receipt never touches another's entry, floor or claim.
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
 * Every signed DM ✓✓ records, for each acked DM's own id, whichever path sealed it (#300): `InboundPipeline.sealDmReceipt`,
 * and `MeshManager.originateDeliveryTick` for the LoRa coalescer's tick and a held tick's flush. Never the room ids that
 * ride along (AckSync owns those), never an id a compact tick alone covered (its id is not in custody, so it could
 * never read as live), and never a declined answer. The acks a reply carries inline are not recorded: the cost is one
 * more receipt on a later re-delivery off the board, once. In memory only, bounded oldest-first at [cap] and aged out
 * [ttlMs] after an entry's last receipt (by then that receipt has left custody, so it would re-seal anyway); a restart
 * re-arms one receipt per held DM, which #176 accepts. Pure: the caller passes the clock. Thread-safe, since the
 * radio's inbound collector and the spool's deliveries run concurrently.
 *
 * The record lands only after the seal, so it cannot stop a second path already past its read. [claim] does: each path
 * that seals a DM ✓✓ claims the DM first, reads this ledger second, and lets go once it has recorded or given up. One
 * event can start two such paths (a commit that moves the root wakes the held tick's flush while the pipeline carries on
 * to its own re-ack, #300), and a `Mutex` around the seal would be a new lock around a store path, which
 * `DmAckCoalescer.hold` reaches under `loraDirectLock`, so the claim never suspends and never waits.
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

    /** A DM as this ledger knows it: the id alone says nothing, since its author chose it. */
    private data class Key(
        val authorId: String,
        val ackedId: String,
    )

    // Acked DM → entry, oldest receipt first (a re-record moves a DM to the end); guarded by `synchronized(this)`.
    private val entries = LinkedHashMap<Key, Entry>()

    // DMs a path is sealing right now, each with whether another path gave way to it; guarded by `synchronized(this)`.
    // Only DMs in flight: it never outlives a seal.
    private val claims = HashMap<Key, Boolean>()

    /**
     * Takes [authorId]'s [ackedId] for one seal, true when no other path holds it. Taken **before** the ledger is read: a
     * path that read first could pass the read, lose the CPU to a whole seal, and then claim a DM already answered. A
     * loser does not answer now and does not drop the id either: it holds it as a declined ✓✓ is held, and the next
     * flush or heal asks the ledger, which skips it if the winner recorded under the root held then and seals it if
     * not. The winner may have sealed under an era since replaced, or thrown. The winner [release]s in a `finally`,
     * after it has recorded, so the next path to claim finds the record.
     */
    fun claim(
        authorId: String,
        ackedId: String,
    ): Boolean =
        synchronized(this) {
            val key = Key(authorId, ackedId)
            if (key in claims) return@synchronized false
            claims[key] = false
            true
        }

    /**
     * Tells the path holding [authorId]'s [ackedId] that a loser has held the id, true when one still does: its [release]
     * then says so. False means it let go first, and the loser must ask for the flush itself. Called after the hold, so
     * whichever side acts finds the id there.
     */
    fun contest(
        authorId: String,
        ackedId: String,
    ): Boolean =
        synchronized(this) {
            val key = Key(authorId, ackedId)
            (key in claims).also { held -> if (held) claims[key] = true }
        }

    /**
     * Lets go of [authorId]'s ids this path [claim]ed, true when a loser [contest]ed one: its id waits in the hold, and
     * the caller should have the hold flushed now: nothing else would wake it until the next root move or heal.
     */
    fun release(
        authorId: String,
        ackedIds: Collection<String>,
    ): Boolean =
        synchronized(this) {
            ackedIds.fold(false) { contested, id -> (claims.remove(Key(authorId, id)) == true) || contested }
        }

    /**
     * Records that [receiptId], sealed under the root [rootTag] names at [now], acks [authorId]'s [ackedId]. A re-seal in
     * the same era doubles the floor; one under another root starts the era's own floor afresh.
     */
    fun record(
        authorId: String,
        ackedId: String,
        rootTag: Long,
        receiptId: String,
        now: Long,
    ) = synchronized(this) {
        val key = Key(authorId, ackedId)
        val previous = entries.remove(key)
        val step = if (previous != null && previous.rootTag == rootTag) (previous.step + 1).coerceAtMost(MAX_STEP) else 0
        entries[key] = Entry(rootTag, receiptId, now, step)
        while (entries.size > cap) entries.remove(entries.keys.first())
    }

    /**
     * The receipt that already answers a copy of [authorId]'s [ackedId] at [now] under the root [currentRootTag] names, or
     * null when the re-delivery must re-seal: never acked here (or aged out, or evicted), acked under another root (or
     * no session is held now, null), or past the floor. The caller still re-seals when the receipt this returns is no
     * longer live in its own custody.
     */
    fun heldReceipt(
        authorId: String,
        ackedId: String,
        currentRootTag: Long?,
        now: Long,
    ): String? =
        synchronized(this) {
            val key = Key(authorId, ackedId)
            val entry = entries[key] ?: return@synchronized null
            // A negative age is our wall clock stepped back (NITZ, a network fix after weeks offline, ADR 026): read
            // as past the floor, or the jump would hold back the held-but-unapplied re-arm by its own size.
            val age = now - entry.sealedAt
            if (age < 0 || age >= ttlMs) {
                entries.remove(key)
                return@synchronized null
            }
            entry.receiptId.takeIf { currentRootTag != null && entry.rootTag == currentRootTag && age < floorMs(entry.step) }
        }

    /** The entry for [authorId]'s [ackedId]: the cheap look before the rule's locked reads, and for tests. */
    fun entry(
        authorId: String,
        ackedId: String,
    ): Entry? = synchronized(this) { entries[Key(authorId, ackedId)] }

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
 * a row the TTL expired and the sweep has not reached, and a receipt nobody re-serves any more must re-seal. The row
 * must be ours ([me] its sender): custody keys on the frame id, and only our own frame under that id is our receipt.
 */
suspend fun ForwardStore.holdsLiveReceipt(
    receiptId: String,
    me: String,
    now: Long,
): Boolean = frame(receiptId, now)?.envelope?.senderId == me
