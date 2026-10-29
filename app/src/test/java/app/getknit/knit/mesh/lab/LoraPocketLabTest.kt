package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.PRESENCE_FRESH_MS
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.lora.LoraMeshTransport
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
 * Boards as endpoints (ADR 2026-10.rp3s), with the real `LoraMeshTransport` composed into real stacks over one
 * [FakeMeshtasticAir]. A board airs only what its own phone's user wrote; what it hears still floods into its BLE/NAN
 * pocket, which costs no air. A pocket is whatever a node holds links to. The DM scenarios are the reach gate's
 * (ADR 2026-10.a4pm): a probe per window, the re-probe for a recipient who stays silent, and the first-hearing re-offer.
 */
@RunWith(RobolectricTestRunner::class)
class LoraPocketLabTest {
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

    private val quick = LabLimits()

    /**
     * Everyone meets by radio first, as people who later split into pockets did: profiles, custody parity, then the
     * cross-pocket links go. No profile rides the board any more, so a stranger heard only over LoRa is never shown
     * (ADR 2026-10.rp3s): the pockets must have met.
     */
    private suspend fun meetThenSplit(
        nodes: List<LabNode>,
        keep: List<Pair<LabNode, LabNode>>,
    ) {
        val all = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
        lab.linkAll(*all.toTypedArray())
        lab.awaitAcquainted(*nodes.toTypedArray())
        lab.awaitCustodyParity(*nodes.toTypedArray())
        all
            .filterNot { (a, b) ->
                keep.any { (x, y) -> (x === a && y === b) || (x === b && y === a) }
            }.forEach { (a, b) -> lab.unlink(a, b) }
    }

