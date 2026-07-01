package app.getknit.knit.mesh

/**
 * Pure anti-entropy decision for the Wi-Fi Aware **cue plane**: tracks each peer's advertised store epoch
 * (learned from its 255-byte cue message — see `WifiAwareTransport`) and the epochs at our last completed
 * data-path sync with it, and answers the one question the connection engine needs — *is a data-path sync
 * with this peer worth bringing up right now?*
 *
 * A peer is **sync-wanted** when either side has advanced since we last synced with it:
 * `localEpoch > syncedLocal[p] || peerEpoch[p] > syncedPeer[p]`. So a fresh cue (first contact), a peer
 * that gained a message, or our own store gaining one all warrant a sync; once [onSynced] records the sync,
 * the peer goes quiet until an epoch moves again.
 *
 * Only the **initiator** (larger nodeId, by the transport's tie-break) ever consults [syncWanted] — the
 * responder side never initiates, so its per-peer synced state going stale is harmless. That asymmetry is
 * why v1 records [onSynced] on the initiator's clean (quiescence) teardown only, and it is correct by
 * construction. No Android deps, so this is JVM-unit-tested ([CueTrackerTest]); all methods are guarded for
 * the transport's callback-thread vs. loop-coroutine access.
 */
class CueTracker {
    private val peerEpoch = HashMap<String, Long>()
    private val syncedLocal = HashMap<String, Long>()
    private val syncedPeer = HashMap<String, Long>()

    /**
     * Record a peer's advertised [epoch] (from its cue), monotonically. Returns true if this leaves the
     * peer sync-wanted on the peer's side (their epoch is now past what we last synced) — the transport
     * uses it to wake the connection loop immediately instead of waiting out its idle.
     */
    @Synchronized
    fun onCue(nodeId: String, epoch: Long): Boolean {
        val merged = maxOf(epoch, peerEpoch[nodeId] ?: Long.MIN_VALUE)
        peerEpoch[nodeId] = merged
        return merged > (syncedPeer[nodeId] ?: UNSYNCED)
    }

    /**
     * True if a data-path sync with [nodeId] is warranted given our current [localEpoch]. Requires having
     * heard at least one cue from the peer (else we hold no handle/epoch for it and there is nothing to
     * pull yet).
     */
    @Synchronized
    fun syncWanted(nodeId: String, localEpoch: Long): Boolean {
        val theirs = peerEpoch[nodeId] ?: return false
        return localEpoch > (syncedLocal[nodeId] ?: UNSYNCED) || theirs > (syncedPeer[nodeId] ?: UNSYNCED)
    }

    /**
     * Record a completed sync with [nodeId] at our [localEpoch] and the peer's latest cued epoch, so the
     * pair is considered in sync until either epoch advances again.
     */
    @Synchronized
    fun onSynced(nodeId: String, localEpoch: Long) {
        syncedLocal[nodeId] = localEpoch
        syncedPeer[nodeId] = peerEpoch[nodeId] ?: 0L
    }

    /** Forget a peer entirely (transport teardown). Kept out of link churn so brief drops don't re-sync. */
    @Synchronized
    fun forget(nodeId: String) {
        peerEpoch.remove(nodeId)
        syncedLocal.remove(nodeId)
        syncedPeer.remove(nodeId)
    }

    @Synchronized
    fun clear() {
        peerEpoch.clear()
        syncedLocal.clear()
        syncedPeer.clear()
    }

    /** Diagnostic snapshot: per-peer `id(peerEpoch,syncedLocal,syncedPeer)`. Temporary debugging aid. */
    @Synchronized
    fun debug(): String = (peerEpoch.keys + syncedLocal.keys + syncedPeer.keys).toSortedSet()
        .joinToString(" ") { "$it(pe=${peerEpoch[it]},sl=${syncedLocal[it]},sp=${syncedPeer[it]})" }

    private companion object {
        // Sentinel "never synced": below any real epoch (which starts at 0), so a first cue is sync-wanted.
        const val UNSYNCED = -1L
    }
}
