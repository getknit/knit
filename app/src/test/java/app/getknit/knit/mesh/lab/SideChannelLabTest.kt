package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The BLE side channel (ADR 2026-09.sjaa) in the box: [LabPages] is the air its pages cross, joined per node
 * the way a board joins a `FakeMeshtasticAir`, and the fast path routes through the real `BleFastRoutePolicy`.
 * The carrier itself is JVM-tested piece by piece (`SideCarouselTest`, `SideScanPolicyTest`, …); what these
 * scenarios pin is what a page does to everything *above* the transport, against the full oracle:
 *
 * - a frame off a page arrives from its **author**, not from the hop, so a node two hops out counts the
 *   author and the relaying neighbor as two distinct hops and `MeshRouter.countOverheard` suppresses its own
 *   relay — the flood's completeness then rests on the fast path's link copy to every linked peer;
 * - a node with **no link at all** hears the room, and either shows it (a key it holds) or parks it until a
 *   link brings the key — the sighted-but-unlinked phone the device trial left open;
 * - nothing DM-form is ever paged, and a frame past three pages rides the links alone.
 *
 * A room post's tick reaches only a live neighbor over links (ADR 2026-09.aa27) and a tick is DM-form, so
 * the page changes nothing there: an author two hops from a reader rides as a carrier, as in `TopologyLabTest`.
 */
