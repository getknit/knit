package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.ResetReseal
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * on under a new session without anybody typing anything.
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
     * #171: Bob is two hops from Alice, behind Carol, and restores while Alice's three DMs to him cross Carol live
     * under the session the restore wipes. His reset reaches Alice through Carol and she re-seals all three at once —
     * under their original ids, which Carol first saw moments ago, so her router drops every re-seal as a duplicate
     * and her custody (and Bob's, after the digest exchange) keeps only the originals Bob cannot open. What gets
     * them to him is the second pass, past Carol's ten-minute window (ADR 2026-10.dmgw).
     */
    @Test
    fun aRestoredNodeBehindARelayGetsWhatCrossedItInsideTheDedupWindow() =
        runBlocking {
            val (alice, bob, carol) = relayedPair()
            bob.restoreFromBackup()
            val sent = sendThreeWhileBobIsAway(alice, bob, carol)

            lab.link(carol, bob)
            // Bob's reset reaches Alice through Carol, and her first re-seal of each of the three crosses to Carol — a
            // second copy of each id on the pipe, since a re-seal has its own signature — and Carol's router has
            // handled it: dropped, for Carol saw every one of these ids inside her window.
            lab.await(sent.size) { sent.count { id -> alice.copiesTo(carol, id) >= 2 } }
            carol.transport.awaitInboundDrained()
            // And Bob has failed to open the originals Carol served him at the link-up, each failure spending his one
            // reopen of that id (SeenSet.reopen). The restore's reset waits on none of them, so that serve can still sit
            // in his queue here; handled after the jump, it spends the reopen inside the window of Carol's next re-serve
            // of the original, whose own reopen is then refused, and the second-pass re-seal behind it drops as seen.
            lab.await(sent.size) { sent.count { id -> carol.copiesTo(bob, id) >= 1 } }
            bob.transport.awaitInboundDrained()

            // The routers' window runs out; the 60 s tick (driven here, the lab clock moves no scheduler) re-seals the
            // three once more, and Carol now relays them.
            lab.clock.advance(ResetReseal.SECOND_PASS_MS)
            alice.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 5, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
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

    /** Alice – Carol – Bob in a line, with a confirmed DM session between the two ends. */
    private suspend fun relayedPair(): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        val carol = lab.node("carol").apply { setDisplayName("Carol") }
        lab.linkAll(alice to carol, carol to bob)
        lab.awaitAcquainted(alice, bob, carol)
        assertTrue(alice.sendDm(bob, "before"))
        lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
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
            // Custody parity is judged between the two members only. Carol is apart from the restore until this last link,
            // so the new era's group frames reach her only live, and a non-member is never back-filled a group's frames
            // (`ForwardSync.onDigest` serves members only): the partition the design does not close. What Carol is here
            // for is re-serving the old era to Bob, and the message half of the oracle is what shows that she did.
            lab.assertConverged(listOf(alice, bob), atLeast = 12, carriers = listOf(carol), custodyAcross = listOf(alice, bob)) { group }
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
}
