package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The own presence key handed to the radio and the `TAG_NEXT` predicate between real stacks (ADR 2026-11.xp6d, step 5b's
 * key side; knit-next #285): what `MeshManager` hands its transport when the key engine's current key changes, and who
 * the predicate refuses at that moment. The lab's radio airs no tags, so the oracle is the hand-off itself
 * ([LabTransport.ownKeysHanded], [LabTransport.tagNextAtHandOff]), read only on a radio with no composite between it and
 * the manager; the key exchange each case rests on is [PresenceKeyLabTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class PresenceKeyFeedLabTest {
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
     * A block made while Alice's mesh is paused (step 5b, A1; design5b's block-while-paused recipe): the paused manager and
     * its key engine live on, so the key they last handed the radio is the one Carol holds. The resume hands the radio no
     * key until the engine's first settle, and the first key it hands is the fresh pair's, never one Carol can resolve.
     */
    @Test
    fun aBlockWhilePausedHandsTheRadioTheFreshKeyFirst() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits = blind).apply { setDisplayName("Carol") }
            // A board beside the radio puts a composite between it and the manager, which hands the key to a Bluetooth
            // plane alone; the lab's radio is not one, so the hand-off is only readable on the radio alone.
            assumeTrue("the hand-off is recorded on a radio without a composite", alice.radioAlone)
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.converse(alice, bob)
            lab.converse(alice, carol)
            lab.awaitHoldsKeysOf(carol, alice)
            // Alice holds Carol's keys too: a session's first pass finds who left through the keys it stores (chaos seed 1005).
            lab.awaitHoldsKeysOf(alice, carol)
            val carolHolds = alice.ownPair()
            lab.await(1) {
                if (alice.transport.ownKeysHanded
                        .lastOrNull()
                        ?.generation == carolHolds.first()
                ) {
                    1
                } else {
                    0
                }
            }

            alice.pauseMesh()
            val handedBefore = alice.transport.ownKeysHanded.size
            alice.block(carol)
            alice.resumeMesh()
            lab.await(1) { if (alice.ownPair().first() > carolHolds.last()) 1 else 0 }
            lab.await(1) {
                if (alice.transport.ownKeysHanded
                        .drop(handedBefore)
                        .any { it != null }
                ) {
                    1
                } else {
                    0
                }
            }

            val handed = alice.transport.ownKeysHanded.drop(handedBefore)
            assertNull("the resume first hands the radio no key", handed.first())
            assertEquals("the first key handed is the fresh pair's", alice.ownPair().first(), handed.first { it != null }?.generation)
            assertTrue(
                "a key carol holds went to the radio after the resume: $handed",
                handed.none { it != null && it.generation in carolHolds },
            )

            lab.link(alice, bob)
            lab.awaitHoldsKeysOf(bob, alice)
            assertHoldsNoneOf(carol, alice, alice.ownPair())
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A linked contact removed mid-session (step 5b, W5's exclusion): when the fresh pair's key is first handed to the
     * radio, the `TAG_NEXT` predicate read in that same call already refuses Carol, who is still linked and holds the
     * old key, while Bob, who stayed, still hears it. A send the radio makes right after a fresh key never hands the
     * leaver the tag the rotation was for. It pins who is departed at that hand-off; the order the engine publishes the
     * two in is `PresenceKeyEngineTest`'s. It is also the regression for the removal's own trigger: run inline, the
     * contacts collector could be busy across the add and the removal and fold both away, leaving the fresh pair to the
     * next heal (`MeshManager.watchPresenceKeys`' kick; about 1 run in 200 before it). A missing rotation fails with
     * which of a lost trigger, a held lock or a key that never named Carol it was.
     */
    @Test
    fun aContactRemovedMidSessionIsRefusedTagNextAtTheFreshKeysFirstHandOff() =
        runBlocking {
            val alice = lab.node("alice", limits = blind).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = blind).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits = blind).apply { setDisplayName("Carol") }
            // A board beside the radio puts a composite between it and the manager (see the block-while-paused case).
            assumeTrue("the hand-off is recorded on a radio without a composite", alice.radioAlone)
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.converse(alice, bob)
            lab.converse(alice, carol)
            // Both directions, as the removal case above: a key frame of Carol's still in flight at the removal is an
            // ordering the mesh does not pin.
            lab.awaitHoldsKeysOf(carol, alice)
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, carol)
            lab.awaitHoldsKeysOf(alice, bob)
            val carolHolds = alice.ownPair()
            alice.transport.tagNextProbe = setOf(carol.nodeId, bob.nodeId)

            alice.removeContact(carol)
            val rotated = lab.tryAwait(1) { if (alice.ownPair().first() > carolHolds.last()) 1 else 0 }
            if (!rotated) {
                // No fresh pair: tell a lost trigger (a heal's pass rotates), a held engine lock (the heal never ends) and
                // a key that never named Carol (Fresh was never due) apart.
                val keys = PresenceKeyPolicy.keysOf(alice.presenceKeys.ownKeys())
                val inAudience = listOfNotNull(keys.current, keys.next).map { carol.nodeId in it.audience }
                val direct = carol.nodeId in alice.directContacts.read()
                val heal = runCatching { alice.heal() }.exceptionOrNull()?.message ?: "finished"
                fail(
                    "alice never minted a fresh pair after removing carol: carol still direct $direct, in current/next " +
                        "audience $inAudience, contact ${alice.isContact(carol)}; a heal $heal, and then rotated " +
                        "${alice.ownPair().first() > carolHolds.last()}\n${lab.report(listOf(alice, bob, carol))}",
                )
            }
            val fresh = alice.ownPair().first()
            lab.await(1) { if (alice.transport.tagNextAtHandOff.any { it.first?.generation == fresh }) 1 else 0 }

            val (_, verdicts) = alice.transport.tagNextAtHandOff.first { it.first?.generation == fresh }
            assertEquals("carol is refused at the fresh key's first hand-off", false, verdicts[carol.nodeId])
            assertEquals("bob, who stayed, still hears it", true, verdicts[bob.nodeId])
            lab.awaitHoldsKeysOf(bob, alice)
            assertHoldsNoneOf(carol, alice, alice.ownPair())
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) {
                it.dmWith(if (it === alice) bob else alice)
            }
        }
}
