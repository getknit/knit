package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
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

/**
 * The clock tier: what the field waits minutes or days for, moved with [LabClock.advance] and then poked —
 * a re-link for the digest exchange, `heal()` for the heartbeat basket — because the jump moves decisions,
 * never schedulers. The router's 10-minute seen window (the self-pin loop, a DM received while its sender
 * was blocked — which stays unseen past it), the 24 h custody TTL, the 12 h profile republish, and the
 * ratchet's retention sweeps after a reset (ADR 027).
 */
@RunWith(RobolectricTestRunner::class)
class TimeLabTest {
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
     * The self-pin loop's walk (`45763fb6`), which the lab never made before: Alice's own profile comes back
     * to her from a peer's custody after the seen window, because her store was wiped and the digest
     * exchange serves her everything she lacks. It must be re-carried (her own sends reconverge) and must
     * pin nothing — no self row, no seal to herself.
     */
    @Test
    fun ourOwnProfileLoopingBackAfterTheSeenWindowPinsNothing() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "before the wipe"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            lab.awaitCustodyParity(alice, bob, carol)

            alice.wipeCustody()
            assertTrue("the wipe took", alice.custodyIds().isEmpty())
            lab.clock.advance(SEEN_WINDOW_LAPSED_MS)
            lab.unlink(alice, bob)
            lab.link(alice, bob)

            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("alice re-carries her own profile", alice.custodyIds().any { it.startsWith("profile-${alice.nodeId}-") })
            assertNull("and never pinned herself", alice.peer(alice))
        }

    /**
     * A DM that arrived while its sender was blocked is dropped on the delivery path and custodied like any
     * addressed DM (ADR 2026-09.bts9: the block list never enters the carry gate), so once the block is lifted
     * there is nothing for the sender's re-serve to bring — the stores already agree — and the message stays
     * unseen past the seen window too. Before bts9 it trickled in here by accident, off the peer's re-serve.
     */
    @Test
    fun aDmReceivedWhileBlockedStaysUnseenAfterTheUnblock() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            bob.block(alice)
            assertTrue(alice.sendDm(bob, "while blocked"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "while blocked")
            lab.awaitCustodyParity(alice, bob)
            // Custody is written before the frame is dispatched (`InboundPipeline.onDeliver`), so parity says
            // nothing about the block check having run: an unblock inside that gap lets the late dispatch surface it.
            bob.transport.awaitInboundDrained()
            assertTrue("bob custodied the DM he would not surface", bob.custodyIds().contains(dm))
            assertTrue("nothing surfaced while blocked", bob.decrypted(bob.dmWith(alice)).isEmpty())

            bob.unblock(alice)
            lab.clock.advance(SEEN_WINDOW_LAPSED_MS)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.awaitCustodyParity(alice, bob)
            lab.settle()
            lab.settle()
            assertTrue("still nothing after the unblock", bob.decrypted(bob.dmWith(alice)).isEmpty())
            assertFalse("and alice never got bob's tick", alice.receiptPlanes(dm).containsKey(bob.nodeId))
        }

    /**
     * A day passes. Every chat frame and every receipt ages out of custody on both nodes alike (the TTL is
     * keyed on the frame's own clock), the profiles are republished under a fresh stamp, a newcomer is
     * offered none of the old traffic, and the delivered history is untouched.
     */
    @Test
    fun custodyExpiresIdenticallyAcrossADay() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "yesterday"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "yesterday too"))
            assertTrue(alice.sendRoom("old news"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.awaitCustodyParity(alice, bob)
            val yesterday = alice.custodyIds()

            lab.clock.advance(A_DAY_AND_A_BIT_MS)
            alice.heal()
            bob.heal()
            // The republish seeds the fresh profile into custody rather than flooding it (it carries no
            // news); the next contact's digest exchange moves it. Re-link, as the 60 s re-offer would.
            // (The heartbeat also seals a fresh CTL_PROFILE to the contact, so the stores are not empty.)
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            assertTrue(
                "yesterday never aged out of both stores alike",
                lab.tryAwait(1) {
                    val stores = listOf(alice, bob).map { it.custodyIds() }
                    if (stores.all { ids -> ids.none { it in yesterday } } && stores.distinct().size == 1) 1 else 0
                },
            )

            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(alice, carol)
            lab.awaitAcquainted(alice, carol)
            lab.settle()
            lab.settle()
            assertTrue("the newcomer was offered nothing of yesterday", carol.roomPosts().isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals(1, alice.roomPosts().values.sumOf { it.size })
        }

    /**
     * Twelve hours on, the heartbeat republishes the profile under a new stamp — one new frame, the old one
     * still there, both stores alike.
     */
    @Test
    fun aRepublishAfterTwelveHoursMintsOneNewFrameNotAVariant() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            lab.awaitCustodyParity(alice, bob)
            val before = alice.custodyIds().filter { it.startsWith("profile-${alice.nodeId}-") }.toSet()

            lab.clock.advance(HALF_A_DAY_AND_A_BIT_MS)
            alice.heal()
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            assertTrue(
                "no fresh profile frame was minted",
                lab.tryAwait(1) {
                    if ((alice.custodyIds().filter { it.startsWith("profile-${alice.nodeId}-") }.toSet() - before).size ==
                        1
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("the old stamp is still carried, not replaced", before.all { it in bob.custodyIds() })
        }

    /**
     * ADR 027: a reset restarts epoch numbering, and the retention sweep used to reap the fresh epochs
     * ahead of the dead era's. A day after a reset, the sweep runs on both sides and the session still
     * reads both ways.
     */
    @Test
    fun theRetentionSweepADayAfterAResetKeepsTheLiveEpochs() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertNull(alice.resetSession(bob))
            assertTrue(alice.sendDm(bob, "after the reset"))
            // Bob already holds two, so a count is a stale read here: wait for *this* DM, which is also Bob
            // having applied the reset ahead of it — his reply must seal under the fresh era, not the retired one.
            assertTrue(lab.tryAwait(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == "after the reset" } })
            assertTrue(bob.sendDm(alice, "read you"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }

            lab.clock.advance(A_DAY_AND_A_BIT_MS)
            alice.heal()
            bob.heal()
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            assertTrue(alice.sendDm(bob, "a day later"))
            assertTrue(lab.tryAwait(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == "a day later" } })
            assertTrue(bob.sendDm(alice, "still here"))
            lab.assertConverged(listOf(alice, bob), atLeast = 6) { it.dmWith(if (it === alice) bob else alice) }
            listOf(alice, bob).forEach { n ->
                assertEquals("${n.name} failed to open a frame", 0L, n.drops(DropReason.DECRYPT_FAILED))
            }
        }

    private companion object {
        /** Past the router's 10-minute seen window. */
        const val SEEN_WINDOW_LAPSED_MS = 11 * 60_000L

        /** Past the 24 h custody TTL and the 12 h profile republish. */
        const val A_DAY_AND_A_BIT_MS = 25 * 60 * 60_000L

        /** Past the 12 h profile republish, short of the custody TTL. */
        const val HALF_A_DAY_AND_A_BIT_MS = 13 * 60 * 60_000L
    }
}