@RunWith(RobolectricTestRunner::class)
class SideChannelLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()
    private lateinit var pages: LabPages

    @Before
    fun setUp() {
        lab = MeshLab()
        pages = LabPages()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * A line Alice–Bob–Carol–Dave, every phone flagged and in page range of every other, Dave's scanner
     * pre-empted (the trial's phone receiving a stream caught four pages in ten; here none). Alice's post
     * reaches Carol twice under two hop ids — her own page at once, Bob's link copy a moment later — so when
     * Bob's copy lands inside Carol's relay jitter, `countOverheard` cancels her relay toward Dave; Dave, deaf
     * to the pages, has the post anyway, off the fast path's link copy Carol sends the moment she delivers it.
     * The jitter is real time the lab cannot pin, so the race is not asserted; the copy is — it must have
     * crossed Carol's pipe to Dave as the fast path's (`via=fast`), whether or not the relay fired too —
     * and the oracle closes over whichever way the race went.
     */
    @Test
    fun aLineHearsTheAuthorOffAPageAndTheLinkCopyStillReachesTheDeafEnd() =
        runBlocking {
            val alice = lab.node("alice", pages = pages).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", pages = pages).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", pages = pages).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", pages = pages).apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, bob to carol, carol to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)
            pages.lossy = { to, _ -> to == dave.nodeId }

            assertTrue(alice.sendRoom("heard two ways"))
            lab.assertConverged(listOf(bob, carol, dave), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }

            val post = alice.decrypted(Conversations.NEARBY).single().first
            assertTrue(
                "Carol never heard Alice's post off a page: ${carol.transport.heardOnPages}",
                carol.transport.heardOnPages.any { it.contains(post) && it.endsWith(alice.nodeId.take(6)) },
            )
            assertTrue(
                "Dave's scanner was pre-empted, yet he heard the post off a page: ${dave.transport.heardOnPages}",
                dave.transport.heardOnPages.none { it.contains(post) },
            )
            assertTrue(
                "the fast path's link copy of the post never crossed Carol's pipe to Dave: " +
                    carol.transport.sent.filter { it.contains(post) },
                carol.transport.sent.any { it.contains(" ${dave.nodeId.take(6)} ") && it.contains(post) && it.endsWith("via=fast") },
            )
        }

    /**
     * A frame crosses a pipe once (`LinkCrossings`, the phone's `writeOnce`). Alice's post reaches Bob by
     * whichever of her two copies is enqueued first — the router's flood or the fast path's link copy — and
     * the other is skipped on her side; Bob's re-fan never goes back toward Alice, and his one copy to Carol
     * is single the same way. Before the memo every `shouldFastFanout` frame crossed each L2CAP link twice
     * and the receiver's SeenSet ate the second: airtime, never divergence, which is why the oracle is the
     * one every scenario runs and the count is what this one pins. The pages are lost on purpose so the link
     * discipline is judged alone (a page would hand Carol the post from Alice directly).
     */
    @Test
    fun aFrameCrossesEachPipeOnce() =
        runBlocking {
            val alice = lab.node("alice", pages = pages).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", pages = pages).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", pages = pages).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            pages.lossy = { _, _ -> true }

            assertTrue(alice.sendRoom("once per pipe"))
            lab.assertConverged(listOf(bob, carol), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }

            val post = alice.decrypted(Conversations.NEARBY).single().first
            for (node in listOf(alice, bob, carol)) {
                // A `sent` line is `#seq <to> <type> <id> relay=… via=…`; group the post's crossings by receiver.
                val perReceiver =
                    node.transport.sent
                        .filter { it.contains(" $post ") }
                        .groupBy { it.split(" ")[1] }
                perReceiver.forEach { (to, lines) ->
                    assertEquals("${node.name} wrote the post to $to more than once: $lines", 1, lines.size)
                }
            }
            assertTrue(
                "Bob handed Alice her own post back: ${bob.transport.sent.filter { it.contains(" $post ") }}",
                bob.transport.sent.none { it.contains(" ${alice.nodeId.take(6)} ") && it.contains(" $post ") },
            )
            assertTrue(
                "Alice's second copy for Bob was not skipped: ${alice.transport.dupSkipped}",
                alice.transport.dupSkipped.any { it.startsWith(bob.nodeId.take(6)) },
            )
        }

    /**
     * Erin met Alice and Bob by link, then fell off both — over the budget, in backoff: sighted, not linked.
     * Alice's post reaches her from the page alone, verified against the key she already holds, shown. What
     * the page does *not* do is converge custody: Bob's tick to Alice is DM-form, so it rides links only, and
     * a linkless Erin holds one frame fewer than the pair until a link's digest exchange heals it — the
     * ordinary re-serve, run here before the oracle.
     */
    @Test
    fun anUnlinkedPeerInRangeHearsThePostFromThePageAlone() =
        runBlocking {
            val alice = lab.node("alice", pages = pages).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", pages = pages).apply { setDisplayName("Bob") }
            val erin = lab.node("erin", pages = pages).apply { setDisplayName("Erin") }
            lab.linkAll(alice to bob, alice to erin, bob to erin)
            lab.awaitAcquainted(alice, bob, erin)
            lab.unlink(alice, erin)
            lab.unlink(bob, erin)
            val links = erin.transport.neighbors.value
            assertTrue("Erin still holds a link: $links", links.isEmpty())
            val sentBefore = erin.transport.sent.size

            assertTrue(alice.sendRoom("to whoever is listening"))
            assertTrue(
                "Erin never got the post with no link: ${erin.decrypted(Conversations.NEARBY)}\n${lab.report(listOf(alice, bob, erin))}",
                lab.tryAwait(1) { erin.decrypted(Conversations.NEARBY).size },
            )
            val post = alice.decrypted(Conversations.NEARBY).single().first
            assertTrue(
                "Erin's copy did not come off a page: ${erin.transport.heardOnPages}",
                erin.transport.heardOnPages.any { it.contains(post) },
            )
            assertEquals("a frame crossed a pipe Erin does not have: ${erin.transport.sent}", sentBefore, erin.transport.sent.size)
            assertTrue("Erin did not custody what she heard", post in erin.custodyIds())

            lab.link(erin, bob)
            lab.assertConverged(listOf(bob, erin), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }
        }

    /**
     * Erin never met anyone: her scanner hears Alice's post but she holds no key to verify it, so the frame
     * is parked for the key (`framesHeld`) — she has no neighbor to ask. The first link she makes carries the
     * request; Bob serves Alice's profile, the parked frame replays, and the oracle's held-equals-replayed
     * invariant closes over it.
     */
    @Test
    fun aStrangerOnThePagesParksThePostAndReplaysItWhenALinkBringsTheKey() =
        runBlocking {
            val alice = lab.node("alice", pages = pages).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", pages = pages).apply { setDisplayName("Bob") }
            lab.linkAll(alice to bob)
            lab.awaitAcquainted(alice, bob)
            // "Never met anyone" is the scenario's premise, not a thing the mesh promises: a first sighting's
            // profile flood pages out too (`watchReachable` → `fastFanout`), unawaited by `awaitAcquainted`, and one
            // trailing past Erin's boot hands her Alice's key before the post. Her scanner misses every profile page
            // but her own peers', so the key can only come over the link she makes below.
            val met = setOf(alice.nodeId, bob.nodeId)
            pages.lossy = { to, wire -> to !in met && WireCodec.decodeEnvelope(wire.signed)?.type == FrameType.PROFILE }
            val erin = lab.node("erin", pages = pages).apply { setDisplayName("Erin") }

            assertTrue(alice.sendRoom("from a stranger's phone"))
            assertTrue(
                "Erin never parked the post she cannot verify: ${erin.heldForKey()} held\n${lab.report(listOf(erin))}",
                lab.tryAwait(1) { erin.heldForKey() },
            )
            assertTrue("Erin showed a post she could not verify", erin.decrypted(Conversations.NEARBY).isEmpty())

            lab.link(erin, bob)
            lab.assertConverged(listOf(bob, erin), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }
            assertTrue("Erin never knew Alice", erin.knows(alice))
        }

    /**
     * What never rides a page: a DM and everything DM-form under it (the sealed tick back, the sealed profile
     * that answers a first frame) go to the linked addressee alone, and a room post too long for three pages
     * rides the links and the flood. Carol, in range and unlinked, hears neither; Alice and Bob converge on both.
     */
    @Test
    fun aDmNeverRidesAPageAndAnOversizedPostRidesTheLinksAlone() =
        runBlocking {
            val alice = lab.node("alice", pages = pages).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", pages = pages).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", pages = pages).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)

            assertTrue(alice.sendDm(bob, "between us"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            val long = String(CharArray(OVERSIZED_CHARS) { LETTERS[Random.nextInt(LETTERS.length)] })
            assertTrue(alice.sendRoom(long))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }

            val dmForm = pages.aired.filter { it.contains(" ${LabPages.FORM_DM} ") || it.contains(" ${LabPages.FORM_GROUP_CHAT} ") }
            assertTrue("a DM-form frame rode a page: $dmForm", dmForm.isEmpty())
            val oversized = alice.decrypted(Conversations.NEARBY).single().first
            assertTrue("the oversized post was not refused by the pages: ${pages.tooBig}", pages.tooBig.any { it.contains(oversized) })
            assertTrue("the oversized post rode a page: ${pages.aired}", pages.aired.none { it.contains(oversized) })
            val chatPages = carol.transport.heardOnPages.filter { it.startsWith("${FrameType.CHAT} ") }
            assertEquals("Carol heard a chat frame off a page she should not have", emptyList<String>(), chatPages)
            assertTrue("Carol has the oversized post", carol.decrypted(Conversations.NEARBY).isEmpty())
            assertTrue("Carol has the DM", carol.decrypted(carol.dmWith(alice)).isEmpty())
        }

    /** How many frames this node has parked for a sender key it does not hold (`framesHeld`). */
    private fun LabNode.heldForKey(): Int = metrics.snapshot().framesHeld.toInt()

    private companion object {
        /** Random letters deflate to ~4.7 bits each, so this many stay past `FastFrameCodec.MAX_PARTS` pages. */
        const val OVERSIZED_CHARS = 2_000
        const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
    }
}
