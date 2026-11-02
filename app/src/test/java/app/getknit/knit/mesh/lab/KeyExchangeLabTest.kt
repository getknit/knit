package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.first
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
 * A frame whose sender's key is not here yet. Two recoveries exist and neither had a cross-node test: the
 * inbound one (`KeyExchange` — park the frame, ask the neighbors for the key with a `keyreq`, a holder serves
 * the profile verbatim, the park replays; "verified on devices" until now), and the outbound one (a DM
 * composed before the recipient's key is known is saved `pendingKey` and re-sealed the moment their profile
 * lands — `context/store-and-forward.md`, retransmit-on-key-arrival).
 */
@RunWith(RobolectricTestRunner::class)
class KeyExchangeLabTest {
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
     * Carol knows Bob. Alice joins on Bob's far side and posts; Bob relays her profile and her post toward
     * Carol, but only the post gets through. Carol cannot verify a stranger, so she parks the post and asks
     * Bob for the key; Bob serves Alice's profile; Carol pins it, replays the post, and the three custodies
     * agree — the served profile is a frame like any other.
     *
     * "Only the post" takes two knobs, because Alice sends Bob a second chat frame the scenario never asked
     * for: Bob's tick for her post is his first sealed frame to her, it carries the X3DH init, and she answers
     * it with a sealed profile (`IntroSync`) that Bob relays toward Carol some jitter after the post. The hold
     * is released on the post alone, and from that moment until the re-link the pipe *loses* every flood
     * relay — the answer, whenever Bob's jitter puts it — and passes only the point-to-point frames, which is
     * what the served key is. Otherwise the answer reaches Carol before the key does and parks beside the
     * post, and "one frame parked" reads two (a 1-in-8 flake on one core).
     *
     * In a boards run Alice's board airs her post (an own one-packet room post, ADR 2026-10.rp3s), so Carol can hear
     * it and ask Bob for the key before the release. Bob's answer then parks in the hold with the relays, so the
     * release lets it through as well. Without that, the release dropped the key and Carol's 30 s re-ask cooldown
     * kept her from asking again (boards run 1 of CI run 41261854309). A boards node's transport is a composite, whose
     * `fastFanout` floods a child with no fast plane at once, beside the router's jittered relay: the hold can park two
     * copies of one post, which the release writes once, so the post is counted by frame.
     */
    @Test
    fun aFrameFromAStrangerIsParkedAndReplayedWhenTheKeyIsFetched() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(bob, carol)
            lab.awaitAcquainted(bob, carol)

