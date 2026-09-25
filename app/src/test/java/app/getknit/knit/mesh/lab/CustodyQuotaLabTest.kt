package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
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
 * The custody store's bounds, shrunk through [LabLimits] so a handful of frames is a full store. Every bound
 * evicts the oldest frame by the frame-global `(sentAt, id)` on every node alike — ADR 006, the rule whose
 * breach (evicting by a per-node key, exempting our own sends) held one field node's digest at 117 frames
 * against its peers' 100 for good. The quota also cuts holes in a ratchet chain (the frames evicted before
 * the recipient came back), which the recipient must read past. Every node gets the same numbers: the
 * digest is folded over what these keep.
 */
@RunWith(RobolectricTestRunner::class)
class CustodyQuotaLabTest {
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
     * Five frames per sender everywhere. Alice and Carol already hold a session (the init frame is not what
     * gets evicted); Alice writes seven DMs to an absent Carol, every store keeps her newest five, so Carol
     * comes back to exactly those, opens them past the hole with the ratchet's skipped keys — no reset — and
     * the three stores agree.
     */
    @Test
    fun aSendersOverQuotaDmsAreEvictedIdenticallyAndTheRecipientReadsPastTheHole() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerSender = 5)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(carol, "hello"))
            lab.await(1) { carol.decrypted(carol.dmWith(alice)).size }
            assertTrue(carol.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }

            lab.unlink(bob, carol)
            val relayedBefore = bob.metrics.snapshot().framesRelayed
            (1..7).forEach { assertTrue(alice.sendDm(carol, "apart $it")) }
            // Wait for Bob's relay decision on all seven, not for a count of five in his custody: the router
            // schedules each first-seen frame's relay after a 0–150 ms jitter, and under load that job can
            // run after the link comes up — it then hands Carol frames custody has already evicted (the first
            // runs flaked exactly so, delivering six or seven).
            assertTrue("bob's relays never fired", lab.tryAwait(7) { (bob.metrics.snapshot().framesRelayed - relayedBefore).toInt() })
            assertEquals("bob keeps alice's newest five DMs, not seven", 5, bob.custodiedChatsFrom(alice, carol))
            assertEquals("alice's own store keeps the same five", 5, alice.custodiedChatsFrom(alice, carol))

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 7, carriers = listOf(alice, bob)) { it.dmWith(alice) }
            assertEquals(setOf("hello", "hi") + (3..7).map { "apart $it" }, carol.decrypted(carol.dmWith(alice)).map { it.second }.toSet())
            assertEquals("nothing failed to decrypt past the hole", 0L, carol.drops(DropReason.DECRYPT_FAILED))
            assertEquals("no reset was needed", 0L, carol.session(alice)?.lastResetSentAt)
        }

    /**
     * The same walk with no session beforehand — the very first frames of the conversation are the ones
     * evicted. Every sealed frame carries the epoch key, so Carol opens what survives without the first
     * two, asks for no reset, and simply never sees them: the quota is loss for an absent recipient, by
     * design, never a wedge.
     */
    @Test
    fun anAbsentRecipientLosesWhatTheQuotaEvictedAndNeedsNoReset() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerSender = 5)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            val relayedBefore = bob.metrics.snapshot().framesRelayed
            (1..7).forEach { assertTrue(alice.sendDm(carol, "apart $it")) }
            assertTrue(lab.tryAwait(7) { (bob.metrics.snapshot().framesRelayed - relayedBefore).toInt() })
            assertEquals(5, bob.custodiedChatsFrom(alice, carol))

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 5, carriers = listOf(alice, bob)) { it.dmWith(alice) }
            assertEquals((3..7).map { "apart $it" }.toSet(), carol.decrypted(carol.dmWith(alice)).map { it.second }.toSet())
            assertEquals(0L, carol.drops(DropReason.DECRYPT_FAILED))
            assertEquals("no reset was needed", 0L, carol.session(alice)?.lastResetSentAt)
        }

    /** The group form: three frames per group everywhere, five sent while Carol was out, the newest three land. */
    @Test
    fun aGroupsOverQuotaFramesAreEvictedIdenticallyAndTheAbsentMemberReadsPastTheHole() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerGroup = 3)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            lab.unlink(bob, carol)
            val relayedBefore = bob.metrics.snapshot().framesRelayed
            (1..5).forEach { assertTrue(alice.sendGroup(groupId, "apart $it")) }
            assertTrue(lab.tryAwait(5) { (bob.metrics.snapshot().framesRelayed - relayedBefore).toInt() })
            assertEquals(3, bob.custodyIds().count { it in (1..5).map { n -> alice.ownMessageId(groupId, "apart $n") } })

            lab.link(bob, carol)
            assertTrue(
                "carol never read the newest three",
                lab.tryAwait(1) {
                    if (carol.decrypted(groupId).map { it.second }.containsAll((3..5).map { "apart $it" })) 1 else 0
                },
            )
            lab.settle()
            assertEquals(
                "the evicted two never arrive",
                (3..5)
                    .map {
                        "apart $it"
                    }.toSet() + "founded",
                carol.decrypted(groupId).map { it.second }.toSet(),
            )
            assertEquals(0L, carol.drops(DropReason.DECRYPT_FAILED))
            // The oracle needs one message set: alice and bob hold what carol never got, so they ride as carriers.
            lab.assertConverged(listOf(carol), atLeast = 4, carriers = listOf(alice, bob)) { groupId }
        }

    /** A full store: twelve rows everywhere, more than that in flight, and the three evict the same oldest. */
    @Test
    fun aFullStoreEvictsTheOldestOnEveryNodeAlike() =
        runBlocking {
            val limits = LabLimits(custodyMaxRows = 12)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            listOf(alice, bob, carol).forEach { n -> (1..3).forEach { assertTrue(n.sendRoom("${n.name} $it")) } }
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 9) { Conversations.NEARBY }
            assertTrue("the cap held", alice.custodyIds().size <= 12)
        }
}
