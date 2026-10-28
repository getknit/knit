package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.pin.PinEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.data.settings.SettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [LocalMessageDelete], the delete-for-me that the chat's long-press and the debug bridge's `PURGE` share,
 * against a **real** in-memory Room DB: every table that hangs off a message loses that message's rows, a neighbour
 * keeps its own, and the attachment blob goes only once nothing else names it.
 */
class LocalMessageDeleteTest : RoomDbTest() {
    private val settings = mockk<SettingsStore>(relaxed = true).also { every { it.ownAvatarHash } returns flowOf(null) }

    private fun delete(): LocalMessageDelete {
        val messages = MessageRepository(db.messageDao())
        return LocalMessageDelete(
            messages = messages,
            reactions = ReactionRepository(db.reactionDao(), db),
            receipts = MessageReceiptRepository(db.messageReceiptDao(), messages, db),
            blobs =
                BlobRepository(
                    blobs = db.blobDao(),
                    messages = db.messageDao(),
                    peers = db.peerDao(),
                    settings = settings,
                    verdicts = db.blobVerdictDao(),
                    groups = db.groupDao(),
                    forward = db.forwardDao(),
                    db = db,
                    savedFiles = db.savedFileDao(),
                    holders = db.attachmentHolderDao(),
                ),
            pins = PinRepository(db.pinDao(), db),
        )
    }

    /** A message in thread "bob" with a reaction, a pin and a delivery receipt, all keyed by [id]. */
    private suspend fun messageWithEverything(
        id: String,
        attachmentHash: String? = null,
    ) {
        db.messageDao().upsert(
            MessageEntity(id = id, senderId = "me", conversationId = "bob", body = id, sentAt = 1L, attachmentHash = attachmentHash),
        )
        db.reactionDao().upsert(ReactionEntity(id, "bob", emoji = "👍", updatedAt = 1L))
        db.pinDao().insert(PinEntity(id, pinnedAt = 1L))
        db.messageReceiptDao().insertIfAbsent(MessageReceiptEntity(id, "bob", 1L, DeliveryPlane.Nearby.code))
    }

    private suspend fun reactionsOn(id: String) =
        db
            .reactionDao()
            .observeForMessage(id)
            .first()
            .size

    private suspend fun receiptsFor(id: String) =
        db
            .messageReceiptDao()
            .observeForMessage(id)
            .first()
            .size

    private suspend fun blob(hash: String) = db.blobDao().insert(BlobEntity(hash, "image/jpeg", byteArrayOf(1, 2, 3)))

    @Test
    fun aDeletedMessageTakesItsReactionsPinReceiptsAndBlobWithItAndLeavesItsNeighbourAlone() =
        runTest {
            blob("h1")
            messageWithEverything("m1", attachmentHash = "h1")
            messageWithEverything("m2")

            delete().delete("m1", attachmentHash = "h1")

            assertNull(db.messageDao().findById("m1"))
            assertEquals(0, reactionsOn("m1"))
            assertFalse("the pin goes with its message", db.pinDao().isPinned("m1"))
            assertEquals(0, receiptsFor("m1"))
            assertFalse("nothing else names h1, so its blob goes", db.blobDao().exists("h1"))

            assertEquals("m2", db.messageDao().findById("m2")?.id)
            assertEquals(1, reactionsOn("m2"))
            assertTrue(db.pinDao().isPinned("m2"))
            assertEquals(1, receiptsFor("m2"))
        }

    @Test
    fun aBlobAnotherMessageStillNamesIsKept() =
        runTest {
            blob("shared")
            messageWithEverything("m1", attachmentHash = "shared")
            messageWithEverything("m2", attachmentHash = "shared")

            delete().delete("m1", attachmentHash = "shared")

            assertNull(db.messageDao().findById("m1"))
            assertTrue("m2 still shows the picture", db.blobDao().exists("shared"))
        }
}
