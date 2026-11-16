package app.getknit.knit.data.receipt

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [MessageReceiptDao] SQL, plus the one thing about [MessageReceiptRepository.record]
 * that cannot be reasoned about from the call sites: the sealed-receipt path already runs inside the ctl
 * commit's `db.withWriteTransaction`, so `record` opens a **nested** transaction on SQLCipher's single
 * connection. Room reuses the in-flight transaction rather than starting a second one — but the failure
 * mode if that were wrong is a silent coroutine deadlock (no thread holds anything, nothing throws), so it
 * is pinned here rather than assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageReceiptRepositoryTest : RoomDbTest() {
    private val dao get() = db.messageReceiptDao()
    private val repo get() = MessageReceiptRepository(dao, MessageRepository(db.messageDao()), db)

    private suspend fun received(id: String) =
        db
            .messageDao()
            .observeById(id)
            .first()!!
            .received

    private suspend fun seed(id: String) = db.messageDao().upsert(MessageEntity(id = id, senderId = "me", body = "", sentAt = 1L))

    @Test
    fun `record flips the tick and names the acker in one transaction`() =
        runTest {
            seed("m1")

            repo.record("m1", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)

            assertTrue(received("m1"))
            val row = dao.observeForMessage("m1").first().single()
            assertEquals("sam", row.ackerNodeId)
            assertEquals(10L, row.notedAt)
            assertEquals(DeliveryPlane.Nearby.code, row.via)
        }

    @Test
    fun `an unattributable receipt still ticks and simply names nobody`() =
        runTest {
            // The tick's "≥1 recipient received it" rule is a wire semantic and must not inherit the row's
            // roster gate — a non-member's group ack ticks exactly as it always has.
            seed("m2")

            repo.record("m2", acker = null, via = DeliveryPlane.Nearby, at = 10L)

            assertTrue(received("m2"))
            assertTrue(dao.observeForMessage("m2").first().isEmpty())
        }

    @Test
    fun `a re-served receipt keeps the first crossing's time and plane`() =
        runTest {
            seed("m3")

            repo.record("m3", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)
            repo.record("m3", acker = "sam", via = DeliveryPlane.Internet, at = 999L)

            val row = dao.observeForMessage("m3").first().single()
            assertEquals(10L, row.notedAt)
            assertEquals(DeliveryPlane.Nearby.code, row.via)
        }

    @Test
    fun `record nested inside an outer transaction commits instead of deadlocking`() =
        runTest {
            // The shape the sealed CTL_RECEIPT path takes in production: the ctl commit holds the
            // transaction, and record opens its own inside it.
            seed("m4")

            db.withWriteTransaction {
                repo.record("m4", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)
            }

            assertTrue(received("m4"))
            assertEquals(
                "sam",
                dao
                    .observeForMessage("m4")
                    .first()
                    .single()
                    .ackerNodeId,
            )
        }

    @Test
    fun `a read after the delivery stamps the row it already has, never earlier than the delivery`() =
        runTest {
            // ADR 2026-11.z6jw: a read rides a delivery receipt, so it lands on the delivered row. Our clock can
            // step back between the two; the read is still never shown before the delivery it implies.
            seed("r1")
            repo.record("r1", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)

            repo.record("r1", acker = "sam", via = DeliveryPlane.Internet, at = 7L, read = true)

            val row = dao.observeForMessage("r1").first().single()
            assertEquals(10L, row.notedAt)
            assertEquals(DeliveryPlane.Nearby.code, row.via)
            assertEquals(10L, row.readAt)
        }

    @Test
    fun `a read that lands first records the delivery too, at the same time`() =
        runTest {
            seed("r2")

            repo.record("r2", acker = "sam", via = DeliveryPlane.LoRa, at = 20L, read = true)

            assertTrue(received("r2"))
            val row = dao.observeForMessage("r2").first().single()
            assertEquals(20L, row.notedAt)
            assertEquals(20L, row.readAt)
            assertEquals(DeliveryPlane.LoRa.code, row.via)
        }

    @Test
    fun `a re-served read keeps the first read's time, and a later plain receipt never clears it`() =
        runTest {
            seed("r3")
            repo.record("r3", acker = "sam", via = DeliveryPlane.Nearby, at = 10L, read = true)

            repo.record("r3", acker = "sam", via = DeliveryPlane.Nearby, at = 50L, read = true)
            repo.record("r3", acker = "sam", via = DeliveryPlane.Nearby, at = 60L)

            assertEquals(
                10L,
                dao
                    .observeForMessage("r3")
                    .first()
                    .single()
                    .readAt,
            )
        }

    @Test
    fun `an unattributable read ticks the delivery and records no read`() =
        runTest {
            seed("r4")

            repo.record("r4", acker = null, via = DeliveryPlane.Nearby, at = 10L, read = true)

            assertTrue(received("r4"))
            assertTrue(dao.observeForMessage("r4").first().isEmpty())
        }

    @Test
    fun `read counts and readers count only reads from the roster`() =
        runTest {
            db.messageDao().upsert(MessageEntity(id = "g1", senderId = "me", conversationId = "g-x", body = "", sentAt = 1L))
            repo.record("g1", acker = "sam", via = DeliveryPlane.Nearby, at = 10L, read = true)
            repo.record("g1", acker = "ann", via = DeliveryPlane.Nearby, at = 11L)
            repo.record("g1", acker = "gone", via = DeliveryPlane.Nearby, at = 12L, read = true)

            assertEquals(mapOf("g1" to 1), repo.observeReadCounts("g-x", listOf("sam", "ann")).first())
            assertEquals(mapOf("g1" to 2), repo.observeDeliveredCounts("g-x", listOf("sam", "ann")).first())
            assertEquals(setOf("sam", "gone"), repo.readersOf("g1"))
        }

    @Test
    fun `deleteOrphans reclaims rows whose message is gone and spares the rest`() =
        runTest {
            // No FK cascade, so a deleted conversation, a group leave, and either retention trim all leave
            // rows behind. Unlike reactions there is no age floor: a receipt is only ever written for a
            // message we already hold, so an orphan can only mean the message has since gone.
            seed("m_live")
            dao.insertIfAbsent(MessageReceiptEntity("m_live", "sam", 1L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m_gone", "sam", 1L, DeliveryPlane.Nearby.code))

            dao.deleteOrphans()

            assertTrue(dao.observeForMessage("m_live").first().isNotEmpty())
            assertTrue(dao.observeForMessage("m_gone").first().isEmpty())
        }

    @Test
    fun `deleteForMessage drops one message's rows and leaves another's alone`() =
        runTest {
            seed("m5")
            seed("m6")
            dao.insertIfAbsent(MessageReceiptEntity("m5", "sam", 1L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m6", "sam", 1L, DeliveryPlane.Nearby.code))

            repo.deleteForMessage("m5")

            assertTrue(dao.observeForMessage("m5").first().isEmpty())
            assertFalse(dao.observeForMessage("m6").first().isEmpty())
        }

    @Test
    fun `observeForMessage returns each acker oldest first`() =
        runTest {
            seed("m7")
            dao.insertIfAbsent(MessageReceiptEntity("m7", "theo", 30L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m7", "sam", 10L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m7", "priya", 20L, DeliveryPlane.Nearby.code))

            assertEquals(listOf("sam", "priya", "theo"), dao.observeForMessage("m7").first().map { it.ackerNodeId })
        }
}
