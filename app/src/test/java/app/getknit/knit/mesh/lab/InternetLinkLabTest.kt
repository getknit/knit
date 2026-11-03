package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.inet.LinkDemandPolicy
import app.getknit.knit.mesh.inet.LinkOutcome
import app.getknit.knit.mesh.inet.LinkSession
import app.getknit.knit.mesh.inet.LinkWhy
import app.getknit.knit.mesh.inet.PunchPolicy
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolCastInfo
import app.getknit.knit.mesh.spool.SpoolSigInfo
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTickets
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.async
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
 * real `DirectLinks` sends its offer and answer through the relay as sigs, punches over the lab's fake NATs ([LabInternet]),
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

    // A relay that fans out sigs and advertises the lab's STUN server, as `lab.lax` does.
    private fun relay() = FakeSpool(sig = SpoolSigInfo(maxBytes = 8_192, rate = 2), stun = listOf(LabInternet.STUN_URL))

    // Alice and Bob on the relay and the lab's Internet behind NATs of [nat], contacts with a confirmed session each way and
    // a DM scope converged at the relay ([MeshLab.meetOnTheRelay]): what a link's offer needs, its sig lane open.
    private suspend fun contacts(
        spool: FakeSpool,
        nat: Nat,
    ): Pair<LabNode, LabNode> {
        // Board-less in a boards sweep too: its one shared air would reach both islands, and a DM would ride it beside the
        // link a scenario is about.
        val alice = lab.node("alice", spool = spool, internet = nat, board = false).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool, internet = nat, board = false).apply { setDisplayName("Bob") }
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

    // What [a]'s link plane remembers of [b] when a link never came: the demand's own inputs (a round that offers nothing
    // logs nothing, and an offer answered BACKOFF logs nothing either).
    private fun linkState(
        a: LabNode,
        b: LabNode,
    ): String {
        val links = checkNotNull(a.links)
        val tried = links.lastTries()[b.nodeId]?.let { "${it.outcome} why=${it.why} at=${it.at}" }
        return "${a.name}: now=${lab.clock.now()} lastUpAt=${links.lastUpAt(b.nodeId)} declinedAt=${links.declinedAt(b.nodeId)} " +
            "lastTry=$tried"
    }

    // Both ends' link planes hold the link, and both managers have acted on it: what a send next may rely on.
    private suspend fun awaitLinked(
        a: LabNode,
        b: LabNode,
    ) {
        val linked = lab.tryAwait(1) { if (a.linkPlaneHolds(b) && b.linkPlaneHolds(a)) 1 else 0 }
        assertTrue("the link never reached both planes\n${a.linkLog}\n${b.linkLog}\n${linkState(a, b)}\n${linkState(b, a)}", linked)
        a.awaitLinksActedOn()
        b.awaitLinksActedOn()
        // The link-up's presence-key pass (a short wait, then maybe a ctl 11 across the link, which is a use of it), best
        // effort: the manager schedules it after it marks the links acted on, so a poll in between passes early; the idle
        // close's loop is what absorbs a ctl 11 that lands later.
        a.awaitPresencePasses()
        b.awaitPresencePasses()
    }

    /**
     * Alice and Bob met once, then parted to two islands: both behind port-restricted NATs, both on one relay that carries
     * sigs. Alice's offer comes up over the punch, and once it is up the relay goes silent (it still fans out sigs, and
     * takes nothing else), so the DM and the group post that follow converge over the link alone, ticks and all.
     */
    @Test
    fun anOfferPunchedThroughTwoNatsCarriesTheDmAndTheGroupAcrossIt() =
        runBlocking {
            val spool = relay()
            // Board-less in a boards sweep too, as in [contacts]: the link alone is the subject.
            val alice = lab.node("alice", spool = spool, internet = Nat.PORT_RESTRICTED, board = false).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool, internet = Nat.PORT_RESTRICTED, board = false).apply { setDisplayName("Bob") }
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
     * offer sends nothing; and the relay, which never stopped, still carries the DM. The give-up is the session's own, met
     * by a clock jump rather than sat out ([LabInternet]).
     */
    @Test
    fun twoSymmetricNatsFindNoPathBackOffAndTheRelayStillCarries() =
        runBlocking {
            val spool = relay()
            val (alice, bob) = contacts(spool, Nat.SYMMETRIC)

            val sentBefore = alice.linkDatagramsSent()
            val attempt = async { checkNotNull(alice.links).offer(bob.nodeId) }
            // Alice's first probe leaves after her session took its start from the clock, and Bob's started before his
            // answer left: a jump now ends both punches at their next probe round, as the give-up would ten seconds on. An
            // attempt that ended before any probe (no sig, no answer) ends the wait too, so the assert names its outcome.
            lab.await(1) { if (attempt.isCompleted || alice.linkDatagramsSent() > sentBefore) 1 else 0 }
            lab.clock.advance(PunchPolicy.GIVE_UP_MS)
            assertEquals(LinkOutcome.NO_PATH, attempt.await())
            assertEquals(LinkOutcome.NO_PATH, checkNotNull(alice.links).lastTries()[bob.nodeId]?.outcome)
            assertFalse("a link came up behind two symmetric NATs", alice.linkPlaneHolds(bob))
            val sigs = spool.sigs.size
            assertEquals(LinkOutcome.BACKOFF, checkNotNull(alice.links).offer(bob.nodeId))
            assertEquals("an offer in its backoff sent a sig", sigs, spool.sigs.size)

            assertTrue(alice.sendDm(bob, "by the relay"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
            val dm = alice.ownMessageId(alice.dmWith(bob), "by the relay")
            assertEquals(DeliveryPlane.Internet, bob.receivedVia(bob.dmWith(alice), dm))
            // Again once a whole DM round trip has crossed the same connection, in order: a sig sent in the backoff would
            // have reached the relay by now, where the read straight after the offer could beat it.
            assertEquals("an offer in its backoff sent a sig", sigs, spool.sigs.size)
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
     * The demand opens a link, the link idles out, and the demand opens it again. The relay goes silent but for its sigs:
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
            // lab's jump makes that long, and so can the link-up's presence-key ctl 11, noted on each end. So: down, or a
            // use after the jump, both events; another period only for such a use, at most [LATE_USE_PERIODS], and a link
            // that stayed up without one fails at once.
            val bothDown = { checkNotNull(alice.links).links.value.isEmpty() && checkNotNull(bob.links).links.value.isEmpty() }
            val uses = { alice.linkUses.get() + bob.linkUses.get() }
            var periods = 0
            while (true) {
                val atJump = uses()
                lab.clock.advance(LinkSession.IDLE_MS)
                assertTrue(
                    "the link neither idled out nor carried a use\n${alice.linkLog}\n${bob.linkLog}",
                    lab.tryAwait(1) { if (bothDown() || uses() > atJump) 1 else 0 },
                )
                if (bothDown()) break
                periods++
                assertTrue(
                    "the link kept carrying uses past $LATE_USE_PERIODS idle periods\n${alice.linkLog}\n${bob.linkLog}",
                    periods <= LATE_USE_PERIODS,
                )
            }
            // Awaited, not read: `DirectLinks.holdUntilClosed` takes the link out of `links` before it logs its end.
            val idled = lab.tryAwait(1) { if ((alice.linkLog + bob.linkLog).any { "IDLE" in it }) 1 else 0 }
            assertTrue("no idle close was logged: ${alice.linkLog + bob.linkLog}", idled)
            lab.await(1) { if (!alice.linkPlaneHolds(bob) && !bob.linkPlaneHolds(alice)) 1 else 0 }
            alice.awaitLinksActedOn()
            bob.awaitLinksActedOn()

            // Dated after the link's end: `DirectLinks.ended` stamps it, and the demand reads a row dated in that very
            // millisecond as one the link had its chance at (`LinkDemandPolicy.offers`, `p.at > lastUpAt`). The wall
            // clock moves on by itself, but a send this close behind the end can land in that millisecond: the chaos
            // failure that showed the demand's inputs (seed 519910471) had sent within 13 ms of the end and offered
            // nothing. `LinkDownloadLimitLabTest`'s unmetered step steps past the same boundary.
            lab.clock.advance(1)
            assertTrue(alice.sendDm(bob, "second"))
            lab.clock.advance(LinkDemandPolicy.TICK_WAIT_MS)
            demand.round()
            awaitLinked(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            val second = alice.ownMessageId(alice.dmWith(bob), "second")
            assertEquals(DeliveryPlane.DirectLink, bob.receivedVia(bob.dmWith(alice), second))
        }

    /**
     * The signal lane alone (spec §7.8, C-7.8-17): the relay fans out casts to anyone, no ticket asked, and sigs too. Alice's
     * demand's offer and Bob's answer go as sigs, the link comes up and carries the DM, and not one cast is sent: a link
     * signal never rides a cast, even where one would be carried.
     */
    @Test
    fun aLinkSignalRidesSigsEvenWhereTheRelayWouldCarryItsCast() =
        runBlocking {
            val spool =
                FakeSpool(
                    cast = SpoolCastInfo(maxBytes = 2_048, rate = 4),
                    sig = SpoolSigInfo(maxBytes = 8_192, rate = 2),
                    stun = listOf(LabInternet.STUN_URL),
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
            assertTrue("a link signal went as a cast: ${spool.casts}", spool.casts.isEmpty())
        }

    /**
     * A walkie call between two islands (docs/VOICE_CALLS.md C-3.11-8, ADR 2026-10.a5yj's walkie amendment). The relay
     * casts to anyone, and with no radio path and no link the first chunk goes that way. The call's hold — the ask a
     * walkie renews from the ring to the hang-up — finds no radio path, so it raises a direct link, and holds it once up.
     * From then on each chunk crosses the link alone: not one more cast, and each counted at Bob's end as come Alice's own
     * way, never through a relay, so his reports never turn her cast on beside a link that delivers (C-3.11-10).
     */
    @Test
    fun aWalkiesHoldRaisesALinkWhereNoRadioReachesAndItsChunksCrossItAloneUncast() =
        runBlocking {
            val spool =
                FakeSpool(
                    cast = SpoolCastInfo(maxBytes = 2_048, rate = 4),
                    sig = SpoolSigInfo(maxBytes = 8_192, rate = 2),
                    stun = listOf(LabInternet.STUN_URL),
                )
            val (alice, bob) = contacts(spool, Nat.PORT_RESTRICTED)

            assertTrue(alice.sendTalk(bob, byteArrayOf(0)))
            lab.await(1) { bob.talksViaRelay.size }
            val cast = spool.casts.size

            // Both ends' calls ask; the first ask finds nothing up yet to hold.
            assertFalse(alice.holdLink(bob))
            bob.holdLink(alice)
            awaitLinked(alice, bob)
            assertTrue(alice.holdLink(bob))
            assertTrue(bob.holdLink(alice))

            for (q in 1..CHUNKS) assertTrue(alice.sendTalk(bob, byteArrayOf(q.toByte())))
            lab.await(CHUNKS + 1) { bob.talks.size }
            assertEquals("a chunk was cast beside the link: ${spool.casts}", cast, spool.casts.size)
            assertEquals("a chunk over the link counted as a relay's", 1, bob.talksViaRelay.size)
            assertEquals((0..CHUNKS).toSet(), bob.talks.map { it.single().toInt() }.toSet())
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    // A relay that carries sigs and frames but takes attachments only from a files ticket, which nobody holds yet.
    private fun filesGatedRelay() =
        FakeSpool(
            sig = SpoolSigInfo(maxBytes = 8_192, rate = 2),
            stun = listOf(LabInternet.STUN_URL),
            ent = true,
            entGates = SpoolTicket.FL_FILES,
        )

    /**
     * The bulk trigger (L-7-7): the relay carries sigs and frames, but gates attachments behind a files ticket Alice does
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
     * A contact with links off says so, and the photos still cross once it turns them on (`docs/DIRECT_LINKS.md` L-2-6,
     * L-7-9). The relay takes no attachments from Alice, so her photo to Bob is due at once; Bob's links are off, so he
     * declines, and her rounds past every backoff offer no more for it. A second photo is newer than the decline: one more
     * offer, declined too. Bob turns his links on. Nothing of Alice's is newer than his last decline, so she has no reason
     * to ask; Bob wants both photos, nobody brings them, and a minute after they arrived his own demand offers her a link,
     * over which both cross.
     */
    @Test
    fun aContactWithLinksOffDeclinesAndOnceOnAsksForThePhotosItIsMissing() =
        runBlocking {
            val (alice, bob) = contacts(filesGatedRelay(), Nat.PORT_RESTRICTED)
            val (aliceDemand, bobDemand) = checkNotNull(alice.demand) to checkNotNull(bob.demand)
            val links = checkNotNull(alice.links)
            bob.linksOn = false
            val declines = { alice.linkLog.count { it.startsWith("link DECLINED →") } }

            suspend fun photo(
                seed: Int,
                caption: String,
            ): String {
                val had = bob.decrypted(bob.dmWith(alice)).size
                assertTrue(alice.sendImage(Random(seed).nextBytes(32_768), caption, to = bob))
                lab.await(had + 1) { bob.decrypted(bob.dmWith(alice)).size }
                return checkNotNull(alice.attachmentHash(alice.dmWith(bob), alice.ownMessageId(alice.dmWith(bob), caption)))
            }
            val first = photo(1, "while bob's links are off")
            aliceDemand.round()
            lab.await(1) { declines() }
            assertTrue(links.declinedAt(bob.nodeId) != null)
            repeat(DECLINED_ROUNDS) {
                lab.clock.advance(PunchPolicy.MAX_BACKOFF_MS)
                aliceDemand.round()
            }
            val second = photo(2, "and another")
            val before = declines()
            aliceDemand.round()
            lab.await(before + 1) { declines() }

            bob.linksOn = true
            lab.clock.advance(LinkDemandPolicy.WANT_WAIT_MS)
            bobDemand.round()
            awaitLinked(bob, alice)
            assertTrue(
                "bob never got both photos\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(2) { listOf(first, second).count { bob.holdsBlob(it) } },
            )
            assertEquals(LinkWhy.WANT, checkNotNull(bob.links).lastTries()[alice.nodeId]?.why)
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            // Every offer Alice launched has settled by now: one per photo, none for the declined one again.
            assertEquals("offered again for the declined photo:\n${alice.linkLog}", 2, declines())
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

    /**
     * The receiver moves to another network mid-file (ADR 2026-10.a5yj's network-change amendment, `docs/DIRECT_LINKS.md`
     * L-4-2, L-7-9). Alice's photo, which no relay takes, is crossing the link when Bob's Wi-Fi goes dark under it, and his
     * phone moves to another network: his link closes at once (`ROUTE`), keeping the part of the photo that landed, while
     * Alice's end still holds the old one. That link had no chance at the photo, so it is not counted against Bob's want:
     * a minute after the message arrived his own demand offers Alice a link from his new network, her answer replaces the
     * link she held, and the photo crosses from where the first copy stopped.
     */
    @Test
    fun aPhotoCutByTheReceiversMoveToAnotherNetworkResumesOverTheLinkHisWantRaises() =
        runBlocking {
            val (alice, bob) = contacts(filesGatedRelay(), Nat.PORT_RESTRICTED)
            val bobDemand = checkNotNull(bob.demand)
            val hash = cutMidFile(alice, bob, darkened = bob, caption = "cut by bob's move", seed = 2_031)
            val adopted = adoptedBy(alice, bob)

            bob.switchNetwork(Nat.PORT_RESTRICTED)
            awaitCutLinkGone(bob, alice)
            assertFalse(bob.holdsBlob(hash))
            // Every ask of Bob's that crossed the cut link answered, so none is served past the jump and renews the memo.
            lab.await(1) { if (alice.metrics.files().blobAsksHandled >= alice.linkAsksIn.size) 1 else 0 }

            // Past the serve memo of the copy that was cut, and a minute past the message: Bob's want is due.
            lab.clock.advance(LinkDemandPolicy.WANT_WAIT_MS)
            bobDemand.round()
            awaitRelinked(bob, alice, adopted)
            bob.reoffer()
            val got = lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 }
            assertTrue("bob never got the photo after his move\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}", got)
            assertEquals(LinkWhy.WANT, checkNotNull(bob.links).lastTries()[alice.nodeId]?.why)
            assertTrue("the photo never resumed: ${alice.linkFiles}", alice.linkFiles.any { hash in it && " from " in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The sender moves (`docs/DIRECT_LINKS.md` L-4-2, L-7-7): Alice's Wi-Fi goes dark with her photo part way across, and
     * her phone moves to another network. Her link closes at once, mid-file, so the photo no relay takes is due again
     * without a wait, and her next round offers from the new network; Bob, who still held the old link, answers, and the
     * answer replaces it. His ask over the new link meets Alice's serve memo for the copy the old one carried, and the
     * next round's ask, past it, brings the rest from where it stopped.
     */
    @Test
    fun aPhotoCutByTheSendersMoveToAnotherNetworkIsOfferedAgainAtOnce() =
        runBlocking {
            val (alice, bob) = contacts(filesGatedRelay(), Nat.PORT_RESTRICTED)
            val demand = checkNotNull(alice.demand)
            val links = checkNotNull(alice.links)
            val hash = cutMidFile(alice, bob, darkened = alice, caption = "cut by alice's move", seed = 2_032)
            val adopted = adoptedBy(alice, bob)
            val firstTry = links.lastTries()[bob.nodeId]

            alice.switchNetwork(Nat.PORT_RESTRICTED)
            awaitCutLinkGone(alice, bob)

            // No clock moves: the photo is due again at once, with no backoff on the new network.
            demand.round()
            awaitRelinked(alice, bob, adopted)
            val again = checkNotNull(links.lastTries()[bob.nodeId])
            assertTrue(
                "no new offer for the photo: ${alice.linkLog}",
                again !== firstTry && again.offerer && again.why == LinkWhy.FILE && again.outcome == LinkOutcome.UP,
            )
            // Every ask that reached Alice answered, so none is served past the jump and renews the memo.
            lab.await(1) { if (alice.metrics.files().blobAsksHandled >= alice.linkAsksIn.size) 1 else 0 }
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            bob.reoffer()
            val got = lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 }
            assertTrue("bob never got the photo after alice's move\n${alice.linkLog}\n${lab.report(listOf(alice, bob))}", got)
            assertTrue("the photo never resumed: ${alice.linkFiles}", alice.linkFiles.any { hash in it && " from " in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    // [moved]'s link to [peer] closed mid-file on its move, and its link plane has dropped it: logged once the link has left
    // its links, so its demand no longer reads it as up, and dropped once the plane let go of the file it was landing, so
    // its mesh no longer reads that file as arriving.
    private suspend fun awaitCutLinkGone(
        moved: LabNode,
        peer: LabNode,
    ) {
        val closed = lab.tryAwait(1) { moved.linkLog.count { "ROUTE mid-file" in it } }
        assertTrue("${moved.name}'s link never closed mid-file on the move\n${moved.linkLog}", closed)
        lab.await(1) { if (moved.linkPlaneHolds(peer)) 0 else 1 }
    }

    // How many links each of [nodes]' planes has adopted so far, for [awaitRelinked].
    private fun adoptedBy(vararg nodes: LabNode): Map<LabNode, Int> = nodes.associateWith { it.linksAdopted.get() }

    // A link made again between [a] and [b] has reached both planes: each adopted one since [before], which the neighbour
    // sets cannot show on an end whose plane still held the old link when the new one replaced it.
    private suspend fun awaitRelinked(
        a: LabNode,
        b: LabNode,
        before: Map<LabNode, Int>,
    ) {
        lab.await(1) { if (listOf(a, b).all { it.linksAdopted.get() > before.getValue(it) }) 1 else 0 }
        awaitLinked(a, b)
    }

    // Alice sends Bob a photo no relay takes, and her demand raises a link for it; [darkened]'s network goes dark once
    // [DARK_AFTER_BYTES] have crossed it, with part of the photo landed on Bob's end and the rest still to come: both
    // ends are mid-file. Returns the photo's hash.
    private suspend fun cutMidFile(
        alice: LabNode,
        bob: LabNode,
        darkened: LabNode,
        caption: String,
        seed: Int,
    ): String {
        darkened.darkenNetworkAfter(DARK_AFTER_BYTES)
        assertTrue(alice.sendImage(Random(seed).nextBytes(BIG_PHOTO_BYTES), caption, to = bob))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == caption } }
        val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), alice.ownMessageId(alice.dmWith(bob), caption)))
        checkNotNull(alice.demand).round()
        val cut =
            lab.tryAwait(1) {
                val mid = darkened.networkDark && checkNotNull(alice.link).busy(bob.nodeId) && checkNotNull(bob.link).busy(alice.nodeId)
                if (mid) 1 else 0
            }
        val state = "dark ${darkened.networkDark}, alice busy ${alice.link?.busy(bob.nodeId)}, bob busy ${bob.link?.busy(alice.nodeId)}"
        assertTrue("the network never went dark mid-file: $state\n${alice.linkFiles}\n${alice.linkLog}\n${bob.linkLog}", cut)
        assertFalse(bob.holdsBlob(hash))
        return hash
    }

    private companion object {
        // Idle periods past the first a late use may cost: the last exchange's own, and the link-up's presence-key ctl 11
        // noted on each end.
        const val LATE_USE_PERIODS = 3

        // A photo the cut lands well inside of on both ends: past what crosses before the network goes dark, and past what
        // the sender's stream then holds unacknowledged (its window, grown on the acks that came back, and its send
        // buffer), so the sender's writer is still blocked on the stream, as on a slow path, when the path dies.
        const val BIG_PHOTO_BYTES = 512 * 1024

        // What crosses a network before it goes dark: the link's handshake and sync, and well over `PartialBlobs.MIN_BYTES`
        // of the photo, so the part that landed is kept.
        const val DARK_AFTER_BYTES = 64 * 1024L

        // The hash prefix `BlobExchange`'s log lines name.
        const val HASH_CHARS = 8

        // Demand rounds run after the proof, each of which would ask again were it still read as stuck.
        const val POST_PROOF_ROUNDS = 3

        // A walkie burst's chunks after the first, over the link.
        const val CHUNKS = 6

        // Demand rounds after a decline, each past the longest backoff, that must offer nothing for the declined photo.
        const val DECLINED_ROUNDS = 3
    }
}
