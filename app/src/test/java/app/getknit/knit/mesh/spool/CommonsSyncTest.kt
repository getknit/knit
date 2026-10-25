package app.getknit.knit.mesh.spool

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.frameIdOf
import app.getknit.knit.mesh.sha256Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commons (spec §7.4) end to end, members over an in-process spool that runs one: profiles ride the
 * ordinary door and pin, posts ride the commons door and never custody, the room is bound to its relay,
 * and the digest converges although nothing a member pulls is ever held. The convergence harness for
 * the feature — nothing here crosses the radio mesh, so `mesh/lab/` has no scenario to add.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // the commons' one convergence harness: posts, reactions, attachments and the stale probe over one rig
class CommonsSyncTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbe"
    private val carol = "ccccccccccccccccccccccccce"

    /** A frame id the spool door admits ([FrameId.admits]), named by a label. */
    private fun fid(label: String) = frameIdOf(label)

    private val secret = ByteArray(32) { (it + 5).toByte() }
    private val otherSecret = ByteArray(32) { (it + 77).toByte() }
    private val now = 1_000L
    private val url = "ws://spool.test/spool/v1"
    private val otherUrl = "ws://other.test/spool/v1"
    private val scopeId = ScopeCrypto.commonsScopeId(secret)
    private val scopeHex = hex(scopeId)
    private val conversationId = Conversations.commonsIdFor(scopeHex)
    private val advertised = SpoolCommonsInfo(name = "Home", maxFrames = 5, ttlMs = 24 * 60 * 60_000L, maxBlob = 65_536)

    // A second room on the same relay (§7.4, `rooms`): its own invite, its own pinned bounds, and it takes attachments.
    private val teamSecret = ByteArray(32) { (it + 140).toByte() }
    private val teamId = ScopeCrypto.commonsScopeId(teamSecret)
    private val teamHex = hex(teamId)
    private val teamRoom = Conversations.commonsIdFor(teamHex)
    private val teamAdvert = SpoolCommonsInfo(name = null, maxFrames = 7, ttlMs = 12 * 60 * 60_000L, maxBlob = 65_536, attach = true)
    private val bothRooms get() = mutableListOf(CommonsRoots(conversationId, url, secret), CommonsRoots(teamRoom, url, teamSecret))

    /** A spool that lists its rooms by tag (S-7.4-1): by default the room above and the team room beside it. */
    private fun roomsSpool(rooms: List<Pair<ByteArray, SpoolCommonsInfo>> = listOf(scopeId to advertised, teamId to teamAdvert)) =
        FakeSpool(rooms = rooms)

    /** The same room on a relay that takes its attachments (S-7.4-5). */
    private fun SpoolCommonsInfo.attaching() = SpoolCommonsInfo(name, maxFrames, ttlMs, maxBlob, attach = true)

    private fun spool(
        powBits: Int = 0,
        commons: Pair<ByteArray, SpoolCommonsInfo>? = scopeId to advertised,
        echoesDeclaredBounds: Boolean = false,
    ) = FakeSpool(powBits = powBits, commons = commons, echoesDeclaredBounds = echoesDeclaredBounds)

    /** The rooms [this] member's worker at [url] reports stale. */
    private fun Member.staleRooms(): Set<String> = sync.status().single { it.url == url }.staleCommons

    /** The in-memory [CommonsStore]: one joined room, its outbox, its members, and its received attachments. */
    private class FakeCommons(
        // Read live, so a test can leave a room mid-run.
        private val rooms: List<CommonsRoots>,
        private val held: suspend (String) -> Boolean,
    ) : CommonsStore {
        // False lists a ref whose bytes are already held, as a read that raced their landing would.
        var filtersHeld = true

        val outbox = LinkedHashMap<String, MutableList<CarriedFrame>>()
        val members = mutableListOf<Pair<String, String>>()

        // What delivered posts named, the rows `MessageDao.commonsAttachmentsMissing` reads in the app.
        val received = mutableListOf<ScopeAttachments.Ref>()

        override suspend fun attachmentRefs(
            conversationId: String,
            selfId: String,
            since: Long,
            skip: Set<String>,
            limit: Int,
        ): List<ScopeAttachments.Ref> =
            received.filter { it.sentAt >= since && it.aHash !in skip && (!filtersHeld || !held(it.aHash)) }.take(limit)

        override suspend fun roots(): List<CommonsRoots> = rooms.toList()

        override suspend fun find(conversationId: String): CommonsRoom? =
            rooms.firstOrNull { it.conversationId == conversationId }?.let { CommonsRoom(it.conversationId, it.spoolUrl, it.secret, null) }

        override suspend fun frames(conversationId: String): List<CarriedFrame> = outbox[conversationId].orEmpty()

        override suspend fun post(
            row: MessageEntity,
            sig: ByteArray,
            signed: ByteArray,
        ) = error("the plane never writes the outbox")

        override suspend fun postReaction(
            conversationId: String,
            messageId: String,
            frameId: String,
            sentAt: Long,
            sig: ByteArray,
            signed: ByteArray,
        ) = error("the plane never writes the outbox")

        override suspend fun recordMember(
            conversationId: String,
            nodeId: String,
            now: Long,
        ) {
            members.add(conversationId to nodeId)
        }

        override suspend fun allMembers(): List<CommonsMember> = members.map { CommonsMember(it.first, it.second, 0L) }

        override suspend fun sweepOutbox(before: Long) = Unit
    }

    /** The local content-addressed store, in memory. */
    private class FakeBlobs(
        vararg initial: Pair<String, ByteArray>,
    ) : ScopeBlobs {
        val stored = LinkedHashMap<String, ByteArray>().apply { putAll(initial) }

        override suspend fun has(aHash: String): Boolean = aHash in stored

        override suspend fun bytes(aHash: String): ByteArray? = stored[aHash]

        override suspend fun save(
            aHash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            stored[aHash] = bytes
        }
    }

    /** Bytes plus their content address — what a post's `attachmentHash` names. */
    private fun image(size: Int = 100_000): Pair<String, ByteArray> {
        val bytes = ByteArray(size) { ((it * 13) and 0xFF).toByte() }
        return sha256Hex(bytes) to bytes
    }

    private class Member(
        val custody: FakeCustody,
        val commons: FakeCommons,
        val blobs: FakeBlobs,
        val sync: ScopeSync,
        val metrics: MeshMetrics,
        val delivered: MutableList<RelayEnvelope>,
        val posts: MutableList<Pair<RelayEnvelope, ChatContent>>,
        val reactions: MutableList<Pair<RelayEnvelope, ReactionContent>>,
        val pinned: MutableSet<String>,
        val tombstoneReports: MutableList<Unit>,
    )

    private fun member(
        spool: FakeSpool,
        self: String,
        rooms: List<CommonsRoots> = listOf(CommonsRoots(conversationId, url, secret)),
        urls: List<String> = listOf(url),
        dialer: SpoolDialer = spool,
        custody: FakeCustody = FakeCustody(),
        // Who this member has pinned. The carry gate accepts a profile from anyone (self-certifying) and
        // anything else only from a pinned sender — the shape of `InboundPipeline.canCarry` — and a
        // delivered profile pins its author, the shape of `handleProfile`.
        pinned: MutableSet<String> = mutableSetOf(self),
        refuse: Set<String> = emptySet(),
        blobs: FakeBlobs = FakeBlobs(),
    ): Member {
        val metrics = MeshMetrics()
        val delivered = mutableListOf<RelayEnvelope>()
        val posts = mutableListOf<Pair<RelayEnvelope, ChatContent>>()
        val reactions = mutableListOf<Pair<RelayEnvelope, ReactionContent>>()
        val commons = FakeCommons(rooms, held = { blobs.has(it) })
        val reports = mutableListOf<Unit>()
        val sync =
            ScopeSync(
                registry = ScopeRegistry(selfId = { self }, roots = { emptyList() }, commons = { commons.roots() }),
                dialer = dialer,
                store = custody,
                selfId = { self },
                urls = { urls },
                canCarry = { _, env -> env.id !in refuse && (env.type == FrameType.PROFILE || env.senderId in pinned) },
                deliver = { wire, env, _ ->
                    delivered.add(env)
                    if (env.type == FrameType.PROFILE) pinned.add(env.senderId)
                    custody.store(CarriedFrame(env, wire.sig, wire.signed), ForwardStore.ORIGIN_RELAY, now)
                },
                commons = commons,
                blobs = blobs,
                hasKey = { it in pinned },
                deliverCommons = { env, post, _, _ ->
                    post.chat?.let { chat ->
                        posts.add(env to chat)
                        chat.attachmentHash?.let { commons.received.add(ScopeAttachments.Ref(it, chat.attachmentMime, env.sentAt)) }
                    }
                    post.reaction?.let { reactions.add(env to it) }
                },
                onCommonsMember = { id, node -> commons.recordMember(id, node, now) },
                onOwnProfileTombstoned = { reports.add(Unit) },
                metrics = metrics,
                clock = { now },
                jitter = { 0L },
            )
        return Member(custody, commons, blobs, sync, metrics, delivered, posts, reactions, pinned, reports)
    }

    private suspend fun Member.seedOwnProfile(
        self: String,
        id: String = FrameId.profileId(self, now),
    ) {
        custody.store(profileFrame(id, self, sentAt = now), ForwardStore.ORIGIN_SELF, now)
    }

    private fun Member.post(
        frame: CarriedFrame,
        room: String = conversationId,
    ) {
        commons.outbox.getOrPut(room) { mutableListOf() }.add(frame)
        sync.onCustodyChanged()
    }

    private fun Member.status(): ScopeStatus =
        sync
            .status()
            .single { it.url == url }
            .scopes
            .single { it.scopeHex == scopeHex }

    private fun TestScope.pump(rounds: Int = 8) {
        repeat(rounds) {
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    /** One 60 s heal tick, for the rounds nothing wakes. */
    private fun TestScope.tick(times: Int = 1) {
        repeat(times) {
            advanceTimeBy(61_000)
            runCurrent()
        }
    }

    private fun sealedPost(
        id: String,
        from: String,
        scope: ByteArray = scopeId,
        keys: ScopeCrypto.SealKeys = ScopeCrypto.commonsSealKeys(secret),
        sealAs: ByteArray = scopeId,
        sentAt: Long = 1_000L,
        attachmentHash: String? = null,
    ): Pair<String, ByteArray> {
        val frame = commonsFrame(id, from, scope, sentAt = sentAt, attachmentHash = attachmentHash)
        val blob = ScopeCrypto.seal(keys, sealAs, frame.sig, frame.signed)
        return hex(ScopeCrypto.blobId(blob)) to blob
    }

    @Test
    fun `a post reaches the other member through the commons door and never through custody`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()

            // The profile rode the ordinary door: delivered, pinned, custodied. The post rode its own.
            assertEquals(listOf(FrameId.profileId(alice, now)), b.delivered.map { it.id })
            assertTrue(b.custody.has(FrameId.profileId(alice, now)))
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertEquals(
                "hello room",
                b.posts
                    .single()
                    .second.body,
            )
            assertFalse(b.custody.has(fid("p1")))
            assertTrue(alice in b.pinned)
            // Both frames named Alice a member; Bob's own profile did not name Bob one on his own device.
            assertEquals(setOf(conversationId to alice), b.commons.members.toSet())
            assertEquals(setOf(conversationId to bob), a.commons.members.toSet())
            // Everything pulled is accounted (nothing a member pulls from a commons is held), so both converge.
            assertTrue(a.status().converged)
            assertTrue(b.status().converged)
            assertEquals(2, b.status().accountedCount)
            assertEquals(1, a.status().accountedCount)
            assertEquals(0, b.status().invalidCount)
        }

    @Test
    fun `a reaction reaches every member through the commons door and never custody`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            val c = member(spool, carol)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            c.seedOwnProfile(carol)
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            // Bob reacts to Alice's post. (Applying one that lands ahead of its post is InboundPipelineTest's.)
            b.post(commonsReactionFrame(fid("r1"), bob, scopeId, messageId = fid("p1"), emoji = "👍"))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            c.sync.start(backgroundScope)
            pump()

            for (reader in listOf(a, c)) {
                val (env, reaction) = reader.reactions.single()
                assertEquals(bob, env.senderId)
                assertEquals(fid("p1"), reaction.messageId)
                assertEquals("👍", reaction.emoji)
                // Never a chat, never custody: a reaction lives on the spool exactly as a post does.
                assertTrue(reader.posts.none { it.first.id == fid("r1") })
                assertFalse(reader.custody.has(fid("r1")))
                assertTrue(reader.status().converged)
            }
            assertEquals(setOf(conversationId to alice, conversationId to bob), c.commons.members.toSet())
        }

    @Test
    fun `our own reaction a newer one superseded is accounted when the room hands it back, never applied or quarantined`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            // The app pins no key for itself (the peers table holds no row for self), so neither does Bob here.
            val b = member(spool, bob, pinned = mutableSetOf())
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            b.post(commonsReactionFrame(fid("r1"), bob, scopeId, messageId = fid("p1"), emoji = "👍"))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            // Bob changes his mind. The newer reaction replaces the older in his outbox (CommonsStore.postReaction),
            // while the spool keeps r1 to its TTL and lists it back to him.
            b.commons.outbox
                .getValue(conversationId)
                .removeAll { it.envelope.id == fid("r1") }
            b.post(commonsReactionFrame(fid("r2"), bob, scopeId, messageId = fid("p1"), emoji = "❤️"))
            pump()
            // Past ScopeSync's park budget (8 rounds): a frame of ours parked for a key we never pin would be
            // quarantined by now.
            tick(9)

            assertEquals(listOf("👍", "❤️"), a.reactions.map { it.second.emoji })
            assertTrue(a.status().converged)
            assertTrue("Bob's own room converges", b.status().converged)
            assertEquals(0, b.status().invalidCount)
            assertTrue("his own superseded reaction is never applied back", b.reactions.isEmpty())
        }

    @Test
    fun `a photo posted to a room whose relay takes attachments reaches the other member sealed under the room key`() =
        runTest {
            val spool =
                spool(
                    commons =
                        scopeId to SpoolCommonsInfo("Home", maxFrames = 5, ttlMs = 24 * 60 * 60_000L, maxBlob = 65_536, attach = true),
                )
            val (aHash, bytes) = image()
            val a = member(spool, alice, blobs = FakeBlobs(aHash to bytes))
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId, sentAt = now, attachmentHash = aHash))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump(rounds = 16)

            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertTrue("the room's own pass fetched the bytes", bytes.contentEquals(b.blobs.stored[aHash]))
            val aid = hex(ScopeCrypto.attachmentId(ScopeCrypto.commonsSealKeys(secret), scopeId, unhex(aHash)))
            assertEquals(3, spool.chunkCount(scopeHex, aid))
            // Pushed exactly once, by its author.
            assertTrue(spool.chunksPut.all { it.startsWith("$aid:") })
            assertEquals(3, spool.chunksPut.size)
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `someone else's attachment the relay never got is asked once, and older ones still arrive`() =
        runTest {
            // Four newer posts, past the upload grace, whose authors never uploaded (uploads locked, over their `am`,
            // gone) and one older one that did: the room's relay is the only way any of them comes, and the four must
            // not hold the fifth back.
            val spool =
                spool(
                    commons =
                        scopeId to SpoolCommonsInfo("Home", maxFrames = 5, ttlMs = 24 * 60 * 60_000L, maxBlob = 65_536, attach = true),
                )
            val (aHash, bytes) = image()
            val a = member(spool, alice, blobs = FakeBlobs(aHash to bytes))
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId, sentAt = now - 6 * 60_000L, attachmentHash = aHash))
            val never = (1..4).map { sha256Hex(byteArrayOf(it.toByte())) }
            never.forEach { b.commons.received.add(ScopeAttachments.Ref(it, "image/webp", now - 5 * 60_000L)) }
            // Alice's upload lands first, so the older one is never asked about while absent (that is the next test's).
            a.sync.start(backgroundScope)
            pump()
            assertEquals(3, spool.chunksPut.size)
            b.sync.start(backgroundScope)
            pump(rounds = 40)
            tick(times = 2)

            assertTrue("the older one still landed", bytes.contentEquals(b.blobs.stored[aHash]))
            val keys = ScopeCrypto.commonsSealKeys(secret)
            for (missing in never) {
                val aid = hex(ScopeCrypto.attachmentId(keys, scopeId, unhex(missing)))
                assertEquals("asked once, then left to the timed recheck", 1, spool.presenceAsks.count { it == aid })
            }
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `a fresh post whose chunks are not up yet keeps the short retry and lands once they are`() =
        runTest {
            // The ordinary race: Bob meets Alice's post before her chunks. Fresh, so the absent answer is retried.
            val spool = spool(commons = scopeId to advertised.attaching())
            val (aHash, bytes) = image()
            val (_, blob) = sealedPost(fid("p1"), alice, sentAt = now, attachmentHash = aHash)
            spool.plantGarbage(scopeHex, blob)
            val b = member(spool, bob, pinned = mutableSetOf(bob, alice))
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()
            val aid = hex(ScopeCrypto.attachmentId(ScopeCrypto.commonsSealKeys(secret), scopeId, unhex(aHash)))
            assertTrue("asked, and absent", spool.presenceAsks.any { it == aid })
            assertFalse(b.blobs.stored.containsKey(aHash))

            val a = member(spool, alice, blobs = FakeBlobs(aHash to bytes))
            a.seedOwnProfile(alice)
            a.post(commonsFrame(fid("p1"), alice, scopeId, sentAt = now, attachmentHash = aHash))
            a.sync.start(backgroundScope)
            pump(rounds = (ScopeSync.ATTACHMENT_RETRY_MS / 1_000).toInt() + 2)

            assertTrue("the short retry fetched it", bytes.contentEquals(b.blobs.stored[aHash]))
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `a post past the upload grace whose chunks are not up is left to the slow recheck`() =
        runTest {
            val spool = spool(commons = scopeId to advertised.attaching())
            val (aHash, bytes) = image()
            val old = now - 6 * 60_000L
            val (_, blob) = sealedPost(fid("p1"), alice, sentAt = old, attachmentHash = aHash)
            spool.plantGarbage(scopeHex, blob)
            val b = member(spool, bob, pinned = mutableSetOf(bob, alice))
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()
            val aid = hex(ScopeCrypto.attachmentId(ScopeCrypto.commonsSealKeys(secret), scopeId, unhex(aHash)))
            assertEquals("asked once, absent, settled", 1, spool.presenceAsks.count { it == aid })

            val a = member(spool, alice, blobs = FakeBlobs(aHash to bytes))
            a.seedOwnProfile(alice)
            a.post(commonsFrame(fid("p1"), alice, scopeId, sentAt = old, attachmentHash = aHash))
            a.sync.start(backgroundScope)
            pump(rounds = (ScopeSync.ATTACHMENT_RETRY_MS / 1_000).toInt() + 2)

            assertEquals(3, spool.chunkCount(scopeHex, aid))
            // The one new ask is Alice's own, before her push: Bob does not ask again inside the recheck.
            assertEquals(2, spool.presenceAsks.count { it == aid })
            assertFalse(b.blobs.stored.containsKey(aHash))
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `someone else's attachment already held is neither asked about nor pushed`() =
        runTest {
            // A room read that raced the bytes' landing lists a ref Bob now holds: never his to push (C-7.4-6's own-only
            // rule, LocalPass.LANDED), and nothing left to fetch.
            val spool = spool(commons = scopeId to advertised.attaching())
            val (aHash, bytes) = image()
            val b = member(spool, bob, blobs = FakeBlobs(aHash to bytes))
            b.seedOwnProfile(bob)
            b.commons.filtersHeld = false
            b.commons.received.add(ScopeAttachments.Ref(aHash, "image/webp", now))
            b.sync.start(backgroundScope)
            pump()
            tick()

            val aid = hex(ScopeCrypto.attachmentId(ScopeCrypto.commonsSealKeys(secret), scopeId, unhex(aHash)))
            assertEquals(0, spool.presenceAsks.count { it == aid })
            assertTrue(spool.chunksPut.none { it.startsWith("$aid:") })
            b.sync.stop()
        }

    @Test
    fun `a room whose relay takes no attachments for it is never sent an attachment record`() =
        runTest {
            // S-7.4-5: the spool carries attachments for its other scopes, but its commons said no `attach`.
            val spool = spool()
            val (aHash, bytes) = image()
            val a = member(spool, alice, blobs = FakeBlobs(aHash to bytes))
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId, sentAt = now, attachmentHash = aHash))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump(rounds = 16)

            // The post itself still crosses: the frame plane is unaffected.
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertEquals(emptyList<String>(), spool.presenceAsks)
            assertEquals(emptyList<String>(), spool.chunksPut)
            assertFalse(b.blobs.stored.containsKey(aHash))
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `a post pulled ahead of its author's profile waits for it instead of being quarantined`() =
        runTest {
            val spool = spool()
            // Alice's post lands on the spool first, then her profile — the order a listing hands Bob.
            val (postHex, blob) = sealedPost(fid("p1"), alice)
            spool.plantGarbage(scopeHex, blob)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            val before = spool.pulled.count { it == postHex }
            val b = member(spool, bob)
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()

            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertEquals(0, b.status().invalidCount)
            // The diagnostics fold is recomputed at the start of a round, so one quiet tick reads the truth.
            tick()
            assertTrue(b.status().converged)
            // Delivered on the round's second pass, so Bob pulled the blob exactly once.
            assertEquals(1, spool.pulled.count { it == postHex } - before)
        }

    @Test
    fun `a post whose author never shows a profile is given up on after a bounded wait`() =
        runTest {
            val spool = spool()
            val (postHex, blob) = sealedPost(fid("p1"), carol)
            spool.plantGarbage(scopeHex, blob)
            val b = member(spool, bob)
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()
            assertTrue(b.posts.isEmpty())
            assertEquals(0, b.status().invalidCount)

            tick(times = 9)

            assertTrue(b.posts.isEmpty())
            assertEquals(1, b.status().invalidCount)
            // A quarantined blob keeps the two digests apart for good (§9.3); what the invalid set buys is quiet.
            val pulls = spool.pulled.count { it == postHex }
            tick(times = 3)
            assertEquals("quarantined, so never asked for again", pulls, spool.pulled.count { it == postHex })
        }

    @Test
    fun `a reconnect does not re-pull what was already accounted`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            val pulls = spool.pulled.size

            spool.dropSockets()
            pump(16)

            assertTrue(b.status().converged)
            assertEquals(pulls, spool.pulled.size)
            assertEquals(1, b.posts.size)
        }

    @Test
    fun `the commons is subscribed only at the relay that runs it`() =
        runTest {
            val home = spool()
            val other = spool(commons = null)
            val dialer =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket? =
                        if (url ==
                            this@CommonsSyncTest.url
                        ) {
                            home.dial(url)
                        } else {
                            other.dial(url)
                        }
                }
            val a = member(home, alice, urls = listOf(url, otherUrl), dialer = dialer)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()

            assertTrue(scopeHex in home.subscribedScopes)
            assertFalse(scopeHex in other.subscribedScopes)
            assertTrue(
                a.sync
                    .status()
                    .single { it.url == otherUrl }
                    .scopes
                    .isEmpty(),
            )
            assertEquals(
                "Home",
                a.sync
                    .status()
                    .single { it.url == url }
                    .rooms[conversationId]
                    ?.name,
            )
        }

    @Test
    fun `a relay that advertises no commons is never asked for one`() =
        runTest {
            val spool = spool(commons = null)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            assertFalse(scopeHex in spool.subscribedScopes)
        }

    @Test
    fun `a room whose invite was rotated is reported stale and asked about once per connection`() =
        runTest {
            // The operator minted a new invite: the spool runs another room, and the id our invite derives is an
            // ordinary scope there, which applies what we declared — as the daemon's store does — and echoes it.
            val spool = spool(commons = ScopeCrypto.commonsScopeId(otherSecret) to advertised, echoesDeclaredBounds = true)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            tick(times = 3)

            assertEquals(setOf(conversationId), a.staleRooms())
            assertEquals("asked once, then left alone", 1, spool.subAttempts[scopeHex])
            assertTrue(
                a.sync
                    .status()
                    .single { it.url == url }
                    .scopes
                    .none { it.scopeHex == scopeHex },
            )
            // A new connection looks once more — the operator may have fixed a slip — and the spool, still running
            // another room, says the same: stale again, and left alone for the rest of the connection.
            spool.dropSockets()
            pump()
            tick(times = 3)
            assertEquals(setOf(conversationId), a.staleRooms())
            assertEquals("one look per connection", 2, spool.subAttempts[scopeHex])
        }

    @Test
    fun `a creation gate asked of a commons reports it stale, and no stamp is mined`() =
        runTest {
            // On a mining spool the rotated id is an unknown scope: S-7.4-3 says the room is never gated, so `err pow` is
            // the spool saying it does not run it.
            val spool = spool(powBits = 8, commons = ScopeCrypto.commonsScopeId(otherSecret) to advertised)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            tick(times = 2)

            assertEquals(setOf(conversationId), a.staleRooms())
            assertTrue(spool.subStamped.none { it.first == scopeHex && it.second })
        }

    @Test
    fun `a room its relay runs is never reported stale, whatever the relay's scopes echo`() =
        runTest {
            val spool = spool(echoesDeclaredBounds = true)
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            tick(times = 2)

            assertEquals(emptySet<String>(), a.staleRooms())
            assertEquals(emptySet<String>(), b.staleRooms())
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
        }

    @Test
    fun `a relay whose hello advertises no commons reports the room joined there stale`() =
        runTest {
            val spool = spool(commons = null)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()

            assertEquals(setOf(conversationId), a.staleRooms())
        }

    @Test
    fun `a stale room whose relay runs it again is cleared on the next connection and carries posts again`() =
        runTest {
            val spool = spool(commons = ScopeCrypto.commonsScopeId(otherSecret) to advertised, echoesDeclaredBounds = true)
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            tick(times = 2)
            assertEquals(setOf(conversationId), a.staleRooms())
            assertEquals(setOf(conversationId), b.staleRooms())

            // The operator puts the old invite back and restarts the relay.
            spool.commons = scopeId to advertised
            spool.dropSockets()
            pump()

            assertEquals(emptySet<String>(), a.staleRooms())
            assertEquals(emptySet<String>(), b.staleRooms())
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            pump()
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertTrue(b.status().converged)
        }

    @Test
    fun `a room its relay restarted without is reported stale only until a hello advertises it again`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            assertEquals(emptySet<String>(), a.staleRooms())

            // A restart that lost the room's setting, then one that has it back.
            spool.commons = null
            spool.dropSockets()
            pump()
            assertEquals(setOf(conversationId), a.staleRooms())
            spool.commons = scopeId to advertised
            spool.dropSockets()
            pump()
            assertEquals(emptySet<String>(), a.staleRooms())
            assertTrue(a.status().converged)
        }

    @Test
    fun `no proof of work is mined for the commons`() =
        runTest {
            val spool = spool(powBits = 8)
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            assertTrue(scopeHex in spool.subscribedScopes)
            assertFalse(spool.stamps.containsKey(scopeHex))
            assertTrue(spool.pushed.isNotEmpty())
        }

    @Test
    fun `the spool's pinned bounds win over what the scope table guessed`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            // Six posts into a room pinned at five: the spool evicts the oldest and Bob still converges,
            // which only holds if the client sized its accounted set and its digest against the pinned cap.
            val b = member(spool, bob)
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            repeat(6) { i -> a.post(commonsFrame(fid("p$i"), alice, scopeId, sentAt = now + i)) }
            pump(16)
            assertTrue(b.status().converged)
            assertEquals(5, spool.liveIds(scopeHex).size)
        }

    @Test
    fun `a post re-sealed from another room and a post that fails the carry gate are quarantined`() =
        runTest {
            val spool = spool()
            // Carol is in this room and another; she re-seals a post she signed for the other room into this one.
            val (crossHex, crossBlob) = sealedPost(fid("x1"), carol, scope = ScopeCrypto.commonsScopeId(otherSecret))
            spool.plantGarbage(scopeHex, crossBlob)
            val b = member(spool, bob, pinned = mutableSetOf(bob, carol, alice), refuse = setOf(fid("bad1")))
            b.seedOwnProfile(bob)
            val (badHex, badBlob) = sealedPost(fid("bad1"), alice)
            spool.plantGarbage(scopeHex, badBlob)
            b.sync.start(backgroundScope)
            pump()

            assertTrue(b.posts.isEmpty())
            assertEquals(2, b.status().invalidCount)
            tick(times = 2)
            assertEquals(1, spool.pulled.count { it == crossHex })
            assertEquals(1, spool.pulled.count { it == badHex })
        }

    @Test
    fun `a post whose id is not canonical is quarantined and never reaches the room`() =
        runTest {
            // #178: the commons door never meets the router, so the spool's own gate is the only one. Android's
            // database would file this post under the real one's message row, the mark dropped.
            val spool = spool()
            val (realHex, realBlob) = sealedPost(fid("p1"), alice)
            val (twinHex, twinBlob) = sealedPost("\uFEFF" + fid("p1"), alice)
            spool.plantGarbage(scopeHex, twinBlob)
            spool.plantGarbage(scopeHex, realBlob)
            val b = member(spool, bob, pinned = mutableSetOf(bob, alice))
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()

            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertEquals(1, b.status().invalidCount)
            tick(times = 2)
            assertEquals("quarantined, so never pulled again", 1, spool.pulled.count { it == twinHex })
            assertEquals(1, spool.pulled.count { it == realHex })
        }

    @Test
    fun `our own profile tombstoned by the room asks for one fresh stamp`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            val profileHex = spool.pushed.single()

            spool.expire(scopeHex, profileHex)
            pump()
            tick(times = 3)

            assertEquals(1, a.tombstoneReports.size)
        }

    @Test
    fun `only our own profile leaves for the room, never a pinned peer's`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            // Carol's profile sits in Alice's custody from the radios; it must not be re-published into the room.
            a.custody.store(profileFrame(FrameId.profileId(carol, now), carol, sentAt = now), ForwardStore.ORIGIN_RELAY, now)
            a.sync.start(backgroundScope)
            pump()
            assertEquals(1, spool.pushed.size)
            assertTrue(a.status().converged)
        }

    @Test
    fun `an event from the room delivers a post without a heal round`() =
        runTest {
            val spool = spool()
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            assertTrue(b.posts.isEmpty())

            a.post(commonsFrame(fid("p1"), alice, scopeId))
            pump(2)

            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            assertTrue(b.status().converged)
        }

    @Test
    fun `leaving drops the subscription at the next reconcile, and rejoining pulls the room again`() =
        runTest {
            val spool = spool()
            val (postHex, blob) = sealedPost(fid("p1"), alice)
            spool.plantGarbage(scopeHex, blob)
            val rooms = mutableListOf(CommonsRoots(conversationId, url, secret))
            val b = member(spool, bob, rooms = rooms, pinned = mutableSetOf(bob, alice))
            b.seedOwnProfile(bob)
            b.sync.start(backgroundScope)
            pump()
            assertEquals(
                1,
                b.sync
                    .status()
                    .single()
                    .scopes.size,
            )
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })

            rooms.clear()
            b.sync.onScopeTableChanged()
            pump(2)
            assertTrue(
                b.sync
                    .status()
                    .single()
                    .scopes
                    .isEmpty(),
            )
            // A post landing while we are out is not delivered: the connection is still subscribed (no
            // `unsub` exists), so the spool fans it out, and the worker must drop it on the floor.
            val (lateHex, lateBlob) = sealedPost(fid("p2"), alice)
            spool.plantGarbage(scopeHex, lateBlob)
            pump(2)
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })

            // Rejoin on the same connection: leaving deleted the history, so the room is pulled afresh —
            // including the post this worker had already accounted before the leave.
            rooms.add(CommonsRoots(conversationId, url, secret))
            b.sync.onScopeTableChanged()
            pump(4)
            assertEquals(listOf(fid("p1"), fid("p1"), fid("p2")), b.posts.map { it.first.id })
            assertEquals(2, spool.pulled.count { it == postHex })
            assertEquals(1, spool.pulled.count { it == lateHex })
            tick()
            assertTrue(b.status().converged)
        }

    @Test
    fun `two rooms on one relay are each subscribed and each reads its own advertisement`() =
        runTest {
            val spool = roomsSpool()
            val a = member(spool, alice, rooms = bothRooms)
            val b = member(spool, bob, rooms = bothRooms)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("home1"), alice, scopeId))
            a.post(commonsFrame(fid("team1"), alice, teamId), room = teamRoom)
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()

            assertTrue(scopeHex in spool.subscribedScopes && teamHex in spool.subscribedScopes)
            assertEquals(setOf(fid("home1"), fid("team1")), b.posts.map { it.first.id }.toSet())
            val relay = b.sync.status().single { it.url == url }
            assertEquals("Home", relay.rooms[conversationId]?.name)
            assertEquals(false, relay.rooms[conversationId]?.attach)
            assertEquals(7, relay.rooms[teamRoom]?.maxFrames)
            assertEquals(true, relay.rooms[teamRoom]?.attach)
            assertEquals(emptySet<String>(), b.staleRooms())
            assertEquals(2, relay.adverts?.count)
            // Each SUB declares its own room's bounds exactly: a listed room is never probed with a short TTL.
            val home = spool.declaredFor(scopeHex)!!
            assertEquals(Triple(5, 24 * 60 * 60_000L, 65_536), Triple(home.maxFrames, home.ttlMs, home.maxBlob))
            val team = spool.declaredFor(teamHex)!!
            assertEquals(Triple(7, 12 * 60 * 60_000L, 65_536), Triple(team.maxFrames, team.ttlMs, team.maxBlob))
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `each room's own attach decides whether its attachments go up`() =
        runTest {
            // S-7.4-5, per room: the team room takes attachments, the room beside it on the same relay does not.
            val spool = roomsSpool()
            val (homeHash, homeBytes) = image(90_000)
            val (teamHash, teamBytes) = image()
            val a = member(spool, alice, rooms = bothRooms, blobs = FakeBlobs(homeHash to homeBytes, teamHash to teamBytes))
            a.seedOwnProfile(alice)
            a.post(commonsFrame(fid("home1"), alice, scopeId, sentAt = now, attachmentHash = homeHash))
            a.post(commonsFrame(fid("team1"), alice, teamId, sentAt = now, attachmentHash = teamHash), room = teamRoom)
            a.sync.start(backgroundScope)
            pump(rounds = 16)

            val teamAid = hex(ScopeCrypto.attachmentId(ScopeCrypto.commonsSealKeys(teamSecret), teamId, unhex(teamHash)))
            val homeAid = hex(ScopeCrypto.attachmentId(ScopeCrypto.commonsSealKeys(secret), scopeId, unhex(homeHash)))
            assertTrue("the team room's attachment went up", spool.chunkCount(teamHex, teamAid) > 0)
            assertTrue(spool.presenceAsks.none { it == homeAid })
            assertTrue(spool.chunksPut.none { it.startsWith("$homeAid:") })
            assertEquals("no record went to a room that said no attach", emptyList<String>(), spool.refusedRoomAttachments.toList())
            a.sync.stop()
        }

    @Test
    fun `a room its relay does not list is stale without a single sub`() =
        runTest {
            // C-7.4-17: a HELLO carrying `rooms` settles it before any SUB — the tag is in the list or it is not.
            val spool = roomsSpool(rooms = listOf(teamId to teamAdvert))
            val a = member(spool, alice)
            a.seedOwnProfile(alice)
            a.sync.start(backgroundScope)
            pump()
            tick(times = 2)

            assertEquals(setOf(conversationId), a.staleRooms())
            assertEquals(null, spool.subAttempts[scopeHex])
            assertTrue(
                a.sync
                    .status()
                    .single { it.url == url }
                    .scopes
                    .none { it.scopeHex == scopeHex },
            )
            a.sync.stop()
        }

    @Test
    fun `a room its relay lists again is run there from the next connection`() =
        runTest {
            val spool = roomsSpool(rooms = listOf(teamId to teamAdvert))
            val a = member(spool, alice)
            val b = member(spool, bob)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.post(commonsFrame(fid("p1"), alice, scopeId))
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()
            assertEquals(setOf(conversationId), b.staleRooms())

            spool.rooms = listOf(scopeId to advertised, teamId to teamAdvert)
            spool.dropSockets()
            pump(rounds = 16)

            assertEquals(emptySet<String>(), a.staleRooms())
            assertEquals(emptySet<String>(), b.staleRooms())
            assertEquals(listOf(fid("p1")), b.posts.map { it.first.id })
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `leaving one of two rooms on a relay keeps the other`() =
        runTest {
            val spool = roomsSpool()
            val rooms = bothRooms
            val a = member(spool, alice, rooms = bothRooms)
            val b = member(spool, bob, rooms = rooms)
            a.seedOwnProfile(alice)
            b.seedOwnProfile(bob)
            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()

            rooms.removeAll { it.conversationId == conversationId }
            b.sync.onScopeTableChanged()
            pump(2)
            a.post(commonsFrame(fid("home1"), alice, scopeId))
            a.post(commonsFrame(fid("team1"), alice, teamId), room = teamRoom)
            pump()

            assertEquals(listOf(fid("team1")), b.posts.map { it.first.id })
            val relay = b.sync.status().single { it.url == url }
            assertEquals(listOf(teamHex), relay.scopes.filter { it.label.startsWith("c-") }.map { it.scopeHex })
            assertEquals(setOf(teamRoom), relay.rooms.keys)
            a.sync.stop()
            b.sync.stop()
        }
}
