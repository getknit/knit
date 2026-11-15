package app.getknit.knit.mesh.spool

import app.getknit.knit.data.scopeanchor.DmScopeAnchorDao
import app.getknit.knit.data.scopeanchor.DmScopeAnchorEntity
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps each DM peer's **anchor** (`docs/SPOOL_PROTOCOL.md` C-3.1-6, ADR 2026-11.dgj2, #324) and turns the armed ones
 * into a restore's scope inputs.
 *
 * A backup carries no ratchet session, so after a restore our resets replace no root and the DM scope the peer still
 * reads is underivable. The anchor is that scope's id and outer seal, kept current here from every confirmed session
 * and carried in the backup ([DmScopeAnchorEntity]). It is valid until the pair's next reset, so it is kept only for
 * the person's contacts among the peers a restore resets ([reconcile]'s `targets`, `MeshManager.anchorTargets`: an
 * accepted DM or an accepted group's member, never a blocked one), and every unarmed row a reconcile did not just write
 * or keep is deleted: a removed contact, a stranger or an unaccepted request whose init we answered, an era whose root
 * has left the ratchet. A blocked peer's row goes armed or not ([reconcile]'s `dropped`). The one row a reconcile leaves
 * as it is belongs to a peer our own reset is still waiting on ([reconcile]'s `keep`): that peer may still be on the
 * root the row names, so a backup taken then still reaches it.
 *
 * Pure apart from the DAO seam and the clock, and serialised by one [lock], so a reconcile, an [arm] and a [remove]
 * never interleave their reads and writes.
 */
class DmScopeAnchors(
    private val dao: DmScopeAnchorDao,
    private val clock: () -> Long,
    /** How long a restore keeps an anchor armed with no answer ([ARM_WINDOW_MS] in production). */
    private val armWindowMs: Long = ARM_WINDOW_MS,
) {
    /** One confirmed session's live root, as `RatchetSessions.exportedRoots` exports it. */
    class Confirmed(
        val peerId: String,
        val pairwiseRoot: ByteArray,
    )

    /**
     * Our own unanswered flagged reset to [peerId], as `RatchetSessions.ownPendingResets` exports it: its root, its init,
     * and the root its slot drains until [prevRootExpiresAt] (the restore's own root once a second reset re-rooted over it).
     */
    class Pending(
        val peerId: String,
        val pairwiseRoot: ByteArray,
        val resetInit: ByteArray,
        val prevPairwiseRoot: ByteArray? = null,
        val prevRootExpiresAt: Long = 0L,
    )

    private val lock = Mutex()

    /**
     * Writes the live scope of each [confirmed] session whose peer is in [targets], unarmed, and deletes every other
     * row of a target that is not armed, but for [keep]'s (a peer our own reset is still waiting on, round-1 finding C3).
     * A confirmed session supersedes its anchor's arming: the restore's reset was answered. A row whose peer is not in
     * [targets] goes whatever its arming (round-2 finding 4), as do [dropped]'s (a blocked peer, S2) and a row armed past
     * any window a restore sets. An unchanged unarmed row is not written again.
     */
    suspend fun reconcile(
        selfId: String,
        confirmed: List<Confirmed>,
        targets: Set<String>,
        keep: Set<String> = emptySet(),
        dropped: Set<String> = emptySet(),
    ) = lock.withLock {
        val now = clock()
        val live = confirmed.filter { it.peerId != selfId && it.peerId in targets && it.peerId !in dropped }
        writeLive(selfId, live, now)
        prune(now, written = live.mapTo(HashSet()) { it.peerId }, targets, keep, dropped)
    }

    /** [reconcile]'s writes, under its lock: each [live] session's scope, unarmed, unless the row already says so. */
    private suspend fun writeLive(
        selfId: String,
        live: List<Confirmed>,
        now: Long,
    ) {
        val stored = dao.all().associateBy { it.peerId }
        for (entry in live) {
            val id = ScopeCrypto.dmScopeId(entry.pairwiseRoot, selfId, entry.peerId)
            val keys = ScopeCrypto.dmSealKeys(entry.pairwiseRoot, selfId, entry.peerId)
            // Unchanged and unarmed: nothing to write (a reconcile runs on every root change). An armed one is written,
            // which disarms it: the restore's reset was answered.
            val held = stored[entry.peerId]
            val same =
                held != null && held.armedUntil == 0L && held.scopeId.contentEquals(id) &&
                    held.sealKey.contentEquals(keys.sealKey) && held.nonceKey.contentEquals(keys.nonceKey)
            if (!same) {
                dao.put(DmScopeAnchorEntity(entry.peerId, id, keys.sealKey, keys.nonceKey, armedUntil = 0L, updatedAt = now))
            }
        }
    }

    /** [reconcile]'s deletes, under its lock. */
    private suspend fun prune(
        now: Long,
        written: Set<String>,
        targets: Set<String>,
        keep: Set<String>,
        dropped: Set<String>,
    ) {
        for (row in dao.all()) {
            when {
                row.peerId in dropped -> dao.delete(row.peerId)

                // Armed past any window a restore sets: a restore file's value, never this phone's own arming.
                row.armedUntil > now + armWindowMs + ARM_CLOCK_SLACK_MS -> dao.delete(row.peerId)

                // No longer one a restore resets as a contact (left the group, the thread deleted): armed or not, its
                // re-sends would otherwise run on for the window (round-2 finding 4).
                row.peerId !in targets -> dao.delete(row.peerId)

                row.peerId in written || row.peerId in keep -> Unit

                else -> dao.deleteUnarmed(row.peerId, now)
            }
        }
    }

    /**
     * Arms [peers]' anchors for the restore's window, after disarming every row: a restore file can arrive with any
     * `armedUntil`, so only the restore that is running decides which anchors it uses. Called by
     * `MeshManager.finishRestore` before its resets go out.
     */
    suspend fun arm(peers: Set<String>) =
        lock.withLock {
            dao.disarmAll()
            val until = clock() + armWindowMs
            for (peer in peers) dao.arm(peer, until)
        }

    /**
     * Whether a restore armed [peerId]'s anchor and its window is still open: `MeshManager`'s prekey trigger asks before
     * it re-sends a restore's reset (ADR 2026-11.dgj2). The same test as [restored].
     */
    suspend fun isArmed(peerId: String): Boolean {
        val now = clock()
        return lock.withLock { dao.all() }.any { it.peerId == peerId && armed(it, now) && wellFormed(it) }
    }

    /** Drops [peerId]'s anchor at once: the contact was removed (ADR 2026-09.adgd). */
    suspend fun remove(peerId: String) = lock.withLock { dao.delete(peerId) }

    /**
     * The restore's scope inputs: each armed anchor joined with our [pending] reset to its peer. A row of the wrong
     * shape, or armed further ahead than any restore arms one, is skipped: a restore file is hostile input.
     */
    suspend fun restored(pending: List<Pending>): List<RestoredDmRoots> {
        if (pending.isEmpty()) return emptyList()
        val now = clock()
        val rows = lock.withLock { dao.all() }
        val armed = rows.filter { armed(it, now) && wellFormed(it) }.associateBy { it.peerId }
        return pending.mapNotNull { reset ->
            val anchor = armed[reset.peerId] ?: return@mapNotNull null
            RestoredDmRoots(
                peerId = reset.peerId,
                pairwiseRoot = reset.pairwiseRoot,
                resetInit = reset.resetInit,
                anchorId = anchor.scopeId,
                anchorKeys = ScopeCrypto.SealKeys(anchor.sealKey, anchor.nonceKey),
                prevPairwiseRoot = reset.prevPairwiseRoot,
                prevRootExpiresAt = reset.prevRootExpiresAt,
            )
        }
    }

    private fun armed(
        row: DmScopeAnchorEntity,
        now: Long,
    ): Boolean = row.armedUntil > now && row.armedUntil <= now + armWindowMs + ARM_CLOCK_SLACK_MS

    private fun wellFormed(row: DmScopeAnchorEntity): Boolean =
        row.scopeId.size == ScopeCrypto.SCOPE_ID_BYTES &&
            row.sealKey.size == ScopeCrypto.KEY_BYTES &&
            row.nonceKey.size == ScopeCrypto.KEY_BYTES

    companion object {
        /**
         * A restore keeps re-sending its reset into an anchor until the peer answers, for at most this long (the user's
         * call on 2026-11-14): a contact with long outages still hears it, and one who never comes back (uninstalled,
         * a stale anchor) costs one small push per [RESEND_FLOOR_MS] for a bounded time, not forever.
         */
        const val ARM_WINDOW_MS = 30L * 24 * 60 * 60_000L

        /**
         * How often the pending reset to an armed peer is sent again: under custody's 24 h TTL, so a copy is always
         * in custody for `ScopeSync` to push once the spool's 48 h retention drops the last one.
         */
        const val RESEND_FLOOR_MS = 20L * 60 * 60_000L

        /**
         * How far past the window a row's `armedUntil` may sit and still count as this phone's own arming (round-4
         * finding 3). The wall clock can step back after `arm` — a network time fix, a user's correction — and a row armed
         * at the old reading then looks armed past any window a restore sets, which the hostile-file guard deletes: the
         * restore's own anchor gone, and #324's strand back. A day absorbs any step a phone takes in practice; a restore
         * file's row armed further ahead is still skipped and dropped.
         */
        const val ARM_CLOCK_SLACK_MS = 24L * 60 * 60_000L
    }
}
