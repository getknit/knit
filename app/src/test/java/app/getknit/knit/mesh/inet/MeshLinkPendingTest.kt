package app.getknit.knit.mesh.inet

import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.inet.LinkDemandPolicy.Pending
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a direct link's demand reads (`docs/DIRECT_LINKS.md` L-7-7), against the real SQL: our DMs still unticked, and
 * for our group posts every member who has not acked them — never someone else's message, a status row, a DM still
 * waiting on its key, a message outside the window, or a group we left.
 */
class MeshLinkPendingTest : RoomDbTest() {
    private val messages by lazy { MessageRepository(db.messageDao()) }
    private val receipts by lazy { MessageReceiptRepository(db.messageReceiptDao(), messages, db) }
    private val groups = mutableMapOf<String, GroupEntity>()
    private val stuck = mutableSetOf<String>()
    private val pending by lazy { MeshLinkPending(messages, receipts, group = { groups[it] }, stuck = { _, hash -> hash in stuck }) }

    private fun dm(
        id: String,
        to: String,
        sentAt: Long,
        from: String = ME,
        received: Boolean = false,
        pendingKey: Boolean = false,
        kind: Int = MessageEntity.KIND_NORMAL,
    ) = MessageEntity(
        id = id,
        senderId = from,
        recipientId = to,
        conversationId = if (from == ME) to else from,
        body = "x",
        sentAt = sentAt,
        received = received,
        pendingKey = pendingKey,
        kind = kind,
    )

    private fun post(
        id: String,
        group: String,
        sentAt: Long,
        from: String = ME,
    ) = MessageEntity(id = id, senderId = from, conversationId = group, body = "x", sentAt = sentAt)

    private fun group(
        id: String,
        members: List<String>,
        left: Boolean = false,
    ) {
        groups[id] =
            GroupEntity(groupId = id, name = id, members = GroupMembersStore.encode(members), createdBy = ME, createdAt = 0, left = left)
    }

    @Test
    fun ourUntickedDmsInTheWindowAndNothingElse() =
        runTest {
            val dao = db.messageDao()
            dao.upsert(dm("m1", "bob", sentAt = 100))
            dao.upsert(dm("m2", "carol", sentAt = 100, received = true))
            dao.upsert(dm("m3", "dave", sentAt = 100, pendingKey = true))
            dao.upsert(dm("m4", "erin", sentAt = 100, kind = MessageEntity.KIND_CALL))
            dao.upsert(dm("m5", ME, sentAt = 100, from = "frank"))
            dao.upsert(dm("m6", "gus", sentAt = 10))

            assertEquals(listOf(Pending("bob", 100)), pending.between(ME, since = 50, until = 200))
        }

    @Test
    fun aGroupPostIsPendingForEveryMemberWhoHasNotAckedItButUs() =
        runTest {
            group("g-1", listOf(ME, "bob", "carol"))
            group("g-2", listOf(ME, "dave"), left = true)
            db.messageDao().upsert(post("p1", "g-1", sentAt = 100))
            db.messageDao().upsert(post("p2", "g-2", sentAt = 100))
            db.messageDao().upsert(post("p3", "g-1", sentAt = 100, from = "bob"))
            receipts.record("p1", "bob", DeliveryPlane.Internet, at = 110)

            assertEquals(listOf(Pending("carol", 100)), pending.between(ME, since = 50, until = 200))
        }

    @Test
    fun aDmAttachmentNoSpoolWillTakeIsPendingAtOnceAndNoOtherAttachment() =
        runTest {
            val dao = db.messageDao()
            dao.upsert(dm("m1", "bob", sentAt = 100, received = true).copy(attachmentHash = "H1"))
            dao.upsert(dm("m2", "carol", sentAt = 100, received = true).copy(attachmentHash = "H2"))
            dao.upsert(dm("m3", "dave", sentAt = 100, received = true))
            stuck += "H1"

            assertEquals(listOf(Pending("bob", 100, bulk = true)), pending.between(ME, since = 50, until = 200))
        }

    private companion object {
        const val ME = "me"
    }
}
