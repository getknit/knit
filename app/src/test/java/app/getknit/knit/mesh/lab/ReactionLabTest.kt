package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
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

/**
 * Reactions between real stacks: the sealed DM form and the sealed group form (ADR 018's `CTL_REACTION`,
 * a ctl frame inside the ratchets), the cleartext room form, and the last-writer-wins rule that lets a
 * retraction and the reaction it retracts arrive in either order. Every case ends in the oracle, whose
 * reaction check reads the same (reactor, emoji) set off every node.
 */
@RunWith(RobolectricTestRunner::class)
class ReactionLabTest {
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
    fun aReactionAndItsRetractionConvergeInADmAGroupAndTheRoom() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            // DM: the sealed ctl form over the pairwise session.
            assertTrue(alice.sendDm(bob, "dm"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "dm")
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            bob.react(bob.dmWith(alice), dm, "👍")
            assertTrue(lab.tryAwait(1) { alice.reactions(dm).size })
            assertEquals(setOf(bob.nodeId to "👍"), alice.reactions(dm))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            bob.react(bob.dmWith(alice), dm, "👍")
            assertTrue(lab.tryAwait(1) { if (alice.reactions(dm).isEmpty()) 1 else 0 })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            // Group: the sealed group form under the sender-key chain.
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "group"))
            val post = alice.ownMessageId(groupId, "group")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            carol.react(groupId, post, "❤️")
            assertTrue(lab.tryAwait(1) { bob.reactions(post).size })
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertEquals(setOf(carol.nodeId to "❤️"), alice.reactions(post))
            carol.react(groupId, post, "❤️")
            assertTrue(lab.tryAwait(1) { if (alice.reactions(post).isEmpty()) 1 else 0 })
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            // Room: cleartext, flooded, custodied like the post itself.
            assertTrue(bob.sendRoom("room"))
            val room = bob.ownMessageId(Conversations.NEARBY, "room")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            alice.react(Conversations.NEARBY, room, "😂")
            assertTrue(lab.tryAwait(1) { carol.reactions(room).size })
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            assertEquals(setOf(alice.nodeId to "😂"), bob.reactions(room))
            alice.react(Conversations.NEARBY, room, "😂")
            assertTrue(lab.tryAwait(1) { if (bob.reactions(room).isEmpty()) 1 else 0 })
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * Bob reacts and takes it back while the link holds; Alice hears the retraction first and the reaction
     * after. The retraction's clock is the later one, so it wins on Alice exactly as it did on Bob — the
     * ratchet's skipped-key path opens the older frame second.
     */
    @Test
    fun aRetractionThatCrossesItsReactionWinsByClock() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "dm"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "dm")
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            bob.transport.hold(alice.transport)
            bob.react(bob.dmWith(alice), dm, "👍")
            bob.react(bob.dmWith(alice), dm, "👍")
            val released = bob.transport.release(alice.transport) { it.reversed() }
            assertTrue("two frames were held, got ${released.size}", released.size >= 2)

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("the retraction stood", emptySet<Pair<String, String>>(), alice.reactions(dm))
        }

    /**
     * A reaction that lands before the message it is on — a DM's ctl frame overtaking the chat frame on a
     * re-serve — must wait for its message rather than be dropped or orphaned. When the message lands the
     * reaction shows, on both sides alike.
     */
    @Test
    fun aReactionThatArrivesBeforeItsDmIsKeptForIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "first"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            alice.transport.hold(bob.transport)
            assertTrue(alice.sendDm(bob, "reacted to"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "reacted to")
            alice.react(alice.dmWith(bob), dm, "🎉")
            val released = alice.transport.release(bob.transport) { it.reversed() }
            assertTrue("two frames were held, got ${released.size}", released.size >= 2)

            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals(setOf(alice.nodeId to "🎉"), bob.reactions(dm))
        }

    /**
     * The group form of the same overtaking: a reaction carries the roster like any group frame, so on a
     * member who has not seen the group yet it is the frame that founds it, with the seed parked and the
     * message following. Every member ends up with the group, the message and the reaction.
     */
    @Test
    fun aGroupReactionThatArrivesFirstFoundsTheGroupAndTheMessageFollows() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendGroup(groupId, "founding post"))
            val post = alice.ownMessageId(groupId, "founding post")
            alice.react(groupId, post, "🎉")
            // Group frames in reverse (the reaction before the post), then every DM (the seed among them).
            val released =
                alice.transport.release(bob.transport) { batch ->
                    batch.filter { it.isGroupFrame() }.reversed() +
                        batch.filterNot { it.isGroupFrame() }
                }
            assertTrue("held ${released.size} frames", released.count { it.isGroupFrame() } == 2)

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
            assertEquals(setOf(alice.nodeId to "🎉"), bob.reactions(post))
        }

    /**
     * A group reaction in the cleartext form: Alice's pin of Carol lacks the ratchet bit, so the group form is off for her
     * and her reactions flood in the clear, naming only a post's id and carrying no roster. A group's roster is its
     * founding set, fixed by its id, so every member already lists her whether her reaction lands after its post or
     * before it. It shows on every member either way, and nobody refuses it.
     */
    @Test
    fun aCleartextGroupReactionFromAMemberShowsWhetherItLandsAfterItsPostOrBefore() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founding"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            assertTrue(bob.sendGroup(groupId, "after"))
            val after = bob.ownMessageId(groupId, "after")
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }

            alice.answerWithCleartextReceipts(carol)
            val fallbacks = alice.metrics.seals().reactionsSealedFallback
            alice.react(groupId, after, "👍")
            assertTrue("it went in the clear", alice.metrics.seals().reactionsSealedFallback > fallbacks)
            assertTrue(lab.tryAwait(2) { listOf(bob, carol).count { (alice.nodeId to "👍") in it.shownReactions(groupId, after) } })

            // Before its post: Bob hears Alice's reaction ahead of the post it names, from Alice and from nobody else.
            alice.transport.hold(bob.transport)
            carol.transport.hold(bob.transport)
            assertTrue(alice.sendGroup(groupId, "before"))
            val before = alice.ownMessageId(groupId, "before")
            alice.react(groupId, before, "🎉")
            val released =
                alice.transport.release(bob.transport) { batch ->
                    batch.filter { it.isReaction() } +
                        batch.filterNot { it.isReaction() }
                }
            assertTrue(
                "the reaction went ahead of the post: ${released.size}",
                released.first().isReaction() && released.any { it.isGroupFrame() },
            )
            carol.transport.release(bob.transport)

            alice.answerWithCleartextReceipts(carol, cleartext = false)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 3) { groupId }
            listOf(alice, bob, carol).forEach { node ->
                assertEquals(node.name, setOf(alice.nodeId to "👍"), node.shownReactions(groupId, after))
                assertEquals(node.name, setOf(alice.nodeId to "🎉"), node.shownReactions(groupId, before))
                assertEquals(node.name, 0L, node.drops(DropReason.REACTION_OUTSIDE_THREAD))
            }
        }

    private fun WireEnvelope.isGroupFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.group != null

    private fun WireEnvelope.isReaction(): Boolean = WireCodec.decodeEnvelope(signed)?.type == FrameType.REACTION
}
