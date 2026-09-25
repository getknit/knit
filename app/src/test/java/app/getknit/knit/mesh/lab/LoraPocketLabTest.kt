package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
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
 * Two mesh pockets bridged by boards (ADR 044, ADR 054), with the real `LoraMeshTransport` composed into
 * real stacks over one [FakeMeshtasticAir] — the "two real stacks" leg the five two-board-rig verifications
 * (y8pu, rre4, t8t8, 7c8n, qsj6) still owe. A pocket is whatever a node holds links to; the boards elect a
 * gateway per pocket off those links. The Trickle interval is shortened through [LabLimits] so an OFFER
 * and the backfill it drives happen inside an await; nothing else about the plane is changed.
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

    /**
     * A Trickle short enough for an OFFER inside an await, long enough not to spend the airtime window on
     * offers alone: at one a second the GOSSIP share ate the tail a tick needs (`loraTickDeferred`).
     */
    private val quick = LabLimits(loraGossipMinMs = 4_000, loraGossipMaxMs = 8_000)

    /**
     * Everyone meets by radio first, as people who later split into pockets did: profiles, custody parity,
     * then the cross-pocket links go. First contact by board alone is minutes, not seconds — a passive
     * gateway fans nothing and the bridge budget serves one profile per 15-min window — and is the clock
     * tier's to pin.
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

    /** Pocket A is Alice's board plus Bob; pocket B is Carol's board plus Dave. A post from a board-less phone crosses. */
    @Test
    fun aRoomPostCrossesTwoPocketsThroughTheGatewaysOnly() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", quick).apply { setDisplayName("Dave") }
            meetThenSplit(listOf(alice, bob, carol, dave), keep = listOf(alice to bob, carol to dave))

            assertTrue(bob.sendRoom("from pocket a"))
            val post = bob.ownMessageId(Conversations.NEARBY, "from pocket a")
            assertTrue("dave never heard bob's post", lab.tryAwait(1) { dave.roomPosts()[bob.nodeId]?.size ?: 0 })
            assertTrue("alice's board carried it", alice.loraTx("fanout:chat") >= 1)
            assertTrue("carol's board heard it", carol.metrics.snapshot().loraReceived >= 1)
            assertEquals("bob has no board", 0L, bob.metrics.snapshot().loraSent)

            // Custody agrees within a pocket, never across: a DM-form frame to a linked peer (Alice's own
            // tick to Bob) never rides the board. The far pocket's ticks are the next scenario's subject.
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
            lab.assertConverged(listOf(carol, dave), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * Issue #48, closed by the gateway taking the tick's last hop. Carol's ✓✓ for Bob's post leaves her board
     * as the targeted `send:chat` (the ride hold runs out, ADR 2026-09.y5f3) and Alice's board hears it. It is
     * a point-to-point `relay = false` frame, so Alice neither delivers it (not hers) nor floods it, and a room
     * tick never escalates into custody (ADR 2026-09.aa27) — but it is addressed to a peer Alice holds a live
     * link to, so `MeshRouter.handOn` hands it over that link. The oracle is the plane Bob records it on: the
     * last hop was the link, not the air.
     *
     * Dave's tick is the residual this does not close: an acker with no board and no spool has nowhere to send
     * one at all.
     */
    @Test
    fun aFarPocketsTickReachesABoardLessAuthorBehindTheGateway() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", quick).apply { setDisplayName("Dave") }
            meetThenSplit(listOf(alice, bob, carol, dave), keep = listOf(alice to bob, carol to dave))

            assertTrue(bob.sendRoom("from pocket a"))
            val post = bob.ownMessageId(Conversations.NEARBY, "from pocket a")
            assertTrue(lab.tryAwait(1) { carol.roomPosts()[bob.nodeId]?.size ?: 0 })
            val via = lab.awaitReceipt(bob, post, carol)
            assertTrue("the tick came the last hop over the link, not the board: $via", via != DeliveryPlane.LoRa)
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
            assertTrue("the gate never fired", alice.metrics.snapshot().loraSkippedLinked >= 1)
            assertEquals("nothing of the DM went on the air", 0, alice.loraTx("far:chat"))
            assertEquals(0, bob.loraTx("far:chat"))
        }

    /**
     * ADR 2026-09.y8pu: a post fanned out while the far board could not hear is backfilled once it can —
     * the far gateway's OFFER names what it holds, and the near one serves what is missing.
     */
    @Test
    fun aPostFannedIntoAnEmptySkyIsBackfilledWhenTheFarBoardReturns() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            lab.link(alice, carol)
            lab.awaitAcquainted(alice, carol)
            lab.awaitCustodyParity(alice, carol)
            lab.unlink(alice, carol)

            air.lossy = { _, _ -> true }
            assertTrue(alice.sendRoom("into an empty sky"))
            assertTrue("the fan-out never left", lab.tryAwait(1) { alice.loraTx("fanout:chat") })
            air.lossy = { _, _ -> false }

            assertTrue("the backfill never repaired it", lab.tryAwait(1) { carol.roomPosts()[alice.nodeId]?.size ?: 0 })
            assertTrue("it came as a bridge serve", alice.metrics.snapshot().loraBridged >= 1)
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { Conversations.NEARBY }
        }

    /** Two boards on one link are one pocket: the election leaves one of them passive, and it stays quiet. */
    @Test
    fun aSecondBoardInAPocketGoesPassiveAndStaysQuiet() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val amber = lab.node("amber", quick, air = air).apply { setDisplayName("Amber") }
            val carol = lab.node("carol", quick, air = air).apply { setDisplayName("Carol") }
            meetThenSplit(listOf(alice, amber, carol), keep = listOf(alice to amber))

            assertTrue("no board went passive", lab.tryAwait(1) { listOf(alice, amber).count { it.metrics.snapshot().loraPassive > 0 } })
            val passive = listOf(alice, amber).single { it.metrics.snapshot().loraPassive > 0 }
            val active = listOf(alice, amber).single { it !== passive }
            val activeFanned = active.loraTx("fanout:chat")

            assertTrue(passive.sendRoom("from the passive board's phone"))
            assertTrue("carol never heard it", lab.tryAwait(1) { carol.roomPosts()[passive.nodeId]?.size ?: 0 })
            assertTrue("the active board carried it", active.loraTx("fanout:chat") > activeFanned)
            assertEquals("the passive board fanned nothing", 0, passive.loraTx("fanout:chat"))
        }

    /** The two-phone trial's last leg: a DM sent while the far board could not hear lands through the bridge. */
    @Test
    fun aDmSentWhileTheFarBoardWasOffLandsThroughTheBridge() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.awaitCustodyParity(alice, bob)
            lab.unlink(alice, bob)

            air.lossy = { _, _ -> true }
            assertTrue(alice.sendDm(bob, "while your board was off"))
            assertTrue("the DM never left", lab.tryAwait(1) { alice.loraTx("far:chat") })
            air.lossy = { _, _ -> false }

            assertTrue("the bridge never carried it", lab.tryAwait(3) { bob.decrypted(bob.dmWith(alice)).size })
            // A DM that arrived over the board is ticked through DmAckCoalescer's 45 s hold (ADR 054), which
            // the lab does not shorten yet; the oracle waits it out.
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = 60_000) { it.dmWith(if (it === alice) bob else alice) }
        }
}
