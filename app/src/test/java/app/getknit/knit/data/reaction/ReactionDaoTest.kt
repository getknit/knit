package app.getknit.knit.data.reaction

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
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
        dao.observeForMessage(id, ME, listOf(Conversations.NEARBY, Conversations.MESHTASTIC), Conversations.COMMONS_PREFIX + "*").first()

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
        dao.observeNewestOnOwnMessages(ME, blocked, rooms = listOf(Conversations.NEARBY, Conversations.MESHTASTIC), commonsGlob = "c-*")

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

    private companion object {
        const val ME = "me-node"
    }
}
