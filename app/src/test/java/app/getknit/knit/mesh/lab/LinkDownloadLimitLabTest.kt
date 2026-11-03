package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.inet.FakeLinkNet.Nat
import app.getknit.knit.mesh.inet.LinkDemandPolicy
import app.getknit.knit.mesh.inet.LinkWhy
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.SpoolSigInfo
import app.getknit.knit.mesh.spool.SpoolTicket
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
import kotlin.random.Random

/**
 * The person's "Ask before large Bluetooth downloads" limit over a contact's direct link (ADR 2026-10.js2p, ADR
 * 2026-10.a5yj's 2026-10-31 amendment, `docs/DIRECT_LINKS.md` L-7-9 and L-7-10): a link is a fast route for a download
 * over the limit while the receiver's network is unmetered, and on a metered one the tap still decides. The links are
 * made as `InternetLinkLabTest` makes them, over the lab's fake NATs and a relay that carries their signals; the relay
 * takes no attachments, so the link is the file's only way across.
 */
@RunWith(RobolectricTestRunner::class)
class LinkDownloadLimitLabTest {
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

    // A relay that carries sigs and frames but takes attachments only from a files ticket, which nobody holds
    // (`InternetLinkLabTest.filesGatedRelay`).
    private fun filesGatedRelay() =
        FakeSpool(
            sig = SpoolSigInfo(maxBytes = 8_192, rate = 2),
            stun = listOf(LabInternet.STUN_URL),
            ent = true,
            entGates = SpoolTicket.FL_FILES,
        )

