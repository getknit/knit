package app.getknit.knit

import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The custody quotas ([ForwardRepository]) must keep every node's carried set — and hence the cue-plane
 * content digest ([StoreDigest]) — **convergent**. The bug this guards: the old quota refused frames past a
 * cap and never bounded our own outbox, so a node that authored more than a quota's worth of custodial frames
 * held rows a capped carrier could never accept, so their digests never matched and the mesh churned NDPs
 * forever (field-observed: a 117-frame sender vs. the 100 per-sender quota). The fix trims each over-quota
 * bucket to its newest-N *by the frame-global sentAt*, applied to every origin, so all nodes keep the same set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardRepositoryTest {

    /** In-memory [ForwardDao] whose eviction ordering mirrors the SQL exactly (sentAt ASC, id ASC LIMIT n). */
    private class FakeForwardDao : ForwardDao {
        val rows = LinkedHashMap<String, ForwardEntity>()

        override suspend fun insert(row: ForwardEntity) { rows.putIfAbsent(row.id, row) } // OnConflict IGNORE
        override suspend fun liveRows(now: Long) = rows.values.filter { it.expiresAt >= now }.sortedByDescending { it.receivedAt }
        override suspend fun exists(id: String) = rows.containsKey(id)
        override suspend fun recipientOf(id: String) = rows[id]?.recipientId
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun deleteExpired(now: Long): Int {
            val before = rows.size
            rows.values.removeIf { it.expiresAt < now }
            return before - rows.size
        }
        override suspend fun count() = rows.size
        override suspend fun allIds() = rows.keys.toList()
        override suspend fun liveIds(now: Long) = rows.values.filter { it.expiresAt >= now }.map { it.id }
        override suspend fun allRows() = rows.values.sortedByDescending { it.receivedAt }
        override suspend fun countBySender(senderId: String) = rows.values.count { it.senderId == senderId }
        override suspend fun countByGroup(groupId: String) = rows.values.count { it.groupId == groupId }
        override suspend fun countBroadcast() =
            rows.values.count { it.type == "chat" && it.recipientId == null && it.groupId == null }

        override suspend fun evictOldest(n: Int) = evict(rows.values.toList(), n)
        override suspend fun evictOldestBySender(senderId: String, n: Int) = evict(rows.values.filter { it.senderId == senderId }, n)
        override suspend fun evictOldestByGroup(groupId: String, n: Int) = evict(rows.values.filter { it.groupId == groupId }, n)
        override suspend fun evictOldestBroadcast(n: Int) =
            evict(rows.values.filter { it.type == "chat" && it.recipientId == null && it.groupId == null }, n)

        private fun evict(bucket: List<ForwardEntity>, n: Int) {
            bucket.sortedWith(compareBy({ it.sentAt }, { it.id })).take(n).forEach { rows.remove(it.id) }
        }
    }

    private fun repo(dao: ForwardDao, digest: StoreDigest) =
        ForwardRepository(dao, digest, maxRows = 1_000, maxPerSender = 5, maxPerGroup = 5, maxBroadcast = 5)

    /** A DM frame (only the per-sender quota + global cap apply) with a caller-set global [sentAt]. */
    private fun dm(id: String, sender: String, sentAt: Long): CarriedFrame {
        val env = RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = sender,
            sentAt = sentAt,
            recipientId = "peer",
            payload = WireCodec.encodePayload(ChatContent(body = "")),
        )
        return CarriedFrame(env, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun `per-sender quota keeps the newest N by sentAt for our own sends`() = runTest {
        val dao = FakeForwardDao()
        val digest = StoreDigest()
        val repo = repo(dao, digest)

        // 8 of our own sends (ORIGIN_SELF), sentAt 1..8. The old code left all 8 (own outbox bypassed the quota).
        (1..8).forEach { repo.store(dm("f%02d".format(it), sender = "self", sentAt = it.toLong()), ForwardStore.ORIGIN_SELF, now = 0L) }

        val kept = dao.allIds().sorted()
        assertEquals(listOf("f04", "f05", "f06", "f07", "f08"), kept) // newest 5 by sentAt
        assertEquals(StoreDigest.fingerprint(kept), digest.version.value) // digest tracks exactly the kept set
    }

    @Test
    fun `two nodes fed the same frames in different arrival orders converge to the same digest`() = runTest {
        val frames = (1..8).map { dm("f%02d".format(it), sender = "peer", sentAt = it.toLong()) }

        val digestA = StoreDigest()
        val repoA = repo(FakeForwardDao(), digestA)
        frames.forEach { repoA.store(it, ForwardStore.ORIGIN_RELAY, now = 0L) } // ascending arrival

        val digestB = StoreDigest()
        val repoB = repo(FakeForwardDao(), digestB)
        frames.reversed().forEach { repoB.store(it, ForwardStore.ORIGIN_RELAY, now = 0L) } // descending (late arrival)

        assertEquals(digestA.version.value, digestB.version.value)
        assertEquals(StoreDigest.fingerprint(listOf("f04", "f05", "f06", "f07", "f08")), digestB.version.value)
    }
}
