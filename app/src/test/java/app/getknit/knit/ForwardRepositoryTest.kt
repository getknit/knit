package app.getknit.knit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [ForwardRepository] against a **real** in-memory [KnitDatabase] (finding #5 — the store/evict/digest
 * coordination was previously verified only against a hand-mirrored `FakeForwardDao`, a `HashMap` whose ordering
 * could silently diverge from the SQLite it stood in for; the raw SQL is now covered by `ForwardDaoTest`).
 *
 * The custody quotas must keep every node's carried set — and hence the cue-plane content digest ([StoreDigest]) —
 * **convergent**. The bug this guards: the old quota refused frames past a cap and never bounded our own outbox, so
 * a node that authored more than a quota's worth of custodial frames held rows a capped carrier could never accept,
 * so their digests never matched and the mesh churned NDPs forever (field-observed: a 117-frame sender vs. the 100
 * per-sender quota). The fix trims each over-quota bucket to its newest-N *by the frame-global sentAt*, applied to
 * every origin, so all nodes keep the same set.
 *
 * The store/remove/sweep mutations now also run their DB writes in a transaction under a repo mutex so the in-memory
 * digest stays in lockstep with the committed rows — see `concurrent store and sweep keep the digest consistent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardRepositoryTest : RoomDbTest() {
    // Secondary in-memory DBs for the two-node convergence tests (RoomDbTest owns the primary `db`).
    private val extraDbs = mutableListOf<KnitDatabase>()

    @After
    fun closeExtraDbs() = extraDbs.forEach { it.close() }

    private fun newDb(): KnitDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { extraDbs += it }

    /** Repo with small quotas (per-sender/group/broadcast = 5) and the default TTLs — the quota-convergence tests. */
    private fun repo(
        database: KnitDatabase,
        digest: StoreDigest,
    ) = ForwardRepository(
        database.forwardDao(),
        digest,
        database,
        maxRows = 1_000,
        maxPerSender = 5,
        maxPerGroup = 5,
        maxBroadcast = 5,
    )

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
            val dao = db.forwardDao()
            val digest = StoreDigest()
            val repo = repo(db, digest)

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
            // Guardrail: an undecodable CHAT payload must STILL store the frame (null column), so the id set — and
            // hence the content digest — stays byte-identical mesh-wide.
            repo.store(chatRaw("d3", "peer", 4L, byteArrayOf(-1, -1, -1)), ForwardStore.ORIGIN_RELAY, now = 0L)

            val byId = dao.allRows().associateBy { it.id }
            assertEquals("CT", byId["d1"]!!.attachmentHash)
            assertEquals("PT", byId["b1"]!!.attachmentHash)
            assertEquals(null, byId["d2"]!!.attachmentHash)
            assertTrue("an undecodable payload must not drop the frame", dao.exists("d3"))
            assertEquals(null, byId["d3"]!!.attachmentHash)
            assertEquals(1, dao.countByAttachmentHash("CT"))
            // The denormalized column must not perturb the content digest — it folds only the id set.
            assertEquals(StoreDigest.fingerprint(dao.allIds()), digest.version.value)
        }

    @Test
    fun `per-sender quota keeps the newest N by sentAt for our own sends`() =
        runTest {
            val dao = db.forwardDao()
            val digest = StoreDigest()
            val repo = repo(db, digest)

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
            val repoA = repo(db, digestA)
            frames.forEach { repoA.store(it, ForwardStore.ORIGIN_RELAY, now = 0L) } // ascending arrival

            val dbB = newDb()
            val digestB = StoreDigest()
            val repoB = repo(dbB, digestB)
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

            ForwardRepository(db.forwardDao(), StoreDigest(), db, ttlMs = ttl)
                .store(frame, ForwardStore.ORIGIN_RELAY, now = 600L) // A receives just after authoring
            val dbB = newDb()
            ForwardRepository(dbB.forwardDao(), StoreDigest(), dbB, ttlMs = ttl)
                .store(frame, ForwardStore.ORIGIN_RELAY, now = 1_400L) // B receives much later (late joiner)

            val daoA = db.forwardDao()
            val daoB = dbB.forwardDao()
            val expiresA = daoA.allRows().single { it.id == "d1" }.expiresAt
            val expiresB = daoB.allRows().single { it.id == "d1" }.expiresAt
            // Expiry is sentAt + ttl = 1_500 on BOTH — independent of local arrival (was 1_600 vs 2_400 under the bug).
            assertEquals(1_500L, expiresA)
            assertEquals("a late joiner must not extend the frame's life", expiresA, expiresB)
        }

    @Test
    fun `a frame past its frame-global expiry is refused as dead on arrival, never stored as residue`() =
        runTest {
            val dao = db.forwardDao()
            val digest = StoreDigest()
            val repo = ForwardRepository(dao, digest, db, ttlMs = 1_000L)

            // sentAt=500 ⇒ dies at absolute 1_500. Arriving at now=2_000 it is already dead — the shape of a
            // skewed-clock peer re-serving a frame every node has swept. Refusal (not insert-then-ignore) is
            // what keeps it out of the table AND tells ForwardSync to skip custodying its blob.
            assertFalse(repo.store(dm("dead", sender = "peer", sentAt = 500L), ForwardStore.ORIGIN_RELAY, now = 2_000L))
            assertEquals(0, dao.allIds().size)
            assertEquals("a refused frame must not touch the digest", 0L, digest.version.value)

            assertTrue(repo.store(dm("live", sender = "peer", sentAt = 1_900L), ForwardStore.ORIGIN_RELAY, now = 2_000L))
            assertEquals(StoreDigest.fingerprint(listOf("live")), digest.version.value)
        }

    @Test
    fun `a future-dated frame is refused and cannot evict honest custody`() =
        runTest {
            val dao = db.forwardDao()
            val digest = StoreDigest()
            val repo = repo(db, digest) // per-sender quota = 5

            (1..5).forEach {
                repo.store(dm("h%d".format(it), sender = "peer", sentAt = 1_000L + it), ForwardStore.ORIGIN_RELAY, now = 1_000L)
            }

            // Future-dated far beyond the skew window: refused outright, so it never displaces the honest frames
            // (the custody-wipe attack — a fresh-timestamped frame would otherwise win every oldest-by-sentAt evict).
            val future = 1_000L + Protocol.MAX_FUTURE_SKEW_MS + 60_000L
            assertFalse(repo.store(dm("evil", sender = "peer", sentAt = future), ForwardStore.ORIGIN_RELAY, now = 1_000L))
            assertFalse(dao.exists("evil"))
            assertEquals(listOf("h1", "h2", "h3", "h4", "h5"), dao.allIds().sorted())

            // A frame within the skew window is still accepted (honest clock skew tolerated).
            assertTrue(
                repo.store(
                    dm("skewed", sender = "other", sentAt = 1_000L + Protocol.MAX_FUTURE_SKEW_MS - 1),
                    ForwardStore.ORIGIN_RELAY,
                    now = 1_000L,
                ),
            )
        }

    @Test
    fun `the TTL sweep is digest-neutral — the lazy fold already dropped the lapsed id (work item 8)`() =
        runTest {
            var now = 0L
            val dao = db.forwardDao()
            val digest = StoreDigest { now }
            val repo = ForwardRepository(dao, digest, db, ttlMs = 1_000L)

            repo.store(dm("dies", sender = "peer", sentAt = 0L), ForwardStore.ORIGIN_RELAY, now = 0L) // expires at 1_000
            repo.store(dm("lives", sender = "peer", sentAt = 5_000L), ForwardStore.ORIGIN_RELAY, now = 0L) // expires at 6_000

            now = 2_000L
            val atBoundary = digest.current() // the read folds "dies" out — no sweep has run yet
            assertEquals(StoreDigest.fingerprint(listOf("lives")), atBoundary)
            assertTrue("the lapsed row is still physical residue awaiting GC", dao.exists("dies"))

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
                database: KnitDatabase,
                digest: StoreDigest,
            ) = ForwardRepository(database.forwardDao(), digest, database, ttlMs = 10_000L, broadcastTtlMs = 1_000L, maxPerSender = 3)

            val frames =
                listOf(
                    dm("dm_old", sender = "S", sentAt = 100L), // expires 10_100 — live throughout
                    dm("dm_mid", sender = "S", sentAt = 200L), // expires 10_200 — live throughout
                    chat("bc_new", "S", 5_000L, null, ChatContent(body = "x")), // broadcast: expires 6_000 — lapses
                )

            var nowA = 5_500L
            val digestA = StoreDigest { nowA }
            val repoA = boundedRepo(db, digestA)
            val dbB = newDb()
            var nowB = 5_500L
            val digestB = StoreDigest { nowB }
            val repoB = boundedRepo(dbB, digestB)
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
                db.forwardDao().liveIds(7_000L).sorted(),
                dbB.forwardDao().liveIds(7_000L).sorted(),
            )
            assertEquals(listOf("dm_mid", "dm_new", "dm_old"), dbB.forwardDao().liveIds(7_000L).sorted()) // nobody evicted a live row
            assertEquals("live-only digests converge across the sweep phase", digestA.current(), digestB.current())
        }

    @Test
    fun `a late joiner sweeps a frame at its frame-global expiry, not TTL-from-arrival`() =
        runTest {
            val ttl = 1_000L
            val dao = db.forwardDao()
            val digest = StoreDigest()
            val repo = ForwardRepository(dao, digest, db, ttlMs = ttl)

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

    @Test
    fun `concurrent store and sweep keep the digest consistent with the live set`() =
        runBlocking {
            // store() and sweepExpired() fire from different coroutines in production (inbound relay + own sends +
            // the 10-min prune loop). Both mutate the shared in-memory digest alongside the table; without the repo
            // mutex a sweep's wholesale rebuild could clobber a store's incremental add, leaving a live row absent
            // from the digest (breaking StoreDigest's `current() == liveFingerprint`). A Room transaction can't
            // enroll the digest, so only the mutex closes this. runTest's single thread can't exercise it — use real
            // threads and assert the invariant holds once quiesced. Fixed clock at 0 so current()'s expiry fold and
            // the liveIds(0) query below agree on `now` (the frames' 24h expiry is otherwise past the wall clock).
            val digest = StoreDigest { 0L }
            val repo = repo(db, digest) // per-sender quota = 5, so eviction (→ digest rebuild) churns constantly
            val n = 300
            val storer =
                launch(Dispatchers.Default) {
                    repeat(n) { i ->
                        repo.store(dm("f%04d".format(i), sender = "peer", sentAt = (i + 1).toLong()), ForwardStore.ORIGIN_RELAY, now = 0L)
                    }
                }
            val sweeper =
                launch(Dispatchers.Default) {
                    repeat(n) { repo.sweepExpired(now = 0L) } // nothing is expired; this exercises rebuildDigest under contention
                }
            storer.join()
            sweeper.join()

            assertEquals(StoreDigest.fingerprint(db.forwardDao().liveIds(now = 0L)), digest.current())
        }
}
