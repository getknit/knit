package app.getknit.knit.mesh.lab

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One backup restored onto two phones (work item #80, ADR 2026-09.ypcc): two nodes over one identity, which
 * can never link to each other (the radios drop their own node id, and so does `LabTransport.connect`) and
 * meet only through a third. Each twin must light its "also active on another phone" state from the other's
 * profile stamp, the first detection's counter-flood must be what lets the far side see it, and once one
 * twin is gone the survivor and the third node must converge as if nothing happened.
 *
 * Bob holds one twin at a time on purpose: the lab keys a link by node id, and so would a real neighbor set,
 * so the scenario alternates the links — which is also how two phones on one identity meet the mesh, one
 * pocket at a time.
 */
@RunWith(RobolectricTestRunner::class)
class CloneLabTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    @Test
    fun bothTwinsLightUpAndTheSurvivorConvergesOnceTheOtherSignsOut() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            // The restored phone carries the same name — it is the same person, on paper.
            val twin = lab.node("alice-twin", sameIdentityAs = alice).apply { setDisplayName("Alice") }
            assertEquals("one identity", alice.nodeId, twin.nodeId)
            val bob = lab.node("bob").apply { setDisplayName("Bob") }

            // 1. Alice meets Bob and edits her profile AFTER the twin booted, so Bob custodies a stamp the twin
            //    never minted and cannot have out-stamped at its own boot (the calendar is real; the jump keeps
            //    the order honest on a slow core).
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.clock.advance(1_000)
            alice.setDisplayName("Alice (phone A)")
            lab.awaitCustodyParity(alice, bob)
            assertEquals("nothing to see yet", 0L, alice.settings.cloneSeenAt.first())
            lab.unlink(alice, bob)

            // 2. Bob meets the twin: custody hands it Alice's stamp under its own node id.
            lab.clock.advance(1_000)
            lab.link(twin, bob)
            lab.awaitAcquainted(twin, bob)
            lab.await(1) { if (twin.settings.cloneSeenAt.first() > 0L) 1 else 0 }
            // The detection re-floods the twin's profile under a fresh stamp — the counter-signal Alice needs.
            lab.awaitCustodyParity(twin, bob)
            lab.unlink(twin, bob)

            // 3. Bob meets Alice again: the twin's counter-flood is newer than anything Alice published. Her
            //    own detection floods once more, which is also what puts her presentation back in front of Bob.
            lab.clock.advance(1_000)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.await(1) { if (alice.settings.cloneSeenAt.first() > 0L) 1 else 0 }
            assertTrue("both twins see it", twin.settings.cloneSeenAt.first() > 0L)

            // 4. The twin signs out — gone for good — and the survivor keeps a normal conversation with Bob,
            //    presented as herself again.
            lab.retire(twin)
            assertTrue(alice.sendDm(bob, "still me"))
            assertTrue(bob.sendDm(alice, "still you"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }
}
