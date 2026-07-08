package app.getknit.knit.mesh.wifiaware

/**
 * Pure "is a NAN sync owed?" decisions, extracted from `WifiAwareTransport`'s family of near-duplicate
 * `cueTarget.keys.any { … }` folds. The transport reduces each cue-target candidate to an immutable
 * [PeerFacts] under its own locks and clock reads (see `WifiAwareTransport.snapshotPeerFacts`), so every
 * method here is a pure boolean fold — JVM-tested ([NanSyncPolicy]'s test), zero Android deps.
 *
 * The load-bearing invariant this object makes structural (and testable): the **recovery/watchdog** sites
 * read the **digest-pure** gate ([PeerFacts.digestWanted] / [PeerFacts.reachableSyncOwed]), while the
 * **admission/teardown** sites read the **bulk-aware** [PeerFacts.syncWanted]. A pending bulk (attachment)
 * transfer — whose bytes the BLE fallback still carries — must raise an *initiate* but must never run the
 * wedge clock or churn subscribe re-arm. [digestWanted] and [bulkWanted] are carried as sibling fields so
 * that split can't silently collapse.
 */
object NanSyncPolicy {
    /**
     * Fully-reduced facts for one cue-target candidate. Every field is computed by the transport as the exact
     * sub-expression of the original inline predicate, so equivalence holds by construction.
     */
    data class PeerFacts(
        /** `localNodeId > nodeId` — we're the initiator (tie-break). `!initiator` ⇒ pure responder (self excluded). */
        val initiator: Boolean,
        /** `nodeId in peers.keys` — a live data-path link exists. */
        val linked: Boolean,
        /** `nodeId in discovered.keys` — we hold a subscribe handle. Only meaningful when built with discovered. */
        val discovered: Boolean,
        /** Digest-pure gate: `nodeId !in suppressed && digestTracker.reconcileWanted(nodeId, v)`. */
        val digestWanted: Boolean,
        /** A pending bulk (attachment) transfer to this peer (`bulkWanted.isWanted(nodeId)`). */
        val bulkWanted: Boolean,
        /** Live link, or heard on the coordination plane within `REACHABLE_LINGER_MS`. */
        val lingerReachable: Boolean,
        /** Corroborated genuinely-present enough for the Tier-2 self-kill (`!hasForeignPlane || linked || foreignReachable`). */
        val corroborated: Boolean,
    ) {
        /** Bulk-aware gate for the admission/teardown sites. */
        val syncWanted: Boolean get() = bulkWanted || digestWanted

        /** Digest-pure "owed to a reachable peer" — the watchdog's owed signal. */
        val reachableSyncOwed: Boolean get() = digestWanted && lingerReachable
    }

    // --- recovery / watchdog sites: DIGEST-PURE ---

    /** Tier-2 owed signal: a reachable peer's digest differs AND it's corroborated present. */
    fun anySyncOwed(facts: List<PeerFacts>): Boolean = facts.any { it.reachableSyncOwed && it.corroborated }

    /** Tier-1 / episode owed signal: a reachable peer's digest differs (uncorroborated). */
    fun anyReachableSyncOwed(facts: List<PeerFacts>): Boolean = facts.any { it.reachableSyncOwed }

    /** Re-fire discovery: blind (no candidates), or an initiator peer is digest-owed but not yet discovered. */
    fun needsRediscovery(facts: List<PeerFacts>): Boolean =
        facts.isEmpty() || facts.any { it.initiator && !it.linked && !it.discovered && it.digestWanted }

    /** Re-arm subscribe to relight ICM as a responder: a smaller-side (responder) peer is digest-owed. */
    fun needsIcmRelight(facts: List<PeerFacts>): Boolean = facts.any { !it.initiator && !it.linked && it.digestWanted }

    /** Fast-tick cadence gate (`rediscoverDelayMs`): any initiator peer is digest-owed (no `!linked` gate — faithful). */
    fun anyInitiatorDigestOwed(facts: List<PeerFacts>): Boolean = facts.any { it.initiator && it.digestWanted }

    // --- admission / teardown sites: BULK-AWARE ---

    /** An NDP is worth bringing up: an unlinked initiator peer is sync-wanted (bulk or digest). */
    fun initiateOwed(facts: List<PeerFacts>): Boolean = facts.any { it.initiator && !it.linked && it.syncWanted }

    /** [initiateOwed] restricted to coordination-plane-reachable peers — the gate for the expensive session cycle. */
    fun initiateOwedToReachable(facts: List<PeerFacts>): Boolean =
        facts.any { it.initiator && !it.linked && it.syncWanted && it.lingerReachable }
}
