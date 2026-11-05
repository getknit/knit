package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.inet.LinkOutcome
import app.getknit.knit.mesh.inet.LinkWhy
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolSigInfo
import app.getknit.knit.mesh.spool.SpoolTicket
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
 * A contact's direct link beside a radio (ADR 2026-10.a5yj's beside-a-radio amendment, `docs/DIRECT_LINKS.md` L-7-11,
 * GitLab #236): two contacts on one home network that the radio links too, with no Wi-Fi Aware between them. The radio
 * carries every frame; an attachment crosses a **free** link — neither end's network metered when it was made — the
 * sender raises for it, and one over the receiver's download limit crosses a link the receiver raises, with no tap. A
 * metered end keeps every byte on the radio. The relay carries the links' signals and takes no attachments, so a file
 * crosses the link or the radio, never the relay.
 */
@RunWith(RobolectricTestRunner::class)
class LinkBesideRadioLabTest {
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
     * Under the receiver's limit, the sender's link: Alice serves Bob's ask for a 300 KB file, too big for the radio's pace
     * and with no Wi-Fi Aware to carry it, so she raises a free link (`why` BULK) and the file crosses it once, while the
     * frames stay on the radio.
     */
    @Test
    fun anAttachmentBesideARadioCrossesAFreeLinkTheSenderRaises() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            val (_, hash) = send(alice, bob, BULK_BYTES, "the plan", seed = 2_361)

