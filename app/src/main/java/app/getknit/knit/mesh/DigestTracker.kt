package app.getknit.knit.mesh

/**
 * Pure anti-entropy decision for the Wi-Fi Aware **cue plane**: tracks each peer's advertised content digest
 * ([StoreDigest.version], learned from its cue) and the version we were both at after our last data-path
 * sync, and answers the one question the connection engine needs — *is a data-path sync with this peer worth
 * bringing up the one scarce NDP right now?*
 *
 * The decisive win over the old counter epoch is the **identical-digest skip**: if a peer's digest already
 * equals ours, our stores match and there is nothing to exchange — so we never waste an NDP, *even on first
 * contact* (e.g. two leaves both fed by the same hub converge to the same version and never sync each other).
 * Otherwise a peer is reconcile-wanted when we've never synced it, or either side's digest has moved since we
 * did. Because a content digest is a hash, not an ordered counter, comparisons are **equality** (no
 * monotone-max) — which is what makes it restart-stable: a peer that restarts to the same store re-advertises
 * the same version and correctly reads as "unchanged".
 *
 * Only the **initiator** (larger nodeId, by the transport's tie-break) consults [reconcileWanted]; the
 * responder never initiates, so its per-peer state going stale is harmless. Records [onReconciled] on the
 * initiator's clean (quiescence) teardown, at our *post-sync* version (so a store that gained nothing new
 * afterward stays quiet). No Android deps ⇒ JVM-unit-tested ([DigestTrackerTest]); all methods are guarded
 * for the transport's callback-thread vs. loop-coroutine access.
 */
class DigestTracker {
    private val peerVersion = HashMap<String, Long>()
    private val syncedVersion = HashMap<String, Long>()

    /**
     * Record a peer's advertised digest [version] (from its cue). Returns true if it *moved* since we last
     * heard from the peer — the transport uses it to wake the connection loop to re-evaluate immediately
     * (the precise decision is [reconcileWanted], which the loop makes with our own current version).
     */
    @Synchronized
    fun onCue(nodeId: String, version: Long): Boolean = peerVersion.put(nodeId, version) != version

    /**
     * True if a data-path sync with [nodeId] is warranted given our current [localVersion]. Requires having
     * heard a cue from the peer; skips when the digests are identical (nothing to exchange); otherwise wanted
     * on first divergence, or when either side has moved since our last completed sync.
     */
    @Synchronized
    fun reconcileWanted(nodeId: String, localVersion: Long): Boolean {
        val theirs = peerVersion[nodeId] ?: return false // no cue heard → we hold no digest for it yet
        if (theirs == localVersion) return false // identical stores → nothing to pull or push
        val synced = syncedVersion[nodeId] ?: return true // digests differ and we've never synced → sync
        return localVersion != synced || theirs != synced // either side moved since we synced
    }

    /**
     * Record a completed sync with [nodeId] at our [localVersion] as of link-down (after the backfill, so a
     * store that ingested during the link and gained nothing *new* afterward is considered in sync). If the
     * pair didn't actually converge, the peer's next cue (a different version) re-triggers.
     */
    @Synchronized
    fun onReconciled(nodeId: String, localVersion: Long) {
        syncedVersion[nodeId] = localVersion
    }

    /** Forget a peer entirely (transport teardown). Kept out of link churn so brief drops don't re-sync. */
    @Synchronized
    fun forget(nodeId: String) {
        peerVersion.remove(nodeId)
        syncedVersion.remove(nodeId)
    }

    @Synchronized
    fun clear() {
        peerVersion.clear()
        syncedVersion.clear()
    }

    /** Diagnostic snapshot: per-peer `id(peerVersion,syncedVersion)`. Temporary debugging aid. */
    @Synchronized
    fun debug(): String = (peerVersion.keys + syncedVersion.keys).toSortedSet()
        .joinToString(" ") { "$it(pv=${peerVersion[it]},sv=${syncedVersion[it]})" }
}
