package app.getknit.knit.mesh.lab

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ContactKey
import app.getknit.knit.mesh.bluetooth.ExpectedTagTable
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import app.getknit.knit.mesh.protocol.PresenceKeyPayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Presence keys a modified client hands over (ADR 2026-11.xp6d's owner-bound amendment; the security review of
 * 2026-11-02), beside `MaliciousForkLabTest`: a fork seals `CTL_PRESENCE_KEY` frames carrying keys it chose through the real
 * seal (`MeshManager.sendPresenceKeyAsAFork`). What the receiver must not do: let a key re-sent as the sender's own cost its
 * owner a thing, keep more than three generations of a churning sender, or list a stranger's key before the user accepts
 * them.
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
     * Alice's own as her own, ahead of Carol's own ctl 11 reaching Alice. The arrival order changes nothing: a tag is bound
     * to its owner's id ([assertBorrowedKeysCostNoOneAnything]).
     */
    @Test
    fun aForkThatHandsAnotherContactsKeyAheadOfItsOwnerTakesNothingFromHer() =
        runBlocking {
            val (alice, carol, mallory) = nodes()
            lab.link(alice, mallory)
            lab.awaitAcquainted(alice, mallory)
            lab.converse(alice, mallory)
            lab.await(1) { if (currentKeyOf(carol) != null) 1 else 0 }
            val carolsKey = ownCurrentKey(carol)
            val base = handBorrowedKeys(alice, mallory, carolsKey)
            assertTrue("alice has not met carol yet", alice.presenceKeys.heldBy(carol.nodeId).isEmpty())

            lab.link(alice, carol)
            lab.awaitAcquainted(alice, carol)
            lab.converse(alice, carol)
            lab.await(1) { if (alice.presenceKeys.heldBy(carol.nodeId).any { it.key.contentEquals(carolsKey) }) 1 else 0 }

            assertBorrowedKeysCostNoOneAnything(alice, carol, mallory, carolsKey, base)
        }

    /** The same fork, after Carol's own ctl 11: nothing Alice held for Carol moves. */
    @Test
    fun aForkThatHandsAnotherContactsKeyAfterItsOwnerTakesNothingFromHer() =
        runBlocking {
            val (alice, carol, mallory) = nodes()
            lab.linkAll(alice to carol, alice to mallory)
            lab.awaitAcquainted(alice, carol, mallory)
            lab.converse(alice, carol)
            lab.converse(alice, mallory)
            lab.await(1) { if (alice.presenceKeys.heldBy(carol.nodeId).size >= 2) 1 else 0 }
            val carolsKey = ownCurrentKey(carol)
            val base = handBorrowedKeys(alice, mallory, carolsKey)

            assertBorrowedKeysCostNoOneAnything(alice, carol, mallory, carolsKey, base)
        }

    private suspend fun nodes(): Triple<LabNode, LabNode, LabNode> =
        Triple(
            lab.node("alice", limits = blind).apply { setDisplayName("Alice") },
            lab.node("carol", limits = blind).apply { setDisplayName("Carol") },
            lab.node("mallory", limits = blind).apply { setDisplayName("Mallory") },
        )

    /** [mallory]'s churn, then [carolsKey] and [alice]'s own key as hers; the first churned generation. */
    private suspend fun handBorrowedKeys(
        alice: LabNode,
        mallory: LabNode,
        carolsKey: ByteArray,
    ): Long {
        lab.await(1) { if (currentKeyOf(alice) != null) 1 else 0 }
        val alicesKey = ownCurrentKey(alice)
        val base = lab.clock.now() + FORK_AHEAD_MS
        repeat(
            CHURN,
        ) { assertTrue(mallory.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(base + it, Random.nextBytes(32)))) }
        assertTrue(mallory.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(base + CHURN, carolsKey)))
        assertTrue(mallory.manager.sendPresenceKeyAsAFork(alice.nodeId, PresenceKeyPayload(base + CHURN + 1, alicesKey)))
        lab.await(1) { if (alice.presenceKeys.heldBy(mallory.nodeId).maxOfOrNull { it.generation } == base + CHURN + 1) 1 else 0 }
        return base
    }

    /**
     * What the store lists goes into a real [ExpectedTagTable], as the transport's feed puts it there (the lab has no table
     * of its own): this is the store-to-resolver proof. Carol's keys stay listed for Carol and her tag resolves to her; the
     * same key under Mallory's id resolves to Mallory; Alice's own tag resolves to no one, and Mallory stays at three
     * generations.
     */
    private suspend fun assertBorrowedKeysCostNoOneAnything(
        alice: LabNode,
        carol: LabNode,
        mallory: LabNode,
        carolsKey: ByteArray,
        base: Long,
    ) {
        val alicesKey = ownCurrentKey(alice)
        assertEquals("three generations kept", 3, alice.presenceKeys.heldBy(mallory.nodeId).size)
        val listed = alice.presenceKeys.presenceKeysFor(setOf(carol.nodeId, mallory.nodeId))
        assertTrue("carol's key stays listed for carol", listed.getValue(carol.nodeId).any { it.key.contentEquals(carolsKey) })
        assertEquals(
            "mallory's last churned key and both she borrowed, newest first",
            listOf(base + CHURN + 1, base + CHURN, base + CHURN - 1),
            listed.getValue(mallory.nodeId).map { it.generation },
        )

        val wall = lab.clock.now()
        val table = ExpectedTagTable(previousKeyMaxMs = PresenceKeyPolicy.WEEK_MS)
        table.setKeys(listed.mapValues { (_, keys) -> ContactKey(keys.map { it.key }) }, nowMs = 0, wallMs = wall)
        assertEquals("carol's tag is carol's", carol.nodeId, table.lookup(tagOf(carolsKey, carol, wall), 0)?.nodeId)
        assertEquals("her key under mallory's id is mallory's", mallory.nodeId, table.lookup(tagOf(carolsKey, mallory, wall), 0)?.nodeId)
        assertEquals("ours under mallory's id is mallory's", mallory.nodeId, table.lookup(tagOf(alicesKey, mallory, wall), 0)?.nodeId)
        assertNull("our own tag is no contact's", table.lookup(tagOf(alicesKey, alice, wall), 0))

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

    private fun tagOf(
        key: ByteArray,
        owner: LabNode,
        wall: Long,
    ): String = NodeId.fromBytes(BlindTag.tag(key, NodeId.toBytes(owner.nodeId), BlindTag.slotOf(wall), 0))

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
            assertFalse(stranger.nodeId in alice.presenceKeys.presenceKeysFor(alice.directContacts.read()))

            alice.accept(alice.dmWith(stranger))
            lab.await(1) { if (stranger.nodeId in alice.directContacts.read()) 1 else 0 }
            assertTrue(stranger.nodeId in alice.presenceKeys.presenceKeysFor(alice.directContacts.read()))
            lab.assertConverged(listOf(alice, stranger), atLeast = 1) { it.dmWith(if (it === alice) stranger else alice) }
        }

    private suspend fun currentKeyOf(node: LabNode): ByteArray? = PresenceKeyPolicy.keysOf(node.presenceKeys.ownKeys()).current?.key

    private suspend fun ownCurrentKey(node: LabNode): ByteArray = checkNotNull(currentKeyOf(node))

    private companion object {
        /** How far ahead of the clock a fork's generations start: above every honest key, so its frames are stored. */
        const val FORK_AHEAD_MS = 60 * 60_000L
        const val CHURN = 6
    }
}