            assertTrue(
                "bob never got the file\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            assertEquals("a copy over the radio: ${alice.transport.files}", 0, alice.transport.files.count { hash in it })
            val raised = checkNotNull(checkNotNull(alice.links).lastTries()[bob.nodeId]) { "alice raised no link" }
            assertEquals(LinkOutcome.UP to LinkWhy.BULK, raised.outcome to raised.why)
            assertTrue("the link is not free", checkNotNull(alice.links).freeLinkTo(bob.nodeId))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A metered end keeps the bytes on the radio. Bob's network is metered: Alice raises a link for the same file, Bob
     * declines it as metered, and the file crosses the radio at once, with no link made.
     */
    @Test
    fun anAttachmentToAMeteredContactBesideARadioStaysOnTheRadio() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            bob.metered = true
            val (_, hash) = send(alice, bob, BULK_BYTES, "the metered plan", seed = 2_362)

            assertTrue(
                "bob never got the file\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals("one copy over the radio: ${alice.transport.files}", 1, alice.transport.files.count { hash in it })
            assertEquals("a copy over a link: ${alice.linkFiles}", 0, alice.linkFiles.count { hash in it })
            val raised = checkNotNull(checkNotNull(alice.links).lastTries()[bob.nodeId]) { "alice raised no link" }
            assertEquals(LinkOutcome.METERED to LinkWhy.BULK, raised.outcome to raised.why)
            assertTrue("a link was made", checkNotNull(alice.links).links.value.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** A small attachment is the radio's: under 128 KiB nothing is raised. */
    @Test
    fun aSmallAttachmentBesideARadioRaisesNoLink() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            val (_, hash) = send(alice, bob, SMALL_BYTES, "a sticker", seed = 2_363)

            assertTrue("bob never got the file", lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 })
            assertEquals(1, alice.transport.files.count { hash in it })
            assertEquals("a link was raised", null, checkNotNull(alice.links).lastTries()[bob.nodeId])
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Over the receiver's limit, the receiver's link: Bob's want for a 1.2 MB file is fast-only (ADR 2026-10.js2p), the
     * radio is no fast route, and Alice's serve would never come. Held for a fast route on an unmetered network, it calls
     * a link beside the radio at once (`why` WANT); the link comes up free, and Bob asks over it fast-only with no tap.
     */
    @Test
    fun aFileOverTheDownloadLimitBesideARadioCrossesALinkTheReceiverRaisesWithNoTap() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            val (id, hash) = send(alice, bob, OVER_LIMIT_BYTES, "the survey", seed = 2_364)
            awaitHeld(bob)

            checkNotNull(bob.demand).round()
            assertTrue(
                "bob never got the file over a link\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals(LinkWhy.WANT, checkNotNull(bob.links).lastTries()[alice.nodeId]?.why)
            assertEquals("bob tapped", false, bob.row(id)?.attachmentFetchApproved)
            lab.await(1) { if (asksLogged(of = alice, hash = hash).isNotEmpty()) 1 else 0 }
            assertTrue("an ask was not fast-only", asksLogged(of = alice, hash = hash).all { it })
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            assertEquals("a copy over the radio: ${alice.transport.files}", 0, alice.transport.files.count { hash in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The ask beats the holder's end of the link (#295), the holder answering. Bob raises the link as above, and Alice is
     * stopped with her HELLO out, so Bob's end is published and his fast-only ask reaches her over the radio while hers is
     * not. She takes it for the link about to be published, and the copy crosses it as soon as her end is, with no tick.
     */
    @Test
    fun aFastOnlyAskAheadOfTheAnsweringHoldersEndOfTheLinkCrossesItWithNoTick() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            val (_, hash) = send(alice, bob, OVER_LIMIT_BYTES, "the early survey", seed = 2_367)
            awaitHeld(bob)

            alice.linkHelloGate.hold()
            checkNotNull(bob.demand).round()
            awaitAskMetInTheWindow(alice, bob, hash)
            alice.linkHelloGate.release()

            assertTrue(
                "bob never got the file over a link\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals(LinkWhy.WANT, checkNotNull(bob.links).lastTries()[alice.nodeId]?.why)
            assertOneCopyOverTheLink(alice, bob, hash)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The other way round (#295): Alice offers the link, and is stopped before she reads Bob's HELLO, so Bob, answering,
     * publishes first and asks. No round of Bob's demand runs: its offer would cross hers, and crossed offers resolve by id,
     * which could leave Bob the offerer.
     */
    @Test
    fun aFastOnlyAskAheadOfTheOfferingHoldersEndOfTheLinkCrossesItWithNoTick() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            val (_, hash) = send(alice, bob, OVER_LIMIT_BYTES, "the offered survey", seed = 2_368)
            awaitHeld(bob)

            alice.linkHelloGate.hold()
            assertTrue("alice raised no link", checkNotNull(alice.links).raiseBulk(bob.nodeId))
            awaitAskMetInTheWindow(alice, bob, hash)
            alice.linkHelloGate.release()

            assertTrue(
                "bob never got the file over a link\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            val raised = checkNotNull(checkNotNull(alice.links).lastTries()[bob.nodeId]) { "alice raised no link" }
            assertTrue("alice did not offer", raised.offerer)
            assertEquals(LinkOutcome.UP to LinkWhy.BULK, raised.outcome to raised.why)
            assertOneCopyOverTheLink(alice, bob, hash)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A link that dies inside the window (#295) takes the copy waiting for it with it, never to the radio: Bob has not
     * agreed to wait on Bluetooth for these bytes, so the copy is dropped, and his next ask brings one over the next link.
     */
    @Test
    fun aHandshakeThatDiesInsideTheWindowDropsTheCopyAndTheNextLinksAskIsServed() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            val (_, hash) = send(alice, bob, OVER_LIMIT_BYTES, "the lost survey", seed = 2_369)
            awaitHeld(bob)

            alice.linkHelloGate.hold()
            checkNotNull(bob.demand).round()
            awaitAskMetInTheWindow(alice, bob, hash)
            alice.linkHelloGate.fail()

            lab.await(1) { alice.routeLog.count { hash in it && "fast-only — dropped" in it } }
            // Over at both ends: a raise refuses a link still listed, and starts nothing while one counts as under way.
            val links = checkNotNull(alice.links)
            lab.await(1) {
                if (bob.linkPlaneHolds(alice) || links.bulkUnderWay(bob.nodeId) || bob.nodeId in links.links.value) 0 else 1
            }
            assertEquals("a copy crossed: ${alice.linkFiles}", 0, alice.linkFiles.count { hash in it })
            assertEquals("a copy over the radio: ${alice.transport.files}", 0, alice.transport.files.count { hash in it })

            // The next link. On a phone Bob's re-ask needs no tick: with the link still down at his wake the want is held and
            // the next edge queries Alice, and with it back in time his confirm wait does, and her HAVE re-asks either way. The
            // lab's jump wakes neither timer, so `reoffer()` stands in for it. The jump goes ahead of the offer, which it would
            // end once out (`LabInternet`).
            lab.clock.advance(BlobExchange.ROUND_MS + 1)
            assertTrue("alice raised no link", links.raiseBulk(bob.nodeId))
            lab.await(1) { if (bob.linkPlaneHolds(alice) && alice.linkPlaneHolds(bob)) 1 else 0 }
            bob.reoffer()
            assertTrue(
                "bob never got the file over the next link\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertOneCopyOverTheLink(alice, bob, hash)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * On a metered network the tap still decides, beside a radio too: Bob's held want calls no link, and the tap's plain
     * ask brings the file over the radio — Alice's link for it is declined as metered.
     */
    @Test
    fun aFileOverTheDownloadLimitToAMeteredContactBesideARadioWaitsForTheTapThenTakesTheRadio() =
        runBlocking {
            val (alice, bob) = onOneLanBesideARadio(filesGatedRelay())
            bob.metered = true
            val (id, hash) = send(alice, bob, OVER_LIMIT_BYTES, "the metered survey", seed = 2_365)
            awaitHeld(bob)

            // Metered, the want is no row a demand reads (`attachmentWantedSince`), so no round offers for it.
            assertEquals("a held want called for a link", null, bob.manager.attachmentWantedSince(hash))
            val crossed = alice.transport.files.count { hash in it } + alice.linkFiles.count { hash in it }
            assertEquals("a copy crossed before the tap", 0, crossed)

            bob.manager.downloadAttachment(hash)
            lab.await(1) { if (bob.row(id)?.attachmentFetchApproved == true) 1 else 0 }
            assertTrue(
                "bob never got the file after the tap\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals("one copy over the radio: ${alice.transport.files}", 1, alice.transport.files.count { hash in it })
            assertEquals("a copy over a link: ${alice.linkFiles}", 0, alice.linkFiles.count { hash in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The asker waits as long as a holder raising a link does (L-7-11; ADR 2026-10.f7gg's first ask). Alice posts a
     * 300 KB picture to a group; Bob takes it before Carol hears of it, so two neighbours hold it when she asks its author
     * alone. Carol's links are held off as low-power mode holds them, so Alice's link for the picture is never answered,
     * and Alice waits out the link's 10 s grace (real time) before the radio carries it. Carol's own state says nothing of
     * what Alice raises: her ask waits the link's grace on top of its own. The lab clock runs in real time between jumps,
     * so one jump leaves her ask's own grace short of Alice's wait — without the link's grace she would ask Bob before
     * Alice's copy starts — and with it well past; she asks nobody else, and the picture crosses once, from Alice.
     */
    @Test
    fun anAskerWaitsOutAHoldersLinkBesideTheRadioBeforeAskingAnotherHolder() =
        runBlocking {
            val spool = filesGatedRelay()
            val home = lab.lan()
            val alice = lab.node("alice", spool = spool, internet = Nat.PORT_RESTRICTED, lan = home).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool, internet = Nat.PORT_RESTRICTED, lan = home).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", spool = spool, internet = Nat.PORT_RESTRICTED, lan = home).apply { setDisplayName("Carol") }
            lab.meetOnTheRelay(alice, bob)
            lab.meetOnTheRelay(alice, carol)
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            listOf(alice, bob, carol).forEach { it.awaitLinksActedOn() }
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "hello all"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            // Carol's links are held off before the picture exists, so no link Alice raises for it is ever up. Over the
            // radio she hears nothing until Bob holds it too: two holders, then the frame.
            carol.linksQuiet = true
            alice.transport.hold(carol.transport)
            bob.transport.hold(carol.transport)
            val picture = Random(2_366).nextBytes(BULK_BYTES)
            assertTrue(alice.sendImage(picture, "two holders beside a radio", groupId = groupId))
            val id = alice.ownMessageId(groupId, "two holders beside a radio")
            val hash = checkNotNull(alice.attachmentHash(groupId, id))
            lab.await(1) { if (bob.holdsBlob(hash)) 1 else 0 }

            alice.transport.release(carol.transport)
            bob.transport.release(carol.transport)
            lab.await(1) { carol.asksFor(hash).size }
            assertEquals("the author alone first: ${carol.asksFor(hash)}", listOf(alice.nodeId), carol.asksFor(hash))
            // Alice is raising a link Carol will not answer, and waits for it. Carol's ask is left [OWN_GRACE_LEFT_MS] of its
            // own grace, short of Alice's wait, and that plus the link's grace, past it.
            lab.await(1) { if (checkNotNull(alice.links).bulkUnderWay(carol.nodeId)) 1 else 0 }
            assertTrue("a link to carol came up", carol.nodeId !in checkNotNull(alice.links).links.value)
            lab.clock.advance(MeshLab.FIRST_ASK_GRACE_MS - OWN_GRACE_LEFT_MS)
            carol.reoffer()
            assertTrue(
                "carol never got the picture\n${alice.linkLog}\n${lab.report(listOf(alice, bob, carol))}",
                lab.tryAwait(1) { if (carol.holdsBlob(hash)) 1 else 0 },
            )
            val asked = carol.asksFor(hash)
            assertEquals("carol asked another holder within the link's wait: $asked", listOf(alice.nodeId), asked)
            assertEquals("one copy, from Alice over the radio", 1, alice.filesTo(carol, hash))
            assertEquals("none from Bob, who held it too", 0, bob.filesTo(carol, hash))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }
        }

    private fun LabNode.filesTo(
        to: LabNode,
        hash: String,
    ) = transport.files.count { it == "${to.nodeId} ATTACHMENT $hash" } + linkFiles.count { hash in it && to.nodeId in it }

    private fun LabNode.asksFor(hash: String) = transport.blobAsks.filter { it.hash == hash }.map { it.to }

    // A relay that carries sigs and frames but takes attachments only from a files ticket, which nobody holds
    // (`LinkDownloadLimitLabTest.filesGatedRelay`).
    private fun filesGatedRelay() =
        FakeSpool(
            sig = SpoolSigInfo(maxBytes = 8_192, rate = 2),
            stun = listOf(LabInternet.STUN_URL),
            ent = true,
            entGates = SpoolTicket.FL_FILES,
        )

    // Alice and Bob behind one home NAT on one LAN, contacts through [spool] with a confirmed session each way, and linked
    // by the radio again once they have met on the relay: what a link beside a radio needs. Both composites hold the radio
    // link before a scenario sends, so a serve reads the radio beside it.
    private suspend fun onOneLanBesideARadio(spool: FakeSpool): Pair<LabNode, LabNode> {
        val home = lab.lan()
        val alice = lab.node("alice", spool = spool, internet = Nat.PORT_RESTRICTED, lan = home).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool, internet = Nat.PORT_RESTRICTED, lan = home).apply { setDisplayName("Bob") }
        lab.meetOnTheRelay(alice, bob)
        lab.link(alice, bob)
        alice.awaitLinksActedOn()
        bob.awaitLinksActedOn()
        return alice to bob
    }

    // Alice sends Bob a named file of [size] bytes, and Bob holds its message. Returns the message id and the file's hash.
    private suspend fun send(
        alice: LabNode,
        bob: LabNode,
        size: Int,
        caption: String,
        seed: Int,
    ): Pair<String, String> {
        val bytes = Random(seed).nextBytes(size)
        assertTrue(alice.sendFile(bytes, "notes.bin", "application/octet-stream", text = caption, to = bob))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == caption } }
        val id = alice.ownMessageId(alice.dmWith(bob), caption)
        return id to checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
    }

    // [node]'s fast-only want has been stepped and held: no fast route among its neighbours. A hold is counted, never
    // logged twice in a row, so the count is the oracle (`LinkDownloadLimitLabTest.awaitHeldWithTheLinkUp`).
    private suspend fun awaitHeld(node: LabNode) {
        val heldBefore = node.metrics.files().blobFastOnlyHeld
        node.reoffer()
        lab.await(1) { if (node.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }
    }

    // [holder] is stopped at its gate with [asker]'s end of the link published, and has met the ask for [hash]. The holder's
    // own line says so, written once the ask reached it: the asker logs its ask after sending it, which would release the
    // gate with the ask still on its way, and a holder publishing ahead of it passes on old code too.
    private suspend fun awaitAskMetInTheWindow(
        holder: LabNode,
        asker: LabNode,
        hash: String,
    ) {
        lab.await(1) { holder.linkHelloGate.parked }
        lab.await(1) { if (askMet(holder, asker, hash)) 1 else 0 }
        assertTrue("the asker's end is not up", asker.linkPlaneHolds(holder))
        assertFalse("the holder's end was published", holder.linkPlaneHolds(asker))
    }

    // Whether [holder] refused [asker]'s fast-only ask for [hash], or handed its copy to the composite's wait.
    private fun askMet(
        holder: LabNode,
        asker: LabNode,
        hash: String,
    ): Boolean =
        holder.routeLog.any { it.startsWith("file route:") && hash in it && "(fast-only)" in it } ||
            ShadowLog.getLogs().any { it.msg.startsWith("blob refuse ${hash.take(HASH_CHARS)}… → ${asker.nodeId}") }

    // One copy of [hash] crossed the link from [holder] to [asker], none the radio, and every ask for it was fast-only.
    private suspend fun assertOneCopyOverTheLink(
        holder: LabNode,
        asker: LabNode,
        hash: String,
    ) {
        lab.await(1) { if (asksLogged(of = holder, hash = hash).isNotEmpty()) 1 else 0 }
        assertTrue("an ask was not fast-only", asksLogged(of = holder, hash = hash).all { it })
        assertEquals("one copy over the link: ${holder.linkFiles}", 1, holder.linkFiles.count { hash in it && asker.nodeId in it })
        assertEquals("a copy over the radio: ${holder.transport.files}", 0, holder.transport.files.count { hash in it })
    }

    // The asks of [of] for [hash] the asker's blob exchange logged, each as whether it said fast-only
    // (`LinkDownloadLimitLabTest.asksLogged`).
    private fun asksLogged(
        of: LabNode,
        hash: String,
    ): List<Boolean> =
        ShadowLog
            .getLogs()
            .map { it.msg }
            .filter { m ->
                ASK_LINES.any { m.startsWith(it) } && "${hash.take(HASH_CHARS)}…" in m && "→ ${of.nodeId}" in m
            }.map { "[fast-only]" in it }

    private companion object {
        // Over the composite's 128 KiB arm gate and under the receiver's 1 MB download limit: a plain want the sender's
        // free link carries.
        const val BULK_BYTES = 300_000

        // Under the arm gate: the radio's.
        const val SMALL_BYTES = 60_000

        // Over the receiver's 1 MB limit as shipped: a named file declares its size, so the want is fast-only.
        const val OVER_LIMIT_BYTES = 1_200_000

        // What a first ask's own grace has left after the scenario's jump: well short of a holder's 10 s link wait, so an
        // ask without the link's grace on top asks another holder before the holder's copy starts; with it, 16 s, well
        // past.
        const val OWN_GRACE_LEFT_MS = 6_000L

        const val HASH_CHARS = 8
        val ASK_LINES = listOf("blob ask ", "blob fetch ", "blob pull ")
    }
}
