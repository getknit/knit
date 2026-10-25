package app.getknit.knit.data.blob

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real SQL behind ADR 2026-10.vy27's proof: per (hash, holder), read from whichever end of the DM this node is, and
 * only for a message between the pair on a short-range plane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentHolderDaoTest : RoomDbTest() {
    private val dao get() = db.attachmentHolderDao()

    private suspend fun held(
        hash: String = H,
        peer: String = PEER,
    ) = dao.heldInPair(hash, ME, peer, DeliveryPlane.shortRangeCodes)

    @Test
    fun `the sender's proof is the recipient holding a message of ours`() =
        runTest {
            db.messageDao().upsert(msg("m1", sender = ME))
            assertFalse("no proof yet", held())

            dao.record(H, PEER, DeliveryPlane.Nearby.code, 5L)
            assertTrue(held())
            assertFalse("another recipient of the same sealed identity needs its own proof", held(peer = OTHER))
        }

    @Test
    fun `the recipient's proof is its own radio arrival of the peer's message`() =
        runTest {
            db.messageDao().upsert(msg("m1", sender = PEER))
            dao.record(H, ME, DeliveryPlane.Nearby.code, 5L)
            assertTrue(held())
            assertFalse("only the pair whose message names it", held(peer = OTHER))
        }

    @Test
    fun `a proof with no message between the pair, a holder on the wrong side or a long-range plane is no proof`() =
        runTest {
            dao.record(H, PEER, DeliveryPlane.Nearby.code, 5L)
            assertFalse("an avatar or a group picture names no message: it uploads as before", held())

            db.messageDao().upsert(msg("m1", sender = PEER))
            assertFalse("the peer holding its own send proves nothing about us", held())

            db.messageDao().upsert(msg("m2", sender = ME, hash = "H2"))
            dao.record("H2", PEER, DeliveryPlane.LoRa.code, 5L)
            dao.record("H2", OTHER, DeliveryPlane.Internet.code, 5L)
            assertFalse("a board carries no blob", held(hash = "H2"))
        }

    @Test
    fun `the first proof stands and goes with its blob`() =
        runTest {
            db.messageDao().upsert(msg("m1", sender = ME))
            dao.record(H, PEER, DeliveryPlane.Nearby.code, 5L)
            dao.record(H, PEER, DeliveryPlane.LoRa.code, 9L)
            assertTrue("a later write does not demote it", held())

            dao.delete(H)
            assertFalse(held())
        }

    /** The developer view's holder rows (ADR 2026-10.s7wg): every proof for the hash, oldest first, and no other hash's. */
    @Test
    fun `the holders of a hash are listed oldest first and no other hash's`() =
        runTest {
            dao.record(H, PEER, DeliveryPlane.WifiAware.code, 9L)
            dao.record(H, ME, DeliveryPlane.Bluetooth.code, 5L)
            dao.record("H2", OTHER, DeliveryPlane.Nearby.code, 1L)

            assertEquals(
                listOf(
                    AttachmentHolderEntity(H, ME, DeliveryPlane.Bluetooth.code, 5L),
                    AttachmentHolderEntity(H, PEER, DeliveryPlane.WifiAware.code, 9L),
                ),
                dao.holdersOf(H),
            )
            assertEquals(emptyList<AttachmentHolderEntity>(), dao.holdersOf("H3"))
        }

    private fun msg(
        id: String,
        sender: String,
        hash: String = H,
    ) = MessageEntity(
        id = id,
        senderId = sender,
        recipientId = if (sender == ME) PEER else ME,
        conversationId = PEER,
        body = "",
        sentAt = 1L,
        attachmentHash = hash,
    )

    private companion object {
        const val ME = "me"
        const val PEER = "peer"
        const val OTHER = "other"
        const val H = "H1"
    }
}
