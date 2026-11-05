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
 * real Internet plane over one [FakeSpool]. They meet over a link first (no profile rides the board any more, ADR
 * 2026-10.rp3s), then the link goes and only the air and the relay remain. Since that ADR the air carries the post
 * and never the tick: a room tick goes home over a link or the relay, or waits.
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

    /**
     * ADR 2026-10.rp3s: the room ✓✓ never rides LoRa. Bob's tick for a post that crossed on Alice's board waits out the
     * ride hold with nowhere to go — no spool, no link, and a board that airs no room tick (`fastSend` refuses, and
     * AckSync no longer reads a LoRa-only author as reachable over the air) — so it stays parked rather than being
     * sealed into an owed entry nothing can carry. The link that comes back is what takes it home.
     */
    @Test
    fun aRoomTickNeverRidesLoRaAndGoesHomeOverTheNextLink() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.unlink(alice, bob)
            // Read before the post exists: the hold's one wake fires `rideHoldMs` after bob takes it, which a stalled
            // run can pass before the receipt checks below return (seed 455287199).
            val unroutedBefore = bob.metrics.receipts().ridesUnrouted

            assertTrue(alice.sendRoom("over the board"))
            val post = alice.ownMessageId(Conversations.NEARBY, "over the board")
            lab.await(1) { bob.roomPosts()[alice.nodeId].orEmpty().size }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, post))

            // Past the ride hold: the due hold was judged and found no route (`ridesUnrouted`), so nothing went on the
            // air for it and the tick still waits for a ride.
            lab.await(1) { (bob.metrics.receipts().ridesUnrouted - unroutedBefore).toInt() }
            assertEquals("a room tick aired: ${bob.loraLog}", 0L, bob.metrics.lora().loraSent)
            assertEquals("still parked for a link or the spool", 1, bob.manager.ackSync.ridingFor(alice.nodeId))

            lab.link(alice, bob)
            assertTrue("the link took it home", lab.awaitReceipt(alice, post, bob) != DeliveryPlane.LoRa)
            assertEquals("and the board never carried it", 0L, bob.metrics.lora().loraSent)
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
            val bobDirectBefore = bob.loraTx("direct:")
            val bobSentBefore = bob.metrics.lora().loraSent
            val aliceSkippedBefore = alice.metrics.lora().loraSkippedInternet

            assertTrue(alice.sendRoom("over the board"))
            val post = alice.ownMessageId(Conversations.NEARBY, "over the board")
            lab.await(1) { bob.manager.ackSync.ridingFor(alice.nodeId) }

            assertTrue(alice.sendDm(bob, "and now over the relay"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "and now over the relay")
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == dm } }
            assertEquals("the DM took the relay", DeliveryPlane.Internet, bob.receivedVia(bob.dmWith(alice), dm))
            // Its compact copy was offered (ADR 2026-10.x9fa) and kept off the air: the relay carries the signed one for free.
            assertTrue("and its LoRa copy was never sent", alice.metrics.lora().loraSkippedInternet > aliceSkippedBefore)

            assertEquals("the room tick rode the DM's receipt", DeliveryPlane.Internet, lab.awaitReceipt(alice, post, bob))
            assertEquals(DeliveryPlane.Internet, lab.awaitReceipt(alice, dm, bob))
            assertEquals("nothing went on the air for it", bobSentBefore, bob.metrics.lora().loraSent)
            assertEquals(bobDirectBefore, bob.loraTx("direct:"))
            // Alice reads the tick off the relay a moment before Bob's own bookkeeping of the ride lands.
            assertTrue(
                "the ride was never counted",
                lab.tryAwait(1) {
                    bob.metrics
                        .receipts()
                        .receiptsRidden
                        .toInt()
                },
            )
            assertTrue("the ride hold never cleared", lab.tryAwait(1) { if (bob.manager.ackSync.ridingFor(alice.nodeId) == 0) 1 else 0 })

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.assertConverged(listOf(alice, bob), atLeast = 5) { it.dmWith(if (it === alice) bob else alice) }
        }

    @Test
    fun aDmGoesOnTheAirOnceTheRelaysAreSwitchedOff() =
        runBlocking {
            // #196: the relays' master switch off left the last present set published, so the board went on
            // treating the peer as covered by the Internet and never aired a DM to it. Both phones leave, as in
            // the field (the other phone reachable only over LoRa); each one's own cover must lapse. Boards answering in lab
            // time: a DM the meeting offered compact into its dark air ends in seconds, not a LongFast minute.
            val air = MeshLab.quickAir()
            val spool = FakeSpool()
            // A DM that arrived over the board is ticked through DmAckCoalescer's 45 s hold (ADR 054); the oracle
            // waits for that tick, so the pair holds it for a second instead.
            val limits = LabLimits(dmAckHoldMs = 1_000L)
            val alice = lab.node("alice", limits, air = air, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits, air = air, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob, air)
            for (node in listOf(alice, bob)) {
                assertTrue("${node.name}'s board never took the relay's cover", lab.tryAwait(1) { node.internetCovered() })
            }

            lab.leaveRelay(alice)
            lab.leaveRelay(bob)
            for (node in listOf(alice, bob)) {
                assertTrue("${node.name}'s board kept the cover of a relay it left", lab.tryAwait(1) { 1 - node.internetCovered() })
            }
            val skippedBefore = alice.metrics.lora().loraSkippedInternet
            // The meeting's own DMs can have been offered compact while its air was dark, before the other's spool presence
            // covered them: one gives up and marks the other's board unreachable, which holds the next offer — alice's DM,
            // or bob's ✓✓ for it (ADR 2026-10.x9fa). Wait those out, then let each board speak: hearing it clears the mark.
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            val boards = listOf(alice, bob).map { checkNotNull(it.lora) }
            assertTrue("a dark send never ended", lab.tryAwait(1) { if (boards.all { it.status.value.direct.inFlight == 0 }) 1 else 0 })
            assertTrue(alice.sendRoom("on alice's board"))
            assertTrue(bob.sendRoom("on bob's board"))
            assertTrue("a board was never heard", lab.tryAwait(1) { if (boards.all { it.status.value.direct.unreachable == 0 }) 1 else 0 })
            assertTrue(alice.sendDm(bob, "over the board now"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "over the board now")
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == dm } }
            assertEquals("the DM went on the air", DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals("and its receipt came back the same way", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertEquals("nothing was kept off the board for a relay", skippedBefore, alice.metrics.lora().loraSkippedInternet)

            // The signed copies never ride LoRa, so custody converges only where they can cross: a link, here (the relays
            // are off). Before ADR 2026-10.x9fa the signed DM itself rode the board and bob custodied it off the air.
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 5) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * #294, the 2026-11-04 field case: alice's DM is kept off the air by her relay cover a moment before her sockets
     * close, and bob, who goes on reading alice as relay-present for the 15-minute window, would keep every ✓✓ and reply
     * off it too. Here the relay swallows everything from DM 1 on (so no ✓✓ can come back that way, whichever race the
     * field would have run), then alice leaves it. Her board catches DM 1 up — on the lapse, or behind DM 2's board ack
     * when a failure from the meeting still holds the re-probe's gap — and bob's board lifts alice's cover on her first
     * live compact frame, so both ✓✓ and his reply cross the air in seconds, well inside `LoraReprobe.BASE_MS` and
     * `SPOOL_COVER_MS`.
     */
    @Test
    fun dmsStrandedByARelayThatWentCatchUpOverTheAirAndTheFarSideAnswersThere() =
        runBlocking {
            val air = MeshLab.quickAir()
            val spool = FakeSpool()
            val limits = LabLimits(dmAckHoldMs = 1_000L)
            val alice = lab.node("alice", limits, air = air, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits, air = air, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob, air)
            for (node in listOf(alice, bob)) {
                assertTrue("${node.name}'s board never took the relay's cover", lab.tryAwait(1) { node.internetCovered() })
            }
            // As in the scenario above: a meeting DM offered into the dark air may have marked a board unreachable. Wait
            // the sends out and let each board speak, so neither side holds the other's next offer.
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            val boards = listOf(alice, bob).map { checkNotNull(it.lora) }
            assertTrue("a dark send never ended", lab.tryAwait(1) { if (boards.all { it.status.value.direct.inFlight == 0 }) 1 else 0 })
            assertTrue(alice.sendRoom("on alice's board"))
            assertTrue(bob.sendRoom("on bob's board"))
            assertTrue("a board was never heard", lab.tryAwait(1) { if (boards.all { it.status.value.direct.unreachable == 0 }) 1 else 0 })

            spool.mute()
            val skippedBefore = alice.metrics.lora().loraSkippedInternet
            assertTrue(alice.sendDm(bob, "are you waiting?"))
            val dm1 = alice.ownMessageId(alice.dmWith(bob), "are you waiting?")
            lab.await(1) { (alice.metrics.lora().loraSkippedInternet - skippedBefore).toInt() }
            lab.leaveRelay(alice)
            // The workers stop before ScopeSync republishes presence: wait for the board's own cover to go, or DM 2 can
            // still be skipped as covered.
            assertTrue("alice's board kept the cover of a relay it left", lab.tryAwait(1) { 1 - alice.internetCovered() })
            assertTrue(alice.sendDm(bob, "resend test"))
            val dm2 = alice.ownMessageId(alice.dmWith(bob), "resend test")

            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == dm1 } }
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == dm2 } }
            assertEquals("DM 1 caught up over the air", DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm1))
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm2))
            assertEquals("DM 1's ✓✓ crossed the air", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm1, bob))
            assertEquals("DM 2's too", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm2, bob))
            assertTrue("bob's board never lifted alice's cover: ${bob.loraLog}", bob.loraLog.contains("lora cover lifted ${alice.nodeId}"))
            assertTrue("bob's relay no longer lists alice, so a 0 below would not be the lift", bob.spoolPresent(alice))
            assertEquals("bob's relay still lists alice; his board no longer reads it", 0, bob.internetCovered())

            assertTrue(bob.sendDm(alice, "heading home"))
            val reply = bob.ownMessageId(bob.dmWith(alice), "heading home")
            lab.await(1) { alice.decrypted(alice.dmWith(bob)).count { it.first == reply } }
            assertEquals(
                "bob's reply went on the air, not to the relay alone",
                DeliveryPlane.LoRa,
                alice.receivedVia(alice.dmWith(bob), reply),
            )

            // The signed copies cross only where they can: a link, once bob is off the swallowing relay too.
            lab.leaveRelay(bob)
            spool.unmute()
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 7) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** How many peers [this] node's board counts as covered by the Internet: 1 or 0 here, a `tryAwait` count. */
    private fun LabNode.internetCovered(): Int = checkNotNull(lora) { "$name has no board" }.status.value.internetCovered

    @Test
    fun aRoomTickWithNoRideIsPushedStraightToTheSpoolWhenItsAuthorIsThere() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val spool = FakeSpool()
            val alice = lab.node("alice", air = air, spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air, spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob, air)
            // A converged scope has nothing left to pull, so the baselines below cannot move under the tick.
            assertTrue(
                "bob's scope never converged after the meeting",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.dmScopeStatus(alice)?.converged == true) 1 else 0 },
            )
            val custodiedBefore = bob.custodiedChatsTo(alice)
            val accountedBefore = bob.metrics.spool().spoolAccounted
            // The band can already hold a frame the pair exchanged while linked: its link copy delivered first,
            // its spool copy accepted before that delivery's custody write landed, so it reads accounted and held
            // at once (harmless — Diagnostics' "aged" count reads one high). The tick is one more than that.
            val bandBefore = bob.dmScopeStatus(alice)?.accountedCount ?: 0
            val bobSentBefore = bob.metrics.lora().loraSent

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
                        .receipts()
                        .receiptsSpooled
                        .toInt()
                },
            )
            assertEquals(1L, bob.metrics.receipts().receiptsSpooled)
            assertEquals("no custody row for it", custodiedBefore, bob.custodiedChatsTo(alice))
            assertTrue(
                "accounted instead, so bob's own heal loop never pulls it back",
                lab.tryAwait(1) { if (bob.metrics.spool().spoolAccounted == accountedBefore + 1) 1 else 0 },
            )
            assertEquals("and nothing on the air", bobSentBefore, bob.metrics.lora().loraSent)
            val settled =
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (bob.dmScopeStatus(alice)?.let {
                            it.converged &&
                                it.accountedCount == bandBefore + 1
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
            assertEquals("the re-link re-served the tick, it did not re-send it", 0L, bob.metrics.receipts().receiptsResent)
        }
}
