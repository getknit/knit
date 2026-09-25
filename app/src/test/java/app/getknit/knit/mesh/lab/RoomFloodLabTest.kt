package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
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
 * A stranger floods the Nearby room. The room is the one thread anyone in radio range can write into, and a
 * node id is free, so the two bounds under test are the ones that do not key on who the sender claims to be:
 * what a bounded flood is allowed to *displace* (the room sweep reads who wrote what — a contact's posts
 * outlive a stranger's) and how fast one *link* may hand posts over at all (`IngressBudget`, the meter in the
 * router that stops the flood at the first honest hop). Both scenarios run the flood through the real send
 * and inbound paths with the policy numbers shrunk, and end in the oracle so the containment is shown not to
 * have cost the honest nodes their convergence.
 */
@RunWith(RobolectricTestRunner::class)
class RoomFloodLabTest {
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
     * Alice talks to Bob (she has DM'd him) and posts in the room herself; Mallory, a stranger, posts twenty
     * times into a room Alice keeps ten of. Newest-first alone would have kept ten of Mallory's and none of
     * Bob's or Alice's own. The sweep keeps every post of theirs, trims Mallory to the per-stranger four, and
     * the room lands under its cap. Bob and Mallory, uncapped, still converge on all of it — the sweep is a
     * local decision and never a wire one. A triangle rather than a line: a room tick toward an author who is
     * not a live neighbor deliberately never escalates into custody (it ride-holds, ADR 2026-09.aa27), so the
     * oracle's tick check needs every author in range of every reader.
     */
    @Test
    fun aStrangersFloodCannotEvictAContactsRoomPosts() =
        runBlocking {
            val alice = lab.node("alice", LabLimits(roomMaxMessages = 10, roomMaxPerStranger = 4)).apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val mallory = lab.node("mallory").apply { setDisplayName("Mallory") }
            lab.linkAll(alice to bob, alice to mallory, bob to mallory)
            lab.awaitAcquainted(alice, bob, mallory)

            assertTrue(alice.sendDm(bob, "hi bob")) // Bob is now someone Alice talks to
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            (1..3).forEach { assertTrue(bob.sendRoom("bob $it")) }
            assertTrue(alice.sendRoom("alice here"))
            (1..20).forEach { assertTrue(mallory.sendRoom("spam $it")) }
            lab.assertConverged(listOf(alice, bob, mallory), atLeast = 24) { Conversations.NEARBY }

            alice.sweepLocalStorage()

            val room = alice.roomPosts()
            assertEquals("Bob's posts survive the flood", (1..3).map { "bob $it" }.toSet(), room[bob.nodeId])
            assertEquals("Alice's own post survives the flood", setOf("alice here"), room[alice.nodeId])
            assertEquals("a stranger keeps only the per-stranger newest few", (17..20).map { "spam $it" }.toSet(), room[mallory.nodeId])
            assertTrue("the room is under its cap: $room", room.values.sumOf { it.size } <= 10)
            // The others never capped anything, and the sweep changed nothing on the wire.
            assertEquals(24, bob.roomPosts().values.sumOf { it.size })
            assertEquals(24, mallory.roomPosts().values.sumOf { it.size })
        }

    /**
     * A line Mallory–Alice–Bob, with Alice's per-link budget at five posts and no refill. Mallory posts twenty:
     * Alice delivers five, refuses fifteen at the router — before verify, custody or relay — and Bob, who only
     * hears the room through Alice, holds the same five: the flood stopped at the first honest hop. Bob's own
     * post still crosses (his link has its own bucket), Mallory still receives it, and Alice and Bob converge
     * on the room, ticks and custody included — the refusal is not a divergence.
     */
    @Test
    fun oneLinksFloodIsMeteredAtIngressAndStopsAtTheFirstHonestHop() =
        runBlocking {
            val alice = lab.node("alice", LabLimits(ingressBurst = 5, ingressPerMinute = 0)).apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val mallory = lab.node("mallory").apply { setDisplayName("Mallory") }
            lab.linkAll(mallory to alice, alice to bob)
            lab.awaitAcquainted(alice, bob, mallory)

            (1..20).forEach { assertTrue(mallory.sendRoom("spam $it")) }

            val refused = lab.tryAwait(15) { alice.drops(DropReason.INGRESS_REFUSED).toInt() }
            assertTrue("Alice refused ${alice.drops(DropReason.INGRESS_REFUSED)} of the fifteen over-budget posts", refused)
            assertTrue(lab.tryAwait(5) { bob.roomPosts()[mallory.nodeId]?.size ?: 0 })
            lab.settle()
            assertEquals("Alice delivered exactly the budget", 5, alice.roomPosts()[mallory.nodeId]?.size)
            assertEquals("Bob heard only what Alice admitted", 5, bob.roomPosts()[mallory.nodeId]?.size)
            assertEquals("the flood never reached Bob's meter", 0L, bob.drops(DropReason.INGRESS_REFUSED))

            assertTrue(bob.sendRoom("hello from bob"))
            assertTrue(lab.tryAwait(1) { mallory.roomPosts()[bob.nodeId]?.size ?: 0 })
            lab.assertConverged(listOf(alice, bob), atLeast = 6) { Conversations.NEARBY }
        }
}
