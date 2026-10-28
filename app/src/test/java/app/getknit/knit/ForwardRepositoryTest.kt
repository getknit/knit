package app.getknit.knit

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
 * every origin, so all nodes keep the same set. The global cap evicts from the largest sender bucket instead (ADR
 * 2026-10.7mqm), so a flood of free identities cannot displace honest custody, and a group frame is carried only under
 * its own roster.
 *
 * The store/remove/sweep mutations now also run their DB writes in a transaction under a repo mutex so the in-memory
 * digest stays in lockstep with the committed rows — see `concurrent store and sweep keep the digest consistent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one store's whole contract over one real database: it grows by a test a bound, by design
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

    /**
     * A frame whose `signed` is exactly [signedBytes] long: [envelope] built with a chat body of "x"s, the body's
     * length searched upward, since CBOR's length prefixes grow at 24, 256 and 65,536 and no fixed offset holds.
     */
    private fun padded(
        signedBytes: Int,
        envelope: (ChatContent) -> RelayEnvelope,
    ): CarriedFrame {
        for (length in 0..signedBytes) {
            val env = envelope(ChatContent(body = "x".repeat(length)))
            val signed = WireCodec.encodeEnvelope(env)
            if (signed.size == signedBytes) return CarriedFrame(env, sig = ByteArray(0), signed = signed)
            check(signed.size < signedBytes) { "no body length makes $signedBytes signed bytes" }
        }
        error("no body length makes $signedBytes signed bytes")
    }

    /** [dm], padded so its `signed` is exactly [signedBytes] long. */
    private fun paddedDm(
        id: String,
        sender: String,
        sentAt: Long,
        signedBytes: Int,
    ): CarriedFrame =
        padded(signedBytes) { content ->
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                recipientId = "peer",
                payload = WireCodec.encodePayload(content),
            )
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
    fun `a carried group frame's attachment carries the roster the frame itself names`() =
        runTest {
            // ADR 2026-10.js2p: a member whose seed is late holds the frame and no group row; the frame's own roster
            // is what tells the database re-arm the attachment is ours.
            val repo = repo(db, StoreDigest())
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "g1",
                    senderId = "alice",
                    sentAt = 1L,
                    group =
                        GroupInfo(
                            id = Conversations.groupIdFor(listOf("alice", "bob")),
                            members = listOf("alice", "bob"),
                            createdBy = "alice",
                        ),
                    payload = WireCodec.encodePayload(ChatContent(attachmentHash = "CT")),
                )
            repo.store(CarriedFrame(env, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env)), ForwardStore.ORIGIN_RELAY, now = 0L)
            repo.store(chat("d1", "peer", 2L, "bob", ChatContent(attachmentHash = "DM")), ForwardStore.ORIGIN_RELAY, now = 0L)

            val byHash = repo.carriedAttachments(0L).associateBy { it.hash }
            assertEquals(setOf("alice", "bob"), byHash.getValue("CT").roster)
            assertNull("a DM names no roster", byHash.getValue("DM").roster)
        }

    @Test
    fun `a carried room post's attachment carries the size the post declares, never an encrypted frame's shell`() =
        runTest {
            // ADR 2026-10.hmfe: what says a room post is large before its row exists. A DM's shell is never read.
            val repo = repo(db, StoreDigest())
            val clip = ChatContent(attachmentHash = "CLIP", attachmentMime = "video/mp4", attachmentSize = 3_000_000L)
            repo.store(chat("r1", "alice", 1L, null, clip), ForwardStore.ORIGIN_RELAY, now = 0L)
            repo.store(chat("r2", "alice", 2L, null, ChatContent(attachmentHash = "PIC")), ForwardStore.ORIGIN_RELAY, now = 0L)
            repo.store(
                chat("d1", "alice", 3L, "bob", ChatContent(attachmentHash = "DM", attachmentSize = 3_000_000L)),
                ForwardStore.ORIGIN_RELAY,
                now = 0L,
            )

            val byHash = repo.carriedAttachments(0L).associateBy { it.hash }
            assertEquals(3_000_000L, byHash.getValue("CLIP").declaredSize)
            assertNull("a post that declares nothing", byHash.getValue("PIC").declaredSize)
            assertNull("a DM's bytes are never loaded", byHash.getValue("DM").declaredSize)
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
    fun `a room post's voice note or file is custodied without its hash so the re-arm never pulls it`() =
        runTest {
            // ADR 2026-10.79j9: no row draws a room post's voice note or file, so the carrier column leaves it out and
            // the database re-arm (attachmentsNeedingFetch) never wants it. The frame itself is custodied as it came.
            val dao = db.forwardDao()
            val digest = StoreDigest()
            val repo = repo(db, digest)

            repo.store(
                chat("r1", "peer", 1L, null, ChatContent(body = "listen", attachmentHash = "NOTE", attachmentMime = "audio/aac")),
                ForwardStore.ORIGIN_RELAY,
                now = 0L,
            )
            repo.store(
                chat("r2", "peer", 2L, null, ChatContent(attachmentHash = "DOC", attachmentMime = "application/pdf")),
                ForwardStore.ORIGIN_RELAY,
                now = 0L,
            )
            repo.store(
                chat("r3", "peer", 3L, null, ChatContent(attachmentHash = "PIC", attachmentMime = "image/jpeg")),
                ForwardStore.ORIGIN_RELAY,
                now = 0L,
            )

            assertTrue(dao.exists("r1") && dao.exists("r2"))
            val byId = dao.allRows().associateBy { it.id }
            assertEquals(null, byId["r1"]!!.attachmentHash)
            assertEquals(null, byId["r2"]!!.attachmentHash)
            assertEquals(mapOf("PIC" to "peer"), repo.attachmentsNeedingFetch(now = 0L))
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
    fun `remove drops the row and its digest entry together`() =
        runTest {
            // The digest is what the cue plane compares, so a row deleted from the table but left in the fold
            // — or the reverse — is exactly the divergence the mutex exists to close. After a remove the store
            // must read as a store that never held the frame, from either side.
            val digest = StoreDigest()
            val repo = repo(db, digest)
            repo.store(dm("keep", sender = "peer", sentAt = 1L), ForwardStore.ORIGIN_RELAY, now = 0L)
            repo.store(dm("gone", sender = "peer", sentAt = 2L), ForwardStore.ORIGIN_RELAY, now = 0L)

            repo.remove("gone")

            assertFalse(repo.has("gone"))
            assertEquals(listOf("keep"), repo.liveIds(now = 0L))
            assertEquals(StoreDigest.fingerprint(listOf("keep")), digest.version.value)
            repo.remove("never-held") // idempotent: a re-serve's cleanup of an id nobody stored
            assertEquals(StoreDigest.fingerprint(listOf("keep")), digest.version.value)
        }

    @Test
    fun `frame returns the carried bytes by id while live and nothing once expired`() =
        runTest {
            val repo = repo(db, StoreDigest())
            val carried = dm("f1", sender = "peer", sentAt = 1_000L)
            repo.store(carried, ForwardStore.ORIGIN_RELAY, now = 1_000L)

            val back = checkNotNull(repo.frame("f1", now = 1_000L))
            assertEquals("f1", back.envelope.id)
            assertTrue(
                "the signed bytes are served verbatim — a re-encode would break the signature",
                carried.signed.contentEquals(back.signed),
            )
            assertEquals(listOf("f1"), repo.liveFramesToFromOthers("peer", me = "me", now = 1_000L, limit = 10).map { it.envelope.id })

            val expired = 1_000L + ForwardRepository.DEFAULT_TTL_MS + 1
            assertNull("past the frame-global expiry it is residue, not a frame", repo.frame("f1", now = expired))
            assertEquals(
                emptyList<String>(),
                repo.liveFramesToFromOthers("peer", me = "me", now = expired, limit = 10).map { it.envelope.id },
            )
            assertNull(repo.frame("unknown", now = 1_000L))
        }

    @Test
    fun `the re-offer reads pick our frames by id and everyone else's by recipient`() =
        runTest {
            val repo = repo(db, StoreDigest())
            repo.store(dm("mine-old", sender = "me", sentAt = 1_000L), ForwardStore.ORIGIN_SELF, now = 1_000L)
            repo.store(dm("mine-new", sender = "me", sentAt = 3_000L), ForwardStore.ORIGIN_SELF, now = 3_000L)
            repo.store(dm("relayed", sender = "carol", sentAt = 2_000L), ForwardStore.ORIGIN_RELAY, now = 2_000L)

            assertEquals(
                listOf("mine-new", "mine-old"),
                repo.liveFramesByIds(listOf("mine-old", "mine-new", "unknown"), now = 3_000L).map { it.envelope.id },
            )
            assertEquals(emptyList<String>(), repo.liveFramesByIds(emptyList(), now = 3_000L).map { it.envelope.id })
            assertEquals(
                listOf("relayed"),
                repo.liveFramesToFromOthers("peer", me = "me", now = 3_000L, limit = 10).map { it.envelope.id },
            )
            val expired = 3_000L + ForwardRepository.DEFAULT_TTL_MS + 1
            assertEquals(emptyList<String>(), repo.liveFramesByIds(listOf("mine-new"), now = expired).map { it.envelope.id })
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

    /** A frame of [type] from [sender] at [sentAt]: a DM to "peer" when [recipientId] is set, a group's with [group], else a room's. */
    private fun frame(
        id: String,
        sender: String,
        sentAt: Long,
        recipientId: String? = null,
        group: GroupInfo? = null,
        type: String = FrameType.CHAT,
    ): CarriedFrame {
        val env =
            RelayEnvelope(
                type = type,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipientId,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(body = "")),
            )
        return CarriedFrame(env, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /** A group's roster as a current build writes it: the members left, and who departed. */
    private fun roster(
        members: List<String>,
        departed: List<String> = emptyList(),
    ) = GroupInfo(
        id = Conversations.groupIdFor(members + departed),
        members = members,
        createdBy = (members + departed).first(),
        departed = departed.ifEmpty { null },
    )

    @Test
    fun `a Sybil flood stamped at the edge of the future window evicts none of the honest frames`() =
        runTest {
            // The malicious-fork review's C2 at a fiftieth of the field numbers (cap 1,000, 200 per sender): five
            // identities fill the cap with frames stamped just inside the future window, the newest of all. The old
            // global rule took the globally oldest frame, so every honest frame went, on every node alike.
            val now = 1_000_000L
            val repo = ForwardRepository(db.forwardDao(), StoreDigest { now }, db, maxRows = 50, maxPerSender = 10)
            val honest = (1..4).flatMap { s -> (1..3).map { i -> dm("h$s-$i", sender = "honest$s", sentAt = now - 60_000L * (5 - i)) } }
            honest.forEach { assertTrue(repo.store(it, ForwardStore.ORIGIN_RELAY, now)) }
            val edge = now + Protocol.MAX_FUTURE_SKEW_MS - 1
            (1..5).forEach { s ->
                (1..10).forEach { i -> repo.store(dm("s$s-%02d".format(i), "sybil$s", edge - i), ForwardStore.ORIGIN_RELAY, now) }
            }

            val held = repo.liveIds(now).toSet()
            assertTrue(
                "every honest frame is kept: missing ${honest.map { it.envelope.id } - held}",
                held.containsAll(honest.map { it.envelope.id }),
            )
            assertEquals(50, held.size)
            val perSybil = (1..5).map { s -> held.count { it.startsWith("s$s-") } }
            assertEquals("the flood's own buckets paid for the cap, evenly: $perSybil", 38, perSybil.sum())
            assertTrue("$perSybil", perSybil.all { it in 7..8 })
        }

    @Test
    fun `an honest frame stored during the flood survives the write that stores it`() =
        runTest {
            // The five minutes after the flood: an honest frame is the oldest in the store, and the old rule evicted it
            // in the same write, after the router had marked it seen.
            val now = 1_000_000L
            val repo = ForwardRepository(db.forwardDao(), StoreDigest { now }, db, maxRows = 50, maxPerSender = 10)
            val edge = now + Protocol.MAX_FUTURE_SKEW_MS - 1
            (1..5).forEach { s ->
                (1..10).forEach { i -> repo.store(dm("s$s-%02d".format(i), "sybil$s", edge - i), ForwardStore.ORIGIN_RELAY, now) }
            }

            assertTrue("store says it holds the frame", repo.store(dm("honest", "alice", now), ForwardStore.ORIGIN_RELAY, now))
            assertTrue(repo.has("honest"))
            assertEquals(50, repo.liveIds(now).size)
        }

    @Test
    fun `store answers false for a frame its own write evicted, so nothing pulls a blob for it`() =
        runTest {
            val repo = repo(db, StoreDigest()) // per-sender quota = 5
            (1..5).forEach { assertTrue(repo.store(dm("f$it", sender = "peer", sentAt = 10L + it), ForwardStore.ORIGIN_RELAY, now = 0L)) }

            assertFalse(
                "older than the newest five: kept nowhere",
                repo.store(dm("late", "peer", sentAt = 1L), ForwardStore.ORIGIN_RELAY, now = 0L),
            )
            assertFalse(repo.has("late"))
            assertTrue(
                "a newer one displaces the oldest and is held",
                repo.store(dm("new", "peer", sentAt = 99L), ForwardStore.ORIGIN_RELAY, now = 0L),
            )
            assertTrue("already held reads as held", repo.store(dm("new", "peer", sentAt = 99L), ForwardStore.ORIGIN_RELAY, now = 0L))
        }

    @Test
    fun `the room quota keeps its newest posts whoever wrote them`() =
        runTest {
            // Only the global cap went fair (ADR 2026-10.7mqm). Shipped builds and knit-ios keep a room's newest-N, and a
            // fair room trim would split an old node from a new one for good at a busy room's quota.
            val repo = ForwardRepository(db.forwardDao(), StoreDigest(), db, maxBroadcast = 3)
            listOf(frame("a1", "A", 1L), frame("b1", "B", 2L), frame("b2", "B", 3L), frame("c1", "C", 4L))
                .forEach { repo.store(it, ForwardStore.ORIGIN_RELAY, now = 0L) }
            assertEquals("A's post is the oldest, though B holds two", listOf("b1", "b2", "c1"), repo.liveIds(0L).sorted())
        }

    @Test
    fun `the group quota keeps its newest frames whoever wrote them`() =
        runTest {
            val repo = ForwardRepository(db.forwardDao(), StoreDigest(), db, maxPerGroup = 3)
            val g = roster(listOf("alice", "bob", "carol"))
            listOf(
                frame("a1", "alice", 1L, group = g),
                frame("b1", "bob", 2L, group = g),
                frame("b2", "bob", 3L, group = g),
                frame("c1", "carol", 4L, group = g),
            ).forEach { repo.store(it, ForwardStore.ORIGIN_RELAY, now = 0L) }
            assertEquals(listOf("b1", "b2", "c1"), repo.liveIds(0L).sorted())
        }

    @Test
    fun `with one quota binding, any two arrival orders keep the same set`() =
        runTest {
            // The global cap alone binds (per-sender quotas far above it), then the per-sender quota alone. Each set of
            // frames arrives in two seeded orders on two nodes; the kept sets must be equal, which is what lets two
            // nodes holding the same frames agree on a digest.
            for (seed in 1..8) {
                val random = Random(seed)
                val frames = (1..40).map { dm("f%02d".format(it), sender = "s${random.nextInt(6)}", sentAt = random.nextLong(1L, 30L)) }
                for ((maxRows, perSender) in listOf(15 to 1_000, 1_000 to 3)) {
                    val kept =
                        listOf(frames.shuffled(Random(seed * 31)), frames.shuffled(Random(seed * 37))).map { order ->
                            val database = newDb()
                            val store =
                                ForwardRepository(
                                    database.forwardDao(),
                                    StoreDigest(),
                                    database,
                                    maxRows = maxRows,
                                    maxPerSender = perSender,
                                )
                            order.forEach { store.store(it, ForwardStore.ORIGIN_RELAY, now = 0L) }
                            store.liveIds(0L).toSet()
                        }
                    assertEquals("seed $seed, cap $maxRows, per sender $perSender", kept[0], kept[1])
                }
            }
        }

    @Test
    fun `stores whose quotas bind together converge by exchanging what each lacks`() =
        runTest {
            // The custody exchange as the mesh runs it, between two and three stores at once: each round every store
            // serves each other one the live frames it lacks, newest-received first, and a receiver drops an id it saw
            // inside the router's seen window (served again only once the window lapses). The room, a sender, and the
            // global cap bind at once, so the arrival order decides what each store holds first. The bound is three
            // windows; a simulation of the rule settled within about two, and never in a two-cycle.
            val window = SeenSet.DEFAULT_TTL_MS
            val step = 60_000L
            for (seed in 1..6) {
                for (count in listOf(2, 3)) {
                    val random = Random(seed * 101 + count)
                    var now = 1_000_000L
                    val g = roster(listOf("s0", "s1", "s2"))
                    val frames =
                        (1..36).map { i ->
                            val sender = "s${random.nextInt(5)}"
                            val sentAt = now - random.nextLong(1L, 600_000L)
                            when (random.nextInt(3)) {
                                0 -> frame("r%02d".format(i), sender, sentAt)
                                1 -> frame("g%02d".format(i), g.members[random.nextInt(3)], sentAt, group = g)
                                else -> frame("d%02d".format(i), sender, sentAt, recipientId = "peer")
                            }
                        }
                    val stores =
                        (0 until count).map {
                            val database = newDb()
                            ForwardRepository(
                                database.forwardDao(),
                                StoreDigest { now },
                                database,
                                maxRows = 14,
                                maxPerSender = 8,
                                maxPerGroup = 6,
                                maxBroadcast = 5,
                            )
                        }
                    val seen = stores.map { SeenSet(ttlMillis = window, clock = { now }) }
                    stores.forEachIndexed { n, store ->
                        frames.filter { random.nextInt(10) < 7 }.shuffled(random).forEach {
                            seen[n].add(it.envelope.id)
                            store.store(it, ForwardStore.ORIGIN_RELAY, now)
                        }
                    }
                    val start = now
                    while (stores.map { it.liveIds(now).toSet() }.distinct().size > 1) {
                        assertTrue(
                            "seed $seed, $count stores: not converged after ${(now - start) / 60_000} min",
                            now - start <= 3 * window,
                        )
                        now += step
                        // Every store's offer is read before any is taken: the exchanges of one round cross on the air.
                        val offers = stores.map { it.liveFrames(now) }
                        val heldBefore = stores.map { it.liveIds(now).toSet() }
                        stores.forEachIndexed { to, store ->
                            offers.forEachIndexed { from, offer ->
                                if (from == to) return@forEachIndexed
                                offer.filter { it.envelope.id !in heldBefore[to] }.forEach {
                                    if (seen[to].add(it.envelope.id)) store.store(it, ForwardStore.ORIGIN_RELAY, now)
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `custody refuses a group frame whose roster is not its own, and counts it`() =
        runTest {
            val metrics = MeshMetrics()
            val repo = ForwardRepository(db.forwardDao(), StoreDigest(), db, metrics = metrics)
            val g = roster(listOf("alice", "bob", "carol"))
            assertTrue(repo.store(frame("ok", "bob", 1L, group = g), ForwardStore.ORIGIN_RELAY, now = 0L))
            assertFalse(
                "a stranger naming the group",
                repo.store(frame("stranger", "mallory", 2L, group = g), ForwardStore.ORIGIN_RELAY, now = 0L),
            )
            val smuggled = g.copy(members = g.members + "mallory")
            assertFalse(
                "a roster with a member smuggled in",
                repo.store(frame("smuggled", "mallory", 3L, group = smuggled), ForwardStore.ORIGIN_RELAY, now = 0L),
            )
            assertFalse(
                "our own frame is held to it too",
                repo.store(frame("own", "mallory", 4L, group = smuggled), ForwardStore.ORIGIN_SELF, now = 0L),
            )
            assertEquals(listOf("ok"), repo.liveIds(0L))
            assertEquals(3L, metrics.groups().groupCustodyRefused)
        }

    @Test
    fun `custody keeps a frame of exactly the byte cap and refuses one byte over, even from a full store`() =
        runTest {
            // #227: the cap is on `signed` (the sig is not counted), and exactly the cap is kept. The refusal is a
            // function of the frame alone: nothing is written, nothing is evicted to make room, the digest is unmoved.
            val now = 1_000_000L
            val metrics = MeshMetrics()
            val digest = StoreDigest { now }
            val dao = db.forwardDao()
            val repo = ForwardRepository(dao, digest, db, maxRows = 3, maxSignedBytes = CAP, metrics = metrics)
            assertTrue(repo.store(dm("a", sender = "alice", sentAt = now - 3), ForwardStore.ORIGIN_RELAY, now))
            assertTrue(repo.store(dm("b", sender = "bob", sentAt = now - 2), ForwardStore.ORIGIN_RELAY, now))
            val atCap = paddedDm("cap", sender = "carol", sentAt = now - 1, signedBytes = CAP)
            assertEquals(CAP, atCap.signed.size)
            assertTrue("exactly the cap is kept", repo.store(atCap, ForwardStore.ORIGIN_RELAY, now))
            val full = listOf("a", "b", "cap")
            assertEquals(full, dao.allIds().sorted())
            val version = digest.version.value

            val over = paddedDm("over", sender = "dave", sentAt = now, signedBytes = CAP + 1)
            assertFalse("one byte over is refused", repo.store(over, ForwardStore.ORIGIN_RELAY, now))
            assertEquals("nothing written, nothing evicted", full, dao.allIds().sorted())
            assertEquals("a refused frame must not touch the digest", version, digest.version.value)
            assertEquals(1L, metrics.frames().custodyOversizeRefused)
        }

    @Test
    fun `our own oversize frame is refused like a relayed one`() =
        runTest {
            val now = 1_000_000L
            val metrics = MeshMetrics()
            val digest = StoreDigest { now }
            val repo = ForwardRepository(db.forwardDao(), digest, db, maxSignedBytes = CAP, metrics = metrics)
            assertFalse(repo.store(paddedDm("mine", sender = "me", sentAt = now, signedBytes = CAP + 1), ForwardStore.ORIGIN_SELF, now))
            assertEquals(0, db.forwardDao().allIds().size)
            assertEquals(0L, digest.version.value)
            assertEquals(1L, metrics.frames().custodyOversizeRefused)
        }

    @Test
    fun `an oversize frame naming a foreign roster counts as oversize, not as a group refusal`() =
        runTest {
            // The byte cap is checked first (#227): every refusal is a function of the frame, so the order only
            // decides which reason is counted.
            val now = 1_000_000L
            val metrics = MeshMetrics()
            val repo = ForwardRepository(db.forwardDao(), StoreDigest { now }, db, maxSignedBytes = CAP, metrics = metrics)
            val g = roster(listOf("alice", "bob", "carol"))
            val foreign = g.copy(members = g.members + "mallory")
            val frame =
                padded(CAP + 1) { content ->
                    RelayEnvelope(
                        type = FrameType.CHAT,
                        id = "both",
                        senderId = "mallory",
                        sentAt = now,
                        group = foreign,
                        payload = WireCodec.encodePayload(content),
                    )
                }
            assertFalse(repo.store(frame, ForwardStore.ORIGIN_RELAY, now))
            assertEquals(1L, metrics.frames().custodyOversizeRefused)
            assertEquals(0L, metrics.groups().groupCustodyRefused)
        }

    @Test
    fun `the sweep deletes a row stored before the byte cap and keeps the digest on the live fold`() =
        runTest {
            // A build without the cap stored the long row; the upgraded sweep reclaims it, and since that moves the live
            // set the rebuild must fold what is left.
            val now = 1_000_000L
            val digest = StoreDigest { now }
            val dao = db.forwardDao()
            val repo = ForwardRepository(dao, digest, db, maxSignedBytes = CAP)
            assertTrue(repo.store(paddedDm("cap", sender = "carol", sentAt = now, signedBytes = CAP), ForwardStore.ORIGIN_RELAY, now))
            val old = paddedDm("old", sender = "dave", sentAt = now, signedBytes = CAP + 1)
            dao.insert(
                ForwardEntity(
                    id = "old",
                    recipientId = "peer",
                    groupId = null,
                    senderId = "dave",
                    type = FrameType.CHAT,
                    origin = ForwardStore.ORIGIN_RELAY,
                    signed = old.signed,
                    sig = ByteArray(0),
                    sentAt = now,
                    receivedAt = now,
                    expiresAt = now + ForwardRepository.DEFAULT_TTL_MS,
                ),
            )
            // A build without the cap folded it into its digest when it stored it.
            digest.add("old", now + ForwardRepository.DEFAULT_TTL_MS, now)
            assertEquals(listOf("cap", "old"), repo.liveIds(now).sorted())
            assertNotEquals(StoreDigest.fingerprint(listOf("cap")), digest.version.value)

            assertEquals("the oversize row counts among what the sweep removed", 1, repo.sweepExpired(now))
            assertEquals(listOf("cap"), repo.liveIds(now))
            assertEquals("the sweep folds the old row out", StoreDigest.fingerprint(listOf("cap")), digest.version.value)
            assertEquals("once the old rows are gone it finds nothing", 0, repo.sweepExpired(now))
        }

    @Test
    fun `the byte cap is the value every port keeps`() {
        // Convergence-critical (ADR 006, #227): knit-ios refuses the same frames, or two stores never agree.
        assertEquals(61_440, ForwardRepository.DEFAULT_MAX_SIGNED_BYTES)
    }

    private companion object {
        const val CAP = 1_024
    }
}
