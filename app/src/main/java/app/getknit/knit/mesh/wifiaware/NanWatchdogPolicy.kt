package app.getknit.knit.mesh.wifiaware

/**
 * Pure decision for the two-tier NAN data-plane **wedge watchdog** (the leaked-request self-heal;
 * `docs/DIGEST_PULL_REATTACH.md`). Extracted from `WifiAwareTransport.checkWedge` so the episode-clock
 * arithmetic — the subtle part, an *owed-episode* measured from when divergence appeared, reset on any
 * progress, NOT time-since-last-link — is exercised on the JVM ([NanWatchdogPolicy]'s test). The transport
 * still owns the two owed signals, the clock, and the side effects (session cycle / process kill); this
 * object only maps them to an [Action] plus the next episode-clock value.
 *
 * Both tiers are measured against the **uncorroborated** [reachableOwed] so they still fire when the
 * corroborating Bluetooth plane is dark; the Tier-2 process kill is additionally gated on the
 * **corroborated** [corroboratedOwed] so an out-of-range-but-still-cueing peer can't self-kill the node.
 */
object NanWatchdogPolicy {
    enum class Action { None, RefreshResponder, RestartProcess }

    /** [action] to take now, and the value the transport must write back into its `syncOwedSince` episode clock. */
    data class Decision(
        val action: Action,
        val nextSyncOwedSince: Long,
    )

    /**
     * @param healthy hardware present, Aware healthy, and a live session — else we can't sync, so not wedged.
     * @param reachableOwed a sync is owed to a *reachable* peer (uncorroborated) — the Tier-1 / episode signal.
     * @param corroboratedOwed the owed peer is corroborated genuinely-present — the extra Tier-2 gate.
     * @param syncOwedSince start of the current owed-with-no-link episode (0 = none).
     * @param lastLinkOrAcceptAt last successful link/accept (either role) — "progress" resets the episode.
     * @param responderRefreshMs / [reattachCooldownMs] / [wedgeRestartMs] the transport's tuning constants.
     */
    @Suppress("LongParameterList")
    fun decide(
        healthy: Boolean,
        reachableOwed: Boolean,
        corroboratedOwed: Boolean,
        now: Long,
        syncOwedSince: Long,
        lastLinkOrAcceptAt: Long,
        lastReattachAt: Long,
        lastRestartAt: Long,
        responderRefreshMs: Long,
        reattachCooldownMs: Long,
        wedgeRestartMs: Long,
    ): Decision {
        // Nothing owed (or can't sync) → not wedged; clear the episode.
        if (!healthy || !reachableOwed) return Decision(Action.None, 0L)
        // Episode not yet started, or a data-path link formed since it began → making progress → (re)start it.
        if (syncOwedSince == 0L || lastLinkOrAcceptAt >= syncOwedSince) return Decision(Action.None, now)
        val owedFor = now - syncOwedSince
        // Tier 1: refresh the (possibly wedged) responder — light, uncorroborated, safe at 0 NDPs. Shares the
        // reattach cooldown with the discovery-loop pinned-responder cycle so the two can't stack.
        if (owedFor >= responderRefreshMs && now - lastReattachAt >= reattachCooldownMs) {
            return Decision(Action.RefreshResponder, syncOwedSince)
        }
        // Tier 2: last-resort process kill, gated on corroboration so an out-of-range peer can't trigger it.
        if (owedFor < wedgeRestartMs || now - lastRestartAt < wedgeRestartMs || !corroboratedOwed) {
            return Decision(Action.None, syncOwedSince)
        }
        return Decision(Action.RestartProcess, syncOwedSince)
    }
}
