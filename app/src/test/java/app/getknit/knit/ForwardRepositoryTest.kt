package app.getknit.knit

import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.forward.ForwardIdExpiry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    /**
     * In-memory [ForwardDao] whose eviction ordering mirrors the SQL exactly (live-filtered `expiresAt >= now`,
     * then sentAt ASC, id ASC LIMIT n).
     */
    private class FakeForwardDao : ForwardDao {
        val rows = LinkedHashMap<String, ForwardEntity>()

        override suspend fun insert(row: ForwardEntity) {
            rows.putIfAbsent(row.id, row)
        } // OnConflict IGNORE

        override suspend fun liveRows(now: Long) = rows.values.filter { it.expiresAt >= now }.sortedByDescending { it.receivedAt }

        override suspend fun exists(id: String) = rows.containsKey(id)

        override suspend fun recipientOf(id: String) = rows[id]?.recipientId

        override suspend fun delete(id: String) {
            rows.remove(id)
        }

        override suspend fun deleteExpired(now: Long): Int {
            val before = rows.size
            rows.values.removeIf { it.expiresAt < now }
            return before - rows.size
        }

        override suspend fun count(now: Long) = rows.values.count { it.expiresAt >= now }

        override suspend fun allIds() = rows.keys.toList()

        override suspend fun countByAttachmentHash(hash: String) = rows.values.count { it.attachmentHash == hash }

        // The fake has no `blobs` table to join, so it returns every referenced hash; the real SQL "needing
        // fetch" anti-join (NOT IN (SELECT hash FROM blobs)) is exercised against an in-memory Room DB by
        // ForwardDaoTest. Here it's enough to prove the column is populated.
        override suspend fun attachmentHashesNeedingFetch() = rows.values.mapNotNull { it.attachmentHash }.distinct()

        override suspend fun liveIds(now: Long) = rows.values.filter { it.expiresAt >= now }.map { it.id }

        override suspend fun liveIdExpiries(now: Long) =
            rows.values.filter { it.expiresAt >= now }.map { ForwardIdExpiry(it.id, it.expiresAt) }

        override suspend fun allRows() = rows.values.sortedByDescending { it.receivedAt }

        override suspend fun countBySender(
            senderId: String,
            now: Long,
        ) = rows.values.count { it.senderId == senderId && it.expiresAt >= now }

        override suspend fun countByGroup(
            groupId: String,
            now: Long,
        ) = rows.values.count { it.groupId == groupId && it.expiresAt >= now }

        override suspend fun countBroadcast(now: Long) =
            rows.values.count { it.type == "chat" && it.recipientId == null && it.groupId == null && it.expiresAt >= now }

        override suspend fun evictOldest(
            n: Int,
            now: Long,
        ) = evict(rows.values.toList(), n, now)

        override suspend fun evictOldestBySender(
            senderId: String,
            n: Int,
            now: Long,
        ) = evict(rows.values.filter { it.senderId == senderId }, n, now)

        override suspend fun evictOldestByGroup(
            groupId: String,
            n: Int,
            now: Long,
        ) = evict(rows.values.filter { it.groupId == groupId }, n, now)

        override suspend fun evictOldestBroadcast(
            n: Int,
            now: Long,
        ) = evict(rows.values.filter { it.type == "chat" && it.recipientId == null && it.groupId == null }, n, now)

        private fun evict(
            bucket: List<ForwardEntity>,
            n: Int,
            now: Long,
        ) {
            bucket
                .filter { it.expiresAt >= now }
                .sortedWith(compareBy({ it.sentAt }, { it.id }))
                .take(n)
                .forEach { rows.remove(it.id) }
        }
    }

    private fun repo(
        dao: ForwardDao,
        digest: StoreDigest,
    ) = ForwardRepository(dao, digest, maxRows = 1_000, maxPerSender = 5, maxPerGroup = 5, maxBroadcast = 5)

    /** A DM frame (only the per-sender quota + global cap apply) with a caller-set global [sentAt]. */
    private fun dm(
        id: String,
        sender: String,
        sentAt: Long,
    ): CarriedFrame {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                recipientId = "peer",
                payload = WireCodec.encodePayload(ChatContent(body = "")),
            )
        return CarriedFrame(env, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /** A chat frame with caller-chosen addressing + [content] (so we can exercise attachment-hash extraction). */
    private fun chat(
        id: String,
        sender: String,
        sentAt: Long,
        recipientId: String?,
        content: ChatContent,
    ): CarriedFrame {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipientId,
                payload = WireCodec.encodePayload(content),
            )
        return CarriedFrame(env, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /** A chat frame with a raw (here: undecodable) payload — to prove the column decode never gates the insert. */
    private fun chatRaw(
        id: String,
        sender: String,
        sentAt: Long,
        payload: ByteArray,
    ): CarriedFrame {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                recipientId = "peer",
                payload = payload,
            )
        return CarriedFrame(env, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun `store denormalizes the attachment hash but never gates the insert on it`() =
        runTest {
            val dao = FakeForwardDao()
            val digest = StoreDigest()
            val repo = repo(dao, digest)

            // A DM referencing an image (the E2E ciphertext hash rides cleartext alongside enc; extraction reads it).
            repo.store(
                chat("d1", "peer", 1L, "peer", ChatContent(attachmentHash = "CT", attachmentMime = "image/jpeg")),
                ForwardStore.ORIGIN_RELAY,
                now = 0L,
            )
            // A broadcast image (plaintext hash).
            repo.store(chat("b1", "peer", 2L, null, ChatContent(body = "hi", attachmentHash = "PT")), ForwardStore.ORIGIN_RELAY, now = 0L)
            // A chat with no image → null column.
            repo.store(chat("d2", "peer", 3L, "peer", ChatContent(body = "no image")), ForwardStore.ORIGIN_RELAY, now = 0L)
            // Guardrail 1: an undecodable CHAT payload must STILL store the frame (null column), so the id set — and
            // hence the content digest — stays byte-identical mesh-wide.
            repo.store(chatRaw("d3", "peer", 4L, byteArrayOf(-1, -1, -1)), ForwardStore.ORIGIN_RELAY, now = 0L)

            assertEquals("CT", dao.rows["d1"]!!.attachmentHash)
            assertEquals("PT", dao.rows["b1"]!!.attachmentHash)
            assertEquals(null, dao.rows["d2"]!!.attachmentHash)
            assertTrue("an undecodable payload must not drop the frame", dao.exists("d3"))
            assertEquals(null, dao.rows["d3"]!!.attachmentHash)
            assertEquals(1, dao.countByAttachmentHash("CT"))
            // The denormalized column must not perturb the content digest — it folds only the id set.
            assertEquals(StoreDigest.fingerprint(dao.allIds()), digest.version.value)
        }

    @Test
    fun `per-sender quota keeps the newest N by sentAt for our own sends`() =
        runTest {
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
    fun `two nodes fed the same frames in different arrival orders converge to the same digest`() =
        runTest {
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

    @Test
    fun `TTL is frame-global — the same frame expires at the same instant on every node`() =
        runTest {
            val ttl = 1_000L
            // One frame authored at sentAt=500, received by two nodes at very different local clocks.
            val frame = dm("d1", sender = "peer", sentAt = 500L)

            val daoA = FakeForwardDao()
            ForwardRepository(daoA, StoreDigest(), ttlMs = ttl)
                .store(frame, ForwardStore.ORIGIN_RELAY, now = 600L) // A receives just after authoring
            val daoB = FakeForwardDao()
            ForwardRepository(daoB, StoreDigest(), ttlMs = ttl)
                .store(frame, ForwardStore.ORIGIN_RELAY, now = 1_400L) // B receives much later (late joiner)

            // Expiry is sentAt + ttl = 1_500 on BOTH — independent of local arrival (was 1_600 vs 2_400 under the bug).
            assertEquals(1_500L, daoA.rows["d1"]!!.expiresAt)
            assertEquals("a late joiner must not extend the frame's life", daoA.rows["d1"]!!.expiresAt, daoB.rows["d1"]!!.expiresAt)
        }

    @Test
    fun `a frame past its frame-global expiry is refused as dead on arrival, never stored as residue`() =
        runTest {
            val dao = FakeForwardDao()
            val digest = StoreDigest()
            val repo = ForwardRepository(dao, digest, ttlMs = 1_000L)

            // sentAt=500 ⇒ dies at absolute 1_500. Arriving at now=2_000 it is already dead — the shape of a
            // skewed-clock peer re-serving a frame every node has swept. Refusal (not insert-then-ignore) is
            // what keeps it out of the table AND tells ForwardSync to skip custodying its blob.
            assertFalse(repo.store(dm("dead", sender = "peer", sentAt = 500L), ForwardStore.ORIGIN_RELAY, now = 2_000L))
            assertEquals(0, dao.rows.size)
            assertEquals("a refused frame must not touch the digest", 0L, digest.version.value)

            assertTrue(repo.store(dm("live", sender = "peer", sentAt = 1_900L), ForwardStore.ORIGIN_RELAY, now = 2_000L))
            assertEquals(StoreDigest.fingerprint(listOf("live")), digest.version.value)
        }

    @Test
    fun `the TTL sweep is digest-neutral — the lazy fold already dropped the lapsed id (work item 8)`() =
        runTest {
            var now = 0L
            val dao = FakeForwardDao()
            val digest = StoreDigest { now }
            val repo = ForwardRepository(dao, digest, ttlMs = 1_000L)

            repo.store(dm("dies", sender = "peer", sentAt = 0L), ForwardStore.ORIGIN_RELAY, now = 0L) // expires at 1_000
            repo.store(dm("lives", sender = "peer", sentAt = 5_000L), ForwardStore.ORIGIN_RELAY, now = 0L) // expires at 6_000

            now = 2_000L
            val atBoundary = digest.current() // the read folds "dies" out — no sweep has run yet
            assertEquals(StoreDigest.fingerprint(listOf("lives")), atBoundary)
            assertTrue("the lapsed row is still physical residue awaiting GC", dao.rows.containsKey("dies"))

            assertEquals(1, repo.sweepExpired(now = now)) // pure storage GC…
            assertEquals("…that must not move the digest", atBoundary, digest.current())
        }

    @Test
    fun `an expired-unswept row must not perturb which live rows the quota evicts (work item 8)`() =
        runTest {
            // Buckets mix TTL classes: an expired short-TTL broadcast row is NEWER by sentAt than two live
            // long-TTL DMs. Node A has swept it; node B has not. When a fourth frame arrives at both, a count
            // over all rows would push B (4 > quota 3) into evicting its oldest LIVE row — a row A keeps — so
            // the live sets (and the live-only digests) would diverge on sweep phase alone.
            fun boundedRepo(
                dao: ForwardDao,
                digest: StoreDigest,
            ) = ForwardRepository(dao, digest, ttlMs = 10_000L, broadcastTtlMs = 1_000L, maxPerSender = 3)

            val frames =
                listOf(
                    dm("dm_old", sender = "S", sentAt = 100L), // expires 10_100 — live throughout
                    dm("dm_mid", sender = "S", sentAt = 200L), // expires 10_200 — live throughout
                    chat("bc_new", "S", 5_000L, null, ChatContent(body = "x")), // broadcast: expires 6_000 — lapses
                )

            var nowA = 5_500L
            val digestA = StoreDigest { nowA }
            val daoA = FakeForwardDao()
            val repoA = boundedRepo(daoA, digestA)
            var nowB = 5_500L
            val digestB = StoreDigest { nowB }
            val daoB = FakeForwardDao()
            val repoB = boundedRepo(daoB, digestB)
            frames.forEach {
                repoA.store(it, ForwardStore.ORIGIN_RELAY, now = 5_500L)
                repoB.store(it, ForwardStore.ORIGIN_RELAY, now = 5_500L)
            }

            nowA = 7_000L // bc_new lapsed on both clocks…
            nowB = 7_000L
            repoA.sweepExpired(now = 7_000L) // …but only A's sweep tick has fired

            val fresh = dm("dm_new", sender = "S", sentAt = 6_000L)
            repoA.store(fresh, ForwardStore.ORIGIN_RELAY, now = 7_000L)
            repoB.store(fresh, ForwardStore.ORIGIN_RELAY, now = 7_000L)

            assertEquals(
                "swept and unswept nodes must keep the identical live set",
                daoA.liveIds(7_000L).sorted(),
                daoB.liveIds(7_000L).sorted(),
            )
            assertEquals(listOf("dm_mid", "dm_new", "dm_old"), daoB.liveIds(7_000L).sorted()) // nobody evicted a live row
            assertEquals("live-only digests converge across the sweep phase", digestA.current(), digestB.current())
        }

    @Test
    fun `a late joiner sweeps a frame at its frame-global expiry, not TTL-from-arrival`() =
        runTest {
            val ttl = 1_000L
            val dao = FakeForwardDao()
            val digest = StoreDigest()
            val repo = ForwardRepository(dao, digest, ttlMs = ttl)

            // Authored at sentAt=500 ⇒ dies at absolute 1_500. This node receives it late (now=600).
            repo.store(dm("d1", sender = "peer", sentAt = 500L), ForwardStore.ORIGIN_RELAY, now = 600L)
            assertTrue(dao.exists("d1"))

            // At now=1_600 the frame is past its frame-global expiry (1_500) and is reclaimed. Under the old
            // `receivedAt + ttl` bug expiresAt would be 1_600, so `expiresAt < now` (1_600 < 1_600) is false and the
            // frame would survive — the non-convergent leak this guards.
            assertEquals(1, repo.sweepExpired(now = 1_600L))
            assertEquals(0, dao.count(now = 1_600L))
            assertEquals(StoreDigest.fingerprint(dao.allIds()), digest.version.value)
        }
}
