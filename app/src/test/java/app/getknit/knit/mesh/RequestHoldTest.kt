package app.getknit.knit.mesh

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.AttachmentFetchRow
import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #282: a message request's attachment waits for the request's acceptance, folded over the rows as `BluetoothFetchGate`
 * folds them — one row that would fetch it fetches it for all — and, before any row is stored, over the custodied frames
 * naming it.
 */
class RequestHoldTest {
    private val me = "me"
    private val stranger = "stranger"
    private val contact = "contact"
    private val group = Conversations.groupIdFor(listOf(me, stranger, contact))
    private val pair2 = Conversations.groupIdFor(listOf(me, stranger))

    private fun row(
        conversationId: String,
        sender: String = stranger,
        own: Boolean = false,
        approved: Boolean = false,
    ) = AttachmentFetchRow("h", sender, null, approved = approved, own = own, conversationId = conversationId)

    /** A snapshot that accepts the conversations in [accepted], with [senders] posting in [group]. */
    private fun snapshot(
        accepted: Set<String> = emptySet(),
        verified: Set<String> = emptySet(),
        authored: Set<String> = emptySet(),
        senders: List<String> = emptyList(),
    ) = AcceptanceSnapshot({ accepted }, { verified }, { authored }, { id -> if (id == group) senders else emptyList() })

    private val acceptsContact: (String) -> Boolean = { it == contact }

    @Test
    fun aStrangersRowsAloneHoldTheAttachment() {
        assertTrue(RequestHold.held(listOf(row(stranger), row(stranger)), acceptsContact))
    }

    @Test
    fun noRowsHoldNothing() {
        assertFalse(RequestHold.held(emptyList(), acceptsContact))
    }

    @Test
    fun anyRowThatWouldFetchItReleasesIt() {
        assertFalse("our own", RequestHold.held(listOf(row(stranger), row(stranger, sender = me, own = true)), acceptsContact))
        assertFalse("tapped", RequestHold.held(listOf(row(stranger, approved = true)), acceptsContact))
        assertFalse("an accepted conversation's", RequestHold.held(listOf(row(stranger), row(contact, sender = contact)), acceptsContact))
    }

    @Test
    fun onlyTheReleasingRowsAreKeptInTheirOrder() {
        val rows = listOf(row(stranger), row(contact, sender = contact), row(stranger, approved = true))
        assertEquals(listOf(rows[1], rows[2]), RequestHold.releasing(rows, acceptsContact))
    }

    @Test
    fun aHeldStrangersRowIsNeverAskedFirstEvenOnceATapApprovedIt() {
        // #321: the tap approves every row naming the hash, so the stranger's earlier row releases it too — and still the
        // contact's row names the holder. Untapped, the stranger's row does not release at all.
        val untapped = listOf(row(stranger), row(contact, sender = contact))
        val tapped = listOf(row(stranger, approved = true), row(contact, sender = contact, approved = true))
        assertEquals(contact, RequestHold.askedFirst(untapped, acceptsContact, emptySet()))
        assertEquals(listOf(tapped[1]), RequestHold.judging(tapped, acceptsContact))
        assertEquals(contact, RequestHold.askedFirst(tapped, acceptsContact, emptySet()))
    }

    @Test
    fun aBlockedSendersRowReleasesTheFetchAndIsNeverAskedFirst() {
        // A thread accepted before its sender was blocked stays accepted: the row fetches, and names nobody.
        val blockedFriend = "blocked-friend"
        val accepts: (String) -> Boolean = { it == contact || it == blockedFriend }
        val rows = listOf(row(stranger), row(blockedFriend, sender = blockedFriend), row(contact, sender = contact))
        assertEquals(contact, RequestHold.askedFirst(rows, accepts, setOf(blockedFriend)))
        assertEquals("unblocked, the earlier row names its sender", blockedFriend, RequestHold.askedFirst(rows, accepts, emptySet()))
        val alone = listOf(row(blockedFriend, sender = blockedFriend))
        assertFalse("their row still fetches it", RequestHold.held(alone, accepts))
        assertEquals("and names no holder", null, RequestHold.askedFirst(alone, accepts, setOf(blockedFriend)))
    }

