package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pairwise DM session between real stacks: the both-initiate race resolving to one root whichever init
 * lands first, a forced reset healing rather than storming (ADR 023/024/026 — a reset strands a tail of
 * old-era custody whose re-serves must not trip the other side's heuristic), and the group key request a
 * member sends after three unreadable frames (`docs/GROUP_FORWARD_SECRECY.md` §7). The oracle's session
 * check reads both sides' root and era after every case.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLabTest {
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

    /** Both send a first DM while neither can hear the other; the inits cross in [aliceFirst] order. */
    private suspend fun bothInitiate(aliceFirst: Boolean) {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)

        alice.transport.hold(bob.transport)
        bob.transport.hold(alice.transport)
        assertTrue(alice.sendDm(bob, "alice opens"))
        assertTrue(bob.sendDm(alice, "bob opens"))
        if (aliceFirst) {
            alice.transport.release(bob.transport)
            bob.transport.release(alice.transport)
        } else {
            bob.transport.release(alice.transport)
            alice.transport.release(bob.transport)
        }
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

        // A second round under whatever root won, then the oracle reads both sides' session once more.
        assertTrue(alice.sendDm(bob, "alice again"))
        assertTrue(bob.sendDm(alice, "bob again"))
        lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        assertEquals(alice.session(bob)?.rootHash, bob.session(alice)?.rootHash)
    }

    @Test
    fun bothSidesInitiateAtOnceAndAlicesInitLandsFirst() = runBlocking { bothInitiate(aliceFirst = true) }

    @Test
    fun bothSidesInitiateAtOnceAndBobsInitLandsFirst() = runBlocking { bothInitiate(aliceFirst = false) }

    /**
     * Both initiate while apart, and the race's loser (the higher node id adopts the lower's root) answers the
     * winner's init — its tick, its sealed-profile answer — before its own opening DM reaches the winner: the
     * frames of one link, crossing in the order a stalled digest collector on the loser produces (found by
     * chaos seeds 1003/1010 on `CustodyLabTest.bothSidesSendWhileApartAndMerge`). The winner confirms on the
     * post-adoption frames, and the loser's init then arrives on a confirmed session.
     */
    @Test
    @Ignore("#83: the loser's late opening DM fails AEAD on the confirmed winner and is never recovered")
    fun theLosersOpeningDmLandingAfterItsAnswersStillOpens() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            val (winner, loser) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice

            winner.transport.hold(loser.transport)
            loser.transport.hold(winner.transport)
            assertTrue(winner.sendDm(loser, "winner opens"))
            assertTrue(loser.sendDm(winner, "loser opens"))
            val opening = loser.ownMessageId(loser.dmWith(winner), "loser opens")

            winner.transport.release(loser.transport)
            lab.await(1) { loser.decrypted(loser.dmWith(winner)).count { it.second == "winner opens" } }
            // The loser's answers under the adopted root — its tick and its sealed-profile intro — are parked
            // behind its opening DM. Distinct frames by id, not copies: a digest re-serve of the opening DM, or of
            // the tick, is a parked chat frame too.
            lab.await(2) {
                loser.transport
                    .held(winner.transport)
                    .filter { it.isChatFrom(loser.nodeId) }
                    .mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
                    .filter { it != opening }
                    .distinct()
                    .size
            }
            loser.transport.release(winner.transport) { batch ->
                batch.sortedBy { WireCodec.decodeEnvelope(it.signed)?.id == opening } // the opening DM last
            }

            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Bob sends while apart; Alice forces a reset while apart. On the merge Bob gets the reset and re-seals
     * his unacked DM under the fresh session, Alice reads it, and — the storm check — Bob never resets back
     * on the old-era copy custody also served him.
     */
    @Test
    fun aForcedResetHealsAndTheOtherSidesUnackedDmsAreResealed() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            lab.unlink(alice, bob)
            assertTrue(bob.sendDm(alice, "sent while apart"))
            assertNull("alice's reset went out", alice.resetSession(bob))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(alice.sendDm(bob, "after the reset"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("alice sent the reset", (alice.session(bob)?.lastResetSentAt ?: 0L) > 0L)
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
        }

    /**
     * The other direction: Alice sends three DMs to an absent Bob (Dave carries them), then forces a reset.
     * When Bob returns the three are sealed under an era the reset retired — pre-era to Bob, so no reset
     * storm — and they still have to reach him.
     */
    @Test
    fun dmsSealedUnderAnEraAResetRetiredStillArriveWithoutAStorm() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, alice to dave, bob to dave)
            lab.awaitAcquainted(alice, bob, dave)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(dave)) { it.dmWith(if (it === alice) bob else alice) }

            lab.unlink(alice, bob)
            lab.unlink(bob, dave)
            (1..3).forEach { assertTrue(alice.sendDm(bob, "apart $it")) }
            assertTrue("dave carries the three", lab.tryAwait(3) { dave.custodiedChatsFrom(alice, bob) })
            assertNull(alice.resetSession(bob))
            lab.unlink(alice, dave)

            lab.link(bob, dave)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(dave)) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
        }

    /**
     * §7: Bob holds the group (the roster rode the first frame) but never got the seed. After three frames
     * he cannot open he asks Alice for the key, Alice re-seals the current seed to him, and the three land.
     */
    @Test
    fun aMemberWhoMissedTheSeedAsksAfterThreeUnreadableFrames() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            (1..3).forEach { assertTrue(alice.sendGroup(groupId, "sealed $it")) }
            val released = alice.transport.release(bob.transport) { batch -> batch.filter { it.isGroupFrame() } }
            assertEquals("three group frames, no seed", 3, released.size)

            assertTrue(
                "bob never asked for the key",
                lab.tryAwait(1) {
                    bob.metrics
                        .snapshot()
                        .groupKeyRequestsSent
                        .toInt()
                },
            )
            val opened = lab.tryAwait(3) { bob.decrypted(groupId).size }
            assertTrue(
                "the three never opened: bob holds ${bob.decrypted(groupId).map { it.second }}\n" +
                    "  alice↔bob sessions: ${alice.session(bob)} / ${bob.session(alice)}\n${lab.report(listOf(alice, bob))}",
                opened,
            )
            assertEquals(1L, bob.metrics.snapshot().groupKeyRequestsSent)
            // The seed the hold ate is still in Alice's custody and nowhere in Bob's; the digest exchange on
            // the next link-up (or the 60 s re-offer) is what serves it. Re-link, then the oracle.
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { groupId }
        }

    private fun WireEnvelope.isGroupFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.group != null

    private fun WireEnvelope.isChatFrom(nodeId: String): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.senderId == nodeId } == true
}
