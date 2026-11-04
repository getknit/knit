package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.AttachmentDeferPolicy
import app.getknit.knit.mesh.spool.FakeSpool
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
 * A DM photo between two contacts only a direct Internet link joins (ADR 2026-10.a5yj's custody-and-blobs amendment): its
 * bytes cross the link once, the recipient's answer over the link is the proof that keeps it off the spool (ADR
 * 2026-10.vy27), and a free radio copy goes before the link's. The device trial of 2026-10-27 (P9 ↔ P7, link only) is the
 * field half: a fresh 692 KB photo crossed in 874 ms, its proof came back on the second query (the first answered NONE
 * before the bytes landed) and nothing went up; a link cut right after the send pushed within 10 s, and the recipient
 * fetched from the spool. Files over the link are read off [LabNode.linkFiles], the radio's off `LabTransport.files`.
 */
@RunWith(RobolectricTestRunner::class)
class DirectLinkAttachmentLabTest {
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

    /** The relay's timed round and attachment re-look cut to a second: a proof round's next pass follows a clock jump. */
    private val rounds = LabLimits().calendarRounds()

    private fun LabNode.dmThreadWith(other: LabNode): (LabNode) -> String = { if (it === this) dmWith(other) else it.dmWith(this) }

    // Passes this process has settled on proof, attachments it pushed, and passes it held one back (per process).
    private fun LabNode.proved(): Int = metrics.spool().spoolAttachProved.toInt()

    private fun LabNode.pushed(): Long = metrics.spool().spoolAttachPushed

    private fun LabNode.deferred(): Int = metrics.spool().spoolAttachDeferred.toInt()

    /** How many copies of [hash] this node's direct-link plane accepted to send [to] (`to kind key` lines). */
    private fun LabNode.linkCopiesTo(
        to: LabNode,
        hash: String,
    ): Int = linkFiles.count { line -> line.split(' ').let { it[0] == to.nodeId && it[2] == hash } }

    /** How many copies of [hash] this node's radio sent [to]. */
    private fun LabNode.radioCopiesTo(
        to: LabNode,
        hash: String,
    ): Int = transport.files.count { line -> line.split(' ').let { it[0] == to.nodeId && it[2] == hash } }

    /** The plane this node's proof that [holder] holds [hash] was learnt over, or null with no such row. */
    private suspend fun LabNode.holderPlane(
        hash: String,
        holder: LabNode,
    ): DeliveryPlane? =
        db
            .attachmentHolderDao()
            .holdersOf(hash)
            .firstOrNull { it.holderId == holder.nodeId }
            ?.let { DeliveryPlane.fromCode(it.plane) }

    /**
     * Returns once [holder] has answered every blob ask [asker] sent it over their current direct link: served, refused or
     * turned into a want. A cut loses what is still on the link, not what has crossed and waits for the holder's router,
     * which takes frames one at a time: an ask waiting there through a cut, a relink and a jump past
     * [BlobExchange.SERVE_MEMO_MS] finds the memo lifted and is served again (chaos seed 499887960, 2026-10-29). Each ask
     * is awaited at the holder's plane, then the holder's `blobAsksHandled` past every ask its plane handed up. For a
     * two-node scenario whose holder has not restarted: that count takes every ask the holder's session handled, from
     * anyone over any plane, while [LabNode.linkAsksIn] outlives a restart. A third node's radio ask would read answered
     * early, and a holder's restart would never catch up. The asker's own radio asks are checked, not assumed.
     */
    private suspend fun awaitAsksAnswered(
        asker: LabNode,
        holder: LabNode,
    ) {
        check(asker.transport.blobAsks.none { it.to == holder.nodeId }) { "${asker.name} asked ${holder.name} over the radio" }
        val up = "${asker.linkUps.get()} ${holder.nodeId} "
        val sent = asker.linkAsksOut.filter { it.startsWith(up) }.map { "${asker.nodeId} ${it.removePrefix(up)}" }
        lab.await(1) { if (holder.linkAsksIn.containsAll(sent)) 1 else 0 }
        lab.await(1) { if (holder.metrics.files().blobAsksHandled >= holder.linkAsksIn.size) 1 else 0 }
    }

