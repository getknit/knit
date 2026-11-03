package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import app.getknit.knit.mesh.protocol.PresenceKeyPayload
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
import kotlin.random.Random

/**
 * Presence keys a modified client hands over (ADR 2026-11.xp6d, "Colliding keys"; the security review of 2026-11-02),
 * beside `MaliciousForkLabTest`: a fork seals `CTL_PRESENCE_KEY` frames carrying keys it chose through the real seal
 * (`MeshManager.sendPresenceKeyAsAFork`). What the receiver must not do: list a key that is another contact's or its own
 * for anyone, keep more than three generations of a churning sender, or list a stranger's key before the user accepts them.
 */
@RunWith(RobolectricTestRunner::class)
class PresenceKeyForkLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    private val blind = LabLimits(blindAdvert = true)

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * Mallory, a direct contact of Alice's running a fork, churns generations, then hands Alice Carol's current key and
     * Alice's own as her own. Alice keeps three of Mallory's generations, and lists neither borrowed key for anyone —
     * Carol's included, until Carol rotates — counting each left out.
     */
    @Test
    fun aForkThatHandsAnotherContactsKeyOrOursAsItsOwnIsListedForNoOneAndKeptToThree() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val carol = lab.node("carol", limits = blind).apply { setDisplayName("Carol") }
            val mallory = lab.node("mallory", limits = blind).apply { setDisplayName("Mallory") }
            lab.linkAll(alice to carol, alice to mallory)
            lab.awaitAcquainted(alice, carol, mallory)
            converse(alice, carol)
            converse(alice, mallory)
            lab.await(1) { if (alice.presenceKeys.heldBy(carol.nodeId).size >= 2) 1 else 0 }
            val carolsKey = ownCurrentKey(carol)
            val alicesKey = ownCurrentKey(alice)

            val base = lab.clock.now() + FORK_AHEAD_MS
            repeat(
                CHURN,
            ) { assertTrue(mallory.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(base + it, Random.nextBytes(32)))) }
            assertTrue(mallory.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(base + CHURN, carolsKey)))
            assertTrue(mallory.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(base + CHURN + 1, alicesKey)))
            lab.await(1) { if (alice.presenceKeys.heldBy(mallory.nodeId).maxOfOrNull { it.generation } == base + CHURN + 1) 1 else 0 }

            assertEquals("three generations kept", 3, alice.presenceKeys.heldBy(mallory.nodeId).size)
            val (listed, excluded) = alice.presenceKeys.presenceKeysFor(setOf(carol.nodeId, mallory.nodeId))
            assertFalse("carol's borrowed key is listed for no one", listed.values.flatten().any { it.key.contentEquals(carolsKey) })
            assertFalse("our own key is listed for no one", listed.values.flatten().any { it.key.contentEquals(alicesKey) })
            assertEquals(
                "mallory's own last churned key alone",
                listOf(base + CHURN - 1),
                listed[mallory.nodeId].orEmpty().map { it.generation },
            )
            assertEquals("each left out is counted", 3, excluded)
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(mallory)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        carol
                    } else {
                        alice
                    },
                )
            }
        }

    /** A stranger's key is stored (it has a peer row and a confirmed session) but listed only once the user accepts them. */
    @Test
    fun aStrangersKeyIsStoredButListedOnlyOnceAccepted() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val stranger = lab.node("stranger", limits = blind).apply { setDisplayName("Stranger") }
            lab.link(alice, stranger)
            lab.awaitAcquainted(alice, stranger)
            assertTrue(stranger.sendDm(alice, "hello"))
            lab.await(1) { alice.decrypted(alice.dmWith(stranger)).size }
            lab.await(1) { if (stranger.session(alice)?.confirmed == true) 1 else 0 }

            val generation = lab.clock.now() + FORK_AHEAD_MS
            assertTrue(stranger.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(generation, Random.nextBytes(32))))
            lab.await(1) { if (alice.presenceKeys.heldBy(stranger.nodeId).any { it.generation == generation }) 1 else 0 }
            assertFalse(stranger.nodeId in alice.presenceKeys.presenceKeysFor(alice.directContacts.read()).first)

            alice.accept(alice.dmWith(stranger))
            lab.await(1) { if (stranger.nodeId in alice.directContacts.read()) 1 else 0 }
            assertTrue(stranger.nodeId in alice.presenceKeys.presenceKeysFor(alice.directContacts.read()).first)
            lab.assertConverged(listOf(alice, stranger), atLeast = 1) { it.dmWith(if (it === alice) stranger else alice) }
        }

    private suspend fun converse(
        a: LabNode,
        b: LabNode,
    ) {
        assertTrue(a.sendDm(b, "hi ${b.name}"))
        lab.await(1) { b.decrypted(b.dmWith(a)).size }
        assertTrue(b.sendDm(a, "hi ${a.name}"))
        lab.await(2) { a.decrypted(a.dmWith(b)).size }
    }

    private suspend fun ownCurrentKey(node: LabNode): ByteArray =
        checkNotNull(PresenceKeyPolicy.keysOf(node.presenceKeys.ownKeys()).current).key

    private companion object {
        /** How far ahead of the clock a fork's generations start: above every honest key, so its frames are stored. */
        const val FORK_AHEAD_MS = 60 * 60_000L
        const val CHURN = 6
    }
}
