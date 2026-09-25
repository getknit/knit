package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.spool.FakeSpool
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
 * A Nearby-room post's ✓✓ reaching an author no short-range radio can see (ADR 2026-09.y5f3): the field day
 * where two posts crossed a LoRa board in seconds and their ticks waited 48 minutes for a Bluetooth link.
 * Two real stacks, each with the real LoRa plane over one [FakeMeshtasticAir] and — in the spool cases — the
 * real Internet plane over one [FakeSpool]. They meet over a link first (the beacon would do it too, minutes
 * later), then the link goes and only the air and the relay remain.
 *
 * Every scenario ends in the full oracle. The direct-push case re-links first: the author custodies the
 * tick it pulled while the acker never does, so custody parity is one ordinary re-serve away — which is
 * exactly what the re-link then shows, without a second send.
 */
@RunWith(RobolectricTestRunner::class)
class RoomTickPlanesLabTest {
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

    @Test
    fun aRoomTickReachesAnAuthorOnlyLoRaCanHearOnceTheRideHoldRunsOut() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.unlink(alice, bob)

            assertTrue(alice.sendRoom("over the board"))
            val post = alice.ownMessageId(Conversations.NEARBY, "over the board")
            lab.await(1) { bob.roomPosts()[alice.nodeId].orEmpty().size }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, post))

            // Nothing else goes bob → alice, so the ride hold runs out and the targeted path carries the tick.
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, post, bob))
            assertEquals("one targeted send, not a flood", 1, bob.loraTx("send:chat"))
            assertEquals("a relay = false tick is never custodied", 0, bob.custodiedChatsTo(alice))

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
        }

    @Test
    fun aRoomTickRidesTheReceiptForADmThatCameOverTheSpool() =
        runBlocking {
            // The field case aa27's carriers missed: the DM came off the spool, its receipt was sealed at
            // once — and now that receipt is a ride. A long hold so the ride, not the deadline, wins.
            val air = FakeMeshtasticAir()
            val spool = FakeSpool()
            val limits = LabLimits(rideHoldMs = 10_000)
            val alice = lab.node("alice", limits, air = air, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits, air = air, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob, air)
            val bobFarBefore = bob.loraTx("far:chat")
            val aliceSkippedBefore = alice.metrics.snapshot().loraSkippedInternet

            assertTrue(alice.sendRoom("over the board"))
            val post = alice.ownMessageId(Conversations.NEARBY, "over the board")
            lab.await(1) { bob.manager.ackSync.ridingFor(alice.nodeId) }

            assertTrue(alice.sendDm(bob, "and now over the relay"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "and now over the relay")
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == dm } }
            assertEquals("the DM took the relay", DeliveryPlane.Internet, bob.receivedVia(bob.dmWith(alice), dm))
            assertTrue("and its LoRa copy was never sent", alice.metrics.snapshot().loraSkippedInternet > aliceSkippedBefore)

            assertEquals("the room tick rode the DM's receipt", DeliveryPlane.Internet, lab.awaitReceipt(alice, post, bob))
            assertEquals(DeliveryPlane.Internet, lab.awaitReceipt(alice, dm, bob))
            assertEquals("nothing went on the air for it", 0, bob.loraTx("send:chat"))
            assertEquals(bobFarBefore, bob.loraTx("far:chat"))
            // Alice reads the tick off the relay a moment before Bob's own bookkeeping of the ride lands.
            assertTrue(
                "the ride was never counted",
                lab.tryAwait(1) {
                    bob.metrics
                        .snapshot()
                        .receiptsRidden
                        .toInt()
                },
            )
            assertTrue("the ride hold never cleared", lab.tryAwait(1) { if (bob.manager.ackSync.ridingFor(alice.nodeId) == 0) 1 else 0 })

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.assertConverged(listOf(alice, bob), atLeast = 5) { it.dmWith(if (it === alice) bob else alice) }
        }

    @Test
    fun aRoomTickWithNoRideIsPushedStraightToTheSpoolWhenItsAuthorIsThere() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val spool = FakeSpool()
            val alice = lab.node("alice", air = air, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob, air)
            val custodiedBefore = bob.custodiedChatsTo(alice)
            val accountedBefore = bob.metrics.snapshot().spoolAccounted

            assertTrue(alice.sendRoom("over the board"))
            val post = alice.ownMessageId(Conversations.NEARBY, "over the board")

            // No DM follows, so the deadline runs out; the author is on the relay, so the tick goes there —
            // signed, point-to-point, and never a custody row on the acker.
            assertEquals(DeliveryPlane.Internet, lab.awaitReceipt(alice, post, bob))
            // Both counters move after the push returns (`if (pushed) onReceiptSpooled()`, the account after
            // the PUT), and Alice pulls the frame off the relay the moment the PUT lands — so her row can be
            // readable a beat before Bob's counters are.
            assertTrue(
                "the spooled tick was never counted",
                lab.tryAwait(1) {
                    bob.metrics
                        .snapshot()
                        .receiptsSpooled
                        .toInt()
                },
            )
            assertEquals(1L, bob.metrics.snapshot().receiptsSpooled)
            assertEquals("no custody row for it", custodiedBefore, bob.custodiedChatsTo(alice))
            assertTrue(
                "accounted instead, so bob's own heal loop never pulls it back",
                lab.tryAwait(1) { if (bob.metrics.snapshot().spoolAccounted == accountedBefore + 1) 1 else 0 },
            )
            assertEquals("and nothing on the air", 0, bob.loraTx("send:chat"))
            val settled =
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (bob.dmScopeStatus(alice)?.let {
                            it.converged &&
                                it.accountedCount == 1
                        } ==
                        true
                    ) {
                        1
                    } else {
                        0
                    }
                }
            assertTrue(
                "bob's scope reads converged with the tick in its accounted band: ${bob.dmScopeStatus(
                    alice,
                )?.let { "local=${it.localCount} spool=${it.spoolCount} converged=${it.converged} accounted=${it.accountedCount}" }}",
                settled,
            )

            // The author custodies what it pulled; the acker never will until a link re-serves it. That is
            // the whole cost, and a re-link shows it is one ordinary re-serve — not a second tick.
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            assertEquals(1, alice.receiptPlanes(post).size)
            assertEquals("the re-link re-served the tick, it did not re-send it", 0L, bob.metrics.snapshot().receiptsResent)
        }
}
