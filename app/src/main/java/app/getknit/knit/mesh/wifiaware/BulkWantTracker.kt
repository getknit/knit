package app.getknit.knit.mesh.wifiaware

/**
 * TTL'd set of peers a **bulk (attachment) transfer** is pending with — the state behind
 * [app.getknit.knit.mesh.MeshTransport.expectBulkTransfer] on the Wi-Fi Aware plane. A noted peer counts as
 * sync-wanted at the NDP *admission* sites only (`driveSync`/`initiateOwed`/`initiateOwedToReachable`),
 * punching through digest parity and BLE-coverage suppression just long enough to raise a data path for the
 * file; it deliberately never feeds the recovery machinery (wedge watchdog, subscribe re-arm, ICM relight) —
 * a pending image has the Bluetooth fallback carrying it, so there is no outage to heal and nothing worth
 * churning discovery (or running the owed-episode clock toward a self-kill) over.
 *
 * Bounded three ways so a mark can never pin the radio:
 * - a sliding [ttlMs] per entry (re-armed only by [note], i.e. a fresh want/serve attempt — never by reads);
 * - a [maxEntries] cap (oldest-expiry evicted — realistic bulk demand is one or two peers);
 * - a per-peer [failCooldownMs] after [noteFailed] (a failed initiate), so the 60 s blobreq re-offer can't
 *   re-mark a NAN-dark peer into an initiate→timeout loop on the single NDI.
 *
 * Cleared eagerly on link-up to the peer ([clear] — the mark's job is done; the transfer either rides the
 * link or the next attempt re-marks) and on transport stop ([clearAll]). Pure (no Android deps) ⇒
 * JVM-unit-tested ([app.getknit.knit.BulkWantTrackerTest]); `@Synchronized` for the transport's
 * callback-thread vs. loop-coroutine access; the transport injects `SystemClock::elapsedRealtime`.
 */
internal class BulkWantTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val failCooldownMs: Long = DEFAULT_FAIL_COOLDOWN_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val wantedUntil = HashMap<String, Long>()
    private val cooldownUntil = HashMap<String, Long>()

    /**
     * Mark a bulk transfer pending with [nodeId]; re-arming slides the TTL. Refused (false) while the peer
     * is on a post-failure cooldown — the caller should not wait for a link that won't come.
     */
    @Synchronized
    fun note(nodeId: String): Boolean {
        val now = clock()
        prune(now)
        cooldownUntil[nodeId]?.let { if (now < it) return false else cooldownUntil.remove(nodeId) }
        if (nodeId !in wantedUntil && wantedUntil.size >= maxEntries) {
            wantedUntil.entries.minByOrNull { it.value }?.let { wantedUntil.remove(it.key) }
        }
        wantedUntil[nodeId] = now + ttlMs
        return true
    }

    /** True while a bulk transfer with [nodeId] is pending (noted and unexpired). */
    @Synchronized
    fun isWanted(nodeId: String): Boolean {
        val until = wantedUntil[nodeId] ?: return false
        if (clock() >= until) {
            wantedUntil.remove(nodeId)
            return false
        }
        return true
    }

    /**
     * An initiate to [nodeId] failed: drop its mark and refuse re-marks for [failCooldownMs], so retry
     * pressure stays with the existing digest-driven backoff instead of bulk demand stacking on top.
     */
    @Synchronized
    fun noteFailed(nodeId: String) {
        wantedUntil.remove(nodeId)
        cooldownUntil[nodeId] = clock() + failCooldownMs
    }

    /** Link to [nodeId] came up (or its transfer resolved): the mark's job is done. Also lifts its cooldown. */
    @Synchronized
    fun clear(nodeId: String) {
        wantedUntil.remove(nodeId)
        cooldownUntil.remove(nodeId)
    }

    @Synchronized
    fun clearAll() {
        wantedUntil.clear()
        cooldownUntil.clear()
    }

    /** `id(+remainingMs)` per live mark, for the transport's periodic state log. */
    @Synchronized
    fun debug(): String {
        val now = clock()
        prune(now)
        return wantedUntil.entries.joinToString(" ") { "${it.key}(+${it.value - now}ms)" }
    }

    private fun prune(now: Long) {
        wantedUntil.entries.removeAll { now >= it.value }
        cooldownUntil.entries.removeAll { now >= it.value }
    }

    companion object {
        // > BULK_GRACE_MS + one CONNECT_BACKOFF_MS retry (a second driveSync attempt inside the window is
        // still armed), one cue heartbeat long, and < RESPONDER_REFRESH_MS as defense-in-depth even though
        // the wedge paths never read this tracker.
        const val DEFAULT_TTL_MS = 25_000L

        // Long enough that the 60 s re-offer re-marks a failed peer at most every other round.
        const val DEFAULT_FAIL_COOLDOWN_MS = 120_000L

        // Realistic bulk demand is 1-2 peers; the cap is the mark-spam bound.
        const val DEFAULT_MAX_ENTRIES = 3
    }
}
