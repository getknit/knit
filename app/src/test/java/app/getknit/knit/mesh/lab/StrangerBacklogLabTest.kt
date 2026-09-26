package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A carrier hands a newcomer the backlog of someone the newcomer has never met (ADR 2026-09.9xuu). The
 * newcomer can verify none of it until the author's key arrives, and its router marks every frame seen on the
 * way in, so a frame the park turns away is deduped for the whole ten-minute seen window — on Bluetooth the
 * carrier does not even re-write it (`LinkCrossings`). With a park of sixteen per sender that left most of a
 * backlog undelivered, and out of the newcomer's custody, for ten minutes (found by the iOS port on a Moto G: 41
 * frames outstanding, 16 parked). Two things close it, and each scenario pins one: the carrier serves profiles
 * ahead of everything else, and a newcomer that meets the backlog keyless anyway parks all of it to replay.
 *
 * Nothing here moves the lab calendar: the old behaviour converged only once the seen window had lapsed.
 */
@RunWith(RobolectricTestRunner::class)
class StrangerBacklogLabTest {
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
     * Bob carries Alice's profile along with her posts. His reply to Carol's digest puts the profile first, so
     * Carol pins Alice before the first post and refuses nothing.
     */
    @Test
    fun aCarrierServesTheAuthorsProfileAheadOfTheirBacklog() =
        runBlocking {
            val (alice, bob, carol) = backlog()

            linkForTheBacklogOnly(bob, carol)
            lab.assertConverged(listOf(bob, carol), atLeast = POSTS) { Conversations.NEARBY }
            assertEquals(POSTS, carol.roomPosts()[alice.nodeId]?.size)
            // What the serve order governs is the backlog: not one of the posts may be refused.
            val posts = alicesPosts(bob, alice)
            val refused = refusedForWantOfKey()
            assertTrue("carol refused alice's posts ${refused intersect posts} for want of her key", (refused intersect posts).isEmpty())
        }

    /**
     * Bob no longer carries Alice's profile — his quota evicted it — so Carol meets the backlog keyless, and
     * parks every post. The air loses Bob's answers to her key request, which would otherwise land partway
     * through his reply (a fine outcome, and not the one this pins); the key comes when Alice herself walks up
     * to Carol, and on the pin Carol replays the whole backlog. With a park of sixteen the rest stayed out for
     * the seen window, Alice's own re-serve deduped with them.
     */
    @Test
    fun aBacklogServedAheadOfItsKeyIsParkedWholeAndReplayed() =
        runBlocking {
            val (alice, bob, carol) = backlog()
            bob.forgetCustody { it.startsWith("profile-${alice.nodeId}-") }

            bob.transport.connect(carol.transport, lossy = { it.hops > 0 || it.isServedProfile() })
            val posts = alicesPosts(bob, alice)
            assertTrue(
                "carol refused ${(refusedForWantOfKey() intersect posts).size} of alice's $POSTS posts\n${lab.report(listOf(bob, carol))}",
                lab.tryAwait(POSTS) { (refusedForWantOfKey() intersect posts).size },
            )
            assertTrue("carol parked the backlog", carol.metrics.snapshot().framesHeld >= POSTS)
            assertTrue(
                "bob never answered carol's key request",
                lab.tryAwait(1) { bob.transport.lost.count { it.isServedProfile() } },
            )

            lab.link(alice, carol)
            assertTrue(
                "carol holds ${carol.roomPosts()[alice.nodeId]?.size} of alice's $POSTS posts\n${lab.report(listOf(alice, bob, carol))}",
                lab.tryAwait(POSTS) { carol.roomPosts()[alice.nodeId]?.size ?: 0 },
            )
            assertTrue(
                "carol is short ${bob.custodyIds() - carol.custodyIds()} of bob's custody",
                lab.tryAwait(1) { if ((bob.custodyIds() - carol.custodyIds()).isEmpty()) 1 else 0 },
            )
            val snap = carol.metrics.snapshot()
            assertEquals("every parked frame replayed", snap.framesHeld, snap.framesReplayed)
        }

    /**
     * Links Bob to Carol with the air losing Bob's *relays* toward her — a copy he forwards carries `hops > 0`,
     * while everything he serves from custody or originates is stamped fresh at 0. A frame of Alice's still in
     * Bob's relay jitter when the link comes up (her sealed answer to his first tick, say, or a post under a
     * stalled worker) would otherwise reach Carol live, ahead of any digest exchange and whatever order it
     * serves in, and she would park it for want of the key. What is lost here the custody reply carries.
     */
    private fun linkForTheBacklogOnly(
        bob: LabNode,
        carol: LabNode,
    ) = bob.transport.connect(carol.transport, lossy = { it.hops > 0 })

    /** Alice posts [POSTS] times to Bob and leaves; Carol has never met her. */
    private suspend fun backlog(): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        val carol = lab.node("carol").apply { setDisplayName("Carol") }
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        repeat(POSTS) { assertTrue(alice.sendRoom("post $it")) }
        lab.await(POSTS) { bob.roomPosts()[alice.nodeId]?.size ?: 0 }
        lab.awaitCustodyParity(alice, bob)
        lab.unlink(alice, bob)
        // Parity can read true just before Alice answers Bob's first tick (`IntroSync`); whatever she sent before
        // the pipe went is in Bob's custody before Carol's digest reaches him, and nothing after ever reaches him.
        bob.transport.awaitInboundDrained()
        return Triple(alice, bob, carol)
    }

    /** The ids of Alice's room posts, as Bob carries them. */
    private suspend fun alicesPosts(
        bob: LabNode,
        alice: LabNode,
    ): Set<String> {
        val posts =
            bob
                .custodyFrames()
                .filter { it.contains(":chat:${alice.nodeId.take(6)}→null@") }
                .mapTo(HashSet()) { it.substringBefore(':') }
        assertEquals(POSTS, posts.size)
        return posts
    }

    /** Every chat frame id some node refused for want of its sender's key, this run. */
    private fun refusedForWantOfKey(): Set<String> =
        ShadowLog.getLogs().mapNotNullTo(HashSet()) { NO_KEY_DROP.find(it.msg)?.groupValues?.get(1) }

    /** Bob's answer to a key request: Alice's profile, served point to point. */
    private fun WireEnvelope.isServedProfile(): Boolean = !relay && WireCodec.decodeEnvelope(signed)?.type == FrameType.PROFILE

    private companion object {
        /** The backlog of the field report: well past the sixteen the park used to hold per sender. */
        const val POSTS = 40

        /** `InboundPipeline.verifyInbound`'s warning for a frame refused for want of its sender's key. */
        val NO_KEY_DROP = Regex("drop chat (\\S+) from \\S+: no key to verify it")
    }
}
