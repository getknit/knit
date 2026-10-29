package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
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
 * pocket, which costs no air. A pocket is whatever a node holds links to. The DM scenarios went with the reach gate:
 * since ADR 2026-10.x9fa a DM rides as a compact unicast, and its scenarios are `LoraDirectLabTest`'s.
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
}
