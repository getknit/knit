package app.getknit.knit.mesh.wifiaware

/**
 * The per-id mark a dial of ours sets when the peer it reached failed the link proof, or answered unproven for an id
 * that must prove itself (R1, ADR 2026-11.264w). While the mark holds, the id is not a sync we owe: the transport folds
 * [isHeld] into `digestSyncWanted` / `bulkSyncWanted` exactly as it folds the initiator hold, so neither admission
 * (`driveSync`) nor the recovery sites — the wedge watchdog's owed clock, the subscribe re-arm, the ICM relight — act
 * on it.
 *
 * Why it exists: without it a refusal ends in `failConnect`'s flat 10 s contention backoff while the digest gate keeps
 * the peer owed, so a downgraded peer (contract §10) or a squatter shadowing the real id redials every 10 s, runs the
 * watchdog's owed episode into three session cycles and then a Tier-2 process kill every 180 s for as long as it
 * stays near. A refusal is not contention: the same peer answers the same way next time, so the wait grows —
 * [BASE_MS] doubling to [MAX_MS] — and only facts the refusing peer cannot produce by being refused lift it:
 *
 * - [forget] — the id became proof-capable or proved itself somewhere (`LinkClaims.onCapable`), or stopped being
 *   proof-capable (`LinkClaims.onReleased`, a downgraded install's older profile reached us). Both change what the
 *   next dial would decide, so the mark and its growth go; one more attempt is cheap.
 * - [lift] — a fresh discovery handle for the id (its NAN restarted, perhaps on a new build): the current wait ends,
 *   the strike count stays, so a peer whose fresh handle refuses again backs off further than before. A session
 *   cycle or a re-arm of ours also yields fresh handles; the kept strikes are what stop that from paying for retries.
 *
 * A refusal never drops the discovery handle (dropping on every fast fail is the 258369aa rediscovery livelock).
 * Responder-side refusals of our own dials reach us as `NoReply`, indistinguishable from contention, so they stay on
 * the flat backoff and never set this mark. Pure (injected clock) and `@Synchronized`: the dial's IO thread writes,
 * the handler and the discovery loop read. Memory only, process lifetime; [MAX_ENTRIES] bounds it against a churn of
 * fresh ids. Tests: [app.getknit.knit.NanClaimRefusalPolicyTest].
 */
internal class NanClaimRefusalPolicy(
    private val now: () -> Long,
) {
    private class Mark(
        var strikes: Int,
        var until: Long,
    )

    // Insertion-ordered so the oldest mark is the one evicted at [MAX_ENTRIES].
    private val marks = LinkedHashMap<String, Mark>()

    /** A dial to [nodeId] ended in a refused or failed claim: (re)mark it. Returns the wait set, in ms. */
    @Synchronized
    fun refused(nodeId: String): Long {
        val mark = marks.remove(nodeId) ?: Mark(0, 0L)
        mark.strikes++
        val wait = waitMs(mark.strikes)
        mark.until = now() + wait
        marks[nodeId] = mark // re-added as the newest
        if (marks.size > MAX_ENTRIES) marks.remove(marks.keys.first())
        return wait
    }

    /** Whether [nodeId] is held out of the sync gates right now. */
    @Synchronized
    fun isHeld(nodeId: String): Boolean = marks[nodeId]?.let { now() < it.until } ?: false

    /** A fresh discovery handle for [nodeId]: end the current wait, keep the strikes. */
    @Synchronized
    fun lift(nodeId: String) {
        marks[nodeId]?.until = 0L
    }

    /** What decides [nodeId]'s next dial changed (proof-capable now, or no longer): drop the mark and its growth. */
    @Synchronized
    fun forget(nodeId: String): Boolean = marks.remove(nodeId) != null

    /** For the diagnostic state line: each id and its strikes, `*` while its wait holds. */
    @Synchronized
    fun debug(): String {
        val t = now()
        return marks.entries.joinToString(",") { (id, m) -> "${id.take(DEBUG_ID_CHARS)}:${m.strikes}${if (t < m.until) "*" else ""}" }
    }

    companion object {
        /** The first refusal's wait: well past the watchdog's Tier-1 (45 s), so a refusal never counts as owed. */
        const val BASE_MS = 120_000L

        /** The ceiling: a peer refusing all day costs one dial per half hour. */
        const val MAX_MS = 30 * 60_000L

        const val MAX_ENTRIES = 256
        private const val MAX_SHIFT = 8 // BASE shl 8 already dwarfs MAX; keeps the Long from wrapping
        private const val DEBUG_ID_CHARS = 8

        /** The wait after the [strikes]-th refusal in a row (1 = the first): 2, 4, 8, 16, 30 min. */
        fun waitMs(strikes: Int): Long = (BASE_MS shl (strikes.coerceAtLeast(1) - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_MS)
    }
}
