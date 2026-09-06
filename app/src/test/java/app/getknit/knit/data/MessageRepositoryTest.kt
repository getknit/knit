package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
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
            repo.markReceived("m1", DeliveryPlane.Nearby)
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

    @Test
    fun `the window hands back the newest messages oldest-first, so the ascending shape is unchanged`() =
        runTest {
            val repo = repo()
            for (i in 1..1_000) repo.save(msg("m$i", sentAt = i.toLong()))

            val window = repo.observeNewestMessages(Conversations.NEARBY, 60).first()

            assertEquals(60, window.size)
            // The DAO reads newest-first; the repository reverses so every consumer of the old
            // `observeMessages` ordering — the rows, the read watermark, the room's channel name — is
            // looking at the same shape it always was, just less of it.
            assertEquals("oldest of the window leads", 941L, window.first().sentAt)
            assertEquals("newest of the thread trails", 1_000L, window.last().sentAt)
        }

    @Test
    fun `a new message rolls the window forward without widening it`() =
        runTest {
            val repo = repo()
            for (i in 1..1_000) repo.save(msg("m$i", sentAt = i.toLong()))
            assertEquals(
                1_000L,
                repo
                    .observeNewestMessages(Conversations.NEARBY, 60)
                    .first()
                    .last()
                    .sentAt,
            )

            repo.save(msg("m1001", sentAt = 1_001L))
            val after = repo.observeNewestMessages(Conversations.NEARBY, 60).first()

            // The cap is enforced by the query, not by trimming afterwards: an arriving message can never
            // push the window past its limit, which is what makes "fewer rows than asked for" a sound test
            // for "nothing older left to read".
            assertEquals(60, after.size)
            assertEquals(1_001L, after.last().sentAt)
            assertEquals(942L, after.first().sentAt)
        }

    @Test
    fun `trimming old history leaves the window untouched`() =
        runTest {
            val repo = repo()
            for (i in 1..1_000) repo.save(msg("m$i", sentAt = i.toLong()))

            // The retention sweep deletes from the far end of the thread. The window is anchored at the
            // newest end, so it simply pulls one more old row in and what the reader is looking at does
            // not move.
            db.messageDao().deleteOlderThan(Conversations.NEARBY, cutoff = 100L)
            val window = repo.observeNewestMessages(Conversations.NEARBY, 60).first()

            assertEquals(60, window.size)
            assertEquals(1_000L, window.last().sentAt)
        }

    @Test
    fun `depthOf reaches a message the window has not, and reports nothing for one that is gone`() =
        runTest {
            val repo = repo()
            for (i in 1..1_000) repo.save(msg("m$i", sentAt = i.toLong()))

            val depth = repo.depthOf(Conversations.NEARBY, "m200")
            assertTrue("far outside the opening window", depth > 60)
            assertTrue(repo.observeNewestMessages(Conversations.NEARBY, depth).first().any { it.id == "m200" })

            assertEquals(0, repo.depthOf(Conversations.NEARBY, "trimmed-away"))
        }

    private fun msg(
        id: String,
        recipientId: String? = null,
        conversationId: String = Conversations.NEARBY,
        attachmentHash: String? = null,
        attachmentKey: String? = null,
        received: Boolean = false,
        pendingKey: Boolean = false,
        sentAt: Long = 1L,
    ) = MessageEntity(
        id = id,
        senderId = "s",
        recipientId = recipientId,
        conversationId = conversationId,
        body = "",
        sentAt = sentAt,
        received = received,
        attachmentHash = attachmentHash,
        attachmentKey = attachmentKey,
        pendingKey = pendingKey,
    )
}
