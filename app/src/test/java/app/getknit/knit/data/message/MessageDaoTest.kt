package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [MessageDao] SQL (finding #5): the `blobs` anti-join that drives attachment fetch and
 * the delivery-critical pending-key / received-flag mutations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDaoTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    @Test
    fun `hashesNeedingFetch returns referenced hashes not yet in blobs, deduped`() =
        runTest {
            dao.upsert(msg("m1", attachmentHash = "H1"))
            dao.upsert(msg("m2", attachmentHash = "H2")) // H2 is held below → excluded
            dao.upsert(msg("m3", attachmentHash = "H1")) // duplicate reference → H1 appears once
            dao.upsert(msg("m4", attachmentHash = null)) // no attachment → excluded
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(listOf("H1"), dao.hashesNeedingFetch())
        }

    @Test
    fun `pendingForRecipient returns only unsealed DMs, and clearPending removes them`() =
        runTest {
            dao.upsert(msg("p1", recipientId = "bob", pendingKey = true))
            dao.upsert(msg("p2", recipientId = "bob", pendingKey = true))
            dao.upsert(msg("sent", recipientId = "bob", pendingKey = false)) // already flooded → not pending
            dao.upsert(msg("other", recipientId = "carol", pendingKey = true)) // different recipient

            assertEquals(setOf("p1", "p2"), dao.pendingForRecipient("bob").map { it.id }.toSet())

            dao.clearPending("p1")
            assertEquals(setOf("p2"), dao.pendingForRecipient("bob").map { it.id }.toSet())
        }

    @Test
    fun `markReceived flips the delivery-ack flag`() =
        runTest {
            dao.upsert(msg("m1", received = false))
            dao.markReceived("m1")
            val stored = dao.observeAll().first().single { it.id == "m1" }
            assertTrue(stored.received)
        }

    @Test
    fun `recipientOf distinguishes a DM from a broadcast or absent message`() =
        runTest {
            dao.upsert(msg("dm", recipientId = "bob"))
            dao.upsert(msg("bc", recipientId = null))
            assertEquals("bob", dao.recipientOf("dm"))
            assertEquals(null, dao.recipientOf("bc"))
            assertEquals(null, dao.recipientOf("missing"))
        }

    @Test
    fun `deleteByConversation clears a whole thread`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1"))
            dao.upsert(msg("b", conversationId = "t1"))
            dao.upsert(msg("c", conversationId = "t2"))

            dao.deleteByConversation("t1")

            assertFalse(dao.exists("a"))
            assertTrue(dao.exists("c"))
        }

    private fun msg(
        id: String,
        recipientId: String? = null,
        conversationId: String = Conversations.NEARBY,
        attachmentHash: String? = null,
        received: Boolean = false,
        pendingKey: Boolean = false,
    ) = MessageEntity(
        id = id,
        senderId = "s",
        recipientId = recipientId,
        conversationId = conversationId,
        body = "",
        sentAt = 1L,
        received = received,
        attachmentHash = attachmentHash,
        pendingKey = pendingKey,
    )
}
