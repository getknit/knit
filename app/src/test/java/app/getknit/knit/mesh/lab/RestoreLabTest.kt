package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.ResetReseal
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A phone that comes back from a backup (`data/backup`, ADR: backup and restore). [LabNode.restoreFromBackup]
 * is the state the restore leaves — identity, contacts and history intact, custody and both ratchets
 * emptied, the restore mark set — and what these cases pin is the mesh's side of it: `MeshManager.finishRestore`
 * runs once, the peers it resets re-seal what the restored node never acked, and the conversation carries
 * on under a new session without anybody typing anything. The re-seal behind a relay's dedup window is the same for a
 * reset forced with no restore, so that case lives here too.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreLabTest {
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
     * Alice restores while Bob's two DMs to her sit in his custody, sealed under the session she no longer
     * has. Her reset is what makes him re-seal them; without it they would drop as `RATCHET_NO_SESSION` on
     * every re-serve until his own heuristic fired hours later.
     */
    @Test
    fun aRestoredNodeGetsWhatWasSentWhileItWasAwayAndCarriesOnUnderANewSession() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "before"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "before too"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val root = alice.session(bob)?.rootHash
            val versionBefore = alice.settings.profileVersion.first()

            alice.restoreFromBackup()
            // The mesh still runs on Bob's side while Alice is down; two more DMs seal under the old session.
            assertTrue(bob.sendDm(alice, "while you were away"))
            assertTrue(bob.sendDm(alice, "and again"))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            assertNotEquals("a new session, not the old one", root, alice.session(bob)?.rootHash)
            assertTrue("the restore's profile bump", alice.settings.profileVersion.first() > versionBefore)
            assertEquals("the hooks ran once", false, alice.settings.restorePending.first())

            assertTrue(alice.sendDm(bob, "after"))
            assertTrue(bob.sendDm(alice, "after too"))
            lab.assertConverged(listOf(alice, bob), atLeast = 6) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * #131: a file Bob sends while Alice is away is sealed under the session her restore wiped, so the first copy
     * she can open is the one Bob re-seals on her reset. That re-seal used to rebuild the content without the
     * file's name and size, and the file landed as a nameless attachment whose bubble never drew. Alice's row
     * carrying both is the proof the re-sealed copy carried them.
     */
    @Test
    fun aFileSentWhileARestoredNodeWasAwayKeepsItsNameThroughTheReseal() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "before"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }

            alice.restoreFromBackup()
            val report = "%PDF-1.7 quarterly figures".toByteArray()
            assertTrue(bob.sendFile(report, "quarterly-report.pdf", "application/pdf", text = "the report", to = alice))
            val id = bob.ownMessageId(bob.dmWith(alice), "the report")

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val row = checkNotNull(alice.row(id)) { "alice holds the file DM" }
            assertEquals("the re-sealed copy named the file", "quarterly-report.pdf", row.attachmentName)
            assertEquals("and gave its size", report.size.toLong(), row.attachmentSize)
            assertEquals("application/pdf", row.attachmentMime)
        }

    /**
     * #291: a re-arm that read Alice's custody while Bob's original file DM sat there unopened, and no row named its
     * hash, holds the hash fast-only (`MeshManager.unopenedForUs`). Bob's re-seal lands while that re-arm waits between
     * its reads and its apply: the row is written and the small file wanted plain, its query on its way to Bob. The
     * re-arm's stale verdict once re-held it — with no Wi-Fi Aware holder in reach nothing was asked, and with no tick in
     * the lab the file never came. A want the delivery made after the re-arm's mark keeps its verdict.
     */
    @Test
    fun aReArmThatReadTheUnopenedFrameNeverReHoldsTheResealsPlainWant() =
        runBlocking {
            // Alice queries before she asks (ADR 2026-10.6e5t): her plain want's first step is a query, so nothing is asked
            // before the re-arm applies, and what the answer then finds is the want as the re-arm left it. No boards, whatever
            // the run: a compact DM is sealed to Alice's identity, not the session her restore wiped, so it opens at once
            // and writes the row before the re-arm can read the unopened signed copy alone.
            val alice = lab.node("alice", LabLimits(blobQuery = true), board = false).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", board = false).apply { setDisplayName("Bob") }
            val thread = { node: LabNode -> node.dmWith(if (node === alice) bob else alice) }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "before")) // Alice wrote in the DM: Bob's file is no message request of hers
            lab.assertConverged(listOf(alice, bob), atLeast = 1, conversation = thread)

            alice.restoreFromBackup()
            val report = "%PDF-1.7 the figures, well under the Bluetooth limit".toByteArray()
            assertTrue(bob.sendFile(report, "figures.pdf", "application/pdf", text = "the figures", to = alice))
            val id = bob.ownMessageId(bob.dmWith(alice), "the figures")
            val hash = checkNotNull(bob.row(id)?.attachmentHash) { "bob's file DM names its sealed blob" }

            // Alice's frames to Bob wait — her reset among them — so the digest exchange (which no hold parks) has Bob serve
            // her the original he sealed under the session she lost, and he cannot re-seal it yet.
            lab.link(alice, bob) { alice.transport.hold(bob.transport) }
            lab.await(1) { if (id in alice.custodyIds()) 1 else 0 }
            alice.transport.awaitInboundDrained()
            assertNull("the original did not open", alice.row(id))
            // And her reset is parked: the release below must carry it, or Bob never re-seals (the restore's hooks send it
            // on their own coroutine, so it can park after the original lands).
            lab.await(1) { alice.heldDmHeaders(alice, bob).count { (_, r) -> r.flags and RatchetHeader.FLAG_RESET != 0 } }
            // Every re-arm already queued ends before this one, on the re-arm lock: the one parked below read this state.
            alice.reoffer()

            val parked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            alice.manager.beforeRewantApplies = {
                alice.manager.beforeRewantApplies = null
                parked.complete(Unit)
                release.await()
            }
            coroutineScope {
                val tick = launch { alice.reoffer() } // reads the unopened frame and no row, then parks under the lock
                parked.await()
                // The reset crosses; Bob's re-seal opens, the row is written and wanted plain: its query to Bob parks.
                alice.transport.release(bob.transport, keepHolding = true)
                lab.await(1) { if (alice.row(id) != null && alice.queriesHeldFor(bob, hash)) 1 else 0 }
                release.complete(Unit) // the re-arm applies what it read before the row
                tick.join()
            }
            alice.transport.release(bob.transport)

            lab.assertConverged(listOf(alice, bob), atLeast = 2, conversation = thread)
            val asks = alice.transport.blobAsks.filter { it.hash == hash }
            assertEquals("asked once, plain: $asks", 1, asks.count { !it.fastOnly })
            assertEquals("one copy", 1, bob.transport.files.count { it == "${alice.nodeId} ATTACHMENT $hash" })
        }

    /** Whether this node's pipe to [to] holds a `blobhave` naming [hash]: a plain want's query, parked on a held pipe. */
    private fun LabNode.queriesHeldFor(
        to: LabNode,
        hash: String,
    ): Boolean =
        transport.held(to.transport).any { wire ->
            val env = WireCodec.decodeEnvelope(wire.signed)
            env?.type == FrameType.BLOB_HAVE && WireCodec.decodePayload<BlobHaveContent>(env.payload)?.hashes?.contains(hash) == true
        }

    /**
     * #171: Bob is two hops from Alice, behind Carol, and restores while Alice's three DMs to him cross Carol live
     * under the session the restore wipes. His reset reaches Alice through Carol and she re-seals all three at once —
     * under their original ids, which Carol first saw moments ago, so her router drops every re-seal as a duplicate
     * and her custody (and Bob's, after the digest exchange) keeps only the originals Bob cannot open. What gets
     * them to him is the second pass, past Carol's ten-minute window (ADR 2026-10.dmgw).
     */
    @Test
    fun aRestoredNodeBehindARelayGetsWhatCrossedItInsideTheDedupWindow() =
        runBlocking {
            // No board on bob, whatever the run: the routers' dedup window over the relay is the subject, and a compact
            // copy of the three would reach him around it.
            val (alice, bob, carol) = relayedPair(bobBoard = false)
            bob.restoreFromBackup()
            val sent = sendThreeWhileBobIsAway(alice, bob, carol)

            lab.link(carol, bob)
            // Bob's reset reaches Alice through Carol, and her first re-seal of each of the three crosses to Carol — a
            // second copy of each id on the pipe, since a re-seal has its own signature — and Carol's router has
            // handled it: dropped, for Carol saw every one of these ids inside her window.
            lab.await(sent.size) { sent.count { id -> alice.copiesTo(carol, id) >= 2 } }
            carol.transport.awaitInboundDrained()
            // Bob's failures to open the originals Carol served him at the link-up are not waited on: the restore's reset
            // waits on none of them, so that serve can still sit in his queue at the jump. Handled after it, a failure
            // reopens the id under a guard that keeps it unmarked until a copy opens (#347), so neither Carol's next
            // re-serve of an original nor the order of the two costs Bob the second pass.

            // The routers' window runs out; the 60 s tick (driven here, the lab clock moves no scheduler) re-seals the
            // three once more, and Carol now relays them.
            lab.clock.advance(ResetReseal.SECOND_PASS_MS)
            alice.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * #347: the case above, with one more copy of each original reaching Bob between the two passes. Bob's custody
     * loses the three (`forgetCustody`, as a quota eviction would), and about three minutes after Alice's first pass his
     * link to Carol drops and comes back, so Carol answers his digest with the three originals again. Before the reopen
     * guard each such copy was admitted as new, marked, failed again with its own reopen refused, and its mark still
     * stood eight minutes later, when the second pass reached Bob's router: dropped as seen, and the DMs never shown.
     * Now a reopened id stays unmarked until a copy of it opens ([app.getknit.knit.mesh.SeenSet.guardFor]).
     */
    @Test
    fun aCopyOfTheOriginalBetweenThePassesNeverCostsTheRestoredNodeTheSecondPass() =
        runBlocking {
            val (alice, bob, carol) = relayedPair(bobBoard = false)
            val thread = { node: LabNode -> node.dmWith(if (node === alice) bob else alice) }
            bob.restoreFromBackup()
            val sent = sendThreeWhileBobIsAway(alice, bob, carol)

            lab.link(carol, bob)
            // As above: Alice's first re-seal of each reaches Carol, and Carol's router drops it as an id seen inside its
            // window. Bob has failed the originals Carol served him at the link-up.
            lab.await(sent.size) { sent.count { id -> alice.copiesTo(carol, id) >= 2 } }
            val firstPass = lab.clock.now()
            carol.transport.awaitInboundDrained()
            lab.await(sent.size) { sent.count { id -> carol.copiesTo(bob, id) >= 1 } }
            bob.transport.awaitInboundDrained()

            // Bob's custody loses the three, and his link to Carol drops and comes back three minutes on: Carol's
            // answer to his digest carries each original again, a second copy of each on that pipe.
            bob.forgetCustody { it in sent }
            lab.clock.advance(RELINK_AFTER_FIRST_PASS_MS)
            lab.unlink(carol, bob)
            lab.link(carol, bob)
            lab.await(sent.size) { sent.count { id -> carol.copiesTo(bob, id) >= 2 } }
            bob.transport.awaitInboundDrained()
            assertTrue("bob still holds none of the three", sent.none { bob.row(it) != null })
            // Each copy met its id's guard: delivered again (and failed again) without taking the mark.
            val guarded = bob.metrics.frames().framesReopenedDelivered
            assertTrue("bob's router delivered $guarded copies under a guard, not one per original", guarded >= sent.size)

            // Alice's second pass is due; Carol's window has run out, Bob's must not have restarted at the relink.
            lab.clock.advance(firstPass + ResetReseal.SECOND_PASS_MS - lab.clock.now())
            alice.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(carol), conversation = thread)
        }

    /**
     * The twin of the case above that worked before #347: Bob's relink to Carol comes after Alice's second pass has
     * opened the three. His custody loses them again, so Carol serves her originals, and each meets an id the open
     * settled: a duplicate, never a delivery under a guard. Past the window a relink carries them into his custody.
     */
    @Test
    fun aCopyOfTheOriginalAfterTheSecondPassChangesNothing() =
        runBlocking {
            val (alice, bob, carol) = relayedPair(bobBoard = false)
            val thread = { node: LabNode -> node.dmWith(if (node === alice) bob else alice) }
            bob.restoreFromBackup()
            val sent = sendThreeWhileBobIsAway(alice, bob, carol)

            lab.link(carol, bob)
            lab.await(sent.size) { sent.count { id -> alice.copiesTo(carol, id) >= 2 } }
            val firstPass = lab.clock.now()
            carol.transport.awaitInboundDrained()
            lab.await(sent.size) { sent.count { id -> carol.copiesTo(bob, id) >= 1 } }
            bob.transport.awaitInboundDrained()
            bob.forgetCustody { it in sent }

            lab.clock.advance(firstPass + ResetReseal.SECOND_PASS_MS - lab.clock.now())
            alice.reoffer()
            lab.await(sent.size) { sent.count { bob.row(it) != null } }
            bob.transport.awaitInboundDrained()

            bob.forgetCustody { it in sent }
            val copiesBefore = sent.associateWith { carol.copiesTo(bob, it) }
            val guardedBefore = bob.metrics.frames().framesReopenedDelivered
            lab.unlink(carol, bob)
            lab.link(carol, bob)
            lab.await(sent.size) { sent.count { id -> carol.copiesTo(bob, id) > copiesBefore.getValue(id) } }
            bob.transport.awaitInboundDrained()
            // A delivered copy goes into custody and a deduped one never does, so this reads each id's own fate.
            assertTrue("carol's originals met settled ids", sent.none { it in bob.custodyIds() })
            assertEquals("none under a guard", guardedBefore, bob.metrics.frames().framesReopenedDelivered)

            // Bob's window on the three runs out (the link cut first, so no copy lands across the jump); a relink's
            // exchange carries them back into his custody.
            lab.unlink(carol, bob)
            lab.clock.advance(SeenSet.DEFAULT_TTL_MS)
            lab.link(carol, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(carol), conversation = thread)
        }

    /**
     * #347, two genuine seals of one DM in two custodies. Carol holds Alice's original (X0); Dave holds the re-seal Alice
     * made for an earlier reset of Bob's (X1), which Bob never received. Bob restores, so neither opens. He meets Carol
     * first, then Dave, before Alice's first pass for the restore, which Carol drops as an id seen inside her window. X1
     * is a valid signature of Alice's that does not open: it is delivered under the guard and leaves the id unmarked, so
     * the second pass opens.
     */
    @Test
    fun twoStaleSealsOfOneDmFromTwoCustodiansBeforeTheFirstPassStillLetTheSecondPassOpen() =
        runBlocking { twoCustodians(staleResealAfterTheFirstPass = false) }

    /**
     * The case above with Dave's stale re-seal reaching Bob three minutes after Alice's first pass. Before the guard it was
     * admitted as new, took the mark with its reopen refused, and dropped the second pass eight minutes on as seen.
     */
    @Test
    fun aStaleResealFromASecondCustodianAfterTheFirstPassStillLetsTheSecondPassOpen() =
        runBlocking { twoCustodians(staleResealAfterTheFirstPass = true) }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // one scenario, two orders: the setup and its awaits are most of it
    private suspend fun twoCustodians(staleResealAfterTheFirstPass: Boolean) {
        // No boards: the radios between the four are the subject. A short flagged-reset floor, so Alice takes the restore's
        // reset a moment after the earlier one (it runs on the wall clock, and the wait for it below is on that clock).
        val limits = LabLimits(resetFloorMs = RESET_FLOOR_MS)
        val alice = lab.node("alice", limits, board = false).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", limits, board = false).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", limits, board = false).apply { setDisplayName("Carol") }
        val dave = lab.node("dave", limits, board = false).apply { setDisplayName("Dave") }
        val thread = { node: LabNode -> node.dmWith(if (node === alice) bob else alice) }
        lab.linkAll(alice to carol, carol to bob, alice to dave, dave to bob)
        lab.awaitAcquainted(alice, bob, carol, dave)
        assertTrue(alice.sendDm(bob, "before"))
        lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
        assertTrue(bob.sendDm(alice, "before too"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol, dave), conversation = thread)
        lab.unlink(dave, bob)
        lab.unlink(carol, bob)

        // X0, under the session Bob holds now: Carol custodies it, and the copy toward Dave is lost on the way.
        alice.transport.hold(dave.transport)
        assertTrue(alice.sendDm(bob, "sealed twice"))
        val dm = alice.ownMessageId(alice.dmWith(bob), "sealed twice")
        lab.await(1) { if (dm in carol.custodyIds()) 1 else 0 }
        lab.await(1) { alice.transport.held(dave.transport).count { frameId(it) == dm } }
        val original =
            alice.transport
                .held(dave.transport)
                .first { frameId(it) == dm }
                .sig
        alice.transport.lossy(dave.transport) { frameId(it) == dm && it.sig.contentEquals(original) }
        // lab-lint: allow filtered-release-then-oracle — the copy is lost on purpose; Dave's copy of the DM is X1 below.
        alice.transport.release(dave.transport) { batch -> batch.filterNot { frameId(it) == dm && it.sig.contentEquals(original) } }

        // X1: an earlier reset of Bob's reaches Alice through Carol, whose way back to Bob is held, and Alice re-seals the
        // DM under it. Carol drops that re-seal as an id seen inside her window; Dave, who never saw the id, keeps it.
        val rootBefore = alice.session(bob)?.rootHash
        assertNull("bob's earlier reset went out", bob.resetSession(alice))
        lab.link(carol, bob) { carol.transport.hold(bob.transport) }
        lab.await(1) { if (alice.session(bob)?.rootHash != rootBefore) 1 else 0 }
        val earlierResetAdoptedBy = System.currentTimeMillis()
        lab.await(1) { if (dm in dave.custodyIds()) 1 else 0 }
        assertTrue(
            "dave holds a seal of the DM carol does not",
            dave.custodiedSeal(dm)?.ct?.contentEquals(carol.custodiedSeal(dm)?.ct) == false,
        )
        lab.unlink(carol, bob) // what Carol held for Bob, the re-seal's answer among it, is lost
        lab.unlink(alice, dave)

        // Bob restores: neither seal opens now. Alice's floor since the earlier reset runs out on the wall clock.
        bob.restoreFromBackup()
        lab.await(1) { if (System.currentTimeMillis() - earlierResetAdoptedBy >= RESET_FLOOR_MS) 1 else 0 }

        // Bob meets Carol, whose way on to Alice is held so the restore's reset waits: she serves X0, which fails and
        // reopens the id under a guard.
        lab.link(carol, bob) { carol.transport.hold(alice.transport) }
        lab.await(1) { if (dm in bob.custodyIds()) 1 else 0 }
        bob.transport.awaitInboundDrained()
        assertNull("X0 did not open", bob.row(dm))

        // Dave serves X1 (Bob's custody lost X0, so his digest asks for it again).
        val meetDave: suspend () -> Unit = {
            bob.forgetCustody { it == dm }
            lab.link(dave, bob)
            lab.await(1) { if (dm in bob.custodyIds()) 1 else 0 }
            bob.transport.awaitInboundDrained()
            assertNull("X1 did not open", bob.row(dm))
        }
        if (!staleResealAfterTheFirstPass) meetDave()

        // The restore's reset crosses to Alice; her first pass reaches Carol, who drops it as an id seen inside her window.
        val copiesBefore = alice.copiesTo(carol, dm)
        carol.transport.release(alice.transport)
        lab.await(1) { if (alice.copiesTo(carol, dm) > copiesBefore) 1 else 0 }
        val firstPass = lab.clock.now()
        carol.transport.awaitInboundDrained()
        if (staleResealAfterTheFirstPass) {
            lab.clock.advance(RELINK_AFTER_FIRST_PASS_MS)
            meetDave()
        }
        assertTrue("X1 reached bob's router under the guard", bob.metrics.frames().framesReopenedDelivered >= 1)

        lab.clock.advance(firstPass + ResetReseal.SECOND_PASS_MS - lab.clock.now())
        alice.reoffer()
        lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = listOf(carol), conversation = thread)
    }

    /**
     * The twin of the case above that worked before #171: Bob comes back after Carol's window has run out, so the
     * reset's first re-seal already crosses her.
     */
    @Test
    fun aRestoredNodeBehindARelayGetsWhatCrossedItBeforeTheDedupWindow() =
        runBlocking {
            val (alice, bob, carol) = relayedPair()
            bob.restoreFromBackup()
            sendThreeWhileBobIsAway(alice, bob, carol)

            lab.clock.advance(ResetReseal.SECOND_PASS_MS)
            lab.link(carol, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A reset's recovery behind a relay, with nothing else ever sent under the DM's id. On a line Alice–Carol–Bob, Bob's
     * DM crosses Carol and is lost on her way to Alice, every copy of it (her custody's re-serves included). Alice resets.
     * Bob takes the reset and re-seals the DM under it at once, under the same id, so Carol's router drops that re-seal as
     * an id seen inside its window. Past the window the second pass (`ResetReseal.SECOND_PASS_MS`, the 60 s tick driven
     * by hand) re-seals it again, Carol relays it, and Alice ends with the DM.
     */
    @Test
    fun aDmLostBehindARelayReachesTheNodeThatResetOnTheSecondPass() =
        runBlocking {
            // No boards: the relay is the only way between Alice and Bob.
            val alice = lab.node("alice", board = false).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", board = false).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", board = false).apply { setDisplayName("Carol") }
            val thread = { node: LabNode -> node.dmWith(if (node === alice) bob else alice) }
            lab.linkAll(alice to carol, carol to bob)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol), conversation = thread)

            carol.transport.hold(alice.transport)
            assertTrue(bob.sendDm(alice, "lost on the way"))
            val dm = bob.ownMessageId(bob.dmWith(alice), "lost on the way")
            lab.await(1) { carol.transport.held(alice.transport).count { frame -> frameId(frame) == dm } }
            val original =
                carol.transport
                    .held(alice.transport)
                    .first { frame -> frameId(frame) == dm }
                    .signed
            carol.transport.lossy(alice.transport) { frame -> frame.signed.contentEquals(original) }
            // lab-lint: allow filtered-release-then-oracle — the DM comes back as its second-pass re-seal, driven by
            // `bob.reoffer()` below.
            carol.transport.release(alice.transport) { batch -> batch.filterNot { frame -> frame.signed.contentEquals(original) } }

            val dedupedBefore =
                carol.metrics
                    .snapshot()
                    .frames.framesDeduped
            assertNull("alice's reset went out", alice.resetSession(bob))
            // Bob's first re-seal under the reset reaches Carol, a second copy of the id on that pipe, and her router has
            // handled it inside her window: as a copy of an id she has seen, not a frame to relay.
            lab.await(1) { if (bob.copiesTo(carol, dm) >= 2) 1 else 0 }
            carol.transport.awaitInboundDrained()
            assertTrue(
                "carol's router took the first re-seal as a copy of an id it had seen",
                carol.metrics
                    .snapshot()
                    .frames.framesDeduped > dedupedBefore,
            )
            val early = alice.decrypted(thread(alice)).map { (_, body) -> body }
            assertTrue("alice holds the DM before the second pass: $early", "lost on the way" !in early)

            lab.clock.advance(ResetReseal.SECOND_PASS_MS)
            bob.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = listOf(carol), conversation = thread)
            assertEquals("bob never reset back", 0L, bob.session(alice)?.lastResetSentAt)
        }

    /**
     * #189 through custody, board-less (ADR 2026-10.4mjm, rule C). Alice and Bob each write first while Carol, between
     * them, holds both ways; the race's winner (the smaller id) reads the loser's init first, so its intro answer is
     * sealed on its own unanswered init and Carol custodies it. The pair converges on the winner's root. Past the skew
     * bound the loser restores; its reset crosses Carol to the winner, which takes it and answers — and Carol's way back
     * to the restored node is held until it carries both the old intro and that answer, then lets the intro through
     * first. The intro wins `resolveRace` against the restored node's unanswered reset, and the answer under the reset
     * moves it back: the reset was its own init, lost to a race, and the winner sealed under it as responder.
     */
    @Test
    fun aRestoredRaceLoserWhoMeetsTheWinnersOldInitFirstMovesBackOntoItsReset() =
        runBlocking {
            val (winner, loser, carol) = racedThroughCarol()
            val thread = { node: LabNode -> node.dmWith(if (node === winner) loser else winner) }
            lab.clock.advance(Protocol.MAX_FUTURE_SKEW_MS + 60_000L)

            loser.restoreFromBackup()
            val restoredAt = lab.clock.now()
            lab.link(carol, loser) { carol.transport.hold(loser.transport) }
            lab.await(1) { if (carol.holdsOldInitCtlAndAnswer(winner, loser, restoredAt)) 1 else 0 }
            carol.releaseInitsFirst(winner, loser)

            lab.assertConverged(listOf(winner, loser), atLeast = 2, carriers = listOf(carol), conversation = thread)
            assertEquals("one root", winner.session(loser)?.rootHash, loser.session(winner)?.rootHash)
            assertTrue("the restored node is its reset's initiator", loser.session(winner)?.weAreInitiator == true)
            assertTrue("the swap ran", loser.metrics.keys().ratchetSwappedBack >= 1)
            assertEquals("no reset sent back", 0L, winner.session(loser)?.lastResetSentAt)

            assertTrue(winner.sendDm(loser, "after the restore"))
            assertTrue(loser.sendDm(winner, "seen"))
            lab.assertConverged(listOf(winner, loser), atLeast = 4, carriers = listOf(carol), conversation = thread)
        }

    /**
     * Rule C's freshness half (design-pass finding 1). The smaller id answers the larger's first contact — sealed under
     * the larger's init, as its responder — and Carol custodies that answer while her way to the larger is held; then
     * the smaller restores. Its reset reaches the larger directly and wins the race against the larger's still
     * unanswered init, so the larger's `prevRoot` is its own init, lost to a race. Carol's re-serve of the old answer
     * comes after: responder-sealed under that very init, but before the reset it lost to — the peer left that root by
     * restoring. Moving back would leave the pair on two roots; it must not.
     */
    @Test
    fun anAnswerSealedBeforeTheRestoreThatWonTheRaceNeverMovesTheLoserBack() =
        runBlocking {
            val one = lab.node("alice", board = false).apply { setDisplayName("Alice") }
            val two = lab.node("bob", board = false).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", board = false).apply { setDisplayName("Carol") }
            val (smaller, larger) = if (one.nodeId < two.nodeId) one to two else two to one
            val thread = { node: LabNode -> node.dmWith(if (node === smaller) larger else smaller) }
            lab.linkAll(smaller to larger, smaller to carol, larger to carol)
            lab.awaitAcquainted(smaller, larger, carol)
            lab.unlink(smaller, larger)
            carol.transport.hold(larger.transport)

            assertTrue(larger.sendDm(smaller, "first from the larger id"))
            lab.await(1) { smaller.decrypted(smaller.dmWith(larger)).size }
            // The smaller's answers under the larger's init — its ✓✓, its intro — reach Carol and go no further.
            lab.await(1) { carol.ctlsFromTo(smaller, larger).size }
            val before = carol.ctlsFromTo(smaller, larger)
            lab.unlink(carol, larger)
            lab.clock.advance(Protocol.MAX_FUTURE_SKEW_MS + 60_000L)

            smaller.restoreFromBackup()
            lab.link(smaller, larger)
            lab.await(1) { if (larger.session(smaller)?.let { it.confirmed && !it.weAreInitiator } == true) 1 else 0 }
            lab.await(1) { if (smaller.session(larger)?.confirmed == true) 1 else 0 }

            // The setup this case is about: the larger lost the race on its own init, so it holds the flag — a plain
            // replacement here would pass the oracle below without ever meeting rule C's freshness half.
            assertTrue("the larger's prevRoot is its own init, lost to a race", larger.session(smaller)?.prevRootOwnInitAt != null)
            lab.link(carol, larger)
            lab.await(before.size) { before.count { it in larger.custodyIds() } }
            larger.transport.awaitInboundDrained()
            lab.assertConverged(listOf(smaller, larger), atLeast = 1, carriers = listOf(carol), conversation = thread)
            assertEquals("one root: the restore's reset", smaller.session(larger)?.rootHash, larger.session(smaller)?.rootHash)
            assertEquals("no swap back onto the root the restore left", 0L, larger.metrics.keys().ratchetSwappedBack)

            assertTrue(smaller.sendDm(larger, "after the restore"))
            assertTrue(larger.sendDm(smaller, "seen"))
            lab.assertConverged(listOf(smaller, larger), atLeast = 3, carriers = listOf(carol), conversation = thread)
            assertEquals(smaller.session(larger)?.rootHash, larger.session(smaller)?.rootHash)
        }

    /**
     * Alice – Carol – Bob, no boards: both write first while Carol holds both ways, the winner reads the loser's init
     * first (so its intro answer carries its own init, and Carol custodies it), then the loser reads the winner's, and
     * the pair converges on the winner's root. Returns (winner, loser, carol).
     */
    private suspend fun racedThroughCarol(): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice", board = false).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", board = false).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", board = false).apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, alice to carol, bob to carol)
        lab.awaitAcquainted(alice, bob, carol)
        lab.unlink(alice, bob)
        val (winner, loser) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice
        carol.transport.hold(winner.transport)
        carol.transport.hold(loser.transport)

        assertTrue(winner.sendDm(loser, "first from the winner"))
        assertTrue(loser.sendDm(winner, "first from the loser"))
        val losersFirst = loser.ownMessageId(loser.dmWith(winner), "first from the loser")
        lab.await(1) { if (losersFirst in carol.custodyIds()) 1 else 0 }
        carol.transport.release(winner.transport)
        lab.await(1) { winner.decrypted(winner.dmWith(loser)).count { it.first == losersFirst } }
        lab.await(1) { carol.initBearingCtlsFrom(winner).size }
        carol.transport.release(loser.transport)
        val thread = { node: LabNode -> node.dmWith(if (node === winner) loser else winner) }
        lab.assertConverged(listOf(winner, loser), atLeast = 2, carriers = listOf(carol), conversation = thread)
        assertEquals(winner.session(loser)?.rootHash, loser.session(winner)?.rootHash)
        return Triple(winner, loser, carol)
    }

    /** Alice – Carol – Bob in a line, with a confirmed DM session between the two ends. */
    private suspend fun relayedPair(bobBoard: Boolean = true): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob", board = bobBoard).apply { setDisplayName("Bob") }
        val carol = lab.node("carol").apply { setDisplayName("Carol") }
        lab.linkAll(alice to carol, carol to bob)
        lab.awaitAcquainted(alice, bob, carol)
        assertTrue(alice.sendDm(bob, "before"))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
        // Bob answers under alice's session, never a race of his own: a compact copy can land his row before her signed
        // one reaches his ratchet, and a race stamped now refuses the restore's reset under the replacement floors. The
        // race itself is #189 (a winner's intro answer, re-served to a restored loser, wins `resolveRace`), which rule C
        // heals only once the winner's answer is sealed past the skew bound (`aRestoredRaceLoserWhoMeetsTheWinnersOld…`);
        // this pair's subject is the dedup window, so it waits the race out rather than meets it.
        lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
        assertTrue(bob.sendDm(alice, "before too"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
        return Triple(alice, bob, carol)
    }

    /** Three DMs to the restored, still unlinked Bob, sealed under the session he lost; returns once Carol carries them. */
    private suspend fun sendThreeWhileBobIsAway(
        alice: LabNode,
        bob: LabNode,
        carol: LabNode,
    ): List<String> {
        val texts = listOf("while you were away", "and again", "and once more")
        texts.forEach { assertTrue(alice.sendDm(bob, it)) }
        val ids = texts.map { alice.ownMessageId(alice.dmWith(bob), it) }
        lab.await(ids.size) { ids.count { it in carol.custodyIds() } }
        return ids
    }

    /** The frame id [frame] carries, or null for a frame that does not decode. */
    private fun frameId(frame: WireEnvelope): String? = WireCodec.decodeEnvelope(frame.signed)?.id

    /** Frames [this] handed [to] under [id], each a distinct write (a pipe carries one copy per signature). */
    private fun LabNode.copiesTo(
        to: LabNode,
        id: String,
    ): Int =
        transport.sent.count { line ->
            // Each line reads "#seq <receiver> <type> <id> …".
            val cols = line.split(' ')
            cols.getOrNull(3) == id && to.nodeId.startsWith(cols[1])
        }

    /**
     * #177: Alice's restore re-mints her group epoch 1 under a newer stamp, so Bob holds two mints of one epoch and,
     * with both eras' frames arriving out of order, a skipped key per mint at the same index. Spending a new-era key
     * used to delete the old mint's key at that index too, and the old era's frame there — re-served from Carol's
     * custody — then dropped as a duplicate on every re-serve, for good.
     */
    @Test
    fun bothMintErasOfARestoredSendersEpochDeliverWhenTheirFramesInterleave() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val group = alice.createGroup(bob)
            assertTrue(alice.sendGroup(group, "old 0"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { group }

            // The old era's n = 1..5 reach Carol's custody; Bob gets n = 5 alone, banking the old mint's keys 1..4.
            alice.transport.hold(bob.transport)
            (1..5).forEach { assertTrue(alice.sendGroup(group, "old $it")) }
            val old = (1..5).map { alice.ownMessageId(group, "old $it") }
            lab.await(5) {
                alice.transport
                    .held(bob.transport)
                    .mapNotNull { idOf(it) }
                    .filter { it in old }
                    .distinct()
                    .size
            }
            // lab-lint: allow release-then-hold — the pipe is meant to run open until the next hold: the restore, its reset
            // and the re-mint's seed riding "new 0" all cross before it, and the wait below sees "new 0" land first.
            alice.transport.release(bob.transport) { batch -> batch.filter { idOf(it) == old[4] } }
            lab.await(1) { bob.decrypted(group).count { it.second == "old 5" } }
            // Its ✓✓ home before the restore: a tick that lands after it is sealed under the DM session the restore wiped,
            // and a reset re-seals unacked DMs, never ticks — the restore's own loss, not this scenario's subject.
            lab.awaitReceipt(alice, old[4], bob)

            alice.restoreFromBackup()
            lab.link(alice, bob)
            assertTrue(alice.sendGroup(group, "new 0")) // the re-mint: epoch 1 again, its seed ahead of it
            lab.await(1) { bob.decrypted(group).count { it.second == "new 0" } }

            // The new era out of order: n = 5 banks the new mint's keys 1..4, then n = 3 spends the new mint's key 3.
            alice.transport.hold(bob.transport)
            (1..5).forEach { assertTrue(alice.sendGroup(group, "new $it")) }
            val new = (1..5).map { alice.ownMessageId(group, "new $it") }
            lab.await(5) {
                alice.transport
                    .held(bob.transport)
                    .mapNotNull { idOf(it) }
                    .filter { it in new }
                    .distinct()
                    .size
            }
            alice.transport.release(bob.transport) { batch ->
                batch.sortedBy { wire -> listOf(new[4], new[2]).indexOf(idOf(wire)).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            lab.await(6) { bob.decrypted(group).count { it.second.startsWith("new ") } }

            // Carol re-serves the old era's n = 1..4, each on the old mint's own stored key. The new era spent its keys at
            // the same four indices, and before #177 each spend took the old mint's key too: all four were lost.
            lab.link(carol, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 12, carriers = listOf(carol)) { group }
        }

    private fun idOf(wire: WireEnvelope): String? = WireCodec.decodeEnvelope(wire.signed)?.takeIf { it.group != null }?.id

    /** The group's sender chain re-mints from one on the restored side; the members open it like any other epoch. */
    @Test
    fun aRestoredNodeReMintsItsGroupChainAndTheMembersFollow() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val group = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(group, "first"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { group }

            alice.restoreFromBackup()
            lab.linkAll(alice to bob, alice to carol)
            assertTrue(alice.sendGroup(group, "after the restore"))
            assertTrue(bob.sendGroup(group, "still here"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 3) { group }
        }

    private companion object {
        /** When Bob's link to Carol comes back after Alice's first pass: well inside the ten minutes before her second. */
        const val RELINK_AFTER_FIRST_PASS_MS = 3 * 60_000L

        /** The flagged-reset floor of the two-custodian cases, in place of the field's minute; on the wall clock. */
        const val RESET_FLOOR_MS = 1_000L
    }
}
