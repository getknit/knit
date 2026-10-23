package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.spool.PublicSpool
import app.getknit.knit.mesh.spool.PublicSpools
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Homes between real stacks (ADR 2026-10.8kfx, knit-spool-coordinator ADR 2026-10.txjf): each node lives on one house
 * relay, issues the configs that tell its peer, and syncs a DM where the conversation's config says, over in-process
 * relays per host ([LabPublicRelays]). The issuer and the scope table run on their own events; each step waits on the
 * state it needs, never on an order of events.
 */
@RunWith(RobolectricTestRunner::class)
class HomeSpoolsLabTest {
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

    private fun url(n: Int) = PublicSpools.urlOf(LabPublicRelays.host(n))

    private suspend fun LabNode.relaysDialled(): Set<String> = manager.spoolStatus().mapTo(HashSet()) { it.url }

    /**
     * Alice on public-2 and Bob on public-3 meet over the radio, so each holds a confirmed session, and part: on return
     * both hold one config naming both homes, the DM scope converged at both, and neither dials public-1 any more.
     */
    private suspend fun twoHomes(relays: LabPublicRelays): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", publicRelays = relays, home = LabPublicRelays.host(2)).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", publicRelays = relays, home = LabPublicRelays.host(3)).apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        assertTrue(alice.sendDm(bob, "hello"))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
        assertTrue(bob.sendDm(alice, "hi"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        val both = setOf(url(2), url(3))
        lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
            val mine = alice.scopeConfig(bob.nodeId)
            if (mine?.spools?.toSet() == both && bob.scopeConfig(alice.nodeId) == mine) 1 else 0
        }
        lab.unlink(alice, bob)
        lab.awaitDmScope(alice, bob)
        lab.awaitDmScope(bob, alice)
        lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.relaysDialled() == both && bob.relaysDialled() == both) 1 else 0 }
        return alice to bob
    }

    @Test
    fun aDmBetweenTwoHomesMeetsAtBothAndLeavesPublicOneAlone() =
        runBlocking {
            val relays = LabPublicRelays(1, 2, 3)
            val (alice, bob) = twoHomes(relays)
            // Each thread holds the two radio messages; the third and fourth cross the relays.
            assertTrue(alice.sendDm(bob, "across two homes"))
            lab.await(3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "and back"))
            lab.await(4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.decrypted(alice.dmWith(bob)).size }
            // The DM scope is carried by the two homes' workers alone, on both sides: public-1 is dialled for nothing.
            for ((node, peer) in listOf(alice to bob, bob to alice)) {
                val status = node.manager.spoolStatus()
                val scope = status.flatMap { it.scopes }.first { it.label == peer.nodeId && !it.retiring }.scopeHex
                val carriers = status.filter { relay -> relay.scopes.any { it.scopeHex == scope } }
                assertEquals(setOf(url(2), url(3)), carriers.mapTo(HashSet()) { it.url })
            }
            lab.assertConverged(listOf(alice, bob), atLeast = 4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
        }

    @Test
    fun aHomeThatDrainsMovesAndTheConversationLeavesIt() =
        runBlocking {
            val relays = LabPublicRelays(1, 2, 3)
            val (alice, bob) = twoHomes(relays)
            val draining = relays.directory.map { if (it.host == LabPublicRelays.host(2)) it.copy(state = PublicSpool.DRAINING) else it }
            bob.putDirectory(draining)
            alice.restart { putDirectory(draining) }
            // The start settles the home on its own coroutine.
            lab.await(1) { if (alice.home() != LabPublicRelays.host(2)) 1 else 0 }
            val moved = checkNotNull(alice.home())
            assertNotEquals(LabPublicRelays.host(2), moved)
            // Whichever open relay Alice's hash picked, the DM's config ends naming her new home and Bob's alone.
            val after = setOf(PublicSpools.urlOf(moved), url(3))
            lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                val mine = alice.scopeConfig(bob.nodeId)
                if (mine?.spools?.toSet() == after && bob.scopeConfig(alice.nodeId) == mine) 1 else 0
            }
            lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                if (alice.relaysDialled() == after &&
                    bob.relaysDialled() == after
                ) {
                    1
                } else {
                    0
                }
            }
            assertTrue(alice.sendDm(bob, "from the new home"))
            lab.await(3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "heard"))
            lab.await(4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.decrypted(alice.dmWith(bob)).size }
            lab.assertConverged(listOf(alice, bob), atLeast = 4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
        }
}
