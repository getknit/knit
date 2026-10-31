package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.protocol.WireEnvelope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [PeerRepository] against a real in-memory DB: [PeerRepository.sweepCap] with a tiny cap — the bound
 * that stops a Sybil profile flood from growing the (otherwise uncapped) `peers` table, while sparing
 * verified/known peers — and the name-collision directory that folds our own name into the universe.
 */
class PeerRepositoryTest : RoomDbTest() {
    private fun repo(
        myName: String = "",
        maxPeers: Int = 2_000,
    ): PeerRepository {
        val profile = mockk<InboundSettings>(relaxed = true)
        every { profile.displayName } returns flowOf(myName)
        val identity = mockk<IdentitySource>(relaxed = true)
        coEvery { identity.nodeId() } returns "me"
        return PeerRepository(db.peerDao(), profile, identity, maxPeers)
    }

    /** The inputs a [LabelFade] reads, held by the test: anchors, the clock and the tick. */
    private inner class Fade(
        scope: CoroutineScope,
    ) {
        val accepted = MutableStateFlow(emptySet<String>())
        val authored = MutableStateFlow(emptySet<String>())
        val groups = MutableStateFlow(emptyList<GroupEntity>())
        var now = NOW
        val ticks = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val fade =
            LabelFade(
                anchors = LabelAnchors(accepted, authored, groups, scope),
                clock = { now },
                ticks = ticks,
                transact = { block -> db.withWriteTransaction { block() } },
            )

        fun repo(myName: String = ""): PeerRepository {
            val profile = mockk<InboundSettings>(relaxed = true)
            every { profile.displayName } returns flowOf(myName)
            val identity = mockk<IdentitySource>(relaxed = true)
            coEvery { identity.nodeId() } returns "me"
            return PeerRepository(db.peerDao(), profile, identity, fade = fade)
        }
    }