    private fun WireEnvelope.isChatFrom(node: LabNode): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.senderId == node.nodeId } == true

    /**
     * Alice and Bob, acquainted and holding DM sessions (on [spool] when given: the DM scope derives), then apart: their
     * radio sighting lapses past [AttachmentDeferPolicy.RADIO_WINDOW_MS], so nothing but the direct link they then make
     * sights either for the other. Returns the two and the link.
     */
    private suspend fun linkOnlyPair(spool: FakeSpool?): Triple<LabNode, LabNode, LabDirectLink> {
        val alice = lab.node("alice", rounds, spool = spool, directLinks = true).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", rounds, spool = spool, directLinks = true).apply { setDisplayName("Bob") }
        if (spool != null) {
            lab.meetOnTheRelay(alice, bob)
        } else {
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.unlink(alice, bob)
        }
        // Unlinked first ([MeshLab.unlink] drains both ends), so no relay is in flight across the jump.
        lab.clock.advance(AttachmentDeferPolicy.RADIO_WINDOW_MS + 60_000L)
        return Triple(alice, bob, lab.directLink(alice, bob))
    }

    /**
     * (a) A DM photo to a recipient only the link reaches: one copy crosses the link, Bob's answer over it is the proof
     * Alice records under [DeliveryPlane.DirectLink] — and Bob, whose bytes came off the link, records his own under it —
     * and nothing goes up. The link is the deferral's sighting through the send's ack grace, and the proof settles it.
     */
    @Test
    fun aDmPhotoToALinkOnlyRecipientCrossesTheLinkOnceAndIsProvedNeverPushed() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = linkOnlyPair(spool)
            val picture = Random(225).nextBytes(32_768)
            assertTrue(alice.sendImage(picture, "over the link", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "over the link")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            assertTrue(
                "bob never held the photo\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            // A query that reached Bob before his store did was answered NONE and floors the next for `PROOF_FLOOR_MS`, as
            // on the device; the jump lets Alice's next deferred pass ask again at once, well inside the ack grace.
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            assertTrue(
                "alice never settled the photo on proof\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.proved() },
            )
            lab.await(1) { if (alice.holderPlane(hash, bob) == DeliveryPlane.DirectLink) 1 else 0 }
            lab.await(1) { if (bob.holderPlane(hash, bob) == DeliveryPlane.DirectLink) 1 else 0 }
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }

            // Past Alice's serve memo, which refuses a repeat ask silently: a wrongful re-ask by Bob, held now, would be
            // served and counted, so the copy count below can fail on what it names. The link is cut first, so no ask
            // sent before Bob held the photo is still in flight when the jump lifts the memo (real time never moves
            // 45 s inside one delivery), and every ask that crossed has been answered under the memo; the new link and the
            // re-offer round then ask afresh.
            awaitAsksAnswered(bob, alice)
            lab.cutDirectLink(alice, bob)
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.directLink(alice, bob)
            bob.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }
            assertEquals("one copy per (hash, link): ${alice.linkFiles}", 1, alice.linkCopiesTo(bob, hash))
            assertEquals("bob served nothing back", 0, bob.linkCopiesTo(alice, hash))
            assertEquals("alice pushed a photo bob holds: ${spool.chunksPut}", 0L, alice.pushed())
            assertEquals("bob pushed a photo the link handed him: ${spool.chunksPut}", 0L, bob.pushed())
            assertTrue("chunks went up: ${spool.chunksPut}", spool.chunksPut.isEmpty())
        }

    /**
     * (b) A radio carrier's copy before the link's (ADR 2026-10.a5yj: `Rank.Internet` in the blind round, `Tier.Internet`
     * among holders, the author included). Carol carried Alice's DM to Bob and pulled its photo while Bob was away; then
     * Alice leaves and Bob comes to Carol, with Alice on a direct link. Carol's copies of the frame never reach him
     * (lossy), so he learns of it over the link with both holders already his neighbours, and fetches from Carol: no file
     * crosses the link.
     */
    @Test
    fun aRadioCarriersCopyGoesBeforeTheLinkedAuthorsInTheBlindRound() = runBlocking { aRadioCarriersCopyGoesBeforeTheLink(LabLimits()) }

    /** (b) with the holder query: both holders say HAVE, and the radio's tier wins. */
    @Test
    fun aRadioCarriersCopyGoesBeforeTheLinkedAuthorsAmongHolders() =
        runBlocking { aRadioCarriersCopyGoesBeforeTheLink(LabLimits(blobQuery = true)) }

    private suspend fun aRadioCarriersCopyGoesBeforeTheLink(limits: LabLimits) {
        val alice = lab.node("alice", limits, directLinks = true).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", limits, directLinks = true).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, bob to carol, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        lab.befriend(alice, bob)
        lab.unlink(alice, bob)
        lab.unlink(bob, carol)

        // No recipient beside Carol to defer to, so the carrier pulls the photo at once.
        val picture = Random(226).nextBytes(16_384)
        assertTrue(alice.sendImage(picture, "carried", to = bob))
        val id = alice.ownMessageId(alice.dmWith(bob), "carried")
        val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
        lab.await(1) { if (carol.holdsBlob(hash)) 1 else 0 }
        lab.unlink(alice, carol)

        // Carol's custody serves of Alice's frames to Bob are lost on the air; everything else between them crosses.
        lab.link(bob, carol) { carol.transport.lossy(bob.transport) { it.isChatFrom(alice) } }
        bob.awaitLinksActedOn()
        lab.directLink(alice, bob)
        assertTrue(
            "bob never held the photo\n${lab.report(listOf(alice, bob, carol))}",
            lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
        )
        assertEquals("a copy crossed the link: ${alice.linkFiles}", 0, alice.linkCopiesTo(bob, hash))
        assertEquals("carol's radio copy: ${carol.transport.files}", 1, carol.radioCopiesTo(bob, hash))

        carol.transport.lossy(bob.transport)
        // Bob's radio island with Carol converges its custody; Alice's side of the link is filtered by the pair's rule.
        lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol), custodyAcross = listOf(bob, carol)) {
            alice.dmThreadWith(bob)(it)
        }
        assertEquals("a copy crossed the link after all: ${alice.linkFiles}", 0, alice.linkCopiesTo(bob, hash))
    }

    /**
     * (c) The #138 shape on a link: Bob's link plane landed the photo, his mesh stopped before the manager stored it, and
     * the restarted session starts with nothing landing — so the want, re-read from the database at the link-up, is asked
     * again and the photo ends held, one round of asks later: the first ask after the restart meets the holder's serve
     * memo for the copy that died with the old link. Once it is held, neither a fresh link-up nor the re-offer round asks
     * for it again.
     */
    @Test
    fun aLinkFileLandingAsTheRecipientsMeshStopsIsAskedAgainAndHeldOnce() =
        runBlocking {
            val (alice, bob) = linkOnlyPair(spool = null)
            lab.befriend(alice, bob)
            bob.linkFileGate.hold()
            val picture = Random(227).nextBytes(16_384)
            assertTrue(alice.sendImage(picture, "landing", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "landing")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.await(1) { bob.linkFileGate.parked }
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == id } }
            // The receipt is sealed after the row: awaited, so the stop cuts nothing the oracle then waits on.
            lab.await(1) { if (bob.nodeId in alice.receiptPlanes(id)) 1 else 0 }
            assertTrue("the photo is not landing on bob's link", checkNotNull(bob.link).arrivingFiles().containsKey(hash))
            assertFalse(bob.holdsBlob(hash))
            assertEquals(1, alice.linkCopiesTo(bob, hash))

            // The stop takes the parked hand-off with its session, and the link with the plane; released while down.
            bob.restart { linkFileGate.release() }
            lab.cutDirectLink(alice, bob)
            lab.directLink(alice, bob)
            // The link-up's ask meets Alice's serve memo for the copy that died with the old link (`SERVE_MEMO_MS`), and is
            // refused; the next round is `ROUND_MS` on, the memo's length by design, and the 60 s tick opens it. The jump
            // and the re-offer round are that tick.
            lab.clock.advance(BlobExchange.ROUND_MS)
            bob.reoffer()
            assertTrue(
                "bob never held the photo after his restart\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals("asked again once: ${alice.linkFiles}", 2, alice.linkCopiesTo(bob, hash))

            // Held now: a link-up's re-ask and the re-offer round find nothing missing. Past Alice's serve memo, so a
            // wrongful re-ask would be served and counted rather than refused silently — and the jump only once the old
            // link is cut and every ask that crossed answered, so an ask sent before Bob held the photo cannot land after it
            // (chaos seeds did, 2026-10-28 and 10-29).
            awaitAsksAnswered(bob, alice)
            lab.cutDirectLink(alice, bob)
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.directLink(alice, bob)
            bob.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { alice.dmThreadWith(bob)(it) }
            assertEquals("a copy crossed after the photo was held: ${alice.linkFiles}", 2, alice.linkCopiesTo(bob, hash))
        }

    /**
     * (d) A forwarded identity (ADR 2026-10.mptm): Alice posts a picture into her group with Carol, whom Bob is not with,
     * then sends the same bytes to Bob, the group's sealed copy under its own hash. The link's rule reads every
     * conversation naming the hash, so it carries the DM's: the photo crosses, is proved over the link, and nothing goes
     * up in the DM's scope (the group's is the group's affair).
     */
    @Test
    fun aPictureForwardedFromAGroupThePeerIsNotInCrossesTheLinkAndIsProvedNeverPushed() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", rounds, spool = spool, directLinks = true).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", rounds, spool = spool, directLinks = true).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", rounds).apply { setDisplayName("Carol") }
            lab.meetOnTheRelay(alice, bob)
            lab.link(alice, carol)
            lab.awaitAcquainted(alice, carol)
            lab.befriend(alice, bob, carol)
            val group = alice.createGroup(carol)
            val picture = Random(228).nextBytes(16_384)
            assertTrue(alice.sendImage(picture, "for the group", groupId = group))
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { group }
            val groupHash = checkNotNull(alice.attachmentHash(group, alice.ownMessageId(group, "for the group")))
            lab.unlink(alice, carol)
            lab.clock.advance(AttachmentDeferPolicy.RADIO_WINDOW_MS + 60_000L)
            lab.directLink(alice, bob)

            lab.await(1) { if (alice.holdsIdentityFor(picture)) 1 else 0 }
            assertTrue(alice.forwardImage(picture, "from the group", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "from the group")
            assertEquals("the DM names the group's copy", groupHash, alice.attachmentHash(alice.dmWith(bob), id))
            assertTrue(
                "bob never held the forwarded photo\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(groupHash)) 1 else 0 },
            )
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            assertTrue(
                "alice never settled the forward on proof\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.proved() },
            )
            lab.await(1) { if (alice.holderPlane(groupHash, bob) == DeliveryPlane.DirectLink) 1 else 0 }
            // Alice's custody holds the group's frames, which never cross the link: her radio island with Carol converges
            // custody, as `DirectLinkLabTest`'s does. Carol already holds the bytes under the group's name, so she pulls none.
            lab.link(alice, carol)
            lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = listOf(carol), custodyAcross = listOf(alice, carol)) {
                alice.dmThreadWith(bob)(it)
            }

            assertEquals("one copy per (hash, link): ${alice.linkFiles}", 1, alice.linkCopiesTo(bob, groupHash))
            val dmScope = checkNotNull(alice.dmScopeStatus(bob)) { "alice holds no DM scope with bob" }.scopeHex
            assertTrue("the forward went up in the DM's scope", spool.attachmentIdsIn(dmScope).isEmpty())
        }

    /**
     * (e) The link cut before the proof came back. Bob's side of the link goes quiet as the photo goes out (his asks,
     * answers and acks are parked, then lost with the cut), so the bytes cannot cross and no answer can settle them;
     * Alice's pass holds on the link's sighting through the ack grace. Cut, the sighting is gone with the link, and her
     * first re-look after it pushes — once — and Bob fetches from the spool.
     */
    @Test
    fun aLinkCutBeforeTheProofCameBackPushesOnceAndTheRecipientFetchesFromTheSpool() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob, link) = linkOnlyPair(spool)
            link.hold(from = bob)
            val picture = Random(229).nextBytes(16_384)
            assertTrue(alice.sendImage(picture, "cut", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "cut")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.first == id } }
            assertTrue(
                "alice never held the photo for the link\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.deferred() },
            )
            assertEquals("pushed while the link was up: ${spool.chunksPut}", 0L, alice.pushed())
            // Bob sends from his own coroutines once he has decrypted: Alice's deferral, made at her send, can come first.
            lab.await(1) { link.heldFrom(bob) }

            lab.cutDirectLink(alice, bob)
            val cutAt = System.nanoTime()
            assertTrue(
                "alice never pushed after the cut\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.pushed().toInt() },
            )
            val tookMs = (System.nanoTime() - cutAt) / 1_000_000
            assertTrue("alice pushed $tookMs ms after the cut: the ack grace's push, not the cut's", tookMs <= PUSH_AFTER_CUT_MS)
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
            assertEquals("pushed more than once: ${spool.chunksPut}", 1L, alice.pushed())
            assertEquals("bob re-pushed what he fetched: ${spool.chunksPut}", 0L, bob.pushed())
            assertEquals("bytes crossed a quiet link: ${alice.linkFiles}", 0, alice.linkCopiesTo(bob, hash))
            assertTrue("bob's copy is no link proof", bob.holderPlane(hash, bob) != DeliveryPlane.DirectLink)
        }

    private companion object {
        // Tells the cut's push (the next 1 s re-look, `calendarRounds`) from one that waited out the ack grace, about 55 s
        // later on the lab clock — half the grace, never the device's 10 s, which a loaded one-core runner could pass.
        const val PUSH_AFTER_CUT_MS = AttachmentDeferPolicy.ACK_GRACE_MS / 2
    }
}