    // Alice and Bob on [spool] and the lab's Internet behind port-restricted NATs, contacts with a confirmed session each
    // way and a DM scope converged at the relay: what a link's offer needs (`InternetLinkLabTest.contacts`).
    private suspend fun contacts(spool: FakeSpool): Pair<LabNode, LabNode> {
        // Board-less in a boards sweep too, as in `InternetLinkLabTest.contacts`: the shared air would join the two islands.
        val alice = lab.node("alice", spool = spool, internet = Nat.PORT_RESTRICTED, board = false).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool, internet = Nat.PORT_RESTRICTED, board = false).apply { setDisplayName("Bob") }
        lab.meetOnTheRelay(alice, bob)
        return alice to bob
    }

    // Both ends' link planes hold the link, and both managers have acted on it (`InternetLinkLabTest.awaitLinked`).
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
        // The link-up's presence-key pass (a short wait, then maybe a ctl 11 across the link, which is a use of it), best
        // effort: the manager schedules it after it marks the links acted on, so a poll in between passes early; the idle
        // close's loop is what absorbs a ctl 11 that lands later.
        a.awaitPresencePasses()
        b.awaitPresencePasses()
    }

    /**
     * A file over the receiver's "Ask before large Bluetooth downloads" limit is a fast-only want (ADR 2026-10.js2p), and a
     * contact's direct link on an unmetered network is a fast route for it (a5yj's fast-route amendment). No relay takes
     * Alice's file, so her demand offers Bob a link at once; Bob, on an unmetered network, asks for it over the link
     * fast-only and it crosses once, with no tap.
     */
    @Test
    fun aFileOverTheDownloadLimitCrossesAnUnmeteredLinkWithNoTap() =
        runBlocking {
            val (alice, bob) = contacts(filesGatedRelay())
            val (id, hash) = fileOverTheLimit(alice, bob, "the survey", seed = 2_033)

            checkNotNull(alice.demand).round()
            awaitLinked(alice, bob)
            assertTrue(
                "bob never got the file over the link\n${alice.linkLog}\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals("bob tapped", false, bob.row(id)?.attachmentFetchApproved)
            // The ask's line is logged after its send returns, so the file can land first: wait for the line.
            lab.await(1) { if (asksLogged(of = alice, hash = hash).isNotEmpty()) 1 else 0 }
            val asks = asksLogged(of = alice, hash = hash)
            assertTrue("bob's asks were not all fast-only: $asks", asks.all { it })
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * On a metered network the tap still decides. The same file and the same link, but Bob's network is metered: his want,
     * stepped with the link in view, holds and asks nothing, so nothing crosses. He taps, the want turns plain, and the
     * file crosses the link that is still up, once.
     */
    @Test
    fun aFileOverTheDownloadLimitWaitsForTheTapOnAMeteredNetworkThenCrossesTheLink() =
        runBlocking {
            val (alice, bob) = contacts(filesGatedRelay())
            bob.metered = true
            val (id, hash) = fileOverTheLimit(alice, bob, "the metered survey", seed = 2_034)

            checkNotNull(alice.demand).round()
            awaitLinked(alice, bob)
            awaitHeldWithTheLinkUp(bob)
            assertTrue("bob asked before the tap: ${asksLogged(of = alice, hash = hash)}", asksLogged(of = alice, hash = hash).isEmpty())
            assertEquals("a held want called for a link", null, bob.manager.attachmentWantedSince(hash))
            assertEquals("a copy crossed before the tap: ${alice.linkFiles}", 0, alice.linkFiles.count { hash in it })

            bob.manager.downloadAttachment(hash)
            lab.await(1) { if (bob.row(id)?.attachmentFetchApproved == true) 1 else 0 }
            assertTrue(
                "bob never got the file after the tap\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            lab.await(1) { if (asksLogged(of = alice, hash = hash).isNotEmpty()) 1 else 0 }
            val asks = asksLogged(of = alice, hash = hash)
            assertTrue("the tap's ask was fast-only: $asks", asks.none { it })
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A link that came and went while the want was held is no reason never to ask (`MeshLinkPending`, `docs/DIRECT_LINKS.md`
     * L-7-9). Bob's network is metered when Alice's link comes up for her file, so it carries nothing for it, and it idles
     * out. Bob's network then turns unmetered: his want is one a link can carry from that step on, so a minute later his own
     * demand offers Alice a link, though one was up after the message arrived, and the file crosses it with no tap.
     */
    @Test
    fun aWantHeldWhileMeteredRaisesItsOwnLinkOnceTheNetworkIsUnmetered() =
        runBlocking {
            val (alice, bob) = contacts(filesGatedRelay())
            val bobDemand = checkNotNull(bob.demand)
            bob.metered = true
            val (id, hash) = fileOverTheLimit(alice, bob, "held while metered", seed = 2_035)

            checkNotNull(alice.demand).round()
            awaitLinked(alice, bob)
            awaitHeldWithTheLinkUp(bob)
            awaitIdledOut(alice, bob)
            assertEquals("a copy crossed while metered: ${alice.linkFiles}", 0, alice.linkFiles.count { hash in it })

            // Some time after the link went, the network turns unmetered: the lab's clock stands still unless moved, and a
            // step in the very instant the link ended would date the want to its end, which no row newer than it matches.
            lab.clock.advance(UNMETERED_AFTER_MS)
            bob.metered = false
            val unmeteredAt = bob.now()
            // Nothing links Bob now, so no blob step runs: his demand's own read is the first look at the unmetered
            // network, and dates the want a link can carry from then. A minute on, it is due.
            bobDemand.round()
            // The round's offer, were it made, would be launched and logged later: pin what it decided on instead. The
            // read below finds the network as the round did, so it returns the time the round set: not before the
            // network turned unmetered, so its minute has not run.
            val since = checkNotNull(bob.manager.attachmentWantedSince(hash)) { "the unmetered want is not linkable" }
            assertTrue("the want is dated before its first unmetered look: $since < $unmeteredAt", since >= unmeteredAt)
            lab.clock.advance(LinkDemandPolicy.WANT_WAIT_MS)
            bobDemand.round()
            awaitLinked(bob, alice)
            assertTrue(
                "bob never got the file once unmetered\n${bob.linkLog}\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1) { if (bob.holdsBlob(hash)) 1 else 0 },
            )
            assertEquals(LinkWhy.WANT, checkNotNull(bob.links).lastTries()[alice.nodeId]?.why)
            assertEquals("bob tapped", false, bob.row(id)?.attachmentFetchApproved)
            assertEquals("one copy over the link: ${alice.linkFiles}", 1, alice.linkFiles.count { hash in it && bob.nodeId in it })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    // Alice sends Bob a named file of [FILE_OVER_LIMIT_BYTES], which declares its size, over the receiver's 1 MB limit as
    // shipped, and Bob holds its message. Returns the message id and the file's hash.
    private suspend fun fileOverTheLimit(
        alice: LabNode,
        bob: LabNode,
        caption: String,
        seed: Int,
    ): Pair<String, String> {
        val bytes = Random(seed).nextBytes(FILE_OVER_LIMIT_BYTES)
        assertTrue(alice.sendFile(bytes, "survey.zip", "application/zip", text = caption, to = bob))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).count { it.second == caption } }
        val id = alice.ownMessageId(alice.dmWith(bob), caption)
        return id to checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
    }

    // [node]'s fast-only want has been stepped and held since its link came up: a re-arm, run after the link is up, holds
    // it once more. A hold is counted, never logged twice in a row, so the count is the oracle.
    private suspend fun awaitHeldWithTheLinkUp(node: LabNode) {
        val heldBefore = node.metrics.files().blobFastOnlyHeld
        node.reoffer()
        lab.await(1) { if (node.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }
    }

    // The link between [a] and [b], with nothing to carry, idles out on the link's clock with nothing but the late uses
    // `awaitLinkIdledOut` allows, and both planes and managers let it go.
    private suspend fun awaitIdledOut(
        a: LabNode,
        b: LabNode,
    ) {
        lab.awaitLinkIdledOut(a, b)
        lab.await(1) { if (!a.linkPlaneHolds(b) && !b.linkPlaneHolds(a)) 1 else 0 }
        a.awaitLinksActedOn()
        b.awaitLinksActedOn()
    }

    // The asks of [of] for [hash] that the asker's blob exchange logged (`blob ask|fetch|pull <hash>… → <of> …`), each as
    // whether it said fast-only. Only the receiver asks in these scenarios, so the line names whose ask it was.
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
        // A named file over the receiver's "Ask before large Bluetooth downloads" limit as shipped (1 MB), so its sender
        // declares its size and the receiver's want is fast-only (ADR 2026-10.js2p).
        const val FILE_OVER_LIMIT_BYTES = 1_200_000

        // How long after a held want's link idled out the receiver's network turns unmetered.
        const val UNMETERED_AFTER_MS = 1_000L

        // The hash prefix `BlobExchange`'s log lines name.
        const val HASH_CHARS = 8

        // How the blob exchange's ask lines start (`BlobExchange.askLine`): a holder's first ask, a widened one, a fetch of
        // one that answered, a pull.
        val ASK_LINES = listOf("blob ask ", "blob fetch ", "blob pull ")
    }
}
