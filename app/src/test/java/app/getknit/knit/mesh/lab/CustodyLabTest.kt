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
