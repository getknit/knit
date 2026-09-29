package app.getknit.knit.mesh.lab

import kotlinx.coroutines.runBlocking
import org.junit.After
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

            // Bob delivers it, and his receipt purges his copy and Dave's. His relay of the DM fires here, while Dave
            // is his only link, so it cannot reach Carol ahead of the receipt.
            val bobRelayed = bob.metrics.snapshot().framesRelayed
            lab.link(dave, bob)
            lab.await(1) {
                val delivered = bob.decrypted(bob.dmWith(alice)).isNotEmpty()
                if (delivered && dm !in bob.custodyIds() && dm !in dave.custodyIds()) 1 else 0
            }
            lab.await(1) { (bob.metrics.snapshot().framesRelayed - bobRelayed).toInt() }
            lab.unlink(dave, bob)

            // Carol takes the receipt from Bob's custody, then meets Alice with it — once her own relay of it has fired,
            // so it cannot slip to Alice past the hold below.
            val carolRelayed = carol.metrics.snapshot().framesRelayed
            lab.link(bob, carol)
            lab.awaitCustodyParity(bob, carol)
            lab.await(1) { (carol.metrics.snapshot().framesRelayed - carolRelayed).toInt() }
            lab.unlink(bob, carol)
            // Carol's frames to Alice are held, digests excepted: Alice serves the DM on Carol's digest before she can
            // hear the receipt. The serve landing on Carol, and her router finishing it, is what says she handled it —
            // not her relay count, which her own leftover relay of the receipt can move first.
            carol.transport.connect(alice.transport, publish = false)
            carol.transport.hold(alice.transport)
            carol.transport.publishNeighbors()
            alice.transport.publishNeighbors()
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
}
