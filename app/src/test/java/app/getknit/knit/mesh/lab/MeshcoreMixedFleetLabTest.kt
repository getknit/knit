package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A Meshtastic fleet and a MeshCore fleet in one lab stay split (ADR 2026-11.gsqu §1, §7 and "Fleets"): one bound board
 * kind a phone, `loraNode` / `CAP_LORA_DIRECT` on one side and the sealed `meshcoreKey` on the other. Alice and Bob carry
 * Meshtastic boards on one air, Carol and Dave MeshCore boards on another; the two airs never hear each other, as two sync
 * words never do, so that split is the harness's. What is asserted is production's, per peer: neither plane holds a board
 * for the other fleet's people, schedules them, or logs a send to them; neither profile names the other kind's board; a
 * DM from one fleet to the other is held in custody, never offered to a board, and lands once over a link.
 */
@RunWith(RobolectricTestRunner::class)
class MeshcoreMixedFleetLabTest {
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

    /** The DM ✓✓ batches a second, not 45; the re-probe's sweep runs every 200 ms. */
    private val quick = LabLimits(dmAckHoldMs = 1_000L, loraLingerSweepMs = 200L)

    private suspend fun holds(
        node: LabNode,
        author: LabNode,
        id: String,
    ): Int = node.decrypted(node.dmWith(author)).count { it.first == id }

    private suspend fun heard(
        node: LabNode,
        author: LabNode,
        text: String,
    ): Int = if (node.roomPosts()[author.nodeId]?.contains(text) == true) 1 else 0

    /** 1 once [a]'s MeshCore plane would take a compact DM to [b] and read [b]'s posts ([LabNode.meshcoreReadyFor]). */
    private fun meshcoreReady(
        a: LabNode,
        b: LabNode,
    ): Int = if (a.meshcoreReadyFor(b.nodeId)) 1 else 0

