package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The founding [MeshLab] scenarios: a message sent to someone who has never heard of the thread lands. The
 * group cases pin cf94a06 (seed-before-roster) from the *user's* side — "the first message in a new group
 * reaches every member" — through the real send path on the creator and the real inbound path on the member,
 * with nothing between them but an in-process link.
 */
@RunWith(RobolectricTestRunner::class)
class GroupFirstMessageLabTest {
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

    /** The baseline that proves the box itself: profiles cross a link and a first DM opens on the other side. */
    @Test
    fun firstDmLandsOnAStranger() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            assertTrue(alice.sendDm(bob, "hi bob"))

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * cf94a06, in the order the creator actually sends: `sealGroupRatchet` floods the sender-key seed *before*
     * the chat frame that carries the roster, so the member sees a key for a group it does not hold yet. Before
     * the fix the DM ratchet consumed that seed and the group's first message sat at GROUP_RATCHET_NO_KEY forever.
     */
    @Test
    fun firstGroupMessageLandsWhenTheSeedOutrunsTheRoster() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "welcome"))

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
        }

    /**
     * The other order custody can serve the same two frames in: the roster-carrying chat frame first, the seed
     * after. The member creates the group row, then adopts the seed and decrypts the custodied frame. The hold
     * window can also catch the intro driver's own DM (a ratchet confirmation after the profile exchange), so
     * the release moves the group frame to the front rather than blindly reversing.
     */
    @Test
    fun firstGroupMessageLandsWhenTheRosterOutrunsTheSeed() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "welcome"))
            val released = alice.transport.release(bob.transport) { batch -> batch.sortedByDescending { it.isGroupFrame() } }
            // Sanity on the fixture itself: the batch carried the group frame and at least one DM (the seed).
            val types = released.map { it.isGroupFrame() }
            assertTrue("held batch was $types, expected a group frame then a DM", types.first() && false in types)

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
        }

    /**
     * Bob receives the seed for a group he does not hold yet and his app dies before the group frame arrives.
     * Since work item #47 the seed carries its founding roster, so what he holds at the restart is the row
     * and the adopted chain — both committed with the seed — not an in-memory park (the park, and the
     * custody replay that healed a park lost to a process death, remain for a seed from a build without the
     * field; `InboundPipelineTest` pins them). On relaunch and re-link the group frame comes in from Alice's
     * custody and opens on its first pass.
     */
    @Test
    fun firstGroupMessageLandsWhenTheMemberRestartsBetweenTheSeedAndTheFrame() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "welcome"))
            // Let only the DMs (the seed among them) through; the group frame stays parked on the link.
            alice.transport.release(bob.transport) { batch -> batch.filterNot { it.isGroupFrame() } }
            assertTrue(
                "bob never pinned the group from the seed",
                lab.tryAwait(1) { if (bob.groupShape(groupId) != null) 1 else 0 },
            )
            assertEquals(0L, bob.metrics.snapshot().groupSeedsHeld)

            bob.restart() // drops the link and, with it, the still-held group frame
            lab.link(alice, bob)

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
        }

    private fun WireEnvelope.isGroupFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.group != null

    /**
     * Three members on a line — Carol reaches Alice only through Bob's relay — so the seed and the roster cross
     * a hop and land on a member the creator never linked to.
     */
    @Test
    fun firstGroupMessageLandsAcrossARelay() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "all three"))

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
        }
}
