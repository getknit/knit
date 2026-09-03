package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `markReceived flips the delivery-ack flag and records the receipt's plane`() =
        runTest {
            dao.upsert(msg("m1", received = false))
            dao.markReceived("m1", DeliveryPlane.Nearby.code)
            val nearby = dao.observeAll().first().single { it.id == "m1" }
            assertTrue(nearby.received)
            assertEquals(DeliveryPlane.Nearby, nearby.receivedPlane)

            dao.upsert(msg("m2", received = false))
            dao.markReceived("m2", DeliveryPlane.Internet.code)
            val relayed = dao.observeAll().first().single { it.id == "m2" }
            assertTrue(relayed.received)
            assertEquals(DeliveryPlane.Internet, relayed.receivedPlane)
        }

    @Test
    fun `markReceived keeps the plane of the receipt that first flipped the tick`() =
        runTest {
            // A receipt is re-served routinely, and the duplicate can cross on the other plane. The mark
            // must keep describing the delivery that actually happened, in both directions.
            dao.upsert(msg("nearby-first", received = false))
            dao.markReceived("nearby-first", DeliveryPlane.Nearby.code)
            dao.markReceived("nearby-first", DeliveryPlane.Internet.code)
            assertEquals(
                DeliveryPlane.Nearby,
                dao
                    .observeAll()
                    .first()
                    .single { it.id == "nearby-first" }
                    .receivedPlane,
            )

            dao.upsert(msg("relay-first", received = false))
            dao.markReceived("relay-first", DeliveryPlane.Internet.code)
            dao.markReceived("relay-first", DeliveryPlane.Nearby.code)
            assertEquals(
                DeliveryPlane.Internet,
                dao
                    .observeAll()
                    .first()
                    .single { it.id == "relay-first" }
                    .receivedPlane,
            )
        }

    @Test
    fun `insertIfAbsent leaves an existing row untouched`() =
        runTest {
            // The inbound write: a re-served frame is the same signed bytes, so the first row for an id is
            // the only one that ever should be — its arrival plane, its tick, and what was added since.
            dao.upsert(
                msg("m1", received = true).copy(
                    receivedVia = DeliveryPlane.LoRa.code,
                    arrivedAt = 111L,
                    voiceDurationMs = 1_500,
                ),
            )

            assertEquals(-1L, dao.insertIfAbsent(msg("m1", received = false).copy(arrivedAt = 999L)))

            val row = dao.observeAll().first().single { it.id == "m1" }
            assertTrue(row.received)
            assertEquals(DeliveryPlane.LoRa, row.receivedPlane)
            // The first crossing is the one that describes when the message actually got here; a re-serve
            // hours later must not restamp it, which is the whole reason this write is IGNORE and not upsert.
            assertEquals(111L, row.arrivedAt)
            assertEquals(1_500, row.voiceDurationMs)
        }

    @Test
    fun `insertIfAbsent inserts a new row`() =
        runTest {
            assertTrue(dao.insertIfAbsent(msg("m2").copy(receivedVia = DeliveryPlane.LoRa.code)) != -1L)
            assertEquals(
                DeliveryPlane.LoRa,
                dao
                    .observeAll()
                    .first()
                    .single { it.id == "m2" }
                    .receivedPlane,
            )
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

    @Test
    fun `countMineIn counts only the local user's messages in a thread`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t", sender = "me"))
            dao.upsert(msg("b", conversationId = "t", sender = "them"))
            dao.upsert(msg("c", conversationId = "other", sender = "me"))
            assertEquals(1, dao.countMineIn("t", "me"))
            assertEquals(0, dao.countMineIn("empty", "me"))
        }

    @Test
    fun `conversationsIAuthoredIn returns distinct threads the user posted in`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1", sender = "me"))
            dao.upsert(msg("b", conversationId = "t1", sender = "me"))
            dao.upsert(msg("c", conversationId = "t2", sender = "me"))
            dao.upsert(msg("d", conversationId = "t3", sender = "them"))
            assertEquals(setOf("t1", "t2"), dao.conversationsIAuthoredIn("me").toSet())
        }

    @Test
    fun `distinctConversations returns every thread id once, regardless of sender`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1", sender = "me"))
            dao.upsert(msg("b", conversationId = "t1", sender = "them")) // same thread, different sender
            dao.upsert(msg("c", conversationId = "t2", sender = "them"))
            dao.upsert(msg("d", conversationId = Conversations.NEARBY, sender = "them"))
            assertEquals(setOf("t1", "t2", Conversations.NEARBY), dao.distinctConversations().toSet())
        }

    @Test
    fun `deleteOldestInConversation keeps only the newest N by sentAt`() =
        runTest {
            (1..5).forEach { dao.upsert(msg("m$it", conversationId = "t", sentAt = it.toLong())) }
            dao.deleteOldestInConversation("t", keep = 2)
            assertEquals(
                setOf("m4", "m5"),
                dao
                    .observeForConversation("t")
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `deleteOlderThan drops messages before the cutoff in that thread only`() =
        runTest {
            dao.upsert(msg("old", conversationId = "t", sentAt = 10L))
            dao.upsert(msg("new", conversationId = "t", sentAt = 100L))
            dao.upsert(msg("other", conversationId = "u", sentAt = 1L))
            dao.deleteOlderThan("t", cutoff = 50L)
            assertFalse(dao.exists("old"))
            assertTrue(dao.exists("new"))
            assertTrue(dao.exists("other")) // a different thread is untouched
        }

    @Test
    fun `conversationActivity reports per-thread count and newest sentAt`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t", sentAt = 5L))
            dao.upsert(msg("b", conversationId = "t", sentAt = 9L))
            dao.upsert(msg("c", conversationId = "u", sentAt = 3L))
            val byId = dao.conversationActivity().associateBy { it.conversationId }
            assertEquals(2, byId["t"]!!.count)
            assertEquals(9L, byId["t"]!!.lastSentAt)
            assertEquals(1, byId["u"]!!.count)
        }

    @Suppress("LongParameterList") // a test data builder — optional params with defaults, not a real API surface
    @Test
    fun `observeById follows one message and goes null once it is deleted`() =
        runTest {
            dao.upsert(msg("m1", sender = "sam", sentAt = 7L))
            dao.upsert(msg("m2"))

            val loaded = dao.observeById("m1").first()
            assertEquals("sam", loaded?.senderId)
            assertEquals(7L, loaded?.sentAt)

            dao.deleteById("m1")
            assertNull(dao.observeById("m1").first())
        }

    @Test
    fun `sendersIn ignores status notices, whose senderId is a subject rather than an author`() =
        runTest {
            dao.upsert(msg("m1", conversationId = "g-1", sender = "spoke"))
            dao.upsert(msg("n1", conversationId = "g-1", sender = "renamed", kind = MessageEntity.KIND_PEER_RENAMED))
            dao.upsert(msg("n2", conversationId = "g-1", sender = "departed", kind = MessageEntity.KIND_MEMBER_LEFT))

            // This list feeds Conversations.isAccepted. Counting a notice's subject as a sender would let
            // someone who merely renamed themselves — or left — promote a stranger's thread out of the
            // message-request inbox without ever having said anything in it.
            assertEquals(listOf("spoke"), dao.sendersIn("g-1"))
        }

    @Test
    fun `hasMessagesIn is satisfied by an ordinary message and never by a notice alone`() =
        runTest {
            dao.upsert(msg("n1", conversationId = "quiet", kind = MessageEntity.KIND_PEER_RENAMED))
            // A notice must not satisfy the gate that decides whether to write a notice, or the first one
            // would license the next and a stranger's rename would still conjure a thread.
            assertFalse(dao.hasMessagesIn("quiet"))
            assertFalse(dao.hasMessagesIn("empty"))

            dao.upsert(msg("m1", conversationId = "quiet"))
            assertTrue(dao.hasMessagesIn("quiet"))
        }

    @Test
    fun `observeAcquaintedPeers needs a message each way in a DM, so a one-sided thread does not count`() =
        runTest {
            // A DM thread is keyed by the other party, so "ours" is the row we sent and "theirs" is the row
            // whose sender is the thread id itself.
            dao.upsert(msg("d1", conversationId = "bob", sender = ME, recipientId = "bob"))
            dao.upsert(msg("d2", conversationId = "bob", sender = "bob", recipientId = ME))
            dao.upsert(msg("d3", conversationId = "carol", sender = ME, recipientId = "carol")) // no reply yet
            dao.upsert(msg("d4", conversationId = "dave", sender = "dave", recipientId = ME)) // never answered

            assertEquals(listOf("bob"), acquainted())
        }

    @Test
    fun `observeAcquaintedPeers counts a group both of us posted in, and never one we stayed quiet in`() =
        runTest {
            dao.upsert(msg("g1", conversationId = "g-book", sender = ME))
            dao.upsert(msg("g2", conversationId = "g-book", sender = "erin"))
            dao.upsert(msg("g3", conversationId = "g-book", sender = "frank"))
            // A group we were added to but never spoke in: everyone in it is still a stranger.
            dao.upsert(msg("g4", conversationId = "g-silent", sender = "gwen"))

            assertEquals(listOf("erin", "frank"), acquainted())
        }

    @Test
    fun `observeAcquaintedPeers ignores the Nearby room and status notices`() =
        runTest {
            // Posting in the same public room is not a conversation, and a notice's sender is the event's
            // subject rather than an author — neither may pass for having met someone.
            dao.upsert(msg("n1", conversationId = Conversations.NEARBY, sender = ME))
            dao.upsert(msg("n2", conversationId = Conversations.NEARBY, sender = "hal"))
            dao.upsert(msg("k1", conversationId = "g-book", sender = ME, kind = MessageEntity.KIND_PEER_RENAMED))
            dao.upsert(msg("k2", conversationId = "g-book", sender = "iris", kind = MessageEntity.KIND_MEMBER_LEFT))
            dao.upsert(msg("k3", conversationId = "jane", sender = ME, recipientId = "jane"))
            dao.upsert(msg("k4", conversationId = "jane", sender = "jane", kind = MessageEntity.KIND_PEER_RENAMED))

            assertEquals(emptyList<String>(), acquainted())
        }

    private suspend fun acquainted(): List<String> =
        dao
            .observeAcquaintedPeers(
                me = ME,
                nearbyId = Conversations.NEARBY,
                groupPattern = Conversations.GROUP_ID_PREFIX + "%",
            ).first()
            .sorted()

    private fun msg(
        id: String,
        recipientId: String? = null,
        conversationId: String = Conversations.NEARBY,
        attachmentHash: String? = null,
        received: Boolean = false,
        pendingKey: Boolean = false,
        sender: String = "s",
        sentAt: Long = 1L,
        kind: Int = MessageEntity.KIND_NORMAL,
    ) = MessageEntity(
        id = id,
        senderId = sender,
        recipientId = recipientId,
        conversationId = conversationId,
        body = "",
        sentAt = sentAt,
        received = received,
        attachmentHash = attachmentHash,
        pendingKey = pendingKey,
        kind = kind,
    )

    private companion object {
        const val ME = "me"
    }
}
