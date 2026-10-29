package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.BlobHasItem
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
 * A fetch asks its neighbours which of them holds the blob before it asks one for it (ADR 2026-10.6e5t): the best
 * holder is asked alone, one that walks away is passed over for the next, a neighbour fetching it is waited on, and a
 * neighbour that cannot answer — the iOS port today — is still asked blind. The file recorder on every transport is
 * the phone's `file …` line: these scenarios count the copies that reach the asker.
 */
@RunWith(RobolectricTestRunner::class)
class BlobHaveLabTest {
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

    private val querying = LabLimits(blobQuery = true)

    private fun LabNode.filesTo(
        to: LabNode,
        hash: String,
    ) = transport.files.count { it == "${to.nodeId} ATTACHMENT $hash" }

    private fun LabNode.asksFor(hash: String) = transport.blobAsks.filter { it.hash == hash }.map { it.to }

    private fun LabNode.queriedFor(hash: String) = transport.blobQueries.filter { hash in it.hashes }.map { it.to }

    /** The states [holder] answered [asker] for [hash], in answer order. */
    private fun answers(
        holder: LabNode,
        asker: LabNode,
        hash: String,
    ) = holder.transport.blobAnswers
        .filter { it.to == asker.nodeId }
        .mapNotNull { it.states[hash] }

    /** Waits until each node has run its link-up blob re-ask toward each of its peers: its digest goes after it. */
    private suspend fun awaitLinkUpHooks(vararg pairs: Pair<LabNode, LabNode>) =
        lab.await(1) { if (pairs.all { (node, peer) -> peer.nodeId in node.transport.digestsSent }) 1 else 0 }