    @Test
    fun `sweepCap evicts the oldest unverified strangers beyond the cap, sparing verified and protected`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "verified", verified = true, updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "known", verified = false, updatedAt = 2L)) // protected (e.g. we messaged them)
            dao.upsert(PeerEntity(nodeId = "old", verified = false, updatedAt = 3L))
            dao.upsert(PeerEntity(nodeId = "new", verified = false, updatedAt = 4L))

            repo(maxPeers = 1).sweepCap(protected = setOf("known"))

            // Cappable pool = {old, new} (verified + protected spared); cap 1 → evict the 1 oldest = "old".
            assertNull(dao.findByNodeId("old"))
            assertNotNull(dao.findByNodeId("new"))
            assertNotNull(dao.findByNodeId("verified"))
            assertNotNull(dao.findByNodeId("known"))
        }

    @Test
    fun `forgetSelf drops only the row pinned for our own node id`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "me", name = "Me", pubKey = "KEY"))
            dao.upsert(PeerEntity(nodeId = "a", name = "Alice"))

            assertTrue(repo().forgetSelf("me"))
            assertNull(dao.findByNodeId("me"))
            assertNotNull(dao.findByNodeId("a"))
            assertFalse("a second sweep is a no-op", repo().forgetSelf("me"))
        }

    @Test
    fun `a peer's proof of key keeps the newest stamp whatever order the copies arrive in`() =
        runTest {
            val repo = repo()
            db.peerDao().upsert(PeerEntity(nodeId = "a", pubKey = "KEY"))

            repo.recordProfileFrame("a", frame(2), sentAt = 20L)
            repo.recordProfileFrame("a", frame(1), sentAt = 10L) // an older copy, re-served late
            assertArrayEquals(byteArrayOf(2), repo.profileFrame("a")!!.signed)

            repo.recordProfileFrame("a", frame(3), sentAt = 20L) // the same stamp: the copy already held stays
            assertArrayEquals(byteArrayOf(2), repo.profileFrame("a")!!.signed)

            repo.recordProfileFrame("a", frame(4), sentAt = 30L)
            val served = repo.profileFrame("a")!!
            assertArrayEquals(byteArrayOf(4), served.signed)
            assertArrayEquals(byteArrayOf(40), served.sig)
            assertFalse("a key is served point to point", served.relay)
            assertNull(repo.profileFrame("b"))
        }

    @Test
    fun `a proof of key goes with the pin it proves`() =
        runTest {
            val dao = db.peerDao()
            val repo = repo(maxPeers = 1)
            dao.upsert(PeerEntity(nodeId = "me", pubKey = "SELF"))
            dao.upsert(PeerEntity(nodeId = "old", updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "new", updatedAt = 2L))
            listOf("me", "old", "new").forEach { repo.recordProfileFrame(it, frame(1), sentAt = 1L) }

            repo.forgetSelf("me")
            assertEquals("the evicted ids are named, for the owner's caches", listOf("old"), repo.sweepCap(protected = emptySet()))

            assertNull(repo.profileFrame("me"))
            assertNull(repo.profileFrame("old"))
            assertNotNull(repo.profileFrame("new"))
            assertEquals("under the cap nothing is evicted, and nothing named", emptyList<String>(), repo.sweepCap(protected = emptySet()))
        }

    @Test
    fun `a stored proof of key over the profile cap is deleted and its pin stays`() =
        runTest {
            // #234: a build before the cap kept such proofs, and the key request path would go on serving them.
            val dao = db.peerDao()
            val repo = repo()
            listOf("long", "atcap").forEach { dao.upsert(PeerEntity(nodeId = it, pubKey = "KEY-$it")) }
            repo.recordProfileFrame("long", WireEnvelope(relay = false, sig = byteArrayOf(1), signed = ByteArray(8_193)), sentAt = 1L)
            repo.recordProfileFrame("atcap", WireEnvelope(relay = false, sig = byteArrayOf(1), signed = ByteArray(8_192)), sentAt = 1L)

            assertEquals(1, repo.deleteOversizeProfileFrames(8_192))

            assertNull(repo.profileFrame("long"))
            assertEquals("the pin it proved stays", "KEY-long", dao.findByNodeId("long")?.pubKey)
            assertEquals(8_192, repo.profileFrame("atcap")?.signed?.size)
        }

    @Test
    fun `observeDirectory folds our own name into the universe and labels same-named peers`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "a", name = "Alice"))
            dao.upsert(PeerEntity(nodeId = "b", name = "Bob"))

            val directory = repo(myName = "Alice").observeDirectory().first()

            assertEquals(Alias.aliasFor("a"), directory.label("a").discriminator) // collides with us
            assertNull(directory.label("b").discriminator)
            assertEquals("Alice (${Alias.aliasFor("me")})", directory.label("me").text) // our own name, from settings
            assertEquals(setOf("a", "b"), directory.byNode.keys)
        }

    @Test
    fun `labelIndex is the same universe read through the light projection`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "a", name = "Sam", pubKey = "k1"))
            dao.upsert(PeerEntity(nodeId = "b", name = "sam", pubKey = "k2"))
            dao.upsert(PeerEntity(nodeId = "c", name = "Carol"))

            val labels = repo(myName = "Me").labelIndex()

            assertEquals("Sam (${Alias.aliasFor("a")})", labels.labelFor("a").text)
            assertEquals("sam (${Alias.aliasFor("b")})", labels.labelFor("b").text)
            assertEquals("Carol", labels.labelFor("c").text)
            assertEquals("Me", labels.labelFor("me").text)
            assertEquals(Alias.aliasFor("zz"), labels.labelFor("zz").text) // never pinned → alias, undiscriminated
        }

    @Test
    fun `a stranger's clash fades a week after it was last heard and the contact reads plain`() =
        runTest {
            val fade = Fade(backgroundScope)
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "c", name = "Alice", lastSeenAt = NOW - DAY)) // a contact, by our own accept
            dao.upsert(PeerEntity(nodeId = "s", name = "Alice", lastSeenAt = NOW - DAY)) // a stranger met once
            fade.accepted.value = setOf("c")
            val repo = fade.repo()
            val directories = Channel<PeerDirectory>(Channel.UNLIMITED)
            backgroundScope.launch { repo.observeDirectory().collect { directories.send(it) } }

            val live = directories.receive()
            assertEquals(Alias.aliasFor("c"), live.label("c").discriminator)
            assertEquals(Alias.aliasFor("s"), live.label("s").discriminator)
            assertEquals(Alias.aliasFor("c"), repo.labelIndex().labelFor("c").discriminator)

            // Six days on, still inside the week: the tick judges again and nothing moves.
            fade.now = NOW + 5 * DAY
            assertEquals(Alias.aliasFor("c"), repo.labelIndex().labelFor("c").discriminator)

            // Past the week, with no write to the table: the tick alone carries the change.
            fade.now = NOW + 6 * DAY + HOUR
            fade.ticks.emit(Unit)
            val faded = directories.receive()
            assertNull(faded.label("c").discriminator)
            assertEquals("Alice", faded.label("c").text)
            assertEquals(Alias.aliasFor("s"), faded.label("s").discriminator) // the stranger stays marked
            assertNull(repo.labelIndex().labelFor("c").discriminator) // a notification title agrees
        }

    @Test
    fun `an anchored or verified peer never goes stale, whichever signal anchors it`() =
        runTest {
            val fade = Fade(backgroundScope)
            val dao = db.peerDao()
            val quiet = NOW - 30 * DAY
            dao.upsert(PeerEntity(nodeId = "fresh", name = "Sam", lastSeenAt = NOW)) // a stranger heard just now
            dao.upsert(PeerEntity(nodeId = "acc", name = "Sam", lastSeenAt = quiet))
            dao.upsert(PeerEntity(nodeId = "dm", name = "Sam", lastSeenAt = quiet))
            dao.upsert(PeerEntity(nodeId = "mem", name = "Sam", lastSeenAt = quiet))
            dao.upsert(PeerEntity(nodeId = "ver", name = "Sam", verified = true, lastSeenAt = quiet))
            dao.upsert(PeerEntity(nodeId = "gone", name = "Sam", lastSeenAt = quiet))
            fade.accepted.value = setOf("acc")
            fade.authored.value = setOf("dm", "g-x")
            fade.groups.value =
                listOf(GroupEntity(groupId = "g-x", name = "", members = "[\"me\",\"mem\"]", createdBy = "me", createdAt = 1L))

            val labels = fade.repo().labelIndex()

            // The quiet contacts and the fresh stranger stay a live clash; only the quiet stranger yields.
            for (id in listOf("fresh", "acc", "dm", "mem", "ver")) assertEquals(id, Alias.aliasFor(id), labels.labelFor(id).discriminator)
            assertEquals(Alias.aliasFor("gone"), labels.labelFor("gone").discriminator)
        }

    @Test
    fun `accepting a quiet stranger brings the clash back`() =
        runTest {
            val fade = Fade(backgroundScope)
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "c", name = "Alice", lastSeenAt = NOW))
            dao.upsert(PeerEntity(nodeId = "s", name = "Alice", lastSeenAt = NOW - 30 * DAY))
            fade.accepted.value = setOf("c")
            val repo = fade.repo()
            assertNull(repo.labelIndex().labelFor("c").discriminator)
            fade.accepted.value = setOf("c", "s")
            // The anchors are a shared flow: the accept reaches `current()` once the share has re-judged it, which a
            // query that returns fast can beat. Wait for it rather than for the scheduler's order.
            fade.fade.anchors
                .observe()
                .first { "s" in it }
            assertEquals(Alias.aliasFor("c"), repo.labelIndex().labelFor("c").discriminator)
        }

    @Test
    fun `heard stamps bank on the flush, the newest per sender, and a stamp inside the gate costs no write`() =
        runTest {
            val fade = Fade(backgroundScope)
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", lastSeenAt = NOW - DAY))
            val repo = fade.repo()

            repo.noteSeen("a", NOW - HOUR)
            repo.noteSeen("a", NOW) // the newest wins
            repo.noteSeen("ghost", NOW) // no row: remembered, nothing written
            assertEquals("memory only until the flush", NOW - DAY, dao.findByNodeId("a")!!.lastSeenAt)
            repo.flushSeen()
            assertEquals(NOW, dao.findByNodeId("a")!!.lastSeenAt)
            assertNull(dao.findByNodeId("ghost"))

            // Inside the six-hour gate: skipped before any query, so the row (moved behind its back) stays put.
            dao.markSeen("a", at = NOW + MINUTE, olderThan = Long.MAX_VALUE)
            repo.noteSeen("a", NOW + 2 * HOUR)
            repo.flushSeen()
            assertEquals(NOW + MINUTE, dao.findByNodeId("a")!!.lastSeenAt)

            // Past it: written.
            repo.noteSeen("a", NOW + 7 * HOUR)
            repo.flushSeen()
            assertEquals(NOW + 7 * HOUR, dao.findByNodeId("a")!!.lastSeenAt)
        }

    @Test
    fun `without a fade nothing is stamped and every known identity counts`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", lastSeenAt = 0L))
            val repo = repo()
            repo.noteSeen("a", NOW)
            repo.flushSeen()
            assertEquals(0L, dao.findByNodeId("a")!!.lastSeenAt)
        }

    /** A stand-in signed profile frame: the bytes are what the store must keep, not a real envelope. */
    private fun frame(tag: Int) = WireEnvelope(sig = byteArrayOf((tag * 10).toByte()), signed = byteArrayOf(tag.toByte()))

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val NOW = 1_800_000_000_000L
    }
}
