package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the [MessageRepository] wrappers over the real [app.getknit.knit.data.message.MessageDao] SQL
 * (the DAO queries themselves are covered by `MessageDaoTest`; this pins the thin repository seam).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryTest : RoomDbTest() {
    private fun repo() = MessageRepository(db.messageDao())

    @Test
    fun `save then exists round-trips`() =
        runTest {
            val repo = repo()
            assertFalse(repo.exists("m1"))
            repo.save(msg("m1"))
            assertTrue(repo.exists("m1"))
        }

    @Test
    fun `observeMessages returns all and per-conversation threads`() =
        runTest {
            val repo = repo()
            repo.save(msg("a", conversationId = "t1"))
            repo.save(msg("b", conversationId = "t1"))
            repo.save(msg("c", conversationId = "t2"))

            val all = repo.observeMessages().first().map { it.id }
            assertEquals(setOf("a", "b", "c"), all.toSet())
            assertEquals(listOf("a", "b"), repo.observeMessages("t1").first().map { it.id })
        }

    @Test
    fun `recipientOf distinguishes a DM from a broadcast message`() =
        runTest {
            val repo = repo()
            repo.save(msg("dm", recipientId = "bob"))
            repo.save(msg("bc", recipientId = null))
            assertEquals("bob", repo.recipientOf("dm"))
            assertNull(repo.recipientOf("bc"))
            assertNull(repo.recipientOf("missing"))
        }

    @Test
    fun `markReceived flips the delivery-ack flag`() =
        runTest {
            val repo = repo()
            repo.save(msg("m1", received = false))
            repo.markReceived("m1")
            assertTrue(
                repo
                    .observeMessages()
                    .first()
                    .single()
                    .received,
            )
        }

    @Test
    fun `pendingForRecipient and clearPending track unsealed DMs`() =
        runTest {
            val repo = repo()
            repo.save(msg("p1", recipientId = "bob", pendingKey = true))
            repo.save(msg("p2", recipientId = "bob", pendingKey = true))

            val pending = repo.pendingForRecipient("bob").map { it.id }
            assertEquals(setOf("p1", "p2"), pending.toSet())
            repo.clearPending("p1")
            val remaining = repo.pendingForRecipient("bob").map { it.id }
            assertEquals(listOf("p2"), remaining)
        }

    @Test
    fun `delete removes a single message`() =
        runTest {
            val repo = repo()
            repo.save(msg("m1"))
            repo.delete("m1")
            assertFalse(repo.exists("m1"))
        }

    @Test
    fun `deleteByConversation clears a whole thread`() =
        runTest {
            val repo = repo()
            repo.save(msg("a", conversationId = "t1"))
            repo.save(msg("b", conversationId = "t2"))
            repo.deleteByConversation("t1")
            assertFalse(repo.exists("a"))
            assertTrue(repo.exists("b"))
        }

    @Test
    fun `countByAttachmentHash tracks live references`() =
        runTest {
            val repo = repo()
            repo.save(msg("a", attachmentHash = "H1"))
            repo.save(msg("b", attachmentHash = "H1"))
            repo.save(msg("c", attachmentHash = null))
            assertEquals(2, repo.countByAttachmentHash("H1"))
            assertEquals(0, repo.countByAttachmentHash("nope"))
        }

    @Test
    fun `attachmentKeyForHash returns the stored per-attachment key`() =
        runTest {
            val repo = repo()
            repo.save(msg("enc", attachmentHash = "H1", attachmentKey = "base64key"))
            assertEquals("base64key", repo.attachmentKeyForHash("H1"))
            assertNull(repo.attachmentKeyForHash("missing"))
        }

    @Test
    fun `hashesNeedingFetch returns referenced hashes not yet held`() =
        runTest {
            val repo = repo()
            repo.save(msg("m1", attachmentHash = "H1"))
            repo.save(msg("m2", attachmentHash = "H2"))
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))
            assertEquals(listOf("H1"), repo.hashesNeedingFetch())
        }

    private fun msg(
        id: String,
        recipientId: String? = null,
        conversationId: String = Conversations.NEARBY,
        attachmentHash: String? = null,
        attachmentKey: String? = null,
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
        attachmentKey = attachmentKey,
        pendingKey = pendingKey,
    )
}