    /**
     * Alice posts a picture and Bob pulls it; then Carol links to both at once. Both say they hold it, and Carol's link
     * to Alice is a slow one (Coded): she asks Bob alone — not the author, whom ADR 2026-10.f7gg's blind round would
     * have asked — and takes one copy.
     */
    @Test
    fun twoHoldersSayTheyHoldItAndTheOneOnTheBetterLinkServesTheOnlyCopy() =
        runBlocking {
            val alice = lab.node("alice", querying).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            awaitLinkUpHooks(alice to bob, bob to alice)

            val picture = Random(6).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "two holders"))
            val id = alice.ownMessageId(Conversations.NEARBY, "two holders")
            val hash = checkNotNull(alice.attachmentHash(Conversations.NEARBY, id))
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            assertEquals("Bob asked Alice, who said she holds it", listOf(alice.nodeId), bob.asksFor(hash))

            carol.transport.slowLinks += alice.nodeId
            lab.linkAll(carol to alice, carol to bob)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            assertTrue(picture.contentEquals(carol.attachmentPlain(Conversations.NEARBY, id)))

            assertEquals("Carol queried both", setOf(alice.nodeId, bob.nodeId), carol.queriedFor(hash).toSet())
            // Both said they hold it. A late link-up batch of Carol's may query again (`linked` forgets a link's answers,
            // the order is not promised), so every answer counts, and each one says the bytes are there.
            listOf(alice, bob).forEach { holder ->
                val said = answers(holder, carol, hash)
                val held = said.all { it == BlobHasItem.HAVE || it == BlobHasItem.SENDING }
                assertTrue("${holder.name} said $said", said.isNotEmpty() && held)
            }
            assertEquals("and she asked Bob alone: ${carol.asksFor(hash)}", listOf(bob.nodeId), carol.asksFor(hash))
            assertEquals("one copy, from Bob", 1, bob.filesTo(carol, hash))
            assertEquals("none from Alice", 0, alice.filesTo(carol, hash))
        }

    /**
     * Dave posts a picture from a build that cannot answer a query (the iOS port today); Bob pulls it from him blind.
     * Carol links to Bob, who says he holds it, and asks him — Bob's copy for her parks on the link. Dave links to her
     * too: he is the author, but a holder that said so beats him, and he is never queried. Then Bob walks away with
     * her copy half-sent: Carol passes him over, and with no other holder that can answer, Dave is asked blind.
     */
    @Test
    fun aHolderThatWalksAwayIsPassedOverAndANeighbourThatCannotAnswerIsAskedBlind() =
        runBlocking {
            val dave = lab.node("dave", LabLimits(answersHolderQueries = false)).apply { setDisplayName("Dave") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.link(dave, bob)
            lab.awaitAcquainted(dave, bob)
            awaitLinkUpHooks(dave to bob, bob to dave)

            val picture = Random(7).nextBytes(4_096)
            assertTrue(dave.sendImage(picture, "walk away"))
            val id = dave.ownMessageId(Conversations.NEARBY, "walk away")
            val hash = checkNotNull(dave.attachmentHash(Conversations.NEARBY, id))
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            assertTrue("Dave cannot answer, so Bob never queried him", bob.queriedFor(hash).isEmpty())
            assertEquals("he asked him blind", listOf(dave.nodeId), bob.asksFor(hash))

            // Nothing of Bob's reaches Carol until her own link-up batch has run: run late, its `linked` would forget the
            // ask she had already made of him, and the fallback below would have no ask to fall back from.
            lab.link(carol, bob) {
                bob.transport.holdFiles(carol.transport)
                bob.transport.hold(carol.transport)
            }
            awaitLinkUpHooks(carol to bob)
            bob.transport.release(carol.transport)
            lab.await(1) { carol.asksFor(hash).size }
            assertEquals("Bob said he holds it, and Carol asked him", listOf(BlobHasItem.HAVE), answers(bob, carol, hash))
            lab.await(1) { bob.transport.heldFiles(carol.transport).count { it == hash } }

            lab.link(carol, dave)
            awaitLinkUpHooks(carol to dave, dave to carol)
            assertEquals("her copy is on its way from Bob: nobody else is asked", listOf(bob.nodeId), carol.asksFor(hash))

            lab.unlink(carol, bob) // Bob walks away, her copy with him
            carol.reoffer()
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            assertTrue("Dave was never queried", carol.queriedFor(hash).none { it == dave.nodeId })
            assertEquals("Bob, then Dave blind: ${carol.asksFor(hash)}", listOf(bob.nodeId, dave.nodeId), carol.asksFor(hash))
            assertEquals("one copy from Dave", 1, dave.filesTo(carol, hash))
            assertTrue(carol.metrics.files().blobFallbacks >= 1)

            lab.link(carol, bob)
            lab.assertConverged(listOf(dave, bob, carol), atLeast = 1) { Conversations.NEARBY }
            assertTrue(picture.contentEquals(carol.attachmentPlain(Conversations.NEARBY, id)))
        }

    /** How many frames of [type] this node has parked for [to]. */
    private fun LabNode.heldOf(
        to: LabNode,
        type: String,
    ) = transport.held(to.transport).count { WireCodec.decodeEnvelope(it.signed)?.type == type }

    /**
     * Work item #140, the 2026-10-06 trace: Alice posts three pictures and Bob pulls them, so both hold them. Carol links
     * to both with her own frames backed up on both links, so both answers miss her wait and she asks the author blind.
     * Her backlog toward Alice clears first: Alice says she holds them, and her copies sit on the link past the grace.
     * Carol confirms her rather than widen. Only then does Bob's late HAVE land, and he is not asked: each picture
     * crosses once, from Alice.
     */
    @Test
    fun aSecondHolderThatAnswersLateIsNotAskedWhileTheFirstOnesCopyIsOnItsWay() =
        runBlocking {
            val alice = lab.node("alice", querying).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            awaitLinkUpHooks(alice to bob, bob to alice)

            val pictures = List(3) { Random(20 + it).nextBytes(4_096) }
            val ids =
                pictures.mapIndexed { i, picture ->
                    assertTrue(alice.sendImage(picture, "late $i"))
                    alice.ownMessageId(Conversations.NEARBY, "late $i")
                }
            val hashes = ids.map { checkNotNull(alice.attachmentHash(Conversations.NEARBY, it)) }
            lab.await(hashes.size) { hashes.count { bob.blobs.exists(it) } }

            // Carol's link-up batch crosses; nothing of Alice's or Bob's reaches her until it has, and from then on
            // nothing more of hers crosses: her own outgoing frames are the backlog.
            lab.link(carol, alice) { alice.transport.hold(carol.transport) }
            lab.link(carol, bob) { bob.transport.hold(carol.transport) }
            awaitLinkUpHooks(carol to alice, carol to bob)
            carol.transport.hold(alice.transport)
            carol.transport.hold(bob.transport)
            // Alice's copies will be fed but not yet seen arriving: the trace's serve, its header not across yet.
            alice.transport.stackFiles(carol.transport)
            alice.transport.release(carol.transport)
            bob.transport.release(carol.transport)
            lab.await(ids.size) { ids.count { carol.attachmentHash(Conversations.NEARBY, it) != null } }
            lab.await(1) { minOf(carol.heldOf(alice, FrameType.BLOB_HAVE), carol.heldOf(bob, FrameType.BLOB_HAVE)) }

            // Both still silent past the wait: the author is asked blind, and those asks queue behind the queries.
            lab.clock.advance(MeshLab.QUERY_WAIT_MS)
            carol.reoffer()
            lab.await(hashes.size) { carol.heldOf(alice, FrameType.BLOB_REQ) }
            assertTrue("Bob is not asked", carol.heldOf(bob, FrameType.BLOB_REQ) == 0)

            // The backlog toward Alice clears: she says she holds them, and serves them into the link unseen.
            carol.transport.release(alice.transport)
            lab.await(hashes.size) { hashes.count { BlobHasItem.HAVE in answers(alice, carol, it) } }
            lab.await(hashes.size) { alice.transport.heldFiles(carol.transport).count { it in hashes } }
            // Handled, steps included: each blob's HAVE from Alice is in hand and its step done, so the grace step runs alone.
            val handled = {
                carol.metrics
                    .files()
                    .let { it.blobAnswersTaken + it.blobAnswersIgnored }
                    .toInt()
            }
            val answerFrames = { holder: LabNode -> holder.transport.blobAnswers.count { it.to == carol.nodeId } }
            lab.await(answerFrames(alice)) { handled() }

            // Past the grace with no header in: Carol confirms Alice, who is sending them, and waits on.
            lab.clock.advance(MeshLab.FIRST_ASK_GRACE_MS)
            carol.reoffer()
            // In that step, not a later cycle's: a blind fetch that widened instead would sit on Alice's floor alone.
            assertTrue("Carol confirms Alice rather than widen", carol.metrics.files().blobConfirms >= hashes.size)

            // Right behind it the backlog toward Bob clears: his answers, late, say he holds all three. The confirm's
            // answer may land on either side of them; the fetch on Alice stands across both.
            carol.transport.release(bob.transport)
            lab.await(hashes.size) { hashes.count { BlobHasItem.SENDING in answers(alice, carol, it) } }
            lab.await(hashes.size) { hashes.count { BlobHasItem.HAVE in answers(bob, carol, it) } }
            // Handled, steps included (`AnswerTaken` counts after them): an ask Bob's answers would buy has gone out.
            lab.await(answerFrames(alice) + answerFrames(bob)) { handled() }
            hashes.forEach { assertEquals("Alice alone is asked for $it", listOf(alice.nodeId), carol.asksFor(it)) }

            alice.transport.releaseFiles(carol.transport)
            lab.await(hashes.size) { hashes.count { carol.blobs.exists(it) } }
            hashes.forEach {
                assertEquals("one copy of $it, from Alice", 1, alice.filesTo(carol, it))
                assertEquals("none from Bob", 0, bob.filesTo(carol, it))
            }
            lab.assertConverged(listOf(alice, bob, carol), atLeast = ids.size) { Conversations.NEARBY }
            ids.forEachIndexed { i, id -> assertTrue(pictures[i].contentEquals(carol.attachmentPlain(Conversations.NEARBY, id))) }
        }

    /**
     * A line: Alice — Bob — Carol. Bob's copy is still on its way from Alice when Carol hears of the picture, so Bob
     * says he is fetching it: Carol waits, asks nobody to pull it through, and queries him again; holding it by then,
     * he serves her the one copy.
     */
    @Test
    fun aNeighbourFetchingItIsWaitedOnThenServesTheOneCopy() =
        runBlocking {
            val alice = lab.node("alice", querying).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            awaitLinkUpHooks(alice to bob, bob to alice)

            alice.transport.holdFiles(bob.transport) // Bob's copy parks on the link, header across
            val picture = Random(8).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "line"))
            val id = alice.ownMessageId(Conversations.NEARBY, "line")
            val hash = checkNotNull(alice.attachmentHash(Conversations.NEARBY, id))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }

            // Carol links only now, and hears of the picture from Bob while his copy is on its way to him — once her own
            // link-up batch has run, so a late one cannot forget the answer she is about to take.
            lab.link(bob, carol) { bob.transport.hold(carol.transport) }
            awaitLinkUpHooks(carol to bob)
            bob.transport.release(carol.transport)
            // Taken, not only arrived: the clock jump below would otherwise find the query unanswered past its wait.
            lab.await(1) {
                carol.metrics
                    .files()
                    .blobAnswersTaken
                    .toInt()
            }
            assertEquals("Bob is fetching it", listOf(BlobHasItem.FETCHING), answers(bob, carol, hash))
            assertTrue("so Carol asks nobody to pull it through", carol.asksFor(hash).isEmpty())

            alice.transport.releaseFiles(bob.transport)
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            lab.clock.advance(MeshLab.FETCHING_RECHECK_MS)
            carol.reoffer() // her recheck: the jump and the tick move it, never the wall clock
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            val blobFrames = { n: LabNode -> n.transport.sent.filter { "blob" in it } }
            val frames = "${blobFrames(carol)} ${blobFrames(bob)}"
            assertEquals(frames, listOf(BlobHasItem.FETCHING, BlobHasItem.HAVE), answers(bob, carol, hash))
            assertEquals(listOf(bob.nodeId), carol.asksFor(hash))
            assertEquals("one copy to Carol, from Bob", 1, bob.filesTo(carol, hash))
            assertEquals("one to Bob, from Alice", 1, alice.filesTo(bob, hash))

            // A room ✓✓ has no two-hop path home (ADR 2026-09.y5f3): Carol's reaches Alice once they meet.
            lab.link(alice, carol)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            assertTrue(picture.contentEquals(carol.attachmentPlain(Conversations.NEARBY, id)))
        }
}
