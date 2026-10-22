package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
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
    fun `attachmentFetchRows lists every row naming a missing attachment, earliest first`() =
        runTest {
            dao.upsert(msg("m1", attachmentHash = "H1", sender = "forwarder", sentAt = 5L))
            dao.upsert(msg("m2", attachmentHash = "H2")) // H2 is held below → excluded
            dao.upsert(msg("m3", attachmentHash = "H1", sender = "author", sentAt = 2L))
            dao.upsert(msg("m4", attachmentHash = null)) // no attachment → excluded
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(
                "one entry per row, the earliest first: its sender is whom a re-armed want asks",
                listOf("H1" to "author", "H1" to "forwarder"),
                dao.attachmentFetchRows(me = "me", commonsGlob = COMMONS_GLOB).map { it.hash to it.senderId },
            )
        }

    @Test
    fun `attachmentFetchRows carries each row's declared size, its tap and whether it is ours`() =
        runTest {
            // ADR 2026-10.js2p: what BluetoothFetchGate folds a re-armed want's verdict over, row by row.
            dao.upsert(msg("a1", attachmentHash = "H1", sender = "peer", sentAt = 1L).copy(attachmentSize = 2_000_000L))
            dao.upsert(msg("a2", attachmentHash = "H1", sender = "me", sentAt = 2L).copy(attachmentSize = 1_500_000L))
            dao.upsert(msg("c1", recipientId = "me", attachmentHash = "H3", sender = "peer", sentAt = 3L))

            // And, for PullThroughGate (#195), when each was sent and whether it is a DM's: only c1 has a recipient.
            assertEquals(
                listOf(
                    AttachmentFetchRow("H1", "peer", 2_000_000L, approved = false, own = false, sentAt = 1L, dm = false),
                    AttachmentFetchRow("H1", "me", 1_500_000L, approved = false, own = true, sentAt = 2L, dm = false),
                    AttachmentFetchRow("H3", "peer", null, approved = false, own = false, sentAt = 3L, dm = true),
                ),
                dao.attachmentFetchRows(me = "me", commonsGlob = COMMONS_GLOB),
            )

            dao.approveAttachmentFetch("H1")
            assertTrue(
                "every row naming the hash",
                dao
                    .attachmentFetchRows(me = "me", commonsGlob = COMMONS_GLOB)
                    .filter {
                        it.hash == "H1"
                    }.all { it.approved },
            )
            assertEquals(2, dao.attachmentFetchRowsFor("H1", me = "me", commonsGlob = COMMONS_GLOB).size)
            assertEquals(
                listOf(3L to true),
                dao.attachmentFetchRowsFor("H3", me = "me", commonsGlob = COMMONS_GLOB).map {
                    it.sentAt to
                        it.dm
                },
            )
            db.blobDao().insert(BlobEntity(hash = "H1", mime = "image/jpeg", bytes = ByteArray(0)))
            assertEquals("by hash, held or not", 2, dao.attachmentFetchRowsFor("H1", me = "me", commonsGlob = COMMONS_GLOB).size)
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
            val nearby = dao.observeById("m1").first()!!
            assertTrue(nearby.received)
            assertEquals(DeliveryPlane.Nearby, nearby.receivedPlane)

            dao.upsert(msg("m2", received = false))
            dao.markReceived("m2", DeliveryPlane.Internet.code)
            val relayed = dao.observeById("m2").first()!!
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
            assertEquals(DeliveryPlane.Nearby, dao.observeById("nearby-first").first()!!.receivedPlane)

            dao.upsert(msg("relay-first", received = false))
            dao.markReceived("relay-first", DeliveryPlane.Internet.code)
            dao.markReceived("relay-first", DeliveryPlane.Nearby.code)
            assertEquals(DeliveryPlane.Internet, dao.observeById("relay-first").first()!!.receivedPlane)
        }

    @Test
    fun `attachmentCarriedByRadio counts only a short-range hop, and only between this pair`() =
        runTest {
            // The §9.5 defer gate's evidence half. A spool or LoRa hop says the message got there, not that
            // a radio moved the bytes, so only the Nearby family may hold an upload back.
            suspend fun carried(hash: String) = dao.attachmentCarriedByRadio(hash, ME, PEER, DeliveryPlane.shortRangeCodes)

            dao.upsert(
                msg("radio", sender = ME, attachmentHash = "H-radio", received = true)
                    .copy(receivedVia = DeliveryPlane.Nearby.code),
            )
            assertTrue("a radio ack is the one that defers", carried("H-radio"))

            // The two reserved radio codes belong to the same family, so a build that starts writing them
            // must not silently stop deferring.
            dao.upsert(
                msg("ble", sender = ME, attachmentHash = "H-ble", received = true)
                    .copy(receivedVia = DeliveryPlane.Bluetooth.code),
            )
            assertTrue("Bluetooth is the Nearby family", carried("H-ble"))
            dao.upsert(
                msg("nan", sender = ME, attachmentHash = "H-nan", received = true)
                    .copy(receivedVia = DeliveryPlane.WifiAware.code),
            )
            assertTrue("Wi-Fi Aware is the Nearby family", carried("H-nan"))

            dao.upsert(
                msg("spool", sender = ME, attachmentHash = "H-spool", received = true).copy(receivedVia = DeliveryPlane.Internet.code),
            )
            assertFalse("the plane the push feeds is not evidence for deferring it", carried("H-spool"))

            dao.upsert(
                msg("board", sender = ME, attachmentHash = "H-board", received = true)
                    .copy(receivedVia = DeliveryPlane.LoRa.code),
            )
            assertFalse("a board carries a frame and never a blob", carried("H-board"))

            // A row from a build older than the column: `Unknown` is in no plane set, so it pushes.
            dao.upsert(msg("legacy", sender = ME, attachmentHash = "H-legacy", received = true))
            assertFalse("an unrecorded plane is not evidence", carried("H-legacy"))

            dao.upsert(
                msg("unacked", sender = ME, attachmentHash = "H-unacked")
                    .copy(receivedVia = DeliveryPlane.Nearby.code),
            )
            assertFalse("the tick still has to be flipped", carried("H-unacked"))

            // The other reading of the same fact: the peer's own send, which reached us over a radio. No
            // tick is required on it — `received` there is the peer's business — and a recipient that
            // ignored this re-uploaded every photo BLE had just handed it.
            dao.upsert(
                msg("theirs", sender = PEER, attachmentHash = "H-theirs").copy(receivedVia = DeliveryPlane.Nearby.code),
            )
            assertTrue("a photo the peer sent us over a radio is carried too", carried("H-theirs"))

            dao.upsert(
                msg("theirs-spool", sender = PEER, attachmentHash = "H-theirs-spool")
                    .copy(receivedVia = DeliveryPlane.Internet.code),
            )
            assertFalse("their send that only crossed the spool is not radio evidence", carried("H-theirs-spool"))

            // A third party's frame is nobody's message between this pair, which is why a carrier never defers.
            dao.upsert(
                msg("carol", sender = "carol", attachmentHash = "H-carol").copy(receivedVia = DeliveryPlane.Nearby.code),
            )
            assertFalse("a carried frame is not this pair's message", carried("H-carol"))

            assertFalse("a hash we hold no row for at all", carried("H-absent"))
        }

    @Test
    fun `attachmentAuthoredHere separates an ack still in flight from one that is never coming`() =
        runTest {
            // The §9.5 grace's other half (issue #46). The gate is asked about a photo in the round the
            // send itself woke, when no receipt could have come back yet — so it needs to know whether an
            // ack is owed at all, which is a different question from whether one has arrived.
            suspend fun ours(hash: String) = dao.attachmentAuthoredHere(hash, ME)

            dao.upsert(msg("mine", sender = ME, attachmentHash = "H-mine"))
            assertTrue("an ack for our own unacked send is merely late", ours("H-mine"))

            // The tick is beside the point here: an acked row of ours is still ours.
            dao.upsert(
                msg("mine-acked", sender = ME, attachmentHash = "H-mine-acked", received = true)
                    .copy(receivedVia = DeliveryPlane.Nearby.code),
            )
            assertTrue("the plane and the tick belong to the other half", ours("H-mine-acked"))

            dao.upsert(msg("from-peer", sender = PEER, attachmentHash = "H-carried"))
            assertFalse("a message the peer authored is not ours to be acked", ours("H-carried"))

            // An avatar writes a `PeerEntity` and no message row, so it reads exactly like a hash we have
            // never seen — and both must keep pushing rather than borrow the grace.
            assertFalse("an avatar names no message row at all", ours("H-avatar"))
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

            val row = dao.observeById("m1").first()!!
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
            assertEquals(DeliveryPlane.LoRa, dao.observeById("m2").first()!!.receivedPlane)
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
                    .observeNewestForConversation("t", 10)
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

    // --- The thread window (ChatWindow / ChatViewModel) --------------------------------------------------
    //
    // These run the real SQL over a thousand-row thread — an ordinary size now that an accepted thread has no
    // retention cap — which is the only place "the screen no longer reads the whole conversation" can be
    // proved. A 1,000-message thread plus a decoy thread; `sentAt` doubles as the row's ordinal so every
    // assertion can name exact messages.

    /** Seeds [count] messages in [conversationId] with `sentAt` running 1..count, oldest first. */
    private suspend fun seedThread(
        conversationId: String,
        count: Int,
        sender: String = "s",
    ) {
        for (i in 1..count) {
            dao.upsert(msg("$conversationId-$i", conversationId = conversationId, sender = sender, sentAt = i.toLong()))
        }
    }

    @Test
    fun `the window reads only the newest N of a thousand, and nothing from another thread`() =
        runTest {
            seedThread(THREAD, 1_000)
            seedThread("other", 300)

            val window = dao.observeNewestForConversation(THREAD, 60).first()

            assertEquals("the cap holds, not the thread size", 60, window.size)
            assertTrue("only this thread", window.all { it.conversationId == THREAD })
            assertEquals("newest first", 1_000L, window.first().sentAt)
            assertEquals("and it stops 60 back, not at the oldest", 941L, window.last().sentAt)
        }

    @Test
    fun `growing the window only adds older messages, leaving the newest end identical`() =
        runTest {
            seedThread(THREAD, 1_000)

            val first = dao.observeNewestForConversation(THREAD, 60).first()
            val grown = dao.observeNewestForConversation(THREAD, 160).first()

            assertEquals(160, grown.size)
            assertEquals("the newest 60 are the same rows in the same order", first, grown.take(60))
            assertEquals("and the extra 100 are older", 841L, grown.last().sentAt)
        }

    @Test
    fun `messages sharing a sentAt keep a stable order across the window boundary`() =
        runTest {
            // Ten messages at one instant — a burst arriving together, which the room really does produce.
            // Without the `id DESC` tiebreak SQLite may return them in any order, so a message could sit
            // inside the window one moment and outside it the next, appearing twice or vanishing mid-scroll.
            for (c in 'a'..'j') {
                dao.upsert(msg("tie-$c", conversationId = THREAD, sentAt = 500L))
            }

            val small = dao.observeNewestForConversation(THREAD, 5).first()
            val large = dao.observeNewestForConversation(THREAD, 10).first()

            assertEquals(listOf("tie-j", "tie-i", "tie-h", "tie-g", "tie-f"), small.map { it.id })
            assertEquals("the small window is the large one's prefix", small, large.take(5))
            assertEquals("every message once, none skipped", 10, large.map { it.id }.distinct().size)
        }

    @Test
    fun `a window wider than the thread returns the whole thread`() =
        runTest {
            seedThread(THREAD, 40)

            val window = dao.observeNewestForConversation(THREAD, 60).first()

            // This is what tells the screen there is no more history to fetch: fewer rows back than asked for.
            assertEquals(40, window.size)
            assertTrue("short of the limit means nothing older", window.size < 60)
        }

    @Test
    fun `depthOf gives the window that just reaches a message, and zero for one that is gone`() =
        runTest {
            seedThread(THREAD, 1_000)

            // The row at sentAt 500 has 500 messages at or newer than it (500..1000 is 501 — inclusive).
            val depth = dao.depthOf(THREAD, "$THREAD-500")
            assertEquals(501, depth)

            val reached = dao.observeNewestForConversation(THREAD, depth).first()
            assertTrue("a window that size contains it", reached.any { it.id == "$THREAD-500" })

            assertEquals("a retention-trimmed target reports no depth", 0, dao.depthOf(THREAD, "never-stored"))
        }

    @Test
    fun `observeSendersIn names every author of a long thread, notices excluded`() =
        runTest {
            // Who you can @-mention must not depend on how far back the reader has scrolled, so this is asked
            // of the table rather than derived from the loaded window.
            seedThread(THREAD, 400, sender = "alice")
            dao.upsert(msg("bob-1", conversationId = THREAD, sender = "bob", sentAt = 900L))
            dao.upsert(msg("left", conversationId = THREAD, sender = "carol", sentAt = 950L, kind = MessageEntity.KIND_MEMBER_LEFT))

            val senders = dao.observeSendersIn(THREAD).first()

            assertEquals(setOf("alice", "bob"), senders.toSet())
            assertFalse("a status notice's subject has not spoken here", "carol" in senders)
        }

    // --- The list screens' per-thread summaries (ChatListViewModel / MessageRequestsViewModel) ------------
    //
    // The chat list and the requests inbox used to read the whole table and fold it; they now ask these
    // questions of the database, so the rules they fold — which row speaks for a thread, who has spoken in
    // a group, what counts as unread — live in SQL and are pinned here.

    private val speaking = setOf(MessageEntity.KIND_NORMAL, MessageEntity.KIND_FILE_TRANSFER)

    @Test
    fun `observeNewestPerConversation picks the newest speaking row per thread, breaking a sentAt tie on id`() =
        runTest {
            seedThread("dm", 3)
            for (c in 'a'..'j') {
                dao.upsert(msg("tie-$c", conversationId = THREAD, sentAt = 500L))
            }

            val heads = dao.observeNewestPerConversation(speaking, emptySet()).first()

            assertEquals(listOf(THREAD, "dm"), heads.map { it.conversationId })
            assertEquals("the id tiebreak the window uses, so a burst cannot flip the preview", "tie-j", heads[0].id)
            assertEquals("dm-3", heads[1].id)
        }

    @Test
    fun `observeNewestPerConversation skips notices, blocked senders and kinds it was not asked for`() =
        runTest {
            dao.upsert(msg("said", conversationId = "dm", sender = "bob", sentAt = 1L))
            dao.upsert(msg("renamed", conversationId = "dm", sender = "bob", sentAt = 5L, kind = MessageEntity.KIND_PEER_RENAMED))
            dao.upsert(msg("spam", conversationId = "dm", sender = "blk", sentAt = 9L))
            dao.upsert(msg("offer", conversationId = "xfer", sender = "bob", sentAt = 2L, kind = MessageEntity.KIND_FILE_TRANSFER))

            val forList = dao.observeNewestPerConversation(speaking, setOf("blk")).first().associateBy { it.conversationId }
            assertEquals("a notice never speaks for a thread, nor does a blocked sender", "said", forList["dm"]?.id)
            assertEquals("a transfer speaks for the chat list", "offer", forList["xfer"]?.id)

            val forInbox = dao.observeNewestPerConversation(setOf(MessageEntity.KIND_NORMAL), setOf("blk")).first()
            assertNull("but not for the requests inbox", forInbox.firstOrNull { it.conversationId == "xfer" })
        }

    @Test
    fun `a notice-only thread has no head but is still a conversation`() =
        runTest {
            dao.upsert(msg("n1", conversationId = "g-1", sender = "x", kind = MessageEntity.KIND_PEER_RENAMED))

            assertTrue(dao.observeNewestPerConversation(speaking, emptySet()).first().isEmpty())
            assertEquals(listOf("g-1"), dao.observeDistinctConversations(emptySet()).first())
        }

    @Test
    fun `observeNewestPerConversation runs with nobody blocked`() =
        runTest {
            // Room expands an empty collection to `NOT IN ()`, which must read as true on the Android driver.
            seedThread("a", 2)
            seedThread("b", 2)

            assertEquals(
                setOf("a-2", "b-2"),
                dao
                    .observeNewestPerConversation(speaking, emptySet())
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `observeDistinctConversations drops a thread whose every row is from a blocked sender`() =
        runTest {
            dao.upsert(msg("s1", conversationId = "blk", sender = "blk"))
            dao.upsert(msg("s2", conversationId = "g-1", sender = "blk"))
            dao.upsert(msg("m1", conversationId = "g-1", sender = "ok"))

            assertEquals(listOf("g-1"), dao.observeDistinctConversations(setOf("blk")).first())
            assertEquals(setOf("blk", "g-1"), dao.observeDistinctConversations(emptySet()).first().toSet())
        }

    @Test
    fun `observeGroupSenders names the ordinary-message senders of groups only, blocked and notices out`() =
        runTest {
            dao.upsert(msg("d1", conversationId = "bob", sender = "bob"))
            dao.upsert(msg("r1", conversationId = Conversations.NEARBY, sender = "hal"))
            dao.upsert(msg("g1", conversationId = "g-1", sender = "p"))
            dao.upsert(msg("g2", conversationId = "g-1", sender = "p", sentAt = 2L))
            dao.upsert(msg("g3", conversationId = "g-1", sender = "q", kind = MessageEntity.KIND_MEMBER_LEFT))
            dao.upsert(msg("g4", conversationId = "g-1", sender = "blk"))
            dao.upsert(msg("g5", conversationId = "g-2", sender = "r"))

            val rows = dao.observeGroupSenders(Conversations.GROUP_ID_PREFIX + "*", setOf("blk")).first()

            assertEquals(
                setOf(ConversationSender("g-1", "p"), ConversationSender("g-2", "r")),
                rows.toSet(),
            )
        }

    @Test
    fun `countUnreadIn counts others' ordinary messages past the watermark, heard posts included`() =
        runTest {
            dao.upsert(msg("t1", conversationId = "dm", sender = "them", sentAt = 2L))
            dao.upsert(msg("mine", conversationId = "dm", sender = ME, sentAt = 10L))
            dao.upsert(msg("heard", conversationId = "dm", sender = ME, sentAt = 11L, originNode = 42L))
            dao.upsert(msg("t2", conversationId = "dm", sender = "them", sentAt = 12L))
            dao.upsert(msg("avatar", conversationId = "dm", sender = "them", sentAt = 13L, kind = MessageEntity.KIND_PEER_AVATAR))
            dao.upsert(msg("spam", conversationId = "dm", sender = "blk", sentAt = 14L))

            // Our own row is read by definition; a post our board heard is somebody else's words and counts.
            assertEquals(2, dao.countUnreadIn("dm", since = 5L, me = ME, blocked = setOf("blk")))
            assertEquals(3, dao.countUnreadIn("dm", since = 5L, me = ME, blocked = emptySet()))
            assertEquals(3, dao.countUnreadIn("dm", since = 0L, me = ME, blocked = setOf("blk")))
            assertEquals(0, dao.countUnreadIn("dm", since = 14L, me = ME, blocked = setOf("blk")))
            assertEquals(0, dao.countUnreadIn("empty", since = 0L, me = ME, blocked = emptySet()))
        }

    @Test
    fun `observeNewestOriginChannel is the newest post that named a channel, and null when none did`() =
        runTest {
            val room = Conversations.MESHTASTIC
            dao.upsert(msg("h1", conversationId = room, sender = ME, sentAt = 1L, originNode = 7L, originChannel = "LongFast"))
            dao.upsert(msg("h2", conversationId = room, sender = ME, sentAt = 2L, originNode = 7L, originChannel = ""))
            dao.upsert(msg("typed", conversationId = room, sender = ME, sentAt = 3L))
            dao.upsert(msg("h3", conversationId = room, sender = ME, sentAt = 4L, originNode = 7L, originChannel = "  "))

            assertEquals("LongFast", dao.observeNewestOriginChannel(room).first())
            assertNull(dao.observeNewestOriginChannel("quiet").first())
        }

    @Test
    fun `observeConversationsIAuthoredIn mirrors the one-shot rule and leaves heard posts out`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1", sender = ME))
            dao.upsert(msg("b", conversationId = "t2", sender = "them"))
            dao.upsert(msg("c", conversationId = Conversations.MESHTASTIC, sender = ME, originNode = 9L))

            assertEquals(listOf("t1"), dao.observeConversationsIAuthoredIn(ME).first())
            assertEquals(dao.conversationsIAuthoredIn(ME), dao.observeConversationsIAuthoredIn(ME).first())
        }

    @Test
    fun `newestSentAt is the thread's newest time, and null for a thread we hold nothing of`() =
        runTest {
            seedThread(THREAD, 40)

            assertEquals(40L, dao.newestSentAt(THREAD))
            assertNull(dao.newestSentAt("never"))
        }

    @Test
    fun `countFromOthers and countMine split the table by sender, notices out of the peer count`() =
        runTest {
            dao.upsert(msg("m1", sender = ME))
            dao.upsert(msg("m2", sender = ME, kind = MessageEntity.KIND_GROUP_CREATED))
            dao.upsert(msg("t1", sender = "them"))
            dao.upsert(msg("t2", sender = "them", sentAt = 2L))
            dao.upsert(msg("t3", sender = "them", sentAt = 3L, kind = MessageEntity.KIND_PEER_RENAMED))

            assertEquals(2, dao.countFromOthers(ME))
            assertEquals(2, dao.countMine(ME))
        }

    @Test
    fun `voiceMetaWanted holds until every row naming the hash has its duration`() =
        runTest {
            // The inbound pipeline's gate before decoding a held voice note again (#132): a re-sent note's second
            // row still wants it, and setVoiceMeta, keyed by hash, answers both rows at once.
            dao.upsert(msg("v1", attachmentHash = "note"))
            dao.upsert(msg("v2", attachmentHash = "note", sentAt = 2L))
            assertTrue(dao.voiceMetaWanted("note"))

            dao.setVoiceMeta("note", 4_000, "AAEC")

            assertFalse(dao.voiceMetaWanted("note"))
            assertFalse("no row names it", dao.voiceMetaWanted("nobody-claims-this"))
        }

    @Test
    fun `setVoiceTranscript writes the text on every row naming the note and no other`() =
        runTest {
            // ADR 2026-10.am8d: the same bytes say the same words in every bubble, so the text is keyed by hash.
            dao.upsert(msg("v1", attachmentHash = "note"))
            dao.upsert(msg("v2", attachmentHash = "note", sentAt = 2L))
            dao.upsert(msg("other", attachmentHash = "else", sentAt = 3L))

            dao.setVoiceTranscript("note", "See you at six.")

            assertEquals("See you at six.", checkNotNull(dao.findById("v1")).voiceTranscript)
            assertEquals("See you at six.", checkNotNull(dao.findById("v2")).voiceTranscript)
            assertNull(checkNotNull(dao.findById("other")).voiceTranscript)
        }

    @Test
    fun `setVideoMeta replaces a described video's claim and leaves an mp4 sent as a file alone`() =
        runTest {
            // ADR 2026-10.hmfe: the bytes' own duration and size win over the sender's claim, but only on a row the
            // sender described as a video. An .mp4 sent as a file has no description and keeps its file bubble.
            dao.upsert(msg("video", attachmentHash = "clip").copy(videoDurationMs = 30_000L, videoWidth = 480, videoHeight = 854))
            dao.upsert(msg("file", attachmentHash = "clip", sentAt = 2L).copy(attachmentName = "clip.mp4"))

            dao.setVideoMeta("clip", 29_970L, 480, 852)

            val video = checkNotNull(dao.findById("video"))
            assertEquals(29_970L, video.videoDurationMs)
            assertEquals(852, video.videoHeight)
            val file = checkNotNull(dao.findById("file"))
            assertNull("an undescribed .mp4 stays a file", file.videoDurationMs)
            assertNull(file.videoWidth)
        }

    @Test
    fun `the field log's latency reads take others' arrivals and receipts for our own, inside the window`() =
        runTest {
            // Someone else's, arrived inside the window over Bluetooth: 1.5 s on the way.
            dao.upsert(msg("in", sender = "peer", sentAt = 10_000L).copy(arrivedAt = 11_500L, receivedVia = DeliveryPlane.Bluetooth.code))
            // Someone else's, arrived before the window: left out.
            dao.upsert(msg("old", sender = "peer", sentAt = 1_000L).copy(arrivedAt = 2_000L))
            // Our own: never an arrival, but its receipt is a round trip.
            dao.upsert(msg("mine", sender = "me", sentAt = 20_000L))
            db.messageReceiptDao().insertIfAbsent(
                MessageReceiptEntity("mine", "peer", notedAt = 23_000L, via = DeliveryPlane.LoRa.code),
            )

            assertEquals(listOf(PlaneDelay(DeliveryPlane.Bluetooth.code, 1_500L)), dao.inboundDelays("me", since = 5_000L))
            assertEquals(listOf(PlaneDelay(DeliveryPlane.LoRa.code, 3_000L)), dao.receiptDelays("me", since = 5_000L))
            assertEquals(emptyList<PlaneDelay>(), dao.receiptDelays("me", since = 30_000L))
        }

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
        originNode: Long? = null,
        originChannel: String? = null,
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
        originNode = originNode,
        originChannel = originChannel,
    )

    private companion object {
        const val COMMONS_GLOB = Conversations.COMMONS_PREFIX + "*"
        const val ME = "me"
        const val PEER = "peer"
        const val THREAD = "thread"
    }
}
