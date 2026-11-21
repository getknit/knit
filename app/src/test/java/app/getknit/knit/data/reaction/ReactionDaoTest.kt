package app.getknit.knit.data.reaction

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.MeshPostOrigin
import app.getknit.knit.ui.group
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [ReactionDao] SQL (finding #5). The headline is [ReactionDao.deleteOrphansOlderThan] —
 * an anti-join against `messages` (there is deliberately no FK cascade) gated by an age floor that spares a
 * reaction which legitimately arrived before its target message via out-of-order mesh delivery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionDaoTest : RoomDbTest() {
    private val dao get() = db.reactionDao()
    private val meshcore get() = db.meshcoreReactionDao()

    @Test
    fun `deleteOrphansOlderThan reclaims only old reactions whose message is gone`() =
        runTest {
            db.messageDao().upsert(MessageEntity(id = "m_live", senderId = "s", body = "", sentAt = 1L))
            dao.upsert(ReactionEntity("m_live", "u", emoji = "👍", updatedAt = 1L)) // message present → kept
            dao.upsert(ReactionEntity("m_gone_old", "u", emoji = "👍", updatedAt = 1L)) // orphan, old → deleted
            dao.upsert(ReactionEntity("m_gone_new", "u", emoji = "👍", updatedAt = 9L)) // orphan but newer than floor

            dao.deleteOrphansOlderThan(olderThan = 5L)

            val remaining = dao.observeAll().first().map { it.messageId }
            assertEquals(setOf("m_live", "m_gone_new"), remaining.toSet())
        }

    @Test
    fun `deleteOffRosterOlderThan reclaims only an old reaction on a held group post from someone off its roster`() =
        runTest {
            db.groupDao().upsert(group(groupId = "g-1", members = listOf("bob")).copy(departed = GroupMembersStore.encode(listOf("dan"))))
            val messages = db.messageDao()
            messages.upsert(MessageEntity(id = "gm", senderId = "bob", conversationId = "g-1", body = "", sentAt = 1L))
            messages.upsert(MessageEntity(id = "unheld-group", senderId = "bob", conversationId = "g-2", body = "", sentAt = 1L))
            messages.upsert(MessageEntity(id = "dm", senderId = "alice", conversationId = "alice", body = "", sentAt = 1L))
            messages.upsert(MessageEntity(id = "room", senderId = "zed", body = "", sentAt = 1L))
            listOf("mallory", "bo", "bob", "dan", ME).forEach { dao.upsert(ReactionEntity("gm", it, emoji = "👍", updatedAt = 1L)) }
            dao.upsert(ReactionEntity("gm", "erin", emoji = "👍", updatedAt = 9L)) // off the roster, but inside the grace
            dao.upsert(ReactionEntity("gm", "frank", emoji = null, updatedAt = 1L)) // a tombstone off the roster
            listOf("unheld-group", "dm", "room", "gone").forEach { dao.upsert(ReactionEntity(it, "mallory", emoji = "👍", updatedAt = 1L)) }

            dao.deleteOffRosterOlderThan(olderThan = 5L, me = ME)

            assertNull("a tombstone off the roster goes too", dao.updatedAtFor("gm", "frank"))
            val left =
                dao
                    .observeAll()
                    .first()
                    .map { it.messageId to it.reactorNodeId }
                    .toSet()
            assertEquals(
                setOf(
                    "gm" to "bob", // a member
                    "gm" to "dan", // departed
                    "gm" to ME, // ours, on the roster or not
                    "gm" to "erin", // a lag the grace still covers
                    "unheld-group" to "mallory", // no group row to judge it by
                    "dm" to "mallory", // not a group post
                    "room" to "mallory",
                    "gone" to "mallory", // an orphan, deleteOrphansOlderThan's
                ),
                left,
            )
        }

    @Test
    fun `observeAll hides retraction tombstones (emoji IS NULL)`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("m2", "u", emoji = null, updatedAt = 1L)) // retracted → hidden from the UI stream
            assertEquals(listOf("m1"), dao.observeAll().first().map { it.messageId })
        }

    @Test
    fun `observeForMessage returns only that message's live reactions, oldest first`() =
        runTest {
            db.messageDao().upsert(MessageEntity(id = "m1", senderId = "s", body = "", sentAt = 1L)) // a room post
            dao.upsert(ReactionEntity("m1", "u2", emoji = "❤️", updatedAt = 20L))
            dao.upsert(ReactionEntity("m1", "u1", emoji = "👍", updatedAt = 10L))
            dao.upsert(ReactionEntity("m1", "u3", emoji = null, updatedAt = 30L)) // retracted → hidden
            dao.upsert(ReactionEntity("m2", "u1", emoji = "😂", updatedAt = 15L)) // another message → excluded

            val rows = forMessage("m1")

            assertEquals(listOf("u1", "u2"), rows.map { it.reactorNodeId })
            assertEquals(listOf("👍", "❤️"), rows.map { it.emoji })
        }

    @Test
    fun `observeForMessage is empty for a message no one has reacted to`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u1", emoji = "👍", updatedAt = 1L))
            assertTrue(forMessage("m_none").isEmpty())
        }

    @Test
    fun `observeForMessage leaves out a reactor from outside the thread whose reaction landed before the message`() =
        runTest {
            // The reaction lands first, an orphan; then the DM it names.
            dao.upsert(ReactionEntity("dm", "stranger", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("dm", "alice", emoji = "❤️", updatedAt = 2L))
            dao.upsert(ReactionEntity("dm", ME, emoji = "😂", updatedAt = 3L))
            db.messageDao().upsert(
                MessageEntity(id = "dm", senderId = ME, recipientId = "alice", conversationId = "alice", body = "", sentAt = 1L),
            )

            assertEquals(listOf("alice", ME), forMessage("dm").map { it.reactorNodeId })
        }

    private suspend fun forMessage(id: String) =
        dao
            .observeForMessage(
                id,
                ME,
                listOf(Conversations.NEARBY, Conversations.MESHTASTIC),
                Conversations.COMMONS_PREFIX + "*",
                Conversations.MESHCORE_PUBLIC,
            ).first()

    @Test
    fun `upsert replaces on the composite key (messageId, reactorNodeId)`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("m1", "u", emoji = "❤️", updatedAt = 2L)) // same PK → replace, not a 2nd row

            assertEquals("❤️", dao.emojiFor("m1", "u"))
            assertEquals(2L, dao.updatedAtFor("m1", "u"))
            assertEquals(1, dao.observeAll().first().size)
        }

    private fun heads(blocked: List<String> = emptyList()) =
        dao.observeNewestOnOwnMessages(
            ME,
            blocked,
            rooms = listOf(Conversations.NEARBY, Conversations.MESHTASTIC),
            commonsGlob = "c-*",
            meshcoreRoom = Conversations.MESHCORE_PUBLIC,
        )

    @Test
    fun `observeNewestOnOwnMessages heads each thread with the newest live reaction someone made to our words`() =
        runTest {
            val messages = db.messageDao()
            db.groupDao().upsert(group(groupId = "g-hikers", members = listOf(ME, "bob", "carol", "mallory")))
            messages.upsert(MessageEntity(id = "mine-dm", senderId = ME, conversationId = "bob", body = "lunch?", sentAt = 1L))
            messages.upsert(MessageEntity(id = "theirs-dm", senderId = "bob", conversationId = "bob", body = "sure", sentAt = 2L))
            messages.upsert(MessageEntity(id = "mine-g", senderId = ME, conversationId = "g-hikers", body = "trail?", sentAt = 1L))
            messages.upsert(
                MessageEntity(id = "mine-n", senderId = ME, conversationId = Conversations.NEARBY, body = "anyone?", sentAt = 1L),
            )
            // A heard Meshtastic post sits in our sender column by convention, and a notice is not a message.
            messages.upsert(
                MessageEntity(id = "heard", senderId = ME, conversationId = "g-hikers", body = "hi", sentAt = 3L, originNode = 0x1234L),
            )
            messages.upsert(
                MessageEntity(
                    id = "notice",
                    senderId = ME,
                    conversationId = "g-hikers",
                    body = "",
                    sentAt = 4L,
                    kind = MessageEntity.KIND_MEMBER_LEFT,
                ),
            )
            dao.upsert(ReactionEntity("mine-dm", "bob", emoji = "👍", updatedAt = 10L)) // the DM thread's head
            dao.upsert(ReactionEntity("mine-dm", "carol", emoji = "❤️", updatedAt = 20L)) // not the DM's peer: never
            dao.upsert(ReactionEntity("mine-dm", ME, emoji = "😂", updatedAt = 30L)) // our own: never
            dao.upsert(ReactionEntity("theirs-dm", "bob", emoji = "🎉", updatedAt = 60L)) // not our words
            dao.upsert(ReactionEntity("mine-g", "bob", emoji = "🔥", updatedAt = 5L))
            dao.upsert(ReactionEntity("mine-g", "carol", emoji = "🎸", updatedAt = 6L)) // the group thread's head
            dao.upsert(ReactionEntity("mine-g", "mallory", emoji = "👎", updatedAt = 40L)) // blocked: never
            dao.upsert(ReactionEntity("mine-g", "dave", emoji = "🎉", updatedAt = 41L)) // not on the roster: never
            dao.upsert(ReactionEntity("mine-g", ME, emoji = "😂", updatedAt = 45L)) // on the roster, but our own: never
            dao.upsert(ReactionEntity("mine-g", "erin", emoji = null, updatedAt = 50L)) // a tombstone: never
            dao.upsert(ReactionEntity("mine-n", "zed", emoji = "👋", updatedAt = 3L)) // a room: anyone
            dao.upsert(ReactionEntity("heard", "bob", emoji = "👍", updatedAt = 70L))
            dao.upsert(ReactionEntity("notice", "bob", emoji = "👍", updatedAt = 80L))
            dao.upsert(ReactionEntity("gone", "bob", emoji = "👍", updatedAt = 90L)) // an orphan

            val heads = heads(blocked = listOf("mallory")).first()

            assertEquals(
                setOf(
                    listOf("bob", "mine-dm", "bob", "👍", "10"),
                    listOf("g-hikers", "mine-g", "carol", "🎸", "6"),
                    listOf(Conversations.NEARBY, "mine-n", "zed", "👋", "3"),
                ),
                heads.map { listOf(it.target.conversationId, it.target.id, it.reactorNodeId, it.emoji, it.reactedAt.toString()) }.toSet(),
            )
        }

    /**
     * A MeshCore room post sits in our sender column under the sentinel `originNode` (ADR 2026-11.gsqu's Stage 6
     * amendment, security B1): a reaction to it is never a reaction to our words — the SQL's own `originNode` guard, beneath
     * `ReactionRepository`'s room list.
     */
    @Test
    fun `observeNewestOnOwnMessages never takes a MeshCore room post for our words`() =
        runTest {
            val room = Conversations.MESHCORE_PUBLIC
            db.messageDao().upsert(
                MessageEntity(
                    id = "heard",
                    senderId = ME,
                    conversationId = room,
                    body = "hi",
                    sentAt = 1L,
                    originNode = MeshPostOrigin.MESHCORE_NODE,
                ),
            )
            db.messageDao().upsert(MessageEntity(id = "mine", senderId = ME, conversationId = room, body = "hello", sentAt = 2L))
            dao.upsert(ReactionEntity("heard", "zed", emoji = "👍", updatedAt = 10L))
            dao.upsert(ReactionEntity("mine", "zed", emoji = "👋", updatedAt = 5L))

            val heads =
                dao
                    .observeNewestOnOwnMessages(
                        ME,
                        emptyList(),
                        rooms = listOf(room),
                        commonsGlob = "c-*",
                        meshcoreRoom = room,
                    ).first()

            assertEquals(listOf("mine"), heads.map { it.target.id })
        }

    @Test
    fun `observeNewestOnOwnMessages takes a blocked reactor back once unblocked`() =
        runTest {
            db.messageDao().upsert(MessageEntity(id = "mine", senderId = ME, conversationId = "bob", body = "hi", sentAt = 1L))
            dao.upsert(ReactionEntity("mine", "bob", emoji = "👎", updatedAt = 10L))

            assertTrue(heads(blocked = listOf("bob")).first().isEmpty())
            assertEquals(listOf("bob"), heads().first().map { it.reactorNodeId })
        }

    @Test
    fun `observeNewestOnOwnMessages counts a commons member as a room's reader`() =
        runTest {
            db.messageDao().upsert(MessageEntity(id = "mine", senderId = ME, conversationId = "c-club", body = "hi", sentAt = 1L))
            dao.upsert(ReactionEntity("mine", "zed", emoji = "👋", updatedAt = 10L))

            assertEquals(listOf("zed"), heads().first().map { it.reactorNodeId })
        }

    @Test
    fun `deleteForMessage drops every reaction on a message`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u1", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("m1", "u2", emoji = "❤️", updatedAt = 1L))
            dao.upsert(ReactionEntity("m2", "u1", emoji = "👍", updatedAt = 1L))

            dao.deleteForMessage("m1")

            assertEquals(listOf("m2"), dao.observeAll().first().map { it.messageId })
            assertTrue(dao.emojiFor("m1", "u1") == null)
        }

    /**
     * The MeshCore room draws us and the channel's claimed names (`mc:`) and nobody else (ADR 2026-11.gsqu's 2026-11-21
     * amendment): a Knit identity's reaction to a row id anyone can derive from the air is never drawn, even one stored
     * before the row landed. The room is passed as the MeshCore clause alone, never in `rooms`, as the repository does.
     */
    @Test
    fun `the MeshCore room draws us and claimed names, never a Knit reactor, even one stored before the row`() =
        runTest {
            val room = Conversations.MESHCORE_PUBLIC
            // A Knit fork's reaction lands first, an orphan; then the post it names.
            dao.upsert(ReactionEntity("heard", "a1b2c3d4", emoji = "👎", updatedAt = 1L))
            db.messageDao().upsert(
                heardRow("heard", room, MeshPostOrigin.MESHCORE_NODE),
            )
            dao.upsert(ReactionEntity("heard", "mc:Bob", emoji = "👍", updatedAt = 2L))
            dao.upsert(ReactionEntity("heard", ME, emoji = "❤️", updatedAt = 3L))

            assertEquals(listOf("mc:Bob", ME), meshcoreDrawn("heard"))
            assertEquals(
                listOf("mc:Bob", ME),
                dao
                    .observeForConversation(room, ME, listOf(Conversations.NEARBY), "c-*", room)
                    .first()
                    .map { it.reactorNodeId },
            )
        }

    @Test
    fun `the Meshtastic room draws only us`() =
        runTest {
            val room = Conversations.MESHTASTIC
            db.messageDao().upsert(heardRow("heard", room, MESHTASTIC_NODE))
            dao.upsert(ReactionEntity("heard", "mc:Bob", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("heard", "a1b2c3d4", emoji = "👎", updatedAt = 2L))
            dao.upsert(ReactionEntity("heard", ME, emoji = "❤️", updatedAt = 3L))

            assertEquals(listOf(ME), meshcoreDrawn("heard"))
        }

    @Test
    fun `a claimed name heads the MeshCore room's line on our own post, a Knit reactor never does`() =
        runTest {
            val room = Conversations.MESHCORE_PUBLIC
            db.messageDao().upsert(MessageEntity(id = "mine", senderId = ME, conversationId = room, body = "hello", sentAt = 1L))
            dao.upsert(ReactionEntity("mine", "mc:Bob", emoji = "👍", updatedAt = 10L))
            dao.upsert(ReactionEntity("mine", "a1b2c3d4", emoji = "👎", updatedAt = 20L)) // newer, but a Knit identity

            val heads =
                dao
                    .observeNewestOnOwnMessages(
                        ME,
                        emptyList(),
                        rooms = listOf(Conversations.NEARBY),
                        commonsGlob = "c-*",
                        meshcoreRoom = room,
                    ).first()

            assertEquals(listOf("mc:Bob" to "👍"), heads.map { it.reactorNodeId to it.emoji })
        }

    @Test
    fun `the per-post eviction takes the oldest other claimed name and never a Knit reactor`() =
        runTest {
            dao.upsert(ReactionEntity("p", ME, emoji = "❤️", updatedAt = 1L)) // ours, oldest of all
            dao.upsert(ReactionEntity("p", "a1b2c3d4", emoji = "👎", updatedAt = 2L))
            dao.upsert(ReactionEntity("p", "mc:Old", emoji = "👍", updatedAt = 3L))
            dao.upsert(ReactionEntity("p", "mc:Mid", emoji = "👍", updatedAt = 4L))
            dao.upsert(ReactionEntity("p", "mc:New", emoji = "👍", updatedAt = 5L))
            dao.upsert(ReactionEntity("q", "mc:Elsewhere", emoji = "👍", updatedAt = 0L)) // another post

            assertEquals(3, meshcore.meshcoreReactorsOn("p"))
            assertEquals(4, meshcore.meshcoreReactions())

            assertEquals(1, meshcore.evictOldestMeshcoreOn("p", keep = "mc:Old")) // the one kept is passed over
            assertNull(dao.updatedAtFor("p", "mc:Mid"))
            assertEquals(1, meshcore.evictOldestMeshcoreOn("p", keep = "mc:New"))
            assertNull(dao.updatedAtFor("p", "mc:Old"))
            assertEquals("only the kept claimed name is left", 0, meshcore.evictOldestMeshcoreOn("p", keep = "mc:New"))
            assertEquals(setOf(ME, "a1b2c3d4", "mc:New"), reactorsOn("p"))
            assertEquals(setOf("mc:Elsewhere"), reactorsOn("q"))
        }

    @Test
    fun `the phone-wide eviction takes the oldest claimed name anywhere but the one kept`() =
        runTest {
            dao.upsert(ReactionEntity("p", "a1b2c3d4", emoji = "👎", updatedAt = 0L))
            dao.upsert(ReactionEntity("p", "mc:Kept", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("q", "mc:Oldest", emoji = "👍", updatedAt = 2L))
            dao.upsert(ReactionEntity("p", "mc:Newer", emoji = "👍", updatedAt = 3L))

            assertEquals(1, meshcore.evictOldestMeshcore(keepMessage = "p", keepReactor = "mc:Kept"))

            assertNull(dao.updatedAtFor("q", "mc:Oldest"))
            assertEquals(setOf("a1b2c3d4", "mc:Kept", "mc:Newer"), reactorsOn("p"))
        }

    @Test
    fun `deleteOffRoomOlderThan reaps an old Knit reactor on a bridged room's post and keeps us and claimed names`() =
        runTest {
            val messages = db.messageDao()
            messages.upsert(heardRow("mc", Conversations.MESHCORE_PUBLIC, MeshPostOrigin.MESHCORE_NODE))
            messages.upsert(heardRow("mt", Conversations.MESHTASTIC, MESHTASTIC_NODE))
            messages.upsert(MessageEntity(id = "near", senderId = "zed", conversationId = Conversations.NEARBY, body = "", sentAt = 1L))
            listOf("mc", "mt", "near").forEach { id ->
                listOf("a1b2c3d4", ME, "mc:Bob").forEach { dao.upsert(ReactionEntity(id, it, emoji = "👍", updatedAt = 1L)) }
            }
            dao.upsert(ReactionEntity("mc", "e5f6a7b8", emoji = "👍", updatedAt = 9L)) // inside the grace

            dao.deleteOffRoomOlderThan(
                olderThan = 5L,
                me = ME,
                bridged = listOf(Conversations.MESHTASTIC, Conversations.MESHCORE_PUBLIC),
                meshcoreRoom = Conversations.MESHCORE_PUBLIC,
            )

            assertEquals(setOf(ME, "mc:Bob", "e5f6a7b8"), reactorsOn("mc"))
            assertEquals("a claimed name is the MeshCore room's alone", setOf(ME), reactorsOn("mt"))
            assertEquals("not a bridged room", setOf("a1b2c3d4", ME, "mc:Bob"), reactorsOn("near"))
        }

    /** A post a bridged room's board heard: in our sender column by convention, under [node]'s origin. */
    private fun heardRow(
        id: String,
        room: String,
        node: Long,
    ) = MessageEntity(id = id, senderId = ME, conversationId = room, body = "hi", sentAt = 1L, originNode = node)

    private suspend fun meshcoreDrawn(id: String) =
        dao.observeForMessage(id, ME, listOf(Conversations.NEARBY), "c-*", Conversations.MESHCORE_PUBLIC).first().map { it.reactorNodeId }

    private suspend fun reactorsOn(id: String) =
        dao
            .observeAll()
            .first()
            .filter { it.messageId == id }
            .map { it.reactorNodeId }
            .toSet()

    private companion object {
        const val ME = "me-node"
        const val MESHTASTIC_NODE = 0x1234L
    }
}
