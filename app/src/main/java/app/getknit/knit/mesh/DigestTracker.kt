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
 * ## No-progress throttle
 * Two nodes whose custody policies differ on any frame (per-node `canCarry` gates, DM-recipient exclusion,
 * quota-eviction asymmetry) can complete sync after sync **without ever converging** — and because each sync's
 * ingest legitimately moves both digests, the "either side moved" test re-arms every round: the re-audit's
 * ~5 s serve loop (`docs/NAN_CONCURRENCY_REAUDIT.md` §3.2), historically damped only by the after-serve
 * reattach's accidental rate-limiting. The throttle bounds it at the decision layer: [THROTTLE_SYNCS]
 * *completed-but-non-convergent* syncs within [THROTTLE_WINDOW_MS] put the peer on an exponential cooldown
 * ([COOLDOWN_INITIAL_MS] doubling to [COOLDOWN_MAX_MS]) during which [reconcileWanted] is false; once engaged,
 * each further non-convergent completion re-arms the (doubled) cooldown immediately. The reset is **observed
 * convergence only** (equal digests, seen by either [onReconciled] or [reconcileWanted]) — deliberately NOT
 * "a digest moved", because in the pathology this exists for, both digests move every round (the §5.3 reset
 * rule as originally proposed would permanently defeat the throttle). Cost: a genuinely-new message to a
 * still-divergent peer waits out ≤ the current cooldown for its *bulk* sync; small frames still arrive
 * instantly via the coordination-plane fast path, and custody re-serves on the next completed sync. A wedge
 * (syncs that never *complete*) never engages the throttle — only [onReconciled] counts — so the wedge
 * watchdog's owed signal is never masked.
 *
 * Only the **initiator** (larger nodeId, by the transport's tie-break) consults [reconcileWanted]; the
 * responder never initiates, so its per-peer state going stale is harmless. Records [onReconciled] on the
 * initiator's clean (quiescence) teardown, at our *post-sync* version (so a store that gained nothing new
 * afterward stays quiet). [clock] is injectable for tests (defaulted, so production construction sites and
 * existing tests are unchanged); the transport passes its `SystemClock::elapsedRealtime`. No Android deps ⇒
 * JVM-unit-tested ([DigestTrackerTest]); all methods are guarded for the transport's callback-thread vs.
 * loop-coroutine access.
 */
class DigestTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val peerVersion = HashMap<String, Long>()
    private val syncedVersion = HashMap<String, Long>()
    private val throttle = HashMap<String, Throttle>()

    /** Per-peer no-progress bookkeeping; exists only while a peer is diverging without convergence. */
    private class Throttle {
        var noProgressCount = 0
        var windowStart = 0L
        var cooldownUntil = 0L
        var nextCooldownMs = COOLDOWN_INITIAL_MS
        var engaged = false
    }

    /**
     * Record a peer's advertised digest [version] (from its cue). Returns true if it *moved* since we last
     * heard from the peer — the transport uses it to wake the connection loop to re-evaluate immediately
     * (the precise decision is [reconcileWanted], which the loop makes with our own current version).
     */
    @Synchronized
    fun onCue(
        nodeId: String,
        version: Long,
    ): Boolean = peerVersion.put(nodeId, version) != version

    /**
     * True if a data-path sync with [nodeId] is warranted given our current [localVersion]. Requires having
     * heard a cue from the peer; skips when the digests are identical (nothing to exchange); suppressed while
     * the peer is on a no-progress cooldown; otherwise wanted on first divergence, or when either side has
     * moved since our last completed sync.
     */
    @Synchronized
    fun reconcileWanted(
        nodeId: String,
        localVersion: Long,
    ): Boolean {
        val theirs = peerVersion[nodeId] ?: return false // no cue heard → we hold no digest for it yet
        if (theirs == localVersion) {
            throttle.remove(nodeId) // observed convergence (by any route) → full throttle reset
            return false // identical stores → nothing to pull or push
        }
        throttle[nodeId]?.let { if (clock() < it.cooldownUntil) return false } // no-progress cooldown
        val synced = syncedVersion[nodeId] ?: return true // digests differ and we've never synced → sync
        return localVersion != synced || theirs != synced // either side moved since we synced
    }

    /**
     * Record a completed sync with [nodeId] at our [localVersion] as of link-down (after the backfill, so a
     * store that ingested during the link and gained nothing *new* afterward is considered in sync). A sync
     * that completed **without converging** (the peer's last-advertised digest still differs from ours) counts
     * toward the no-progress throttle; a converged one fully resets it.
     */
    @Synchronized
    fun onReconciled(
        nodeId: String,
        localVersion: Long,
    ) {
        syncedVersion[nodeId] = localVersion
        if (peerVersion[nodeId] == localVersion) {
            throttle.remove(nodeId) // converged → full reset (count, cooldown, and backoff growth)
            return
        }
        val now = clock()
        val t = throttle.getOrPut(nodeId) { Throttle() }
        // Pre-engagement, stale counts decay: a fresh window restarts the tally. Once engaged, every further
        // non-convergent completion re-arms the (doubled) cooldown immediately — no free rounds.
        if (!t.engaged && now - t.windowStart > THROTTLE_WINDOW_MS) {
            t.windowStart = now
            t.noProgressCount = 0
        }
        t.noProgressCount++
        if (t.engaged || t.noProgressCount >= THROTTLE_SYNCS) {
            t.engaged = true
            t.cooldownUntil = now + t.nextCooldownMs
            t.nextCooldownMs = (t.nextCooldownMs * 2).coerceAtMost(COOLDOWN_MAX_MS)
        }
    }

    /** Forget a peer entirely (transport teardown). Kept out of link churn so brief drops don't re-sync. */
    @Synchronized
    fun forget(nodeId: String) {
        peerVersion.remove(nodeId)
        syncedVersion.remove(nodeId)
        throttle.remove(nodeId)
    }

    @Synchronized
    fun clear() {
        peerVersion.clear()
        syncedVersion.clear()
        throttle.clear()
    }

    /** Diagnostic snapshot: per-peer `id(peerVersion,syncedVersion[,cd=cooldownRemaining])`. */
    @Synchronized
    fun debug(): String {
        val now = clock()
        return (peerVersion.keys + syncedVersion.keys)
            .toSortedSet()
            .joinToString(" ") {
                val remaining = throttle[it]?.let { t -> (t.cooldownUntil - now).coerceAtLeast(0) } ?: 0
                val cd = if (remaining > 0) ",cd=$remaining" else ""
                "$it(pv=${peerVersion[it]},sv=${syncedVersion[it]}$cd)"
            }
    }

    companion object {
        // Completed-but-non-convergent syncs within the window that engage the cooldown. Three rounds is
        // enough to distinguish chronic divergence from the normal converge-in-one-or-two dance.
        const val THROTTLE_SYNCS = 3
        const val THROTTLE_WINDOW_MS = 60_000L

        // Cooldown once engaged: exponential from 30 s, capped at 10 min. Reset only by observed convergence.
        const val COOLDOWN_INITIAL_MS = 30_000L
        const val COOLDOWN_MAX_MS = 600_000L
    }
}