    @Test
    fun ourOwnRowAndATappedRowAloneStillSayWhoIsAsked() {
        val own = row(stranger, sender = me, own = true)
        assertEquals("our own row names us", me, RequestHold.askedFirst(listOf(row(stranger), own), acceptsContact, emptySet()))
        // Only tapped rows: the tap is the consent, and they are all there is to go by, in their order.
        val tappedOnly = listOf(row(stranger), row(stranger, sender = "another", approved = true), row(stranger, approved = true))
        assertEquals(listOf(tappedOnly[1], tappedOnly[2]), RequestHold.judging(tappedOnly, acceptsContact))
        assertEquals("another", RequestHold.askedFirst(tappedOnly, acceptsContact, emptySet()))
        assertEquals(
            "a blocked sender's tapped row is passed over",
            stranger,
            RequestHold.askedFirst(tappedOnly, acceptsContact, setOf("another")),
        )
    }

    @Test
    fun noReleasingRowNamesNobody() {
        assertEquals(emptyList<AttachmentFetchRow>(), RequestHold.judging(listOf(row(stranger), row(stranger)), acceptsContact))
        assertEquals(null, RequestHold.askedFirst(listOf(row(stranger), row(stranger)), acceptsContact, emptySet()))
        assertEquals(null, RequestHold.askedFirst(emptyList(), acceptsContact, emptySet()))
    }

    @Test
    fun aTappedRequestsRowNeverMovesAContactsDeadline() {
        // The deadline folds the judging rows (`PullThroughGate.until`): a stranger's later DM row, or a stranger's group
        // row, approved by the tap, neither stretches nor lifts the window of the contact's DM.
        fun dm(
            sender: String,
            sentAt: Long,
            isDm: Boolean = true,
        ) = AttachmentFetchRow("h", sender, null, approved = true, own = false, sentAt = sentAt, dm = isDm, conversationId = sender)
        val contactsWindow = 2_000L + ForwardRepository.DEFAULT_TTL_MS
        val stretched = listOf(dm(stranger, 1_000L), dm(contact, 2_000L), dm(stranger, 9_000_000L))
        assertEquals(contactsWindow, PullThroughGate.until(RequestHold.judging(stretched, acceptsContact)))
        val lifted = listOf(dm(stranger, 1_000L, isDm = false), dm(contact, 2_000L))
        assertEquals(contactsWindow, PullThroughGate.until(RequestHold.judging(lifted, acceptsContact)))
        assertEquals("every releasing row would have lifted it", null, PullThroughGate.until(RequestHold.releasing(lifted, acceptsContact)))
    }

    @Test
    fun aPublicRoomOrACommonsIsNeverARequest() =
        runTest {
            val acceptance = snapshot()
            assertTrue(acceptance.isAccepted(Conversations.NEARBY, stranger))
            assertTrue(acceptance.isAccepted(Conversations.commonsIdFor("ab".repeat(32)), stranger))
            assertTrue(acceptance.isAccepted(Conversations.MESHTASTIC, stranger))
            assertFalse(acceptance.isAccepted(stranger))
        }

    @Test
    fun aDmIsAcceptedByTheAcceptListAVerificationOrAMessageWeWrote() =
        runTest {
            assertTrue(snapshot(accepted = setOf(stranger)).isAccepted(stranger))
            assertTrue(snapshot(verified = setOf(stranger)).isAccepted(stranger))
            assertTrue(snapshot(authored = setOf(stranger)).isAccepted(stranger))
        }

    @Test
    fun aGroupAKnownSenderPostedInIsAcceptedAndOneOnlyAStrangerPostedInIsNot() =
        runTest {
            assertTrue(snapshot(accepted = setOf(contact), senders = listOf(stranger, contact)).isAccepted(group))
            assertFalse(snapshot(accepted = setOf(contact), senders = listOf(stranger)).isAccepted(group))
            // A frame not stored yet counts its own sender: the contact's first post accepts it, a stranger's does not.
            assertTrue(snapshot(accepted = setOf(contact), senders = listOf(stranger)).isAccepted(group, sender = contact))
            assertFalse(snapshot(accepted = setOf(contact)).isAccepted(group, sender = stranger))
        }

    @Test
    fun aSnapshotReadsEachSourceOnceAndARoomNone() =
        runTest {
            var reads = 0
            val acceptance =
                AcceptanceSnapshot(
                    { setOf<String>().also { reads++ } },
                    { setOf<String>().also { reads++ } },
                    { setOf<String>().also { reads++ } },
                    { emptyList<String>().also { reads++ } },
                )
            acceptance.isAccepted(Conversations.NEARBY)
            assertEquals("a room reads nothing", 0, reads)
            acceptance.acceptedAmong(listOf(stranger, contact, group, group))
            assertEquals("three sets and one group's senders", 4, reads)
        }

