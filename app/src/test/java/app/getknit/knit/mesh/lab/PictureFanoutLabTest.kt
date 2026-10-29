package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.CarrierPullPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * A picture goes to its linked recipient first, one Bluetooth copy at a time (ADR 2026-10.g24x). On 2026-10-06 the
 * Pixel 9 fed a 394 KB DM photo to four phones at once — the recipient and three carriers — and split its Bluetooth
 * budget four ways, so the recipient waited about 6.5 min. The file recorder on every transport is the phone's
 * `file …` line: these scenarios count the copies each holder fed.
 */
@RunWith(RobolectricTestRunner::class)
class PictureFanoutLabTest {
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

    private fun LabNode.filesTo(
        to: LabNode,
        hash: String,
    ) = transport.files.count { it == "${to.nodeId} ATTACHMENT $hash" }

    private fun LabNode.asksFor(hash: String) = transport.blobAsks.filter { it.hash == hash }

    /** Waits until each node has run its link-up blob re-ask toward each of its peers: its digest goes after it. */
    private suspend fun awaitLinkUpHooks(vararg pairs: Pair<LabNode, LabNode>) =
        lab.await(1) { if (pairs.all { (node, peer) -> peer.nodeId in node.transport.digestsSent }) 1 else 0 }

    /**
     * Three phones linked to each other. Alice sends Bob a picture; Carol carries the DM but leaves its bytes to Bob,
     * who is linked to her and fetching them. Only once the hold's window has passed does her tick take her own copy,
     * for a late joiner — by then from either holder.
     */
    @Test
    fun aCarrierLinkedToADmsRecipientLeavesItsPictureToItThenTakesItsCopyLater() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            awaitLinkUpHooks(alice to bob, alice to carol, bob to alice, bob to carol, carol to alice, carol to bob)

            val deferred = carol.metrics.files().carrierPullsDeferred
            val picture = Random(141).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for bob", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "for bob")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            // Custody holds the frame before its pull is decided: the decision itself is what is awaited.
            lab.await(1) { if (carol.metrics.files().carrierPullsDeferred > deferred) 1 else 0 }

            carol.reoffer() // her tick re-reads what custody lacks: still held back while Bob is linked
            assertFalse("Carol pulled the picture of a DM whose recipient she is linked to", carol.blobs.exists(hash))
            assertTrue("and asked nobody for it: ${carol.asksFor(hash)}", carol.asksFor(hash).isEmpty())
            assertEquals("Alice fed one copy, Bob's", 0, alice.filesTo(carol, hash))

            lab.clock.advance(CarrierPullPolicy.DEFER_MS)
            carol.reoffer()
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            assertEquals("one copy for Carol, from either holder", 1, alice.filesTo(carol, hash) + bob.filesTo(carol, hash))

            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), id)))
            assertEquals("Alice fed Bob once", 1, alice.filesTo(bob, hash))
        }

    /**
     * Alice sends her group a picture, and Bob and Carol, linked to her alone, both ask her for it. Bob's ask lands
     * first and his copy is still being fed (its bytes parked on the link) when Carol's arrives: Alice parks Carol's
     * ask rather than feed a second copy beside it, and serves her once Bob's feed has ended.
     */
    @Test
    fun aSecondAskForAPictureBeingFedWaitsForThatFeedThenIsServedOnce() =
        runBlocking {
            // Alice reads Bob's held feed as live however long the scenario holds it: its stall is not what this one is about.
            val alice = lab.node("alice", limits = LabLimits(feedStallMs = PARKED_LONG_MS)).apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            awaitLinkUpHooks(alice to bob, alice to carol, bob to alice, carol to alice)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "hello both"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            alice.transport.holdFiles(bob.transport) // Bob's copy: the header across, the bytes still being fed
            alice.transport.hold(carol.transport) // Carol hears of the picture once Bob's copy is on the link
            val picture = Random(142).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "one at a time", groupId = groupId))
            val id = alice.ownMessageId(groupId, "one at a time")
            val hash = checkNotNull(alice.attachmentHash(groupId, id))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }

            val parked = alice.metrics.files().blobAsksParked
            alice.transport.release(carol.transport)
            lab.await(1) { if (alice.metrics.files().blobAsksParked > parked) 1 else 0 }
            assertEquals("Carol's ask waits behind Bob's feed", 0, alice.filesTo(carol, hash))

            alice.transport.releaseFiles(bob.transport) // Bob's feed ends: Carol is next
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }
            assertTrue(picture.contentEquals(carol.attachmentPlain(groupId, id)))
            assertEquals("Alice fed Bob once", 1, alice.filesTo(bob, hash))
            assertEquals("and Carol once, after him", 1, alice.filesTo(carol, hash))
        }

    /**
     * A feed to a stalled link must not hold everyone else (ADR 2026-10.nsr7): Bob's copy never ends here (his pipe holds
     * its bytes, and nothing reports its progress), and Carol's parked ask is served beside it once it stalls.
     */
    @Test
    fun aParkedAskIsServedBesideAFeedThatNeverEnds() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            awaitLinkUpHooks(alice to bob, alice to carol, bob to alice, carol to alice)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "hello both"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            alice.transport.holdFiles(bob.transport)
            alice.transport.hold(carol.transport)
            val picture = Random(143).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "beside a stalled feed", groupId = groupId))
            val id = alice.ownMessageId(groupId, "beside a stalled feed")
            val hash = checkNotNull(alice.attachmentHash(groupId, id))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }
            val parked = alice.metrics.files().blobAsksParked
            alice.transport.release(carol.transport)
            lab.await(1) { if (alice.metrics.files().blobAsksParked > parked) 1 else 0 } // parked behind Bob's feed

            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 } // once Bob's feed stalls, with it still held
            assertEquals("Carol was fed once", 1, alice.filesTo(carol, hash))
            alice.transport.releaseFiles(bob.transport)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }
            assertEquals("Bob was fed once", 1, alice.filesTo(bob, hash))
        }

    private companion object {
        // Longer than any await in a scenario: a held feed that never stalls on its own.
        const val PARKED_LONG_MS = 10 * 60_000L
    }
}
