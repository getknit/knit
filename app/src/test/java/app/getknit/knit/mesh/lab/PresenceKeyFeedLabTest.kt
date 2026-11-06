package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * A new era that reuses generation numbers. Alice's clock ran ahead and was corrected, so the backup's last generation
     * sits past the clock and `max(last + 1, now)` is a plain counter: two restores from that one backup mint the same two
     * numbers with other bytes. Bob, holding exactly those two, stores the second restore's keys as a new era, and the
     * `node:generation` of each row does not move. The oracle is what reaches his radio, since the stored rows are right
     * either way: a feed token that cannot see the new bytes leaves his resolver on keys Alice no longer has.
     */
    @Test
    fun aSecondRestoreFromOneBackupHandsTheReaderItsNewKeysUnderTheSameGenerations() =
        runBlocking {
            val limits = blind.copy(resetFloorMs = SHORT_FLOOR_MS)
            val alice = lab.node("alice", limits = limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits = limits).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.converse(alice, bob)
            // Ticks first: a receipt held across a reset is lost by design (ADR 2026-10.8hn6), which is not what this tests.
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.awaitHoldsKeysOf(bob, alice)
            lab.awaitHoldsKeysOf(alice, bob)
            val before = alice.ownPair()
            alice.presenceSkewMs = AHEAD_MS
            alice.heal()
            lab.await(1) { if (alice.ownPair() != before) 1 else 0 }
            lab.awaitHoldsKeysOf(bob, alice)
            val backup = alice.settings.presenceKeyLastGeneration()
            alice.presenceSkewMs = 0L
            assertTrue("the backup's last generation sits past the corrected clock", backup > lab.clock.now())

            val first = restoreAndAwaitTheReader(alice, bob, backup)
            // lab-lint: allow sleep-as-sync — a floor to outwait, not an event: the open path stamps it on the wall clock,
            // which a lab clock jump never moves, and a longer wait here only passes it by more.
            delay(2 * SHORT_FLOOR_MS)
            val second = restoreAndAwaitTheReader(alice, bob, backup)

            for (i in first.indices) assertFalse("generation ${backup + 1 + i} has new bytes", first[i].contentEquals(second[i]))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** One restore from the backup carrying [backup]: Bob's radio ends holding Alice's two new keys. Returns those keys. */
    private suspend fun restoreAndAwaitTheReader(
        alice: LabNode,
        bob: LabNode,
        backup: Long,
    ): List<ByteArray> {
        alice.restoreFromBackup(lastGeneration = backup)
        lab.link(alice, bob)
        lab.awaitHoldsKeysOf(bob, alice)
        lab.awaitHoldsKeysOf(alice, bob)
        val generations = listOf(backup + 1, backup + 2)
        assertEquals("the counter minted the same two numbers", generations, alice.ownPair())
        assertEquals(
            "bob holds exactly those two",
            generations,
            bob.presenceKeys
                .heldBy(alice.nodeId)
                .map { it.generation }
                .sorted(),
        )
        val keys = generations.map { ownKey(alice, it) }
        val handed = {
            val listed =
                bob.transport.presenceKeysHanded
                    .lastOrNull()
                    ?.get(alice.nodeId)
                    .orEmpty()
            listed.size == keys.size && keys.all { key -> listed.any { it.contentEquals(key) } }
        }
        assertTrue("bob's radio never got alice's new keys\n${lab.report(listOf(alice, bob))}", lab.tryAwait(1) { if (handed()) 1 else 0 })
        return keys
    }

    private companion object {
        /** Under the year a restore clamps at: the carried generation must survive it. */
        const val AHEAD_MS = 30 * PresenceKeyPolicy.DAY_MS

        /** The floor on a peer's flagged reset, shortened on both nodes so the second restore's reset can outwait it. */
        const val SHORT_FLOOR_MS = 1_000L
    }
}
