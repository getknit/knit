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
 * Otherwise a peer is reconcile-wanted whenever the digests differ and it is not on a hold-off.
 * Because a content digest is a hash, not an ordered counter, comparisons are **equality** (no
 * monotone-max) — which is what makes it restart-stable: a peer that restarts to the same store re-advertises
 * the same version and correctly reads as "unchanged".
 *
 * ## The hold-off, keyed on progress
 * Two nodes whose custody policies differ on any frame (per-node `canCarry` gates, DM-recipient exclusion,
 * quota-eviction asymmetry) can complete sync after sync **without ever converging**, and two full stores never show
 * equal digests again (the custody-carry-policy android order, step 8): the re-audit's ~5 s serve loop
 * (`docs/NAN_CONCURRENCY_REAUDIT.md` §3.2). The hold-off bounds it at the decision layer. Each exchange (one data path)
 * is judged at its end by what [progress] says it moved ([onReconciled], against the marks [open] read as the path
 * came up, kept per link so a displaced link's marks never judge the newcomer's exchange):
 * - **Equal digests** clear the hold-off, and the pair skips the sync (seen by either [onReconciled] or
 *   [reconcileWanted]).
 * - **Moved** (custody kept a frame that arrived from the peer, or a later digest of the peer's judged a release to it
 *   kept: `CustodyProgress.progressMark`) clears it.
 * - **Moved nothing**, on a path that carried the peer's custody digest and was opened for a digest sync, counts:
 *   [THROTTLE_SYNCS] such exchanges within [THROTTLE_WINDOW_MS] engage a hold-off of [COOLDOWN_INITIAL_MS], and every
 *   further one re-arms it doubled, to [COOLDOWN_MAX_MS]; it also starts custody's watch for a frame the peer lacks
 *   (`CustodyProgress.watchFresh`). During the hold-off [reconcileWanted] is false unless that watch saw one
 *   (`CustodyProgress.fresh`), on the side that would bring the path up.
 * - **Unjudged**: a path that carried no custody digest of the peer's (`CustodyProgress.digestSeen` did not move), or
 *   that was opened for a bulk transfer with no digest sync wanted, says nothing about progress, and leaves the hold-off
 *   as it was.
 * - **No word**: custody keeps nothing for the peer (an unproven link, a build older than the link proof, or
 *   [CustodyProgress.NONE]): the exchange counts as moving nothing, which is the throttle this class ran before
 *   progress existed, convergence its only reset.
 *
 * **Three in a minute before it engages** (kept, the owner's call): Aware's "moved" counts a release only once the
 * *next* path's digest judged it kept, so the exchange that follows a one-way catch-up is the one that proves it. Without
 * the tally every catch-up path that released and took nothing would hold the pair 30 s before the path that judges it;
 * with it, two quick exchanges more per episode is all a non-convergent pair costs before the hold-off engages.
 *
 * **The responder trusts the link instead** (the initiator alone keeps a hold-off). The responder (smaller nodeId) never
 * brings the path up; its want for a larger peer ([reconcileWanted]'s `initiator = false`) feeds only its digest-pure owed
 * signal — the watchdog's episodes and the ICM relight — and it cannot see the initiator's hold-off. So it trusts that the
 * link works within [RESPONDER_TRUST_MS] of (a) the last data path with that peer that came up ([onPathUp]: the
 * responder served its dial, proof the link is alive) or closed with the two digests still different ([onReconciled] with
 * `initiator = false`), or (b) the plane's start ([onPlaneStarted]), a restart grace, so a responder that restarts — a
 * Tier-2 kill included — cannot loop. While it trusts, digest divergence alone is no want. Observed convergence (a
 * converged last exchange, or equal digests read by [reconcileWanted]) ends that peer's trust and its restart grace, so
 * below the cap a new divergence is owed at once, as before.
 * [RESPONDER_TRUST_MS] is 2 × [COOLDOWN_MAX_MS] = 1,200,000 ms, above the initiator's worst gap between dials **while its
 * one NDI is free**: its 600 s hold-off, two idle discovery ticks after it (2 × 240 s at most, nothing waking the loop at
 * the hold-off's end), the 1.5 s settle and the 15 s handshake timeout: 600 + 480 + 1.5 + 15 = 1,096.5 s. A busy NDI
 * (another peer's link, up to 30 s, or longer while a file is in flight; dials queued behind backoff) can push the gap past
 * that; the renewal at every path that comes up keeps the trust following a link that is alive, and a gap past the cap
 * reads as owed, which is the watchdog's to judge. Cost: a responder whose initiator truly
 * wedged after a non-convergent exchange (or right after a restart) notices it up to 20 min later, and in that window
 * does not relight ICM for that peer. Accepted too: a new frame on the smaller-id node for the larger waits out the
 * larger's hold-off unless the fast plane carries it.
 *
 * Cost: a genuinely-new message to a still-divergent peer that custody's watch does not see waits out ≤ the current
 * hold-off for its *bulk* sync; small frames still arrive instantly via the coordination-plane fast path. A wedge (syncs
 * that never *complete*) never engages the hold-off — only [onReconciled] counts — so the wedge watchdog's owed signal
 * is never masked by one.
 *
 * [clock] is injectable for tests (defaulted, so production construction sites and existing tests are unchanged); the
 * transport passes its `SystemClock::elapsedRealtime`. [progress] is custody's word (`CarryLedger` in production; [NONE]
 * keeps the throttle as it was). No Android deps ⇒ JVM-unit-tested ([DigestTrackerTest]); all methods are guarded for
 * the transport's callback-thread vs. loop-coroutine access, and [progress]'s reads are O(1) and call nothing back.
 */
@Suppress("TooManyFunctions") // the cue, the gate, the exchange's open and its verdict, and the plane's teardown
class DigestTracker(
    private val progress: CustodyProgress = CustodyProgress.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val peerVersion = HashMap<String, Long>()

    /** Our version at each peer's last completed sync. Diagnostic only ([debug]) — see [reconcileWanted]. */
    private val syncedVersion = HashMap<String, Long>()
    private val throttle = HashMap<String, Throttle>()

    // The responder's trust (class KDoc): when each larger peer's last data path closed with the digests still different.
    // Kept through [forget] (the 150 s prune: a peer heard again resumes its trust); dropped when the pair is seen
    // converged or the trust lapses, and by [clear]. Bounded by the peers it names, each entry lapsing after
    // [RESPONDER_TRUST_MS].
    private val trustedAt = HashMap<String, Long>()

    // The plane's start ([onPlaneStarted]): the responder's restart grace. Null until the plane says it started.
    private var startedAt: Long? = null

    // Peers seen converged since the plane's start: their restart grace is over (a new divergence is owed at once). Filled
    // where an observed convergence drops a trust, so bounded by the peers that converged; emptied by [clear].
    private val convergedSinceStart = HashSet<String>()

    /** Per-peer no-progress bookkeeping; exists only while a peer is diverging without convergence or progress. */
    private class Throttle {
        var noProgressCount = 0
        var windowStart = 0L
        var cooldownUntil = 0L
        var nextCooldownMs = COOLDOWN_INITIAL_MS
        var engaged = false
    }

    /**
     * Custody's marks for one peer as one data path came up ([open]), and whether a digest sync was wanted then: what
     * [onReconciled] judges that path's exchange against. Kept by the link, never by the peer.
     */
    class Exchange internal constructor(
        internal val progressAt: Long,
        internal val digestAt: Long,
        internal val digestWanted: Boolean,
        // Custody's fresh watch had fired as the path came up: the lift that may have opened it.
        internal val fresh: Boolean = false,
    )

    /** What [onReconciled] made of one exchange (the transport logs it). */
    enum class Verdict {
        /** Equal digests: the hold-off cleared. */
        CONVERGED,

        /** Custody's progress mark moved: the hold-off cleared (initiator; the responder only renews its trust). */
        MOVED,

        /** The peer's digest crossed, a digest sync was wanted, and nothing moved: counted. */
        STILL,

        /** No digest of the peer's crossed, or the path was opened bulk-only: nothing changed (initiator). */
        UNJUDGED,

        /** Custody keeps no word for the peer: counted, as before progress existed. */
        NO_WORD,
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
     * heard a cue from the peer; skips when the digests are identical (nothing to exchange). As the [initiator]:
     * suppressed while the peer is on a hold-off, unless custody saw a frame the peer lacks since it began; otherwise
     * wanted whenever the digests differ. As the responder: wanted when the digests differ and it does not trust the
     * link (the class KDoc's [RESPONDER_TRUST_MS]).
     */
    @Synchronized
    fun reconcileWanted(
        nodeId: String,
        localVersion: Long,
        initiator: Boolean = true,
    ): Boolean {
        val theirs = peerVersion[nodeId] ?: return false // no cue heard → we hold no digest for it yet
        if (theirs == localVersion) {
            throttle.remove(nodeId) // observed convergence (by any route) → full throttle reset
            trustedAt.remove(nodeId) // and a new divergence is owed at once, the restart grace included
            convergedSinceStart += nodeId
            return false // identical stores → nothing to pull or push
        }
        // The responder keeps no hold-off: it trusts the link (class KDoc). No fresh lift either, since its fresh frame
        // cannot raise the initiator's path.
        if (!initiator) return !trusts(nodeId)
        // The initiator's hold-off, lifted by a fresh frame.
        throttle[nodeId]?.let { if (clock() < it.cooldownUntil && !progress.fresh(nodeId)) return false }
        // Digests differ and we're off cooldown ⇒ sync. Deliberately no [syncedVersion] test: downstream of
        // the identical-digest skip above, "either side moved since we synced" is a tautology — were neither
        // side moved, both would equal syncedVersion and therefore each other, which the skip already
        // returned on. The hold-off, not this decision, is what bounds the re-sync loop when two
        // peers complete sync after sync without converging.
        return true
    }

    /**
     * Custody's marks for [nodeId] as a data path to it comes up, and whether a digest sync was wanted at that moment
     * ([digestWanted]: false for a path opened for a bulk transfer alone). The link keeps it for [onReconciled].
     */
    fun open(
        nodeId: String,
        digestWanted: Boolean,
    ): Exchange = Exchange(progress.progressMark(nodeId), progress.digestSeen(nodeId), digestWanted, progress.fresh(nodeId))

    /**
     * Record a completed sync with [nodeId] at our [localVersion] as of link-down (after the backfill), judged against
     * the [exchange] its link opened with (null: no marks, so no word). The recorded version is a diagnostic ([debug]);
     * the initiator's hold-off and the responder's trust are what this method exists for (the class KDoc has the rules).
     * [initiator] is our role on that link.
     */
    @Synchronized
    fun onReconciled(
        nodeId: String,
        localVersion: Long,
        exchange: Exchange? = null,
        initiator: Boolean = true,
    ): Verdict {
        syncedVersion[nodeId] = localVersion
        if (peerVersion[nodeId] == localVersion) {
            throttle.remove(nodeId) // converged → full reset (count, cooldown, and backoff growth)
            trustedAt.remove(nodeId)
            convergedSinceStart += nodeId
            return Verdict.CONVERGED
        }
        val verdict = judge(nodeId, exchange)
        // The responder counts nothing: a path that closed with the digests still different renews its trust in the link.
        if (!initiator) {
            trust(nodeId)
            return verdict
        }
        when (verdict) {
            Verdict.MOVED -> {
                throttle.remove(nodeId)
            }

            Verdict.STILL -> {
                count(nodeId)
                progress.watchFresh(nodeId) // from this exchange's end: a frame the peer lacks lifts the hold-off
            }

            Verdict.NO_WORD -> {
                count(nodeId)
            }

            // Says nothing about progress: the hold-off stays as it was. A path a fresh frame lifted it for is that frame's
            // one try: the watch starts over, so an exchange that keeps carrying no digest cannot loop on the same lift.
            Verdict.UNJUDGED -> {
                if (exchange?.fresh == true) progress.watchFresh(nodeId)
            }

            Verdict.CONVERGED -> {}
        }
        return verdict
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
        trustedAt.clear()
        convergedSinceStart.clear()
        startedAt = null
    }

    /**
     * A data path with the larger peer [nodeId] came up: the responder served its dial, proof the link is alive, so its
     * trust renews from now (class KDoc). Called for a peer we do not initiate to.
     */
    @Synchronized
    fun onPathUp(nodeId: String) = trust(nodeId)

    /** The plane started: the responder trusts every link for [RESPONDER_TRUST_MS] from now (the restart grace). */
    @Synchronized
    fun onPlaneStarted() {
        startedAt = clock()
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

    // What one exchange moved, by custody's marks now against those its link opened with. A mark that reads lower than at
    // the open (custody dropped the peer's word meanwhile) is "not higher": nothing moved.
    private fun judge(
        nodeId: String,
        exchange: Exchange?,
    ): Verdict {
        val digests = progress.digestSeen(nodeId)
        return when {
            exchange == null || digests == 0L -> Verdict.NO_WORD
            !exchange.digestWanted || digests <= exchange.digestAt -> Verdict.UNJUDGED
            progress.progressMark(nodeId) > exchange.progressAt -> Verdict.MOVED
            else -> Verdict.STILL
        }
    }

    // One exchange that moved nothing. Pre-engagement, stale counts decay: a fresh window restarts the tally. Once engaged,
    // every further one re-arms the (doubled) hold-off immediately — no free rounds.
    private fun count(nodeId: String) {
        val now = clock()
        val t = throttle.getOrPut(nodeId) { Throttle() }
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

    // The responder's trust in its link to [nodeId] renews now; lapsed entries leave.
    private fun trust(nodeId: String) {
        val now = clock()
        trustedAt.values.removeAll { now - it >= RESPONDER_TRUST_MS }
        trustedAt[nodeId] = now
    }

    // Whether the responder trusts its link to [nodeId] now: within [RESPONDER_TRUST_MS] of the last non-convergent path
    // with it, or of the plane's start.
    private fun trusts(nodeId: String): Boolean {
        val now = clock()
        val grace = startedAt?.takeIf { nodeId !in convergedSinceStart }
        val since = listOfNotNull(trustedAt[nodeId], grace).maxOrNull() ?: return false
        return now - since < RESPONDER_TRUST_MS
    }

    companion object {
        // Exchanges that moved nothing within the window that engage the hold-off. Three rounds is enough to tell chronic
        // divergence from the normal converge-in-one-or-two dance, and lets the path after a one-way catch-up judge it.
        const val THROTTLE_SYNCS = 3
        const val THROTTLE_WINDOW_MS = 60_000L

        // The hold-off once engaged: exponential from 30 s, capped at 10 min. Cleared by convergence, or (initiator) progress.
        const val COOLDOWN_INITIAL_MS = 30_000L
        const val COOLDOWN_MAX_MS = 600_000L

        // How long the responder trusts its link to a larger peer after a non-convergent path or the plane's start: above
        // the initiator's worst gap between dials (class KDoc's arithmetic: 1,096.5 s).
        const val RESPONDER_TRUST_MS = 2 * COOLDOWN_MAX_MS
    }
}
