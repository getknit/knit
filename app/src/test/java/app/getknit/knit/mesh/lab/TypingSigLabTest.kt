package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolSigInfo
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
 * Typing cues between real stacks with no radio path, over a relay's signal lane (spec §7.8, ADR 2026-10.97vs): a DM
 * cue both ways, a group cue to a member on the far island, and a phone with typing indicators off sending none. A
 * sig is never stored, so nothing here converges: what two nodes exchange is the cue, and the pins are that it lands
 * in the peer's typing map and in no store.
 */
@RunWith(RobolectricTestRunner::class)
class TypingSigLabTest {
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

    /** Whether [node] shows [typist] typing in [conversationId]: 1 or 0, for [MeshLab.await]. */
    private fun shows(
        node: LabNode,
        conversationId: String,
        typist: LabNode,
    ): Int =
        if (node.manager.typing.value[conversationId]
                ?.contains(typist.nodeId) == true
        ) {
            1
        } else {
            0
        }

    @Test
    fun aDmCueCrossesTheRelayWithNoRadioPathBothWays() =
        runBlocking {
            val spool = FakeSpool(sig = SIG_BOUNDS)
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(bob.sendDm(alice, "hello"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.unlink(alice, bob)
            // The sig door needs each side's answered SUB on the pair's current DM scope.
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            val pushedBefore = spool.pushed.size

            alice.manager.sendTyping(bob.nodeId)
            lab.await(1) { shows(bob, alice.nodeId, alice) }
            bob.manager.sendTyping(alice.nodeId)
            lab.await(1) { shows(alice, bob.nodeId, bob) }

            assertTrue("both cues rode the signal lane: ${spool.sigs}", spool.sigs.size >= 2)
            // A sig is never pushed. Not pinned to zero: a stray frame of the pair's (a late receipt, a profile) may push.
            assertTrue("a cue was pushed", spool.pushed.size - pushedBefore < 2)
            assertEquals("no thread gained a row", 2, bob.decrypted(bob.dmWith(alice)).size)
        }

    @Test
    fun aGroupCueReachesTheMemberOnTheFarIsland() =
        runBlocking {
            val spool = FakeSpool(sig = SIG_BOUNDS)
            val (alice, bob, carol) = lab.threeOnOneRelay(spool)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded together"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.awaitGroupScope(alice, groupId, bob, carol)
            lab.awaitGroupScope(carol, groupId, alice, bob)

            alice.manager.sendTyping(groupId)
            // Carol's only path is the relay; Bob may hear it over his link with Alice too, and keeps one copy.
            lab.await(1) { shows(carol, groupId, alice) }
            lab.await(1) { shows(bob, groupId, alice) }
        }

    /**
     * The switch off sends nothing on any plane. The barrier is the cue Bob sends once it is back on: a connection's
     * records reach the spool in order, so once that one has been fanned out to Alice, a cue the switch let through
     * before it would already be in the spool's count.
     */
    @Test
    fun typingIndicatorsOffSendsNoCue() =
        runBlocking {
            val spool = FakeSpool(sig = SIG_BOUNDS)
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(bob.sendDm(alice, "hello"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.unlink(alice, bob)
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            val sigsBefore = spool.sigs.size

            bob.settings.setTypingIndicatorsEnabled(false)
            bob.manager.sendTyping(alice.nodeId)
            bob.settings.setTypingIndicatorsEnabled(true)
            bob.manager.sendTyping(alice.nodeId)
            lab.await(1) { shows(alice, bob.nodeId, bob) }

            assertEquals("only the cue sent with the switch on reached the relay", sigsBefore + 1, spool.sigs.size)
        }

    private companion object {
        val SIG_BOUNDS = SpoolSigInfo(maxBytes = 8_192, rate = 2)
    }
}
