package app.getknit.knit.mesh.lab

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
 * The Your mesh screen's numbers, read off real stacks: the carrier in the line Alice–Bob–Carol is credited
 * for the DM it carried and handed straight to Carol, the two parties are credited nothing (the mesh
 * over-claims for nobody), and every node has met exactly the phones it was linked to. The walk is
 * [CustodyLabTest]'s, so the same convergence oracle brackets the counts.
 */
@RunWith(RobolectricTestRunner::class)
class ContributionLabTest {
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

    @Test
    fun theCarrierIsCreditedForTheDmItHandedOnAndThePartiesAreNot() =
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

            // The credit is booked after the serve's `send` returns (`ForwardSync.onDigest` → `onServed`), so
            // Carol can hold the DM — and the oracle can pass — a moment before Bob's ledger moves; and the
            // met set is written by its own `neighbors` collector, not by the link.
            assertTrue("bob was never credited", lab.tryAwait(1) { bob.contributions().deliveredToRecipient.toInt() })
            assertTrue("bob never recorded meeting both", lab.tryAwait(2) { bob.peopleMet() })
            val bobs = bob.contributions()
            assertTrue("bob handed the DM straight to carol: $bobs", bobs.deliveredToRecipient >= 1)
            assertTrue("handed-straight-to is a subset of passed-along: $bobs", bobs.passedAlong >= bobs.deliveredToRecipient)
            assertTrue("the first credit stamps since: $bobs", bobs.since > 0L)
            // Alice authored the DM and Carol was its addressee; neither did anything for anyone else.
            assertEquals("alice is credited nothing", 0L, alice.contributions().passedAlong)
            assertEquals("carol is credited nothing", 0L, carol.contributions().passedAlong)

            assertEquals("bob met both", 2, bob.peopleMet())
            assertEquals("alice met bob only", 1, alice.peopleMet())
            assertEquals("carol met bob only", 1, carol.peopleMet())
        }

    /** The numbers are lifetime ones: a relaunch reads them back, and re-linking the same phones adds no one. */
    @Test
    fun theNumbersSurviveARestart() =
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
            // Carol's row is readable before Bob's ledger is credited (the credit follows the serve's `send`).
            assertTrue("bob was never credited", lab.tryAwait(1) { bob.contributions().deliveredToRecipient.toInt() })
            assertTrue("bob never recorded meeting both", lab.tryAwait(2) { bob.peopleMet() })
            val before = bob.contributions()
            assertTrue("bob was credited before the restart: $before", before.deliveredToRecipient >= 1)

            bob.restart()
            lab.link(bob, carol)

            val after = bob.contributions()
            assertEquals("the persisted totals came back", before, after)
            assertEquals("the same phones are not met twice", 2, bob.peopleMet())
        }
}
