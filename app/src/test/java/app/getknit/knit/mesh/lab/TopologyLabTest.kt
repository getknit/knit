package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Shapes beyond a line: the diamond where every frame is overheard twice (the same-neighbor overhear bug's
 * cousin — `MeshRouter`'s suppression must count neighbors, and two real ones must not cancel a relay a third
 * node still needs), a long line (the hop budget), a ring that partitions and heals, and a crowd. A room
 * post's tick only reaches a live neighbor over links alone (ADR 2026-09.aa27), so where an author is two
 * hops from a reader it rides as a carrier and the readers converge among themselves.
 */
@RunWith(RobolectricTestRunner::class)
class TopologyLabTest {
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
    fun aNewcomerCrossesADiamondDespiteTwoOverheardCopies() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, alice to carol, bob to dave, carol to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)

            assertTrue(alice.sendDm(dave, "across the diamond"))
            lab.assertConverged(listOf(alice, dave), atLeast = 1, carriers = listOf(bob, carol)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        dave
                    } else {
                        alice
                    },
                )
            }

            val groupId = alice.createGroup(bob, carol, dave)
            assertTrue(alice.sendGroup(groupId, "all four"))
            assertTrue(lab.tryAwait(1) { dave.decrypted(groupId).size })
            assertTrue(dave.sendGroup(groupId, "from the far corner"))
            lab.assertConverged(listOf(alice, bob, carol, dave), atLeast = 2) { groupId }

            assertTrue(alice.sendRoom("room, two hops"))
            lab.assertConverged(listOf(bob, carol, dave), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }
        }

    @Test
    fun aFiveNodeLineDeliversEndToEndAndConvergesCustody() =
        runBlocking {
            val nodes =
                listOf("alice", "bob", "carol", "dave", "erin").map { n ->
                    lab.node(n).apply { setDisplayName(n.replaceFirstChar { it.uppercase() }) }
                }
            lab.linkAll(*nodes.zipWithNext().toTypedArray())
            lab.awaitAcquainted(*nodes.toTypedArray())
            val alice = nodes[0]
            val bob = nodes[1]
            val carol = nodes[2]
            val dave = nodes[3]
            val erin = nodes[4]

            assertTrue(alice.sendDm(erin, "end to end"))
            lab.assertConverged(listOf(alice, erin), atLeast = 1, carriers = listOf(bob, carol, dave)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        erin
                    } else {
                        alice
                    },
                )
            }

            val groupId = alice.createGroup(carol, erin)
            assertTrue(alice.sendGroup(groupId, "the odd ones"))
            assertTrue(lab.tryAwait(1) { erin.decrypted(groupId).size })
            assertTrue(erin.sendGroup(groupId, "heard at the far end"))
            lab.assertConverged(listOf(alice, carol, erin), atLeast = 2, carriers = listOf(bob, dave)) { groupId }
        }

    /** A ring cut in two places: each arc keeps talking, a DM crosses the cut, and both cuts heal at once. */
    @Test
    fun aRingHealsAPartitionFromBothSides() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, bob to carol, carol to dave, dave to alice)
            lab.awaitAcquainted(alice, bob, carol, dave)
            val groupId = alice.createGroup(bob, carol, dave)
            assertTrue(alice.sendGroup(groupId, "whole ring"))
            lab.assertConverged(listOf(alice, bob, carol, dave), atLeast = 1) { groupId }

            lab.unlink(alice, bob)
            lab.unlink(carol, dave)
            assertTrue(alice.sendGroup(groupId, "from the alice-dave arc"))
            assertTrue(bob.sendGroup(groupId, "from the bob-carol arc"))
            assertTrue(dave.sendDm(carol, "across the cut"))
            lab.assertConverged(listOf(alice, dave), atLeast = 2) { groupId }
            lab.assertConverged(listOf(bob, carol), atLeast = 2) { groupId }

            lab.linkAll(alice to bob, carol to dave)
            lab.assertConverged(listOf(alice, bob, carol, dave), atLeast = 3) { groupId }
            lab.assertConverged(listOf(carol, dave), atLeast = 1, carriers = listOf(alice, bob)) {
                it.dmWith(
                    if (it ===
                        carol
                    ) {
                        dave
                    } else {
                        carol
                    },
                )
            }
        }

    /** Eight phones in one room, all in range of each other, each posting: the "dense crowd" ADR vybk asked about. */
    @Test
    fun eightNodesFloodTheRoomAndStillConverge() =
        runBlocking {
            val names = listOf("alice", "bob", "carol", "dave", "erin", "frank", "grace", "heidi")
            val nodes = names.map { n -> lab.node(n).apply { setDisplayName(n.replaceFirstChar { it.uppercase() }) } }
            val links = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
            lab.linkAll(*links.toTypedArray())
            lab.awaitAcquainted(*nodes.toTypedArray())

            nodes.forEach { n -> (1..3).forEach { assertTrue(n.sendRoom("${n.name} $it")) } }
            lab.assertConverged(nodes, atLeast = 24) { Conversations.NEARBY }
        }
}
