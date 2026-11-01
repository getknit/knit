package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A contact's direct Internet link joins two radio islands (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` §7): the real
 * `DirectLinkTransport` on each end, over in-memory streams ([MeshLab.directLink]) — no punch, no relay. What the two
 * contacts say to each other, and in the group they share, converges across it; the Nearby room and a stranger's frames
 * on either island never cross it, either way.
 */
@RunWith(RobolectricTestRunner::class)
class DirectLinkLabTest {
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
     * Alice, Bob and Carol meet by radio and start a group; then Bob leaves for another island, where he meets Dave, a
     * stranger to the rest, and Alice and Bob keep in touch over a direct link. Alice's DM and her group post reach Bob
     * over the link, and his delivery ticks come back over it; Carol's group post reaches him through Alice, with her
     * sender-key seed, and his tick to her goes back the same way (frames between two members of a group the pair
     * shares cross, L-7-2). Neither island's room posts reach the other: the link carries the pair's frames (and their
     * shared group's), so the two islands' stores never become one.
     */
    @Test
    fun twoIslandsConvergeThePairsOwnTrafficOverOneDirectLinkAndNothingElse() =
        runBlocking {
            val alice = lab.node("alice", directLinks = true).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", directLinks = true).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(alice, bob)
            lab.link(alice, carol)
            lab.link(bob, carol)
            lab.awaitAcquainted(alice, bob, carol)
            val group = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(group, "welcome"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { group }

            // Bob leaves for his own island, where only Dave is; Alice and Bob link over the Internet.
            lab.unlink(alice, bob)
            lab.unlink(bob, carol)
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.link(bob, dave)
            // Dave's key reaches Bob by his link-up profile push, on his own neighbour pass: wait for it, or Dave's post below
            // can land first and park for the key, a round trip the convergence checks never wait on.
            lab.await(1) { if (bob.knows(dave)) 1 else 0 }
            lab.directLink(alice, bob)

            assertTrue(alice.sendDm(bob, "over the link"))
            assertTrue(alice.sendGroup(group, "from alice, across"))
            assertTrue(carol.sendGroup(group, "from carol's island"))
            assertTrue(carol.sendRoom("carol's room post"))
            assertTrue(dave.sendRoom("dave's room post"))

            lab.assertConverged(listOf(alice, bob), atLeast = 1, custodyAcross = listOf(alice, carol)) {
                it.dmWith(if (it === alice) bob else alice)
            }
            // The group's three posts on all three, Carol's seed and the ticks between her and Bob crossing the link through
            // Alice.
            // Alice's island, stable since the group began, converges its custody; Dave, a stranger who joined Bob's island
            // late, is checked for what must never reach him, not for parity (a newcomer's custody waits on a re-offer).
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 3, custodyAcross = listOf(alice, carol)) { group }

            // Each room post reached the end of the link on its own island, which relayed it toward the far end, and the
            // link refused that very frame; only then do the far islands' empty rooms prove anything.
            lab.await(1) { alice.roomPosts()[carol.nodeId]?.size ?: 0 }
            lab.await(1) { bob.roomPosts()[dave.nodeId]?.size ?: 0 }
            val carolsPost = carol.ownMessageId(Conversations.NEARBY, "carol's room post")
            val davesPost = dave.ownMessageId(Conversations.NEARBY, "dave's room post")
            lab.await(1) { if (carolsPost in checkNotNull(alice.link).refusedIds()) 1 else 0 }
            lab.await(1) { if (davesPost in checkNotNull(bob.link).refusedIds()) 1 else 0 }
            assertNull("carol's room post reached bob's island", bob.roomPosts()[carol.nodeId])
            assertNull(dave.roomPosts()[carol.nodeId])
            assertNull("dave's room post reached alice's island", alice.roomPosts()[dave.nodeId])
            assertNull(carol.roomPosts()[dave.nodeId])
        }
}
