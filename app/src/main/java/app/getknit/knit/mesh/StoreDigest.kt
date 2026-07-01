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
 * Deliberately covers **only the carried message set** — the shared state that converges across the mesh — and
 * *not* this device's own profile, which is per-node and never converges (folding it in would make every
 * node's version unique and defeat the "identical ⇒ skip" comparison). A profile edit still propagates: it's
 * flooded on edit and pushed to every newcomer on connect (`MeshManager.pushProfileTo`); it just no longer
 * forces a dedicated data-path sync while the mesh is otherwise idle.
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
        _version.value = currentIds.fold(0L) { acc, id -> acc xor hash64(id) }
    }

    /** Snapshot of the held message ids (for the data-path digest exchange). */
    @Synchronized
    fun messageIds(): Set<String> = HashSet(ids)

    private companion object {
        // FNV-1a 64-bit basis + prime (0xCBF29CE484222325 / 0x100000001B3). A stable, well-distributed hash of
        // an id's UTF-8 bytes — String.hashCode is only 32-bit, so an XOR of those would collide too readily.
        private const val FNV64_OFFSET = -0x340D631B7BDDDCDBL
        private const val FNV64_PRIME = 0x100000001B3L
        private const val BYTE_MASK = 0xFFL

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