    @Test
    fun aCarriedFrameIsOursOnlyWhenAddressedToUsAndNotOurOwn() {
        val pair = setOf(me, stranger)
        assertEquals(stranger, RequestHold.conversationOf(CarriedAttachment("h", me, 0L, senderId = stranger), me))
        assertEquals(pair2, RequestHold.conversationOf(CarriedAttachment("h", null, 0L, stranger, pair2, roster = pair), me))
        assertEquals(null, RequestHold.conversationOf(CarriedAttachment("h", "carol", 0L, senderId = stranger), me))
        assertEquals(null, RequestHold.conversationOf(CarriedAttachment("h", null, 0L, stranger, pair2, roster = setOf(stranger, "x")), me))
        assertEquals(null, RequestHold.conversationOf(CarriedAttachment("h", null, 0L, me, pair2, member = true, roster = pair), me))
    }

    /** #282's B2: a stranger who lists us only as departed still addresses us, and our group row says so too. */
    @Test
    fun aGroupFrameListingUsOnlyAsDepartedOrHeldAsOurGroupIsOurs() {
        val departed = CarriedAttachment("h", null, 0L, stranger, pair2, roster = setOf(stranger), departed = setOf(me))
        assertEquals(pair2, RequestHold.conversationOf(departed, me))
        val ourRow = CarriedAttachment("h", null, 0L, stranger, pair2, member = true, roster = setOf(stranger))
        assertEquals(pair2, RequestHold.conversationOf(ourRow, me))
    }

    /**
     * #282's B3: a group naming anyone besides us and its sender — in its roster or its departed list — is carried for
     * them, under the carrier rules; so is one whose roster is unknown.
     */
    @Test
    fun aGroupFrameNamingAThirdPartyIsCarriedForThem() {
        assertEquals(
            null,
            RequestHold.conversationOf(CarriedAttachment("h", null, 0L, stranger, group, roster = setOf(me, stranger, contact)), me),
        )
        val thirdDeparted = CarriedAttachment("h", null, 0L, stranger, pair2, roster = setOf(me, stranger), departed = setOf(contact))
        assertEquals(null, RequestHold.conversationOf(thirdDeparted, me))
        assertEquals(null, RequestHold.conversationOf(CarriedAttachment("h", null, 0L, stranger, pair2, member = true), me))
    }

    /**
     * #282's B3, shared by `InboundPipeline.onCarriedFrame` and the re-arm's fast-only hold (#291): a request group naming
     * a third member is carried for that member; accepted, it is ours; a DM, a two-member group or our own frame is never
     * somebody else's carrier copy by this rule.
     */
    @Test
    fun aRequestGroupNamingAThirdMemberIsCarriedForOthersUntilItIsAccepted() =
        runTest {
            val third = CarriedAttachment("h", null, 0L, stranger, group, member = true, roster = setOf(me, stranger, contact))
            assertTrue(RequestHold.carriedForOthers(third, me, snapshot()))
            assertFalse("accepted", RequestHold.carriedForOthers(third, me, snapshot(accepted = setOf(group))))
            assertFalse(
                "a contact posted in it",
                RequestHold.carriedForOthers(third, me, snapshot(accepted = setOf(contact), senders = listOf(contact))),
            )
            val two = CarriedAttachment("h", null, 0L, stranger, pair2, member = true, roster = setOf(me, stranger))
            assertFalse("only us and its sender", RequestHold.carriedForOthers(two, me, snapshot()))
            assertFalse("a DM", RequestHold.carriedForOthers(CarriedAttachment("h", me, 0L, senderId = stranger), me, snapshot()))
            assertFalse("our own", RequestHold.carriedForOthers(third.copy(senderId = me), me, snapshot()))
        }

    @Test
    fun aCarriedHashIsHeldOnlyWhenEveryFrameNamingItIsAHeldRequestsToUs() {
        val acceptedSender: (String, String) -> Boolean = { _, sender -> sender == contact }
        val toUs = CarriedAttachment("h", me, 0L, senderId = stranger)
        val forCarol = CarriedAttachment("h", "carol", 0L, senderId = stranger)
        val contactsGroup = CarriedAttachment("h", null, 0L, contact, pair2, member = true, roster = setOf(me, contact))
        assertTrue(RequestHold.carriedHeld(listOf(toUs), me, acceptedSender))
        assertFalse("one frame carried for somebody else", RequestHold.carriedHeld(listOf(toUs, forCarol), me, acceptedSender))
        assertFalse("one from an accepted sender", RequestHold.carriedHeld(listOf(toUs, contactsGroup), me, acceptedSender))
        assertFalse("none", RequestHold.carriedHeld(emptyList(), me, acceptedSender))
    }
}