    private fun clique(nodes: List<LabNode>): Array<Pair<LabNode, LabNode>> =
        nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }.toTypedArray()

    /**
     * Each fleet's room post reaches its own fleet over its own board; Alice's DM to Carol, across the fleets, is held in
     * custody — no board of either kind is asked for it — and lands once when a link joins them. Once the four converge
     * over links, each plane is read per peer of the other fleet: no board holder, no re-probe schedule, no send logged.
     */
    @Test
    @Suppress("LongMethod") // one scenario: each fleet's room, the cross-fleet DM, then the per-peer oracle
    fun aMeshtasticFleetAndAMeshcoreFleetStaySplit() =
        runBlocking {
            val meshtastic = MeshLab.quickAir()
            val meshcore = lab.meshcoreAir()
            meshcore.meshcoreEdit { link("carol", "dave") }
            val alice = lab.node("alice", quick, air = meshtastic).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = meshtastic).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, meshcore = meshcore).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", quick, meshcore = meshcore).apply { setDisplayName("Dave") }
            val everyone = listOf(alice, bob, carol, dave)
            val pairs = clique(everyone)
            lab.linkAll(*pairs)
            lab.awaitAcquainted(*everyone.toTypedArray())
            // The Meshtastic fleet's positive control: each plane holds its own fleet's board (a `loraNode` rides every
            // profile). The MeshCore fleet's comes at the end, once Carol and Dave are contacts.
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            pairs.forEach { (a, b) -> lab.unlink(a, b) }

            // Each fleet's room, before any link: over its own board.
            val aliceTx = alice.loraTx("")
            val carolTx = carol.loraTx("")
            assertTrue(alice.sendRoom("from the meshtastic hill"))
            assertTrue(carol.sendRoom("from the meshcore valley"))
            lab.await(1) { heard(bob, alice, "from the meshtastic hill") }
            lab.await(1) { heard(dave, carol, "from the meshcore valley") }
            // Each line is logged once the board took the post, so it can trail the landing: awaited, then held still.
            lab.await(aliceTx + 1) { alice.loraTx("") }
            lab.await(carolTx + 1) { carol.loraTx("") }
            val aliceAired = alice.loraTx("")
            val carolAired = carol.loraTx("")
            val alicePost = alice.ownMessageId(Conversations.NEARBY, "from the meshtastic hill")
            val carolPost = carol.ownMessageId(Conversations.NEARBY, "from the meshcore valley")
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, alicePost))
            assertEquals(DeliveryPlane.LoRa, dave.receivedVia(Conversations.NEARBY, carolPost))

            // Across the fleets: held for a link, never failed and never offered to a board.
            assertTrue(alice.sendDm(carol, "across the fleets"))
            val dm = alice.ownMessageId(alice.dmWith(carol), "across the fleets")
            lab.await(1) { if (dm in alice.custodyIdsFrom(alice)) 1 else 0 }
            assertTrue("no tick for it yet", alice.receiptPlanes(dm).isEmpty())

            lab.linkAll(*pairs)
            lab.await(1) { holds(carol, alice, dm) }
            assertNotEquals("it came over a link", DeliveryPlane.LoRa, carol.receivedVia(carol.dmWith(alice), dm))
            assertNotEquals("its ✓✓ too", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, carol))
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { it.dmWith(if (it === alice) carol else alice) }
            lab.assertConverged(everyone, atLeast = 2) { Conversations.NEARBY }
            assertEquals("it landed once", 1, holds(carol, alice, dm))
            assertNotEquals(
                "alice's post reached carol off the air",
                DeliveryPlane.LoRa,
                carol.receivedVia(Conversations.NEARBY, alicePost),
            )
            assertNotEquals("carol's reached alice off the air", DeliveryPlane.LoRa, alice.receivedVia(Conversations.NEARBY, carolPost))
            // The full-frame fan-out carries an own room post only (`LoraFramePolicy.eligible`), so nothing more aired on
            // either side of the DM: not its sealed frame, not its ✓✓.
            assertEquals("alice's board aired more than her post: ${alice.loraLog}", aliceAired, alice.loraTx(""))
            assertEquals("carol's board aired more than her post: ${carol.loraLog}", carolAired, carol.loraTx(""))
            for (n in listOf(alice, carol)) {
                assertTrue("${n.name}'s plane saw the cross-fleet DM: ${n.loraLog}", n.loraLog.none { dm in it })
            }

            // The MeshCore fleet's positive control: Carol and Dave as each other's direct contacts (a MeshCore board key goes
            // to direct contacts only), so the absences below are read off planes that do hold a board (lora-reviewer, Stage
            // 8). Last, after the four converged: the claim each then mints is sealed to its contact alone, and the others
            // rightly keep the version before it (Stage 5b-1), which a convergence across the fleets would read as a split.
            pairs.forEach { (a, b) -> lab.unlink(a, b) }
            lab.meetOverMeshcore(carol, dave)
            lab.await(1) { meshcoreReady(carol, dave) }
            lab.await(1) { meshcoreReady(dave, carol) }

            // Read per peer: neither plane books, holds or schedules anything for the other fleet.
            for (m in listOf(alice, bob)) {
                val plane = checkNotNull(m.lora)
                for (c in listOf(carol, dave)) {
                    val mc = checkNotNull(c.meshcore)
                    assertNull("${m.name}'s Meshtastic plane holds ${c.name}'s board", plane.boardHolderOf(c.nodeId))
                    assertNull("${c.name}'s MeshCore plane holds ${m.name}'s board", mc.boardHolderOf(m.nodeId))
                    assertTrue("${m.name} schedules ${c.name}", plane.reprobeSchedule().none { it.recipientId == c.nodeId })
                    assertTrue("${c.name} schedules ${m.name}", mc.reprobeSchedule().none { it.recipientId == m.nodeId })
                    assertTrue("${m.name} sent ${c.name} something: ${m.loraLog}", m.loraLog.none { "-> ${c.nodeId}" in it })
                    assertTrue("${c.name} sent ${m.name} something: ${c.loraLog}", c.loraLog.none { "-> ${m.nodeId}" in it })

                    // Neither profile names the other kind's board.
                    val cAtM = checkNotNull(m.peer(c)) { "${m.name} holds no row for ${c.name}" }
                    assertNull("${c.name}'s profile names a Meshtastic board at ${m.name}", cAtM.loraNode)
                    assertEquals("${c.name} claims the Meshtastic door", 0L, (cAtM.capabilities ?: 0L) and Protocol.CAP_LORA_DIRECT)
                    assertNull(
                        "${m.name}'s profile names a MeshCore board at ${c.name}",
                        checkNotNull(c.peer(m)) { "${c.name} holds no row for ${m.name}" }.meshcoreKey,
                    )
                }
            }
            assertNotNull("bob's Meshtastic board, named at alice", alice.peer(bob)?.loraNode)
            assertNotNull("dave's MeshCore board, named at carol", carol.peer(dave)?.meshcoreKey)
        }
}
