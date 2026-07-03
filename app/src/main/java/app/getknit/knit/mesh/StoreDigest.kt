package app.getknit.knit.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A **content digest** of the carried-message set — an order-independent 64-bit fingerprint of the ids in the
 * [ForwardStore]. The Wi-Fi Aware **cue plane** (`WifiAwareTransport`) advertises the [version] over the tiny
 * no-data-path message channel so a neighbor can decide, *without* bringing up a scarce NAN data path, whether
 * a sync is worth it — two nodes whose carried set matches produce the **same** version and skip the data path.
 *
 * Replaces the old monotone `SyncEpoch` counter. Two wins from being content-derived rather than a counter:
 *  - **no no-op syncs** — the version only differs when the carried *set* differs, so an already-matching pair
 *    never wastes an NDP (the scarcest resource here — see `docs/DIGEST_PULL_REATTACH.md`);
 *  - **restart-stable** — the same store after a restart yields the same version, so a peer that watermarked
 *    us before the restart doesn't miss us (the old session-local counter reset to 0 and could be ignored by
 *    a peer's monotone-max until it climbed back).
 *
 * Covers **every id in the carried set** — all custodied frame types (chat, reaction, receipt, group-update/
 * leave, and profiles), since general custody (commit `b277e06`) put them all in `forward_store` and the store
 * rebuilds the digest from `dao.allIds()`. Profiles converge despite being per-node because custody is
 * **symmetric**: each node holds its own profile (`ORIGIN_SELF`) *and* every peer's (`ORIGIN_RELAY`), so once
 * profiles have propagated the whole mesh shares one id set and the "identical ⇒ skip" comparison still holds.
 * (This corrects an earlier "message-only, excludes own profile" note from Phase 1a, before general custody.)
 *
 * The fingerprint is XOR of `hash64(id)` over every held id: order-independent and its own inverse, so
 * add/remove is O(1) ([add]/[remove]); [setMessages] rebuilds it wholesale (init, sweep, cap-eviction). Owned
 * as a DI singleton — the store impl maintains it and the transport reads [version] — so neither constructs
 * the other. All mutators are `@Synchronized` (store writes on an IO dispatcher; the transport reads on its
 * callback thread).
 */
class StoreDigest {
    private val _version = MutableStateFlow(0L)

    /** The current 64-bit content fingerprint; the transport cues it and compares peers' against it. */
    val version: StateFlow<Long> = _version.asStateFlow()

    // In-memory mirror of the held message-id set: guards add/remove idempotency and is the source for a
    // future data-path id-diff (see docs). Bounded by the store's global cap.
    private val ids = HashSet<String>()

    /** Fold a newly-carried message id in (no-op if already present). */
    @Synchronized
    fun add(id: String) {
        if (ids.add(id)) _version.value = _version.value xor hash64(id)
    }

    /** Fold a dropped message id out (no-op if not present). */
    @Synchronized
    fun remove(id: String) {
        if (ids.remove(id)) _version.value = _version.value xor hash64(id)
    }

    /** Rebuild the fingerprint from the full current id set (startup, TTL sweep, cap eviction). */
    @Synchronized
    fun setMessages(currentIds: Collection<String>) {
        ids.clear()
        ids.addAll(currentIds)
        _version.value = fingerprint(currentIds)
    }

    /** Snapshot of the held message ids (for the data-path digest exchange). */
    @Synchronized
    fun messageIds(): Set<String> = HashSet(ids)

    companion object {
        // FNV-1a 64-bit basis + prime (0xCBF29CE484222325 / 0x100000001B3). A stable, well-distributed hash of
        // an id's UTF-8 bytes — String.hashCode is only 32-bit, so an XOR of those would collide too readily.
        private const val FNV64_OFFSET = -0x340D631B7BDDDCDBL
        private const val FNV64_PRIME = 0x100000001B3L
        private const val BYTE_MASK = 0xFFL

        /**
         * The content fingerprint of an id set: XOR of [hash64] over every id — order-independent and identical
         * to the incremental [add]/[remove] fold. Exposed so a diagnostic (the `debug.STORE` dump) can recompute
         * the digest over an arbitrary subset (live-only vs. all rows) and compare it against [version] to tell
         * an expired-but-unswept divergence apart from an in-memory-digest drift.
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