    /**
     * ADR 2026-10.rp3s, end to end. Alice's board alone is one pocket; Bob's board and his board-less pocket-mate
     * Carol, linked over BLE, are the other. Alice's own room post crosses on her board, Bob's board hears it, and it
     * floods on to Carol over the link — inbound bridging costs no air and stays. Carol's own post reaches Bob over the
     * link, and his board never airs it: a pocket-mate's frame is not his to spend air on. So Alice never gets it, by
     * design, and the room converges within Bob's pocket.
     */
    @Test
    fun aFarPostFloodsIntoThePocketButAPocketMatesPostNeverAirs() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick).apply { setDisplayName("Carol") }
            meetThenSplit(listOf(alice, bob, carol), keep = listOf(bob to carol))

            val aliceFanned = alice.loraTx("fanout:chat")
            assertTrue(alice.sendRoom("from the ridge"))
            val post = alice.ownMessageId(Conversations.NEARBY, "from the ridge")
            assertTrue(
                "alice's board carried her own post: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraAired(post, "fanout:chat", aliceFanned) },
            )
            assertTrue("bob's board heard it", lab.tryAwait(1) { bob.roomPosts()[alice.nodeId]?.size ?: 0 })
            assertTrue("and it flooded on to carol over the link", lab.tryAwait(1) { carol.roomPosts()[alice.nodeId]?.size ?: 0 })

            val bobFanned = bob.loraTx("fanout:chat")
            assertTrue(carol.sendRoom("from the hut"))
            assertTrue("bob got carol's post over the link", lab.tryAwait(1) { bob.roomPosts()[carol.nodeId]?.size ?: 0 })
            lab.assertConverged(listOf(bob, carol), atLeast = 2) { Conversations.NEARBY }
            assertEquals("bob's board never aired carol's post: ${bob.loraLog}", bobFanned, bob.loraTx("fanout:chat"))
            assertEquals("carol has no board", 0L, carol.metrics.lora().loraSent)
            assertEquals("so alice never got it", 0, alice.roomPosts()[carol.nodeId]?.size ?: 0)
        }

    /** ADR 054's recipient gate: a DM to someone on a live link never spends airtime. */
    @Test
    fun aDmToALinkedPeerNeverRidesTheBoard() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            assertTrue(alice.sendDm(bob, "over the link"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "and back"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("the gate never fired", alice.metrics.lora().loraSkippedLinked >= 1)
            assertEquals("nothing of the DM went on the air: ${alice.loraLog}", 0, alice.loraTx("far:chat"))
            assertEquals("nothing of the reply went on the air: ${bob.loraLog}", 0, bob.loraTx("far:chat"))
        }

    /**
     * The two-phone trial's last leg, without the bridge (ADR 2026-10.rp3s): a DM sent while the far board could not
     * hear goes out as the window's probe into an empty sky. Nothing makes a silent Bob heard any more, so the
     * re-probe re-airs it a window later; Bob's board is back, his ✓✓ makes him heard, and it lands.
     */
    @Test
    fun aDmSentWhileTheFarBoardWasOffLandsByTheReprobe() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            air.lossy = { _, _ -> true }
            val farBefore = alice.loraTx("far:chat")
            assertTrue(alice.sendDm(bob, "while your board was off"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "while your board was off")
            assertTrue("the DM never left: ${alice.loraLog}", lab.tryAwait(1) { alice.loraAired(dm, "far:chat", farBefore) })
            air.lossy = { _, _ -> false }

            lab.clock.advance(LoraMeshTransport.DM_PROBE_WINDOW_MS + MINUTE)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue("it came by the re-probe: ${alice.loraLog}", alice.metrics.lora().loraReprobed >= 1)
        }

    /**
     * The reach-gate scenarios' limits: a ✓✓ that comes back in a second rather than after DmAckCoalescer's 45 s, and a
     * linger sweep (which also runs the re-probe) that notices a calendar jump inside an await.
     */
    private val reach = quick.copy(dmAckHoldMs = 1_000L, loraLingerSweepMs = 500L)

    /**
     * Alice and Bob, each with a board, meet over a link and exchange a DM each way, then go quiet for longer
     * than the plane remembers a hearing (`REACHABLE_LINGER_MS`, 45 min — the calendar moves, as the field's
     * afternoon would) and split. The unlink is what re-reads the heard set, so each is unheard on the other's
     * board from that moment: the shape of two friends whose boards have not spoken all day.
     */
    private suspend fun quietPair(
        air: FakeMeshtasticAir,
        limits: LabLimits,
    ): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", limits, air = air).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", limits, air = air).apply { setDisplayName("Bob") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        assertTrue(alice.sendDm(bob, "hello"))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
        assertTrue(bob.sendDm(alice, "hi"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        lab.awaitCustodyParity(alice, bob)
        // Split first, then move the calendar: a relay copy still in its 0–150 ms jitter lands inside the router's
        // 10-min SeenSet and is deduplicated, where landing after the jump it would be a re-delivery that mints a
        // fresh ✓✓. The shortened linger sweep then has each board forget the other inside the await.
        lab.unlink(alice, bob)
        lab.clock.advance(LoraMeshTransport.REACHABLE_LINGER_MS + MINUTE)
        lab.await(1) { if (alice.loraHears(bob) || bob.loraHears(alice)) 0 else 1 }
        return alice to bob
    }

    /**
     * ADR 2026-10.a4pm, end to end: a DM to a peer the board has not heard rides only as the window's one probe.
     * Alice texts a quiet Bob three times. The first goes out as `far:chat`; the others wait in custody. Bob's
     * board hears the probe, so Alice is heard there and his ✓✓ rides back; that makes Bob heard on Alice's
     * board, and her first hearing of him re-offers what she held.
     */
    @Test
    fun aDmToAQuietFarPeerProbesOnceAndTheRestLandOnItsTick() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            val farBefore = alice.loraTx("far:chat")
            val probesBefore = alice.metrics.lora().loraDmProbes
            val heldBefore = alice.metrics.lora().loraUnheardHeld
            val reofferedBefore = alice.metrics.lora().loraReoffered

            listOf("are you up there", "it is windy", "bring a coat").forEach { assertTrue(alice.sendDm(bob, it)) }

            lab.assertConverged(listOf(alice, bob), atLeast = 5) { it.dmWith(if (it === alice) bob else alice) }
            val probes = alice.metrics.lora().loraDmProbes - probesBefore
            val held = alice.metrics.lora().loraUnheardHeld - heldBefore
            val live = alice.loraTx("far:chat") - farBefore
            val reoffered = alice.metrics.lora().loraReoffered - reofferedBefore
            assertEquals("exactly one probe: ${alice.loraLog}", 1L, probes)
            // Each DM aired once, live or re-offered: held behind the probe, or live because Bob's ✓✓ had already
            // made him heard when it was sent — and a DM held while that first hearing's re-offer was reading
            // custody is re-offered by it all the same. Which, the interleaving picks; the sum it cannot.
            assertEquals("each DM aired once: live=$live reoffered=$reoffered", 3L, live + reoffered)
            assertTrue("the probe is the first thing aired: ${alice.loraLog}", live >= 1)
            assertTrue("a held DM is never left behind: held=$held reoffered=$reoffered", held <= reoffered)
        }

    /**
     * The cost side of the same rule: Bob's board is off (the air loses everything), and Alice texts him
     * anyway. One probe per window reaches the air, not one per DM. Once the window has ended and Bob's board
     * is back, one more probe flies — the next DM's, or the re-probe of the newest held one (ADR 2026-10.rp3s), whichever
     * claims the window first — his ✓✓ makes him heard, and the re-offer carries the backlog.
     */
    @Test
    fun aDmToAnAbsentPeerSpendsOneProbePerWindow() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            val farBefore = alice.loraTx("far:chat")
            val probesBefore = alice.metrics.lora().loraDmProbes
            val reofferedBefore = alice.metrics.lora().loraReoffered

            val heldBefore = alice.metrics.lora().loraUnheardHeld
            air.lossy = { _, _ -> true }
            listOf("one", "two", "three").forEach { assertTrue(alice.sendDm(bob, it)) }
            lab.await(2) { (alice.metrics.lora().loraUnheardHeld - heldBefore).toInt() }
            assertTrue("the probe never left: ${alice.loraLog}", lab.tryAwait(1) { alice.loraTx("far:chat") - farBefore })
            assertEquals("one probe for three DMs", 1L, alice.metrics.lora().loraDmProbes - probesBefore)

            // Clean air before the clock moves: the window that reopens is also the re-probe's due time.
            air.lossy = { _, _ -> false }
            lab.clock.advance(LoraMeshTransport.DM_PROBE_WINDOW_MS)
            assertTrue(alice.sendDm(bob, "four"))

            lab.assertConverged(listOf(alice, bob), atLeast = 6) { it.dmWith(if (it === alice) bob else alice) }
            val probes = alice.metrics.lora().loraDmProbes - probesBefore + alice.metrics.lora().loraReprobed
            assertEquals("the window reopened once: ${alice.loraLog}", 2L, probes)
            // The first probe, lost to the dark board, comes back too: the re-offer never asks the fan-out's dedup slot.
            assertTrue("the backlog came by the re-offer", alice.metrics.lora().loraReoffered - reofferedBefore >= 2L)
        }

    /**
     * The absent peer comes back long after: Bob's board was dark while Alice texted, and returns more than
     * `PRESENCE_FRESH_MS` later with a post in the room — the first fresh frame of his Alice's board hears. Alice
     * writes nothing new. (Not a profile edit: Bob's first-hearing beacon can take the 5-min floor ahead of the edit,
     * and the edited profile then waits out the floor — a residual of ADR 038 §6, not this scenario's.) Her first hearing
     * re-offers the old DMs — too old to make *her* heard on Bob's board — so Bob's ✓✓s ride only because our own
     * ticks are not gated (ADR 2026-10.a4pm); gated, Alice would show one tick until radio contact.
     */
    @Test
    fun anAbsentPeerThatReturnsLongAfterTicksEveryDmWithoutANewOne() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val (alice, bob) = quietPair(air, reach)
            air.lossy = { _, _ -> true }
            listOf("one", "two").forEach { assertTrue(alice.sendDm(bob, it)) }
            assertTrue(
                "the probe never left: ${alice.loraLog}",
                lab.tryAwait(1) {
                    alice.metrics
                        .lora()
                        .loraDmProbes
                        .toInt()
                },
            )

            air.lossy = { _, _ -> false }
            lab.clock.advance(PRESENCE_FRESH_MS + MINUTE)
            assertTrue(bob.sendRoom("back in range")) // live traffic: his board is heard again

            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** Whether this node's board has heard [peer] within the linger — the reach the DM gate reads. */
    private fun LabNode.loraHears(peer: LabNode): Boolean =
        checkNotNull(lora) { "$name has no board" }.reachable.value.any { it.nodeId == peer.nodeId }

    private companion object {
        const val MINUTE = 60_000L
    }
}
