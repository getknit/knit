package app.getknit.knit.data

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
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
    private fun repo() = GroupRepository(db.groupDao(), MessageRepository(db.messageDao()), db)

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
            val notice = messages.observeForConversation("g").first().single()
            assertEquals(MessageEntity.KIND_MEMBER_LEFT, notice.kind)
            assertEquals("b", notice.senderId)
            assertEquals(100L, notice.sentAt)
        }

    @Test
    fun `recordDeparture is a no-op for a non-member`() =
        runTest {
            seedGroup("g", listOf("a", "b"))

            assertFalse(repo().recordDeparture("g", "z", leftAt = 100L))

            assertEquals(listOf("a", "b"), GroupMembersStore.decode(db.groupDao().findById("g")!!.members))
            assertTrue(messages.observeForConversation("g").first().isEmpty())
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
    fun `delete removes the group row and its messages`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            messages.upsert(MessageEntity(id = "m1", senderId = "a", conversationId = "g", body = "hi", sentAt = 1L))

            repo().delete("g")

            assertNull(db.groupDao().findById("g"))
            assertFalse(messages.exists("m1"))
        }
}
