package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.DropReason
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
            val released = bob.transport.release(carol.transport) { batch -> batch.filter { it.isRoomPostFrom(alice.nodeId) } }
            assertEquals(1, released.size)

            assertTrue("carol never refused the stranger's frame", lab.tryAwait(1) { carol.drops(DropReason.NO_SENDER_KEY).toInt() })
            assertTrue(
                "carol never asked for the key",
                lab.tryAwait(1) {
                    carol.metrics
                        .snapshot()
                        .keyRequestsSent
                        .toInt()
                },
            )
            assertTrue(
                "bob never served it",
                lab.tryAwait(1) {
                    bob.metrics
                        .snapshot()
                        .keysServed
                        .toInt()
                },
            )
            assertTrue("carol never pinned alice", lab.tryAwait(1) { if (carol.knows(alice)) 1 else 0 })

            // Issue #49 (found here on 2026-09-14, fixed by ADR 2026-09.7bu7): the served profile is a
            // point-to-point `relay = false` frame, and the custody gate used to take flood frames only — so
            // Carol delivered it, pinned it and stored nothing, while her router had marked the id seen, which
            // deduped the custodial copy the next digest exchange served for the whole SeenSet window (10 min).
            // She now custodies it on delivery, so the stores agree here rather than eleven minutes from here;
            // the relayed copies the hold ate are still the digest exchange's to repair (the re-link does that).
            bob.transport.lossy(carol.transport) // a clean link again: the re-link's digest exchange repairs what the hold and the air ate
            lab.unlink(bob, carol)
            lab.link(bob, carol)
            assertTrue(lab.tryAwait(1) { if (carol.roomPosts()[alice.nodeId]?.contains("from a stranger") == true) 1 else 0 })
            assertTrue(lab.tryAwait(1) { if (bob.custodyIds().minus(carol.custodyIds()).isEmpty()) 1 else 0 })
            val short = bob.custodyIds() - carol.custodyIds()
            assertTrue("carol is short nothing bob carries, got $short", short.isEmpty())
            val snap = carol.metrics.snapshot()
            assertEquals("one frame parked, one replayed", 1L to 1L, snap.framesHeld to snap.framesReplayed)
            assertEquals("the missing key was recovered", 1L, snap.keysRecovered)
            assertEquals("Alice", carol.peer(alice)?.name)
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
}
