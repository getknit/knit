package app.getknit.knit.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A **content digest** of the carried-message set — an order-independent 64-bit fingerprint of the **live**
 * (non-expired) ids in the [ForwardStore]. The Wi-Fi Aware **cue plane** (`WifiAwareTransport`) advertises the
 * digest over the tiny no-data-path message channel so a neighbor can decide, *without* bringing up a scarce
 * NAN data path, whether a sync is worth it — two nodes whose carried set matches produce the **same** version
 * and skip the data path.
 *
 * Replaces the old monotone `SyncEpoch` counter. Two wins from being content-derived rather than a counter:
 *  - **no no-op syncs** — the version only differs when the carried *set* differs, so an already-matching pair
 *    never wastes an NDP (the scarcest resource here — see `docs/DIGEST_PULL_REATTACH.md`);
 *  - **restart-stable** — the same store after a restart yields the same version, so a peer that watermarked
 *    us before the restart doesn't miss us (the old session-local counter reset to 0 and could be ignored by
 *    a peer's monotone-max until it climbed back).
 *
 * Covers **every live id in the carried set** — all custodied frame types (chat, reaction, receipt, group-update/
 * leave, and profiles), since general custody (commit `b277e06`) put them all in `forward_store`. Profiles
 * converge despite being per-node because custody is **symmetric**: each node holds its own profile
 * (`ORIGIN_SELF`) *and* every peer's (`ORIGIN_RELAY`), so once profiles have propagated the whole mesh shares
 * one id set and the "identical ⇒ skip" comparison still holds.
 *
 * ## Live-only membership + the lazy expiry fold
 * The digest folds only ids whose frame-global expiry (`sentAt + TTL`, see `ForwardEntity.expiresAt`) hasn't
 * passed — matching what a sync can actually exchange (`ForwardDao.liveIds`). Folding over *all* rows made
 * every TTL boundary a divergence window: an expired-but-unswept row inflated one node's digest until its
 * 10-minute sweep tick, and sweeps phase independently per node, so peers churned sync-wanted on residue a
 * sync can never reconcile (work item #8). Because `expiresAt` derives from the originator's signed `sentAt`,
 * every node now flips the same id out at the same absolute instant (modulo clock skew) — the sweep is pure
 * storage GC and digest-neutral by construction.
 *
 * Expiry is a function of *time*, which a [StateFlow] can't observe on its own, so the fold is **lazy**:
 * [current] folds newly-expired ids out (batched into one version change), updates [version], and returns the
 * fresh value. **Every read that feeds a decision or an advertisement must go through [current]** — the
 * transports' sync gates, cue/SSI/BLE-advert encoders, and `DigestTracker.onReconciled` recording — never raw
 * `version.value` (which may predate a boundary). [version] remains the *push* channel: the fold's single
 * update fires the existing digest-change collectors (re-cue, SSI republish, BLE re-advertise), and the 30 s
 * cue heartbeat (whose `cueAll` reads [current]) bounds staleness on an otherwise-idle node — no expiry timer,
 * so Doze can defer nothing. See `docs/DIGEST_PULL_REATTACH.md` A1.
 *
 * The fingerprint is XOR of `hash64(id)` over every live held id: order-independent and its own inverse, so
 * add/remove is O(1) ([add]/[remove]) and the lazy fold is O(newly-expired). [setMessages] rebuilds it
 * wholesale (init, sweep, cap-eviction). Owned as a DI singleton — the store impl maintains it and the
 * transports read it — so neither constructs the other. All mutators are `@Synchronized` (store writes on an
 * IO dispatcher; the transports read on their callback threads). [clock] is injectable for tests and must be
 * **wall-clock** (`sentAt` is the originator's wall clock, so expiry comparisons cross node boundaries).
 */
class StoreDigest(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _version = MutableStateFlow(0L)

    /**
     * The current 64-bit content fingerprint as a **push** channel (re-cue / re-advertise collectors). May be
     * stale across a TTL boundary until the next [current] call folds it — decisions and outbound
     * advertisements must read [current] instead.
     */
    val version: StateFlow<Long> = _version.asStateFlow()

    // In-memory mirror of the held live id set (id -> frame-global expiresAt): guards add/remove idempotency,
    // drives the lazy expiry fold, and is the source for a future data-path id-diff. Bounded by the store's
    // global cap.
    private val ids = HashMap<String, Long>()

    /**
     * Fold a newly-carried message id in (no-op if already present, or if [expiresAt] has already passed by
     * [now] — a dead-on-arrival frame must never enter the live fold; the store refuses to persist it too).
     * [now] defaults to [clock] but the store impl passes the same `now` it stamped/guarded the row with, so
     * one timestamp is the whole operation's time authority.
     */
    @Synchronized
    fun add(
        id: String,
        expiresAt: Long,
        now: Long = clock(),
    ) {
        if (expiresAt < now || id in ids) return
        ids[id] = expiresAt
        _version.value = _version.value xor hash64(id)
    }

    /** Fold a dropped message id out (no-op if not present). */
    @Synchronized
    fun remove(id: String) {
        if (ids.remove(id) != null) _version.value = _version.value xor hash64(id)
    }

    /**
     * Rebuild the fingerprint from the full current `(id, expiresAt)` set (startup, TTL sweep, cap eviction).
     * Entries already expired by [now] are dropped, not folded — the rebuild source (`ForwardDao`) is
     * live-filtered, but the guard keeps the invariant local.
     */
    @Synchronized
    fun setMessages(
        entries: Collection<Pair<String, Long>>,
        now: Long = clock(),
    ) {
        ids.clear()
        entries.forEach { (id, expiresAt) -> if (expiresAt >= now) ids[id] = expiresAt }
        _version.value = fingerprint(ids.keys)
    }

    /**
     * The **live** fingerprint: folds out every id whose expiry has passed since the last read (one batched
     * [version] update, so a boundary costs one cue burst / SSI republish / BLE re-advertise no matter how many
     * frames it expires), then returns the fresh value. This is the read for every decision/advert site.
     */
    @Synchronized
    fun current(): Long {
        val now = clock()
        var v = _version.value
        val iter = ids.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value < now) {
                iter.remove()
                v = v xor hash64(entry.key)
            }
        }
        if (v != _version.value) _version.value = v
        return v
    }

    /** Snapshot of the held **live** message ids (for the data-path digest exchange). */
    @Synchronized
    fun messageIds(): Set<String> {
        current()
        return HashSet(ids.keys)
    }

    companion object {
        // FNV-1a 64-bit basis + prime (0xCBF29CE484222325 / 0x100000001B3). A stable, well-distributed hash of
        // an id's UTF-8 bytes — String.hashCode is only 32-bit, so an XOR of those would collide too readily.
        private const val FNV64_OFFSET = -0x340D631B7BDDDCDBL
        private const val FNV64_PRIME = 0x100000001B3L
        private const val BYTE_MASK = 0xFFL

        /**
         * The content fingerprint of an id set: XOR of [hash64] over every id — order-independent and identical
         * to the incremental [add]/[remove] fold. Exposed so a diagnostic (the `debug.STORE` dump) can recompute
         * the digest over an arbitrary subset (live-only vs. all rows) and compare it against [current] to tell
         * benign expired-but-unswept residue (`allFingerprint` lags) apart from an in-memory-digest drift
         * (`liveFingerprint` disagrees — the invariant `current() == liveFingerprint` must always hold).
         */
        fun fingerprint(ids: Iterable<String>): Long = ids.fold(0L) { acc, id -> acc xor hash64(id) }

        fun hash64(s: String): Long {
            var h = FNV64_OFFSET
            for (b in s.encodeToByteArray()) {
                h = h xor (b.toLong() and BYTE_MASK)
                h *= FNV64_PRIME
            }
            return h
        }
    }
}