            bob.transport.hold(carol.transport)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendRoom("from a stranger"))
            assertTrue(
                "bob never relayed alice's post",
                lab.tryAwait(1) { bob.transport.held(carol.transport).count { it.isRoomPostFrom(alice.nodeId) } },
            )
            bob.transport.lossy(carol.transport) { it.relay } // the flood relays from here on are the air's to lose
            val released =
                bob.transport.release(carol.transport) { batch ->
                    batch.filter { it.isRoomPostFrom(alice.nodeId) || (!it.relay && it.isProfileFrom(alice.nodeId)) }
                }
            assertOnePostReleased(released, alice, listOf(alice, bob, carol))

            assertTrue("carol never refused the stranger's frame", lab.tryAwait(1) { carol.drops(DropReason.NO_SENDER_KEY).toInt() })
            assertTrue(
                "carol never asked for the key",
                lab.tryAwait(1) {
                    carol.metrics
                        .keys()
                        .keyRequestsSent
                        .toInt()
                },
            )
            assertTrue(
                "bob never served it",
                lab.tryAwait(1) {
                    bob.metrics
                        .keys()
                        .keysServed
                        .toInt()
                },
            )
            assertTrue("carol never pinned alice", lab.tryAwait(1) { if (carol.knows(alice)) 1 else 0 })
            awaitIntroAnswerHeld(alice, bob)

            // Issue #49 (found here on 2026-09-14, fixed by ADR 2026-09.7bu7): the served profile is a
            // point-to-point `relay = false` frame, and the custody gate used to take flood frames only — so
            // Carol delivered it, pinned it and stored nothing, while her router had marked the id seen, which
            // deduped the custodial copy the next digest exchange served for the whole SeenSet window (10 min).
            // She now custodies it on delivery, so the stores agree here rather than eleven minutes from here;
            // the relayed copies the hold ate are still the digest exchange's to repair (the re-link does that).
            bob.transport.lossy(carol.transport) // a clean link again: the re-link's digest exchange repairs what the hold and the air ate
            lab.unlink(bob, carol)
            lab.link(bob, carol)
            awaitRepaired(alice, bob, carol, "from a stranger")
            val snap = carol.metrics.snapshot()
            assertEquals("one frame parked, one replayed", 1L to 1L, snap.keys.framesHeld to snap.keys.framesReplayed)
            assertEquals("the missing key was recovered", 1L, snap.keys.keysRecovered)
            assertEquals("Alice", carol.peer(alice)?.name)
            // Alice as a carrier: on a line her post's tick from Carol has no way back to her (no third party's room
            // tick crosses Bob), so she is not asked for the tick check, only for custody.
            lab.assertConverged(listOf(bob, carol), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }
        }

    /**
     * Alice writes to Carol before ever meeting her — no row, no key — and the DM parks as `pendingKey`.
     * When they meet, Carol's profile pins the key and the DM is sealed and flooded under its original id.
     */
    @Test
    fun aDmComposedBeforeTheKeyIsKnownFloodsWhenTheProfileLands() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            assertTrue(alice.sendDm(carol, "composed early"))
            val early = alice.ownMessageId(alice.dmWith(carol), "composed early")
            assertTrue(
                "the DM parked on pendingKey",
                alice.messages
                    .observeNewestMessages(alice.dmWith(carol), MeshLab.WINDOW)
                    .first()
                    .single {
                        it.id ==
                            early
                    }.pendingKey,
            )

            lab.link(alice, carol)
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { it.dmWith(if (it === alice) carol else alice) }
            assertEquals(early, carol.decrypted(carol.dmWith(alice)).single().first)
        }

    /**
     * One post of [author]'s let through, counted by frame: the flood's copy and the fast path's can both park, and the
     * release writes them once (`LabTransport.deliverHeld`, as the phone's link does).
     */
    private fun assertOnePostReleased(
        released: List<WireEnvelope>,
        author: LabNode,
        nodes: List<LabNode>,
    ) {
        val posts = released.filter { it.isRoomPostFrom(author.nodeId) }
        val distinct = posts.mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }.distinct()
        if (distinct.size != 1) throw AssertionError("the release let through ${describe(posts)}\n${lab.report(nodes)}")
    }

    /**
     * Alice answers Bob's tick for her post (his first sealed frame to her) with a sealed profile addressed to Bob, the
     * KDoc's second chat frame. Bob keeps it and floods it no further, so Carol's carrier copy comes from a digest
     * exchange: the re-link's when it has reached Bob by then, else the 60 s re-offer, past the oracle (boards seed
     * 747986550). Waits until it is in Bob's custody.
     */
    private suspend fun awaitIntroAnswerHeld(
        alice: LabNode,
        bob: LabNode,
    ) {
        lab.await(1) {
            alice.metrics
                .keys()
                .introsAnswered
                .toInt()
        }
        lab.await(1) { if (bob.custodyIds().containsAll(alice.custodyIdsFrom(alice))) 1 else 0 }
    }

    /**
     * After the re-link, Carol shows Alice's [post] and holds everything Bob carries. Each wait's own answer is its
     * verdict: a re-read after it races frames custodied since (a tick, a relayed answer).
     */
    private suspend fun awaitRepaired(
        alice: LabNode,
        bob: LabNode,
        carol: LabNode,
        post: String,
    ) {
        val nodes = listOf(alice, bob, carol)
        if (!lab.tryAwait(1) { if (carol.roomPosts()[alice.nodeId]?.contains(post) == true) 1 else 0 }) {
            val keys = carol.metrics.snapshot().keys
            throw AssertionError("carol never showed alice's post: ${carol.roomPosts()}, $keys\n${lab.report(nodes)}")
        }
        if (!lab.tryAwait(1) { if (bob.custodyIds().minus(carol.custodyIds()).isEmpty()) 1 else 0 }) {
            throw AssertionError("carol is short ${bob.custodyIds() - carol.custodyIds()} of what bob carries\n${lab.report(nodes)}")
        }
    }

    /** Each frame's id, relay flag and signed bytes' length: what a held batch let through, for a failure to name. */
    private fun describe(frames: List<WireEnvelope>): String =
        frames.joinToString { w -> "${WireCodec.decodeEnvelope(w.signed)?.id} relay=${w.relay} bytes=${w.signed.size}" }
}
