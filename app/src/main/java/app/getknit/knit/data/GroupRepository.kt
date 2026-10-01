package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore
import app.getknit.knit.mesh.spool.GroupRootStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single source of truth for group chats (and, transactionally, their group-ratchet state hooks). */
class GroupRepository(
    private val dao: GroupDao,
    private val messages: MessageRepository,
    private val db: KnitDatabase,
    private val groupRatchet: GroupRatchetStore,
    // The spool plane's group root (docs/SPOOL_PROTOCOL.md §3.2) shares this class's two lifecycle hooks:
    // a departure obliges a re-mint, and leaving/deleting drops the root with the rest of the group's key
    // state. Nullable so rigs that don't exercise the plane construct unchanged.
    private val groupRoots: GroupRootStore? = null,
) {
    fun observeGroups(): Flow<List<GroupEntity>> = dao.observeAll()

    fun observeGroup(groupId: String): Flow<GroupEntity?> = dao.observeById(groupId)

    suspend fun find(groupId: String): GroupEntity? = dao.findById(groupId)

    suspend fun upsert(group: GroupEntity) = dao.upsert(group)

    /** Every group we still hold (leave-tombstoned rows excluded). */
    suspend fun active(): List<GroupEntity> = dao.allActive()

    /** The groups we still hold that decided on the photo [hash] and do not show it yet (ADR 2026-09.nxcq). */
    suspend fun awaitingPhoto(hash: String): List<GroupEntity> = dao.awaitingPhoto(hash)

    /** Decided group photos not shown yet whose bytes are not local — what a pull must still fetch. */
    suspend fun photoHashesNeedingFetch(): List<String> = dao.photoHashesNeedingFetch()

    /**
     * The non-left groups whose effective roster contains [memberId] (the roster is a JSON column, so
     * this filters in memory — bounded by the user's group count). Feeds the seed re-distribution
     * triggers (docs/GROUP_FORWARD_SECRECY.md §3).
     */
    suspend fun groupsWith(memberId: String): List<GroupEntity> = active().filter { memberId in GroupMembersStore.decode(it.members) }

    /**
     * Drops the seed-outbox rows a device once kept for *itself* — [nodeId] is our own — in every group we
     * still hold, and returns how many there were. A member never seals a seed to itself, but builds before
     * 2026-09-13 did (`flushGroupKeys(me)` off a self-pinned `peers` row), and a row outlives the loop that
     * wrote it: it dies only with the member's departure or the group's leave, and we never depart our own
     * groups. Rows for a left group are already gone (leave drops them all). Run once at mesh start; a
     * no-op on a clean device.
     */
    suspend fun forgetSelf(nodeId: String): Int =
        db.withWriteTransaction {
            groupsWith(nodeId).count { group ->
                val had = groupRatchet.keySend(group.groupId, nodeId) != null
                if (had) groupRatchet.deleteKeySend(group.groupId, nodeId)
                had
            }
        }

    /**
     * [groupsWith] as a live flow, for surfaces that show "groups in common" beside a peer.
     *
     * The `left` filter is explicit here because [GroupDao.observeAll] has none — only `allActive()`
     * carries `WHERE left = 0`. Drop it and a group you have already walked out of keeps listing itself
     * as shared.
     */
    fun observeGroupsWith(memberId: String): Flow<List<GroupEntity>> =
        dao.observeAll().map { groups ->
            groups.filter { !it.left && memberId in GroupMembersStore.decode(it.members) }
        }

    /**
     * Records that [leaverId] left [groupId] (from their own signed `groupleave` frame): drops them from
     * the roster, tombstones them in [GroupEntity.departed] so a straggler's stale full roster can't
     * re-add them, and inserts a "member left" status notice stamped [leftAt] (the frame's sentAt, for
     * stable cross-device ordering). The whole read-modify-write plus the message insert run in one
     * transaction so the count and the notice can't tear apart and so a concurrent rename can't clobber
     * the tombstone. Returns true only when [leaverId] was actually a current member — a no-op (already
     * gone, never a member, or a group we've left) returns false, which both dedups a re-flooded leave
     * and tells the caller not to surface anything. The status row's id is deterministic so a replay
     * upserts the same row rather than duplicating it.
     *
     * A leave older than the member's recorded rejoin ([recordRejoin]'s notice) is also a no-op: once a
     * member can come back, a custody re-serve of their earlier `groupleave` would otherwise evict them
     * again. Leave and rejoin are last-writer-wins on the member's own `sentAt` clock.
     */
    suspend fun recordDeparture(
        groupId: String,
        leaverId: String,
        leftAt: Long,
    ): Boolean =
        db.withWriteTransaction {
            val group = dao.findById(groupId) ?: return@withWriteTransaction false
            if (group.left) return@withWriteTransaction false
            val members = GroupMembersStore.decode(group.members)
            if (leaverId !in members) return@withWriteTransaction false
            val rejoinedAt = messages.sentAtOf(StatusNotices.rejoinId(groupId, leaverId)) ?: 0L
            if (leftAt <= rejoinedAt) return@withWriteTransaction false
            val departed = GroupMembersStore.decode(group.departed)
            dao.upsert(
                group.copy(
                    members = GroupMembersStore.encode(members - leaverId),
                    departed = GroupMembersStore.encode((departed + leaverId).distinct()),
                ),
            )
            // Leave-rekey (docs/GROUP_FORWARD_SECRECY.md #6.1), atomic with the roster shrink: drop our
            // send chains so the next send mints a fresh epoch distributed to the REMAINING members only
            // (the leaver reads nothing sealed after this commits), and drop the leaver's outbox row.
            // Their recv chains drain via the 48h sweep — their pre-leave frames may still re-serve.
            groupRatchet.deleteSendChains(groupId)
            groupRatchet.deleteKeySend(groupId, leaverId)
            // The spool plane's rotation hook (docs/SPOOL_PROTOCOL.md §3.2), atomic with the same roster
            // shrink: record that a re-mint is OWED. The mint itself happens on the heal pass, not here —
            // splitting the obligation from the act is what makes rotation survive a crash between them,
            // and it is what lets the mint grace cover a deterministic re-minter who never comes back.
            groupRoots?.markRemintDue(groupId, leftAt)
            messages.save(StatusNotices.memberLeft(groupId, leaverId, leftAt))
            true
        }

    /**
     * The mirror of [recordDeparture], for a founding member who left and re-added themselves with their
     * own signed frame (`InboundPipeline.reconcileGroup` decides that — it is the only writer of the
     * pinned roster, and has already moved [memberId] from `departed` back into `members` in this same
     * transaction). This records the rest: a "member rejoined" notice stamped [rejoinedAt] (the frame's
     * `sentAt`, the clock [recordDeparture] reads back), and — when [rekey] — the same rekey a departure
     * forces: our send chains die so the next send mints a fresh epoch, distributed to the roster as it
     * is *now*, and the rejoiner reads nothing sealed while they were out. [rekey] is the caller's
     * per-(group, member) floor, so a leave/rejoin loop cannot make every member re-mint on each turn.
     * The group's spool root is untouched: a rejoiner adopts the current one from the next gossip.
     */
    suspend fun recordRejoin(
        groupId: String,
        memberId: String,
        rejoinedAt: Long,
        rekey: Boolean,
    ) {
        db.withWriteTransaction {
            if (rekey) groupRatchet.deleteSendChains(groupId)
            messages.save(StatusNotices.memberRejoined(groupId, memberId, rejoinedAt))
        }
    }

    /**
     * Leaves [groupId]: tombstones the row (so inbound frames are dropped and never resurrect it) and
     * deletes the thread's messages so it vanishes from the chat list. The local user stops receiving;
     * other members still treat them as a roster entry (membership is reconstructed per-device from
     * frames), but their frames are now ignored here.
     *
     * The tombstone + message purge run in one transaction so they can't tear apart, and so a
     * concurrent inbound group frame — whose reconcile is likewise transactional — can't observe the
     * row as still-present between the two writes and resurrect it.
     */
    suspend fun leave(groupId: String) {
        db.withWriteTransaction {
            dao.markLeft(groupId)
            messages.deleteByConversation(groupId)
            // All group ratchet state dies with our membership (chains, skipped keys, outbox) — and with it
            // the group's spool root, so the scope stops being derived the moment we leave.
            groupRatchet.purgeGroup(groupId)
            groupRoots?.purge(groupId)
        }
    }

    /**
     * Deletes [groupId] locally without leaving: hard-deletes the row and clears its messages, so the
     * chat disappears now but the next inbound group frame re-creates it via MeshManager.reconcileGroup
     * (contrast [leave], which tombstones to block re-add). The row delete + message purge run in one
     * transaction so they can't tear apart.
     */
    suspend fun delete(groupId: String) {
        db.withWriteTransaction {
            dao.deleteById(groupId)
            messages.deleteByConversation(groupId)
            // A re-created group (via reconcileGroup) starts with clean ratchet state — and a clean root, so
            // it re-adopts the current one from the first gossiping ctl DM rather than reviving a stale scope.
            groupRatchet.purgeGroup(groupId)
            groupRoots?.purge(groupId)
        }
    }
}
