package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Store-and-forward: a message sent while its recipient is out of reach still arrives — the product's core
 * promise, and the custody rules (who carries what, the digest exchange, the re-offer on a fresh link, the
 * seeds an absent member is still owed) that the sealed-receipts work rewrote. Every case ends in the full
 * oracle, so the carrier's store is checked against the parties' as well as the messages themselves.
 */
@RunWith(RobolectricTestRunner::class)
class CustodyLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * A line Alice–Bob–Carol. Carol walks away; Alice sends her a DM, which only Bob can carry; Alice walks
     * away too. Carol comes back to Bob alone and gets the DM from his custody; Alice comes back and gets
     * Carol's receipt the same way.
     */
    @Test
    fun dmSentWhileTheRecipientWasAwayArrivesThroughACarrier() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            assertTrue(alice.sendDm(carol, "for carol, via bob"))
            lab.unlink(alice, bob)

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 1) { it.dmWith(alice) }

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }

    /**
     * The group form of the same walk: Carol is away when Alice creates the group and sends its first message,
     * so the sender-key seed and the roster-carrying frame both reach Carol from Bob's custody rather than from
     * Alice — the seed-before-roster pair, served in whichever order the digest diff produces.
     */
    @Test
    fun groupCreatedWhileAMemberWasAwayArrivesThroughACarrier() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded while carol was out"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
            lab.unlink(alice, bob)

            lab.link(bob, carol)
            lab.assertConverged(listOf(bob, carol), atLeast = 1) { groupId }

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
        }

    /**
     * A receipt that lands before its DM (#100). Bob answers Alice with the cleartext receipt, which purges the DM
     * from every carrier that holds it. Dave alone hears Alice's DM and hands it to Bob; Carol then hears Bob's
     * receipt with no DM to purge, and only afterwards meets Alice, who has not heard it yet and serves her the DM.
     * A carrier in Carol's place used to keep that DM for its full day while everyone else had purged it; the walk
     * pins both orders that would hide it (Bob's jittered relay reaching Carol ahead of the receipt, and Alice
     * purging on Carol's receipt before serving her the DM), so it fails every time without the fix.
     */
    @Test
    fun aDmWhoseCleartextReceiptLandsFirstIsCarriedByNobody() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to dave, dave to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol, dave)
            bob.answerWithCleartextReceipts(alice)
            lab.unlink(dave, bob)
            lab.unlink(bob, carol)

            assertTrue(alice.sendDm(bob, "acked in the clear"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "acked in the clear")
            lab.await(1) { if (dm in dave.custodyIds()) 1 else 0 }
            lab.unlink(alice, dave)

            // Bob delivers it, and his receipt purges his copy and Dave's.
            lab.link(dave, bob)
            lab.await(1) {
                val delivered = bob.decrypted(bob.dmWith(alice)).isNotEmpty()
                if (delivered && dm !in bob.custodyIds() && dm !in dave.custodyIds()) 1 else 0
            }
            lab.unlink(dave, bob)

            // Carol takes the receipt from Bob's custody, then meets Alice with it. Bob's relay of the DM reads its
            // targets after the router's jitter, which can run out after this link (no count of his relays says it
            // has not), so the air loses his relays toward Carol: the DM cannot reach her ahead of the receipt, and
            // the receipt crosses as a custody serve at hop 0. The link is down again before anyone sends toward
            // Carol, so nothing later needs his relays over it.
            bob.transport.connect(carol.transport, lossy = { it.hops > 0 })
            lab.awaitCustodyParity(bob, carol)
            lab.unlink(bob, carol)
            // Carol's frames to Alice are held from the link's first frame, digests excepted: Alice serves the DM on
            // Carol's digest before she can hear the receipt, and Carol's own relay of the receipt, however late its
            // jitter runs out, parks behind the hold. The serve landing on Carol, and her router finishing it, is what
            // says she handled it.
            lab.link(carol, alice) { carol.transport.hold(alice.transport) }
            lab.await(1) {
                // Each line reads "#seq <receiver> <type> <id> …"; Alice's earlier send to Dave is already in the log.
                alice.transport.sent.count { line ->
                    val cols = line.split(' ')
                    cols.getOrNull(3) == dm && carol.nodeId.startsWith(cols[1])
                }
            }
            carol.transport.awaitInboundDrained()
            carol.transport.release(alice.transport)
            lab.await(1) { if (dm !in alice.custodyIds()) 1 else 0 }

            lab.linkAll(alice to dave, dave to bob, bob to carol)
            bob.answerWithCleartextReceipts(alice, cleartext = false)
            assertTrue(bob.sendDm(alice, "a sealed reply"))
            val thread = { n: LabNode -> n.dmWith(if (n === alice) bob else alice) }
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol, dave), conversation = thread)
        }

    /**
     * A first contact whose ✓✓ lands late (#301). Bob knows Alice from a contact card alone when her first DM reaches him,
     * and his ✓✓ for it is held until his first sealed frame has confirmed Alice's session: her DM still shows it.
     */
    @Test
    fun aCleartextReceiptForAFirstContactLandingAfterTheSessionConfirmedStillTicksIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            bob.answerWithCleartextReceipts(alice)
            bob.transport.hold(alice.transport)

            assertTrue(alice.sendDm(bob, "first words"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "first words")
            assertFalse("a first contact carries alice's init", alice.row(dm)?.sealedConfirmed == true)
            lab.await(1) { if (cleartextReceiptsFor(bob, alice, dm).isNotEmpty()) 1 else 0 }

            val receipts = cleartextReceiptsFor(bob, alice, dm)
            bob.answerWithCleartextReceipts(alice, cleartext = false)
            assertTrue(bob.sendDm(alice, "a sealed reply"))
            // lab-lint: allow filtered-release-then-oracle — the receipts it leaves out are sent again below, once the
            // session has confirmed.
            bob.transport.release(alice.transport, keepHolding = true) { held -> held.filter { frame -> frame !in receipts } }
            lab.await(1) { if (alice.session(bob)?.confirmed == true) 1 else 0 }
            assertTrue("no ✓✓ yet", bob.nodeId !in alice.receiptPlanes(dm))

            receipts.forEach { frame -> bob.transport.send(frame, Peer(alice.nodeId)) }
            bob.transport.release(alice.transport)
            lab.awaitReceipt(alice, dm, bob)
            assertEquals(0L, alice.drops(DropReason.CLEARTEXT_ANSWER_FROM_SEALING_PEER))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { n -> n.dmWith(if (n === alice) bob else alice) }
        }

    /**
     * An accepted cost of #301, pinned so it stays a lost ✓✓ and never a false one. Bob knows Alice from a contact card
     * alone (his pin of her carries no `CAP_RATCHET`) when her group's seed reaches him, and his ack of the seed confirms
     * her session. Her next DM is sealed inside that session, and the receipt Bob sends for it before her profile reaches
     * him does not tick it. The group's posts and ticks still converge.
     *
     * Bob's way to Alice is held until his seed ack is parked on it, so the scenario shows that ack is the one sealed DM
     * of his that reaches her before her session confirms: unconfirmed while it waits, confirmed with her outbox showing
     * the seed acked once it alone of his sealed DMs has crossed.
     */
    @Test
    fun aDmSentAfterASeedAckFromACardOnlyContactShowsNoTickFromItsReceipt() =
        runBlocking {
            // No boards, whatever the run: the held link must be Bob's only way to Alice.
            val alice = lab.node("alice", board = false).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", board = false).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            bob.answerWithCleartextReceipts(alice)

            val groupId = alice.createGroup(bob)
            bob.transport.hold(alice.transport)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.await(1) { if (bob.decrypted(groupId).any { (_, body) -> body == "founded" }) 1 else 0 }
            lab.await(1) { if (sealedDmsHeld(bob.transport.held(alice.transport), bob, alice).isNotEmpty()) 1 else 0 }
            val parked = bob.transport.held(alice.transport)
            assertEquals("bob's sealed DMs to alice before her session confirms", 1, sealedDmsHeld(parked, bob, alice).size)
            assertFalse("alice confirmed with bob's way to her held", alice.session(bob)?.confirmed == true)
            assertEquals("alice's outbox saw bob's seed ack early", 0, seedAckedBy(alice, groupId, bob))

            bob.transport.releaseFirst(alice.transport, parked.size)
            // The ack lands on her outbox after the commit that confirms her session, so it is the one awaited.
            lab.await(1) { if (seedAckedBy(alice, groupId, bob) > 0) 1 else 0 }
            assertTrue("alice unconfirmed after bob's seed ack", alice.session(bob)?.confirmed == true)
            bob.transport.release(alice.transport)

            assertTrue(alice.sendDm(bob, "after the seed"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "after the seed")
            assertTrue("sealed inside the confirmed session", alice.row(dm)?.sealedConfirmed == true)
            lab.await(1) {
                val ticked = if (bob.nodeId in alice.receiptPlanes(dm)) 1 else 0
                ticked + alice.drops(DropReason.CLEARTEXT_ANSWER_FROM_SEALING_PEER).toInt()
            }
            assertFalse("alice's DM shows a ✓✓", bob.nodeId in alice.receiptPlanes(dm))
            assertEquals(1L, alice.drops(DropReason.CLEARTEXT_ANSWER_FROM_SEALING_PEER))

            bob.answerWithCleartextReceipts(alice, cleartext = false)
            assertTrue(bob.sendGroup(groupId, "a reply"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { groupId }
        }

    /** The DM-form sealed frames among [frames] that [from] addressed to [to]: no group header, a ciphertext. */
    private fun sealedDmsHeld(
        frames: List<WireEnvelope>,
        from: LabNode,
        to: LabNode,
    ): List<WireEnvelope> =
        frames.filter { frame ->
            val env = WireCodec.decodeEnvelope(frame.signed)
            val enc = env?.takeIf { e -> e.type == FrameType.CHAT }?.let { e -> WireCodec.decodePayload<ChatContent>(e.payload)?.enc }
            env != null && env.senderId == from.nodeId && env.recipientId == to.nodeId && env.group == null && enc != null
        }

    /** The epoch [member] has acknowledged of [author]'s seed for [groupId], as [author]'s outbox records it (0: none). */
    private suspend fun seedAckedBy(
        author: LabNode,
        groupId: String,
        member: LabNode,
    ): Int =
        author.db
            .groupRatchetDao()
            .keySend(groupId, member.nodeId)
            ?.ackedEpoch ?: 0

    /** The receipt frames parked on [from]'s pipe to [to] that name [id]. */
    private fun cleartextReceiptsFor(
        from: LabNode,
        to: LabNode,
        id: String,
    ): List<WireEnvelope> =
        from.transport.held(to.transport).filter { frame ->
            val env = WireCodec.decodeEnvelope(frame.signed)
            env?.type == FrameType.RECEIPT && WireCodec.decodePayload<ReceiptContent>(env.payload)?.ackId == id
        }

    /**
     * Partition and merge. Alice and Bob know each other, lose each other, and both keep talking — Alice even
     * starts a group with Bob while they are apart. On the merge every message crosses in both directions from
     * custody alone: the DM each sealed to the other, the group's seed and its roster, and then the receipts.
     */
    @Test
    fun bothSidesSendWhileApartAndMerge() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            lab.unlink(alice, bob)
            assertTrue(alice.sendDm(bob, "alice, apart"))
            assertTrue(bob.sendDm(alice, "bob, apart"))
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "group, apart"))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
        }

    /**
     * A node's own frame is in its custody before it is on the air (#141). Alice posts while Bob is out of range and
     * her database is slow to commit the custody row; Bob comes back while the write is still landing. The link-up's
     * digest exchange reads a store without the post, so it is the flood that must carry it — and the flood goes out
     * only once the row has landed, to whoever is linked by then. Flooding first had nobody to reach and left Bob
     * without the post until the 60 s re-offer.
     */
    @Test
    fun aPostWhoseCustodyWriteIsSlowReachesAPeerThatLinksMeanwhile() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            lab.unlink(alice, bob)
            // Bob's sign that Alice has answered his link-up digest: a post he can only have from her custody.
            assertTrue(alice.sendRoom("from custody"))
            alice.custodyGate.hold { it.senderId == alice.nodeId && it.type == FrameType.CHAT }
            val slow = async { alice.sendRoom("slow to commit") }
            lab.await(1) { alice.custodyGate.parked }

            lab.link(alice, bob)
            lab.await(1) { if ("from custody" in bob.roomPosts()[alice.nodeId].orEmpty()) 1 else 0 }
            alice.custodyGate.release()
            assertTrue(slow.await())
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { Conversations.NEARBY }
        }

    /**
     * #176's second path, the corrected two-holder recipe of its 2026-10-16 13:56 comment: a back-fill fed by two
     * holders hands a delivered DM back to its recipient past her router's seen window. Alice, Bob and Carol all hold
     * Bob's DM to Alice, and Alice's receipt has reached Bob. Ten minutes on, Dave, who lacks it, links Alice with
     * her frames to him held (her own copy cannot cross first), then Carol, whose custody serves him the DM; his relay
     * reaches Alice as a fresh flood. Her exists-gate used to seal a second receipt under a fresh id. Inside the re-arm
     * floor, with her first receipt still live in her custody and the session root unchanged, it seals nothing
     * (ADR 2026-10.8hn6).
     */
    @Test
    fun aDeliveredDmBackFilledToItsRecipientPastItsSeenWindowDrawsNoSecondReceipt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(bob.sendDm(alice, "for alice"))
            val dm = bob.ownMessageId(bob.dmWith(alice), "for alice")
            val thread = { n: LabNode -> if (n === alice) n.dmWith(bob) else n.dmWith(alice) }
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol), conversation = thread)
            // Her one receipt is counted only after its send returns, so Bob's tick (and the oracle) can come first.
            lab.await(1) {
                alice.metrics
                    .receipts()
                    .receiptsSealed
                    .toInt()
            }
            val sealed = alice.metrics.receipts().receiptsSealed

            // Everyone apart, and past Alice's seen window for the DM (cut first, so no late copy lands after the jump).
            lab.unlink(alice, bob)
            lab.unlink(bob, carol)
            lab.clock.advance(SeenSet.DEFAULT_TTL_MS + 60_000L)

            val skippedBefore = alice.metrics.receipts().receiptsReAckSkipped
            val heldBefore = alice.metrics.receipts().receiptsHeld
            lab.link(alice, dave) { alice.transport.hold(dave.transport) }
            alice.transport.awaitNeighborsHandled()
            dave.transport.awaitNeighborsHandled()
            lab.link(carol, dave)
            // Dave's relay of Carol's copy landing on Alice, and her router finishing it, is the re-delivery.
            lab.await(1) {
                dave.transport.sent.count { line ->
                    val cols = line.split(' ')
                    cols.getOrNull(3) == dm && alice.nodeId.startsWith(cols[1])
                }
            }
            alice.transport.awaitInboundDrained()
            // Await the answer itself, not only the drain: a skip, a seal or a hold — the asserts below say which it had to
            // be. (The drain once returned at a board node's merge channel, before the answer: KNIT_LAB_BOARDS seed 2008.)
            lab.await(1) {
                val r = alice.metrics.receipts()
                ((r.receiptsReAckSkipped - skippedBefore) + (r.receiptsHeld - heldBefore) + (r.receiptsSealed - sealed)).toInt()
            }
            alice.transport.release(dave.transport)

            lab.linkAll(alice to bob, bob to carol)
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol, dave), conversation = thread)
            assertEquals("Alice sealed one receipt for Bob's DM in all", sealed, alice.metrics.receipts().receiptsSealed)
            assertTrue(
                "Alice's re-ack gate met Dave's copy and answered it with the receipt she holds",
                alice.metrics.receipts().receiptsReAckSkipped > skippedBefore,
            )
        }
}
