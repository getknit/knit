package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.inet.LinkDemandPolicy
import app.getknit.knit.mesh.inet.LinkOutcome
import app.getknit.knit.mesh.inet.LinkSession
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolCastInfo
import app.getknit.knit.mesh.spool.SpoolSigInfo
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTickets
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.first
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
import org.robolectric.shadows.ShadowLog
import kotlin.random.Random

/**
 * A contact's direct Internet link made the way a phone makes it (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md`): each node's
 * real `DirectLinks` casts its offer and answer through the relay, punches over the lab's fake NATs ([LabInternet]),
 * seals its lane, and hands the stream to the node's link plane; `LinkDemand` offers on its own. Where
 * `DirectLinkLabTest` joins two link planes by in-memory streams, these run everything that comes before them.
 */
@RunWith(RobolectricTestRunner::class)
class InternetLinkLabTest {
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

    // A relay that fans out casts and advertises the lab's STUN server, as `lab.lax` does.
    private fun relay() = FakeSpool(cast = SpoolCastInfo(maxBytes = 2_048, rate = 4), stun = listOf(LabInternet.STUN_URL))

    // Alice and Bob on the relay and the lab's Internet behind NATs of [nat], contacts with a confirmed session each way and
    // a DM scope converged at the relay ([MeshLab.meetOnTheRelay]): what a link's offer needs, its cast door open.
    private suspend fun contacts(
        spool: FakeSpool,
        nat: Nat,
    ): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", spool = spool, internet = nat).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool, internet = nat).apply { setDisplayName("Bob") }
        lab.meetOnTheRelay(alice, bob)
        return alice to bob
    }

    // When this node last met [peer] by radio, as Your mesh reads it; null for never.
    private suspend fun LabNode.lastMet(peer: LabNode): Long? =
        db
            .metPeerDao()
            .observe(peer.nodeId)
            .first()
            ?.lastMetAt

    // Whether this node's mesh counts [peer] nearby: `MeshController.neighbors`, the short-range set.
    private fun LabNode.seesNearby(peer: LabNode): Boolean = manager.neighbors.value.any { it.nodeId == peer.nodeId }

    // Both ends' link planes hold the link, and both managers have acted on it: what a send next may rely on.
    private suspend fun awaitLinked(
        a: LabNode,
        b: LabNode,
    ) {
        assertTrue(
            "the link never reached both planes\n${a.linkLog}\n${b.linkLog}",
            lab.tryAwait(1) { if (a.linkPlaneHolds(b) && b.linkPlaneHolds(a)) 1 else 0 },
        )
        a.awaitLinksActedOn()
        b.awaitLinksActedOn()
    }

    /**
     * Alice and Bob met once, then parted to two islands: both behind port-restricted NATs, both on one relay that carries
     * casts. Alice's offer comes up over the punch, and once it is up the relay goes silent (it still fans out casts, and
     * takes nothing else), so the DM and the group post that follow converge over the link alone, ticks and all.
     */
    @Test
    fun anOfferPunchedThroughTwoNatsCarriesTheDmAndTheGroupAcrossIt() =
        runBlocking {
            val spool = relay()
            val alice = lab.node("alice", spool = spool, internet = Nat.PORT_RESTRICTED).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool, internet = Nat.PORT_RESTRICTED).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val group = alice.createGroup(bob)
            assertTrue(alice.sendGroup(group, "welcome"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { group }
            lab.unlink(alice, bob)
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)

            assertEquals(LinkOutcome.UP, checkNotNull(alice.links).offer(bob.nodeId))
            awaitLinked(alice, bob)
            spool.mute()

            assertTrue(alice.sendDm(bob, "over the link"))
            assertTrue(bob.sendGroup(group, "the group over the link"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { group }
            val dm = alice.ownMessageId(alice.dmWith(bob), "over the link")
            val post = bob.ownMessageId(group, "the group over the link")
            assertEquals(DeliveryPlane.DirectLink, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals(DeliveryPlane.DirectLink, alice.receivedVia(group, post))
            assertEquals(DeliveryPlane.DirectLink, alice.receiptPlanes(dm)[bob.nodeId])
        }

    /**
     * Both behind symmetric NATs — a fresh mapping per destination, so neither end's reflexive candidate is the port the
     * other must aim at: the punch gives up and the attempt ends NO_PATH; the contact then waits out its backoff, so a second
     * offer casts nothing; and the relay, which never stopped, still carries the DM.
     */
    @Test
    fun twoSymmetricNatsFindNoPathBackOffAndTheRelayStillCarries() =
        runBlocking {
            val spool = relay()
            val (alice, bob) = contacts(spool, Nat.SYMMETRIC)

            assertEquals(LinkOutcome.NO_PATH, checkNotNull(alice.links).offer(bob.nodeId))
            assertEquals(LinkOutcome.NO_PATH, checkNotNull(alice.links).lastTries()[bob.nodeId]?.outcome)
            assertFalse("a link came up behind two symmetric NATs", alice.linkPlaneHolds(bob))
            val casts = spool.casts.size
            assertEquals(LinkOutcome.BACKOFF, checkNotNull(alice.links).offer(bob.nodeId))
            assertEquals("an offer in its backoff cast", casts, spool.casts.size)

            assertTrue(alice.sendDm(bob, "by the relay"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
            val dm = alice.ownMessageId(alice.dmWith(bob), "by the relay")
            assertEquals(DeliveryPlane.Internet, bob.receivedVia(bob.dmWith(alice), dm))
        }

    /**
     * A link is not a radio. Alice and Carol share a radio island and a group with Bob; Bob is on his own, reached over a
     * link. Carol's post goes to Bob through Alice over the link, and Bob's frames back to Carol (his tick, his seed's
     * acknowledgement — how many depends on timing) through Alice over the radio. Bob is never Alice's neighbour nearby,
     * the link neither makes him heard indirectly nor meets him again, and Alice's Your mesh credit is the radio's alone:
     * every frame she hands Carol is addressed to Carol, so it counts once passed along and once delivered, while Carol's
     * post — a group frame, no recipient — would count passed along only, had the link earned it.
     */
    @Test
    fun aLinkPeerIsNeverNearbyNeverMetAndEarnsNoCredit() =
        runBlocking {
            val spool = relay()
            val alice = lab.node("alice", spool = spool, internet = Nat.PORT_RESTRICTED).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool, internet = Nat.PORT_RESTRICTED).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            val group = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(group, "welcome"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { group }
            lab.unlink(alice, bob)
            lab.unlink(bob, carol)
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            val metBefore = alice.lastMet(bob)
            val heardBefore = alice.manager.heardIndirectly.value[bob.nodeId]
            val creditBefore = alice.contributions()

            assertEquals(LinkOutcome.UP, checkNotNull(alice.links).offer(bob.nodeId))
            awaitLinked(alice, bob)
            spool.mute()
            assertTrue(carol.sendGroup(group, "from carol's island"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2, custodyAcross = listOf(alice, carol)) { group }
            val post = carol.ownMessageId(group, "from carol's island")
            assertEquals(DeliveryPlane.DirectLink, bob.receivedVia(group, post))

            assertFalse("bob reads as nearby to alice", alice.seesNearby(bob))
            assertFalse("alice reads as nearby to bob", bob.seesNearby(alice))
            // Gone with its linger or as it was: never stamped anew by what the link carried.
            val heardAfter = alice.manager.heardIndirectly.value[bob.nodeId]
            assertTrue("the link made bob heard indirectly: $heardBefore → $heardAfter", heardAfter == null || heardAfter == heardBefore)
            assertEquals("the link met bob again", metBefore, alice.lastMet(bob))
            // The credit is booked after the radio's send returns, so Carol's copy of Bob's tick can land first: await the
            // booking itself (as ContributionLabTest does). A wrongful link credit for Carol's post would land at the link's
            // send, before Bob's tick existed, so once this returns the equality below is safe.
            assertTrue(
                "the radio's hand-offs of bob's frames to carol earned nothing",
                lab.tryAwait(1) { if (alice.contributions().deliveredToRecipient > creditBefore.deliveredToRecipient) 1 else 0 },
            )
            val credit = alice.contributions()
            val passed = credit.passedAlong - creditBefore.passedAlong
            val delivered = credit.deliveredToRecipient - creditBefore.deliveredToRecipient
            assertEquals("a frame was credited that was not handed to its recipient: the link's of carol's post", delivered, passed)
        }

    /**
     * The demand opens a link, the link idles out, and the demand opens it again. The relay goes silent but for its casts:
     * Alice's DM goes unticked, so once it has waited the tick wait her demand offers Bob a link, over which the DM and its
     * tick cross. With nothing more to carry the link closes idle; a second DM, written after it went, reopens it.
     */
    @Test
    fun theDemandOpensALinkThatIdlesOutAndReopensForTheNextMessage() =
        runBlocking {
            val spool = relay()
            val (alice, bob) = contacts(spool, Nat.PORT_RESTRICTED)
            val demand = checkNotNull(alice.demand)
            spool.mute()

            assertTrue(alice.sendDm(bob, "first"))
            lab.clock.advance(LinkDemandPolicy.TICK_WAIT_MS)
            demand.round()
            awaitLinked(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            val first = alice.ownMessageId(alice.dmWith(bob), "first")
            assertEquals(DeliveryPlane.DirectLink, bob.receivedVia(bob.dmWith(alice), first))

            // Nothing left to carry: past the idle close on the link's clock, and both ends' links end. A use of the first
            // exchange can land just after the jump — a sender notes its use once the send returns, after the far end
            // already stored the frame this scenario saw — and moves the idle close a whole period on, which only the
            // lab's jump makes that long. So: down, or a use after the jump, both events; a second period only for that
            // use, and a link that stayed up without one fails at once.
            val bothDown = { checkNotNull(alice.links).links.value.isEmpty() && checkNotNull(bob.links).links.value.isEmpty() }
            val uses = { alice.linkUses.get() + bob.linkUses.get() }
            val atJump = uses()
            lab.clock.advance(LinkSession.IDLE_MS)
            assertTrue(
                "the link neither idled out nor carried a use\n${alice.linkLog}\n${bob.linkLog}",
                lab.tryAwait(1) { if (bothDown() || uses() > atJump) 1 else 0 },
            )
            if (!bothDown()) {
                assertTrue("the link stayed up with no use after the jump", uses() > atJump)
                lab.clock.advance(LinkSession.IDLE_MS)
                assertTrue("the link never idled out\n${alice.linkLog}\n${bob.linkLog}", lab.tryAwait(1) { if (bothDown()) 1 else 0 })
            }
            assertTrue("no idle close was logged: ${alice.linkLog + bob.linkLog}", (alice.linkLog + bob.linkLog).any { "IDLE" in it })
            lab.await(1) { if (!alice.linkPlaneHolds(bob) && !bob.linkPlaneHolds(alice)) 1 else 0 }
            alice.awaitLinksActedOn()
            bob.awaitLinksActedOn()

            assertTrue(alice.sendDm(bob, "second"))
            lab.clock.advance(LinkDemandPolicy.TICK_WAIT_MS)
            demand.round()
            awaitLinked(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            val second = alice.ownMessageId(alice.dmWith(bob), "second")
            assertEquals(DeliveryPlane.DirectLink, bob.receivedVia(bob.dmWith(alice), second))
        }

    /**
     * The signal lane (spec §7.8, C-7.8-17): neither phone holds a ticket, and the relay sells its casts, so a cast offer
     * could not leave. It advertises sigs, which no ticket gates, so Alice's demand offers over a sig, Bob answers over
     * one, and the link comes up and carries the DM. No cast could leave here (no ticket); that a spool taking sigs never
     * gets the cast as well is `ScopeSyncTest`'s, on an ungated spool.
     */
    @Test
    fun aFreePhoneOffersALinkOverTheRelaysSigsWhereItsCastsWantATicket() =
        runBlocking {
            val spool =
                FakeSpool(
                    cast = SpoolCastInfo(maxBytes = 2_048, rate = 4),
                    sig = SpoolSigInfo(maxBytes = 8_192, rate = 2),
                    stun = listOf(LabInternet.STUN_URL),
                    ent = true,
                    entGates = SpoolTicket.LEGACY_GATES,
                )
            val (alice, bob) = contacts(spool, Nat.PORT_RESTRICTED)
            val demand = checkNotNull(alice.demand)
            spool.mute()

            assertTrue(alice.sendDm(bob, "over a free lane"))
            lab.clock.advance(LinkDemandPolicy.TICK_WAIT_MS)
            demand.round()
            awaitLinked(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            val sent = alice.ownMessageId(alice.dmWith(bob), "over a free lane")
            assertEquals(DeliveryPlane.DirectLink, bob.receivedVia(bob.dmWith(alice), sent))
            assertTrue("the offer and its answer went as sigs: ${spool.sigsFanned}", spool.sigsFanned >= 2)
            assertTrue("a cast left with no ticket to carry it: ${spool.casts}", spool.casts.isEmpty())
        }

    // A relay that carries casts and frames but takes attachments only from a files ticket, which nobody holds yet.
    private fun filesGatedRelay() =
        FakeSpool(
            cast = SpoolCastInfo(maxBytes = 2_048, rate = 4),
            stun = listOf(LabInternet.STUN_URL),
            ent = true,
            entGates = SpoolTicket.FL_FILES,
        )

    /**
     * The bulk trigger (L-7-7): the relay carries casts and frames, but gates attachments behind a files ticket Alice does
     * not hold, so her DM photo has no spool to go up to. The demand offers Bob a link at once — no tick wait, the photo's
     * message itself still rides the relay — and the photo crosses the link, once, and Bob records it as the link's.
     *
     * Then the sender's proof (ADR 2026-10.vy27, a5yj): the spool plane settled the photo as locked before the link was
     * up, so its own rounds ask nothing; the demand's rounds ask Bob over the link instead, and his HAVE becomes Alice's
     * `DirectLink` holder row. Proved, the rounds ask no more, and a files ticket granted afterwards reopens her uploads
     * and settles the photo PROVED: it never goes up.
     */
    @Test
    fun aPhotoNoRelayWillTakeOpensALinkAtOnceAndCrossesIt() =
        runBlocking {
            val spool = filesGatedRelay()
            val (alice, bob) = contacts(spool, Nat.PORT_RESTRICTED)
            val demand = checkNotNull(alice.demand)
            assertTrue(alice.sendImage(Random(2_028).nextBytes(32_768), "no relay takes this", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "no relay takes this")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))

            // No clock moves: a photo no spool will take is due at once.
            demand.round()
            awaitLinked(alice, bob)
            assertTrue(
                "bob never held the photo\n${alice.linkLog}\n${bob.linkLog}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            lab.await(1) { if (bob.holderPlane(hash, bob) == DeliveryPlane.DirectLink) 1 else 0 }

            // The first round's query went before the bytes landed; past its floor, the next round asks again.
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            demand.round()
            assertTrue(
                "alice never proved bob's hold over the link\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (alice.holderPlane(hash, bob) == DeliveryPlane.DirectLink) 1 else 0 },
            )
            val queries = alice.metrics.files().blobProofQueriesSent
            assertTrue("no proof query went out", queries > 0)
            // Proved, the rounds ask no more: three of them, each past the floor, read nothing stuck.
            repeat(POST_PROOF_ROUNDS) {
                lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
                demand.round()
                assertTrue("a proved photo is still asked about", alice.manager.proveStuckOverLinks(setOf(bob.nodeId)).isEmpty())
            }

            // A files ticket now: uploads reopen and the locked photo is looked at again — proved, never uploaded.
            val proved = alice.metrics.spool().spoolAttachProved
            val ticket = SpoolTicketsTest.ticket(aud = checkNotNull(SpoolUrl.hostname(MeshLab.SPOOL_URL)), fl = SpoolTicket.FL_FILES)
            assertTrue(SpoolTickets(alice.settings).import(MeshLab.SPOOL_URL, ticket.text) is SpoolTickets.Import.Held)
            assertTrue(
                "the reopened round never settled the photo as proved\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.metrics.spool().spoolAttachProved > proved) 1 else 0
                },
            )
            assertTrue("chunks went up: ${spool.chunksPut}", spool.chunksPut.isEmpty())
            // One batch queued in the round the proof overtook may still go out after it; a round asking after the proof
            // would add one per round.
            val after = alice.metrics.files().blobProofQueriesSent - queries
            assertTrue("$after proof queries after the proof", after <= 1)
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * FETCHING is no proof, and no reason to stop asking. Bob's link lands the photo but his mesh has not taken it yet
     * (his hand-off held), so he answers Alice's proof query FETCHING and she records nothing; once his store holds it,
     * her next demand round past the floor asks again and his HAVE proves it.
     */
    @Test
    fun aFetchingAnswerIsAskedAgainAndTheHaveThatFollowsProves() =
        runBlocking {
            val spool = filesGatedRelay()
            val (alice, bob) = contacts(spool, Nat.PORT_RESTRICTED)
            val demand = checkNotNull(alice.demand)
            bob.linkFileGate.hold()
            assertTrue(alice.sendImage(Random(2_029).nextBytes(32_768), "fetching first", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "fetching first")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))

            demand.round()
            awaitLinked(alice, bob)
            assertTrue(
                "the photo never reached bob's link\n${alice.linkLog}\n${bob.linkLog}",
                lab.tryAwait(1) { bob.linkFileGate.parked },
            )
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            demand.round()
            val fetching = "blob proof ← ${bob.nodeId}"
            assertTrue(
                "bob never answered FETCHING\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) {
                    ShadowLog.getLogs().count { fetching in it.msg && "${hash.take(HASH_CHARS)} FETCHING" in it.msg }
                },
            )
            assertEquals("a FETCHING answer proved a hold", null, alice.holderPlane(hash, bob))

            bob.linkFileGate.release()
            lab.await(1) { if (bob.holderPlane(hash, bob) == DeliveryPlane.DirectLink) 1 else 0 }
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            demand.round()
            assertTrue(
                "alice never proved bob's hold after his FETCHING\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (alice.holderPlane(hash, bob) == DeliveryPlane.DirectLink) 1 else 0 },
            )
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            assertTrue("chunks went up: ${spool.chunksPut}", spool.chunksPut.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A group's picture gets no proof query: no member's hold speaks for another's (ADR 2026-10.vy27 is a DM's). Alice's
     * picture to a group of two crosses the link to Bob, and her demand's rounds, run with the link up, ask him nothing
     * and leave no holder row for him on her side.
     */
    @Test
    fun aGroupPictureOverTheLinkIsNeverAskedForProof() =
        runBlocking {
            val spool = filesGatedRelay()
            val (alice, bob) = contacts(spool, Nat.PORT_RESTRICTED)
            val demand = checkNotNull(alice.demand)
            val group = alice.createGroup(bob)
            assertTrue(alice.sendGroup(group, "welcome"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { group }

            assertEquals(LinkOutcome.UP, checkNotNull(alice.links).offer(bob.nodeId))
            awaitLinked(alice, bob)
            assertTrue(alice.sendImage(Random(2_030).nextBytes(32_768), "to the group", groupId = group))
            val id = alice.ownMessageId(group, "to the group")
            val hash = checkNotNull(alice.attachmentHash(group, id))
            assertTrue(
                "bob never held the group's picture\n${alice.linkLog}\n${bob.linkLog}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            lab.clock.advance(BlobExchange.PROOF_FLOOR_MS)
            demand.round()

            assertTrue("a group's picture was asked about", alice.manager.proveStuckOverLinks(setOf(bob.nodeId)).isEmpty())
            assertEquals("a proof query went out", 0L, alice.metrics.files().blobProofQueriesSent)
            assertEquals(null, alice.holderPlane(hash, bob))
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { group }
        }

    private suspend fun LabNode.holderPlane(
        hash: String,
        holder: LabNode,
    ): DeliveryPlane? =
        db
            .attachmentHolderDao()
            .holdersOf(hash)
            .firstOrNull { it.holderId == holder.nodeId }
            ?.let { DeliveryPlane.fromCode(it.plane) }

    private companion object {
        // The hash prefix `BlobExchange`'s log lines name.
        const val HASH_CHARS = 8

        // Demand rounds run after the proof, each of which would ask again were it still read as stuck.
        const val POST_PROOF_ROUNDS = 3
    }
}
