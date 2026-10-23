package app.getknit.knit.data

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.scopeconfig.ScopeConfigRepository
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.spool.ScopeConfig
import app.getknit.knit.mesh.spool.ScopeConfigPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transactional group mutations had no dedicated test. Exercises `recordDeparture` (roster edit +
 * departed tombstone + status notice, all in one transaction) and `leave`/`delete` against the real DB.
 */
class GroupRepositoryTest : RoomDbTest() {
    private val groupRatchet by lazy { GroupRatchetRepository(db.groupRatchetDao(), clock = { 1L }) }

    private fun repo() = GroupRepository(db.groupDao(), MessageRepository(db.messageDao()), db, groupRatchet)

    private val messages get() = db.messageDao()

    private suspend fun seedGroup(
        id: String,
        members: List<String>,
        left: Boolean = false,
    ) = db.groupDao().upsert(
        GroupEntity(
            groupId = id,
            name = "",
            members = GroupMembersStore.encode(members),
            createdBy = members.first(),
            createdAt = 1L,
            left = left,
        ),
    )

    @Test
    fun `recordDeparture drops the member, tombstones them, and inserts a status notice`() =
        runTest {
            seedGroup("g", listOf("a", "b", "c"))

            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))

            val group = db.groupDao().findById("g")!!
            assertEquals(listOf("a", "c"), GroupMembersStore.decode(group.members))
            assertTrue("b" in GroupMembersStore.decode(group.departed))
            val notice = messages.observeNewestForConversation("g", 10).first().single()
            assertEquals(MessageEntity.KIND_MEMBER_LEFT, notice.kind)
            assertEquals("b", notice.senderId)
            assertEquals(100L, notice.sentAt)
        }

    @Test
    fun `recordDeparture forces a rekey and drops the leaver's outbox row atomically`() =
        runTest {
            seedGroup("g", listOf("a", "b", "c"))
            groupRatchet.commitSend(
                GroupRatchetEngine.SendChain(
                    groupId = "g",
                    epoch = 1,
                    seed = ByteArray(32),
                    chainKey = ByteArray(32),
                    count = 3,
                    mintedAt = 1L,
                    export = ByteArray(32),
                ),
            )
            groupRatchet.markKeySent("g", "b", epoch = 1, at = 1L)
            groupRatchet.markKeySent("g", "c", epoch = 1, at = 1L)

            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))

            // Send chains are retired with the departure — every key gone, the number kept, so the next send
            // mints epoch 2 (never a second epoch 1, #167) that the leaver never receives; nothing is re-served.
            // The leaver stops being a distribution target, remaining members keep theirs.
            assertRetiredAt(epoch = 1)
            assertNull(groupRatchet.keySend("g", "b"))
            assertEquals(1, groupRatchet.keySend("g", "c")?.sentEpoch)
        }

    @Test
    fun `leave purges all group ratchet state`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            groupRatchet.commitSend(
                GroupRatchetEngine.SendChain(
                    groupId = "g",
                    epoch = 1,
                    seed = ByteArray(32),
                    chainKey = ByteArray(32),
                    count = 0,
                    mintedAt = 1L,
                    export = ByteArray(32),
                ),
            )
            groupRatchet.insertRecvChain(
                GroupRatchetEngine.RecvChain(
                    groupId = "g",
                    senderId = "b",
                    epoch = 1,
                    mintedAt = 1L,
                    chainKey = ByteArray(32),
                    next = 0,
                    lastUsedAt = 1L,
                ),
            )
            groupRatchet.markKeySent("g", "b", epoch = 1, at = 1L)

            repo().leave("g")

            // Keys gone, number kept: re-creating the same people is the same id (ADR 2026-10.xgwj).
            assertRetiredAt(epoch = 1)
            assertTrue(groupRatchet.recvChains("g", "b", 1).isEmpty())
            assertNull(groupRatchet.keySend("g", "b"))
        }

    @Test
    fun `recordRejoin writes the notice and rekeys only when asked`() =
        runTest {
            seedGroup("g", listOf("a", "b", "c"))
            groupRatchet.commitSend(sendChain("g"))

            repo().recordRejoin("g", "b", rejoinedAt = 200L, rekey = true)

            // Our send chain was retired with the membership change, exactly as on a departure…
            assertRetiredAt(epoch = 1)
            val notice = messages.observeNewestForConversation("g", 10).first().single()
            assertEquals(MessageEntity.KIND_MEMBER_REJOINED, notice.kind)
            assertEquals("b", notice.senderId)
            assertEquals(200L, notice.sentAt)

            // …and a floored rejoin (the caller's per-member bound) keeps it.
            groupRatchet.commitSend(sendChain("g"))
            repo().recordRejoin("g", "b", rejoinedAt = 300L, rekey = false)
            assertEquals(1, groupRatchet.sendChain("g")?.epoch)
            // The notice row is deterministic: the second rejoin moved the one line, not added another.
            assertEquals(1, messages.observeNewestForConversation("g", 10).first().size)
        }

    @Test
    fun `recordDeparture ignores a leave older than the member's recorded rejoin`() =
        runTest {
            // A custody re-serve of the leave that preceded a rejoin must not evict the member again:
            // leave and rejoin are last-writer-wins on the member's own sentAt clock.
            seedGroup("g", listOf("a", "b", "c"))
            repo().recordRejoin("g", "b", rejoinedAt = 200L, rekey = false)

            assertFalse(repo().recordDeparture("g", "b", leftAt = 100L))
            assertTrue("b" in GroupMembersStore.decode(db.groupDao().findById("g")!!.members))

            // A leave newer than the rejoin still applies.
            assertTrue(repo().recordDeparture("g", "b", leftAt = 300L))
            assertFalse("b" in GroupMembersStore.decode(db.groupDao().findById("g")!!.members))
        }

    @Test
    fun `the clocks live on the group, so a sweep that takes the lines leaves both guards standing`() =
        runTest {
            // IOS-6: the retention sweep may drop a request thread's `leave:` and `rejoin:` lines while the group
            // lives on. The clocks are no longer read off them (ADR 2026-09.v6fu).
            seedGroup("g", listOf("a", "b", "c"))
            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))
            messages.deleteByConversation("g")
            assertEquals("the leave clock outlives its line", 100L, repo().leftAt("g", "b"))

            repo().recordRejoin("g", "b", rejoinedAt = 200L, rekey = false)
            messages.deleteByConversation("g")
            // The departure shrank the roster; the rejoin's caller (reconcileGroup) restores it, so stand in for it.
            seedGroup("g", listOf("a", "b", "c"))
            assertFalse("an old leave cannot evict again once the rejoin line is gone", repo().recordDeparture("g", "b", leftAt = 150L))
            assertEquals(100L, repo().leftAt("g", "b"))
            assertTrue(repo().recordDeparture("g", "b", leftAt = 300L))
            assertEquals(300L, repo().leftAt("g", "b"))
        }

    @Test
    fun `leave drops the clocks, so a group re-created after leaving starts every member afresh`() =
        runTest {
            // ContactsViewModel.createGroup re-creates a group we left as a fresh row with the whole founding
            // roster; its clocks start at 0 with it, as they did when the lines went with the thread, and as on
            // knit-ios, whose re-created group is a fresh record.
            seedGroup("g", listOf("a", "b", "c"))
            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))
            repo().recordRejoin("g", "b", rejoinedAt = 200L, rekey = false)

            repo().leave("g")
            assertNull(db.groupDao().clocksOf("g", "b"))

            seedGroup("g", listOf("a", "b", "c")) // the row createGroup writes
            assertEquals(0L, repo().leftAt("g", "b"))
            assertTrue(repo().recordDeparture("g", "b", leftAt = 150L))
        }

    private suspend fun assertRetiredAt(epoch: Int) {
        val chain = checkNotNull(groupRatchet.sendChain("g")) { "the rekey dropped the epoch's number" }
        assertEquals(epoch, chain.epoch)
        assertTrue("the retired row holds no key", chain.isRetired && chain.chainKey.isEmpty() && chain.export.isEmpty())
        assertTrue("a retired chain is never re-served", groupRatchet.sendChains("g").isEmpty())
    }

    private fun sendChain(groupId: String) =
        GroupRatchetEngine.SendChain(
            groupId = groupId,
            epoch = 1,
            seed = ByteArray(32),
            chainKey = ByteArray(32),
            count = 3,
            mintedAt = 1L,
            export = ByteArray(32),
        )

    @Test
    fun `recordDeparture drops the leaver's scope config and keeps every other member's`() =
        runTest {
            seedGroup("g", listOf("a", "b", "c"))
            val configs = ScopeConfigRepository(db)
            for ((issuer, version) in listOf("b" to 3, "c" to 2)) {
                val config =
                    ScopeConfig(
                        "g",
                        "g",
                        issuer,
                        listOf("wss://$issuer.example/spool/v1"),
                        maxFrames = 400,
                        ttlMs = 1L,
                        maxBlob = 1,
                        version,
                    )
                assertEquals(ScopeConfigPolicy.Decision.STORE, configs.offer(config, receivedAt = 1L))
            }
            assertEquals("b", configs.winning("g")?.issuer)

            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))

            // A phone that heard b's leave before b's config refuses the config; this one, which heard them the other
            // way round, reads the same winner once the leave lands.
            assertEquals("c", configs.winning("g")?.issuer)
        }

    @Test
    fun `recordDeparture is a no-op for a non-member`() =
        runTest {
            seedGroup("g", listOf("a", "b"))

            assertFalse(repo().recordDeparture("g", "z", leftAt = 100L))

            assertEquals(listOf("a", "b"), GroupMembersStore.decode(db.groupDao().findById("g")!!.members))
            assertTrue(messages.observeNewestForConversation("g", 10).first().isEmpty())
        }

    @Test
    fun `recordDeparture is a no-op on a group we have already left`() =
        runTest {
            seedGroup("g", listOf("a", "b"), left = true)
            assertFalse(repo().recordDeparture("g", "b", leftAt = 100L))
        }

    @Test
    fun `leave tombstones the group and purges its messages`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            messages.upsert(MessageEntity(id = "m1", senderId = "a", conversationId = "g", body = "hi", sentAt = 1L))

            repo().leave("g")

            assertTrue(db.groupDao().findById("g")!!.left)
            assertFalse(messages.exists("m1"))
        }

    @Test
    fun `delete removes the group row, its messages and its member clocks`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            messages.upsert(MessageEntity(id = "m1", senderId = "a", conversationId = "g", body = "hi", sentAt = 1L))

            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))

            repo().delete("g")

            assertNull(db.groupDao().findById("g"))
            assertFalse(messages.exists("m1"))
            assertNull("the clocks go with the group", db.groupDao().clocksOf("g", "b"))
        }

    /**
     * The trap `observeGroupsWith` exists to avoid: its source, `GroupDao.observeAll`, has no `left = 0`
     * clause — only the suspend `allActive()` does — so a group you walked out of would keep listing itself
     * as one you have in common on the other person's profile.
     */
    @Test
    fun `observeGroupsWith lists only groups the member shares and we have not left`() =
        runTest {
            seedGroup("shared", listOf("me", "them"))
            seedGroup("left", listOf("me", "them"), left = true)
            seedGroup("theirsOnly", listOf("me", "someone-else"))

            val shared = repo().observeGroupsWith("them").first()

            assertEquals(listOf("shared"), shared.map { it.groupId })
        }

    /** The suspend and flow forms answer the same question, so a caller can swap one for the other. */
    @Test
    fun `observeGroupsWith agrees with groupsWith`() =
        runTest {
            seedGroup("a", listOf("me", "them"))
            seedGroup("b", listOf("me", "them"), left = true)
            seedGroup("c", listOf("me", "them"))

            val repo = repo()
            assertEquals(
                repo.groupsWith("them").map { it.groupId }.sorted(),
                repo
                    .observeGroupsWith("them")
                    .first()
                    .map { it.groupId }
                    .sorted(),
            )
        }

    /**
     * The seed-outbox rows a pre-2.6.0 device wrote toward *itself* (the self-pin loop) outlive the fix,
     * because only a departure or a leave removes one and we never depart our own groups. The start-up
     * sweep takes exactly those: every other member's row, in every group, is untouched.
     */
    @Test
    fun `forgetSelf drops only our own seed-outbox rows and counts them`() =
        runTest {
            seedGroup("g1", listOf("me", "a", "b"))
            seedGroup("g2", listOf("me", "a"))
            seedGroup("g3", listOf("me", "b"))
            groupRatchet.markKeySent("g1", "me", epoch = 1, at = 1L)
            groupRatchet.markKeySent("g1", "a", epoch = 1, at = 1L)
            groupRatchet.markKeySent("g2", "me", epoch = 2, at = 1L)
            groupRatchet.markKeySent("g2", "a", epoch = 2, at = 1L)

            assertEquals(2, repo().forgetSelf("me"))

            assertNull(groupRatchet.keySend("g1", "me"))
            assertNull(groupRatchet.keySend("g2", "me"))
            assertEquals(1, groupRatchet.keySend("g1", "a")?.sentEpoch)
            assertEquals(2, groupRatchet.keySend("g2", "a")?.sentEpoch)
            assertEquals("a second sweep is a no-op", 0, repo().forgetSelf("me"))
        }
}
