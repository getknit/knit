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
 * What a direct link's demand reads (`docs/DIRECT_LINKS.md` L-7-7, L-7-9), against the real SQL: our DMs still
 * unticked, and for our group posts every member who has not acked them — never someone else's message, a status row, a
 * DM still waiting on its key, a message outside the window, or a group we left — and the files either way that call for
 * a link.
 */
class MeshLinkPendingTest : RoomDbTest() {
    private val messages by lazy { MessageRepository(db.messageDao()) }
    private val receipts by lazy { MessageReceiptRepository(db.messageReceiptDao(), messages, db) }
    private val groups = mutableMapOf<String, GroupEntity>()
    private val stuck = mutableSetOf<String>()

    // hash -> since when the blob exchange's want of it is one a link may carry (`MeshManager.attachmentWantedSince`).
    private val wanted = mutableMapOf<String, Long>()
    private val pending by lazy {
        MeshLinkPending(
            messages,
            receipts,
            group = { groups[it] },
            stuck = { _, hash -> hash in stuck },
            wantedSince = { _, hash -> wanted[hash] },
        )
    }

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

            assertEquals(listOf(Pending("bob", 100, LinkWhy.FILE)), pending.between(ME, since = 50, until = 200))
        }

    @Test
    fun aDmAttachmentOfTheirsWeWantIsPendingByWhenItArrivedAndNoOtherAttachment() =
        runTest {
            val dao = db.messageDao()
            // Sent long ago by its own clock, arrived here at 150: the row's time is ours.
            dao.upsert(dm("m1", ME, sentAt = 5, from = "bob").copy(attachmentHash = "H1", arrivedAt = 150))
            dao.upsert(dm("m2", ME, sentAt = 5, from = "carol").copy(attachmentHash = "H2", arrivedAt = 150))
            dao.upsert(dm("m3", ME, sentAt = 5, from = "dave").copy(attachmentHash = "H3", arrivedAt = 10))
            // Ours, stamped as arrived (a copy of our own sent from another device carries one): never WANT.
            dao.upsert(dm("m4", "erin", sentAt = 150, received = true).copy(attachmentHash = "H4", arrivedAt = 150))
            dao.upsert(dm("m5", ME, sentAt = 150, from = ME, received = true).copy(attachmentHash = "H5", arrivedAt = 150))
            // A contact's group post: a group's picture is never a DM's to ask its sender for.
            dao.upsert(post("p1", "g-1", sentAt = 5, from = "bob").copy(attachmentHash = "H6", arrivedAt = 150))
            listOf("H1", "H3", "H4", "H5", "H6").forEach { wanted[it] = 0L }

            assertEquals(listOf(Pending("bob", 150, LinkWhy.WANT)), pending.between(ME, since = 50, until = 200))
        }

    @Test
    fun aFileIsReadOverTheWholeRangeButAMessageOnlyOverItsLastWindow() =
        runTest {
            val dao = db.messageDao()
            val until = 10 * LinkDemandPolicy.WINDOW_MS
            val early = until - 2 * LinkDemandPolicy.WINDOW_MS
            dao.upsert(dm("m1", "bob", sentAt = early))
            dao.upsert(dm("m2", "carol", sentAt = early, received = true).copy(attachmentHash = "H1"))
            stuck += "H1"

            assertEquals(listOf(Pending("carol", early, LinkWhy.FILE)), pending.between(ME, since = 0, until = until))
        }

    /**
     * ADR 2026-10.js2p and a5yj: a fast-only want held on a metered network is no row at all, and once it can use a link —
     * the tap, or the network turning unmetered — its row is timed from then, not from its message's arrival. A link the
     * pair's chat raised and closed while the want was held (a move to Wi-Fi ends it `ROUTE`) had no chance at the file,
     * and a row left at the arrival would never call for a link again.
     */
    @Test
    fun aWantThatBecameLinkableAfterALinkCameAndWentIsOfferedForAndAHeldOneIsNot() =
        runTest {
            val dao = db.messageDao()
            dao.upsert(dm("m1", ME, sentAt = 5, from = "bob").copy(attachmentHash = "H1", arrivedAt = 100))
            val linkUpAt = 200L // the chat's link was up then, while the want was held, and closed
            val lastUpAt = { peer: String -> linkUpAt.takeIf { peer == "bob" } }
            val noRadio = { _: String -> false }

            // Held: fast-only on a metered network, so the exchange reports no linkable time.
            assertEquals(emptyList<Pending>(), pending.between(ME, since = 0, until = 1_000))

            // The tap (or the turn to unmetered) at 300: the row is timed from it, after the link that came and went.
            wanted["H1"] = 300L
            val rows = pending.between(ME, since = 0, until = 1_000)
            assertEquals(listOf(Pending("bob", 300, LinkWhy.WANT)), rows)
            assertEquals(
                "not before its minute, counted from the tap",
                emptyList<Pair<String, LinkWhy>>(),
                LinkDemandPolicy.offers(rows, now = 300 + LinkDemandPolicy.WANT_WAIT_MS - 1, noRadio, up = emptySet(), lastUpAt = lastUpAt),
            )
            assertEquals(
                listOf("bob" to LinkWhy.WANT),
                LinkDemandPolicy.offers(rows, now = 300 + LinkDemandPolicy.WANT_WAIT_MS, noRadio, up = emptySet(), lastUpAt = lastUpAt),
            )

            // Timed from the arrival instead, the link that was up at 200 would have had its chance: no offer, ever.
            assertEquals(
                emptyList<Pair<String, LinkWhy>>(),
                LinkDemandPolicy.offers(
                    listOf(Pending("bob", 100, LinkWhy.WANT)),
                    now = 300 + LinkDemandPolicy.WANT_WAIT_MS,
                    noRadio,
                    up = emptySet(),
                    lastUpAt = lastUpAt,
                ),
            )
        }

    @Test
    fun aWantLinkableBeforeItsMessageArrivedIsTimedFromTheArrival() =
        runTest {
            db.messageDao().upsert(dm("m1", ME, sentAt = 5, from = "bob").copy(attachmentHash = "H1", arrivedAt = 150))
            wanted["H1"] = 120L // a custody pull wanted it before the row was written

            assertEquals(listOf(Pending("bob", 150, LinkWhy.WANT)), pending.between(ME, since = 50, until = 200))
        }

    private companion object {
        const val ME = "me"
    }
}
