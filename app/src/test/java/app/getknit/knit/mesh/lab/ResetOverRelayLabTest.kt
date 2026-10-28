package app.getknit.knit.mesh.lab

import app.getknit.knit.data.relay.coveredLabelsOf
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.FakeSpool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DM session resets between two phones a relay alone connects (#182, ADR 2026-10.j67x): our own reset, while it is
 * unanswered, keeps the scope of the root it replaced — the one the peer is still on — and rides it, while the new
 * root's scope takes the peer's answer. Each scenario parts the radios before the reset and judges the pair on what
 * the relay alone carried; where one re-links at the end, it is for the oracle's custody leg only. One is radio-only,
 * for an order a single scope cannot fix (its KDoc says why).
 */
@RunWith(RobolectricTestRunner::class)
class ResetOverRelayLabTest {
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

    private fun dmThread(
        a: LabNode,
        b: LabNode,
    ): (LabNode) -> String = { if (it === a) it.dmWith(b) else it.dmWith(a) }

    /**
     * #182 (ADR 2026-10.j67x): a reset sent to a peer the relay alone reaches. Alice's reset mints a root Bob can
     * learn only from the reset, and Bob is still subscribed to the scope of the root it replaced — so that scope
     * stays on Alice's table, retiring, and takes the reset (C-3.1-5), while the new root's scope takes Bob's
     * answer. Before the fix both scopes left Alice's table the moment she reset: the reset stayed on the
     * radios, Bob never adopted it, and Alice stayed unconfirmed with no scope for the pair (the Pixel 9 field
     * case, 2026-10-16). Pass: Bob adopts over the relay, Alice confirms on his answer over the relay, both hold
     * the new DM scope, and a fresh DM each way lands.
     */
    @Test
    fun aResetToAPeerOnlyTheRelayReachesCrossesItAndThePairCarriesOnUnderTheNewSession() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val oldRoot = checkNotNull(alice.session(bob)).rootHash
            val oldScope = checkNotNull(alice.dmScopeStatus(bob)).scopeHex
            assertTrue("covered before the reset", alice.relayCovers(bob))

            // Bob off the relay for a moment, so the unanswered state holds still long enough to read.
            lab.leaveRelay(bob)
            assertEquals("the reset went out", null, alice.resetSession(bob))
            val newRoot = checkNotNull(alice.session(bob)).rootHash
            assertTrue("a fresh root", newRoot != oldRoot)
            assertTrue(
                "the thread never read \"not covered yet\" while the reset was unanswered",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.dmScopeStatus(bob)?.pendingReset == true && !alice.relayCovers(bob)) 1 else 0
                },
            )
            lab.rejoinRelay(bob)
            assertTrue(
                "bob never adopted the reset over the relay: bob is on ${bob.session(alice)?.rootHash}, alice reset to $newRoot",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.session(alice)?.rootHash == newRoot) 1 else 0 },
            )
            assertTrue(
                "alice never confirmed on bob's answer over the relay (bob on ${bob.session(alice)?.rootHash}, alice on $newRoot)",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.session(bob)?.confirmed == true) 1 else 0 },
            )
            // The old scope stays listed, converged, until each table re-derives on its own coroutine: wait past it.
            lab.awaitDmScope(alice, bob, notScope = oldScope)
            lab.awaitDmScope(bob, alice, notScope = oldScope)
            val newScope = checkNotNull(alice.dmScopeStatus(bob)).scopeHex
            assertEquals("both sides on one scope", newScope, bob.dmScopeStatus(alice)?.scopeHex)
            assertTrue(
                "the thread never read covered again once the reset was answered",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.relayCovers(bob)) 1 else 0 },
            )

            assertTrue(alice.sendDm(bob, "after the reset"))
            assertTrue(bob.sendDm(alice, "heard it"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { dmThread(alice, bob)(it) }
        }

    /**
     * Finding 2 on ADR 2026-10.j67x: a second reset over our own, the first still unanswered past the one-minute
     * floor. The first never left Alice (she was off the relay too). The second keeps the root the first replaced
     * in its drain slot — the root Bob is on — so it rides the scope Bob still pulls, and only it does: the first
     * is a reset of ours that is no longer pending, pushed nowhere. Before, the slot took the first reset's root,
     * whose scope nobody subscribes, and the second reset was stranded exactly as #182's first was. The radio
     * re-link at the end serves the oracle's custody leg alone: the withheld first reset is a frame only Alice
     * holds until a radio exchange hands it over (inert at Bob, older than the session it would replace).
     */
    @Test
    fun aSecondResetPastTheFloorWithTheFirstUnansweredStillCrossesTheRelay() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val oldScope = checkNotNull(alice.dmScopeStatus(bob)).scopeHex

            lab.leaveRelay(alice)
            assertEquals("the first reset went out", null, alice.resetSession(bob))
            val firstRoot = checkNotNull(alice.session(bob)).rootHash
            lab.clock.advance(RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS)
            assertEquals("the second reset went out", null, alice.resetSession(bob))
            val newRoot = checkNotNull(alice.session(bob)).rootHash
            assertTrue("the second re-rooted", newRoot != firstRoot)
            lab.rejoinRelay(alice)

            assertTrue(
                "bob never adopted the second reset over the relay: bob is on ${bob.session(alice)?.rootHash}, alice on $newRoot",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.session(alice)?.rootHash == newRoot) 1 else 0 },
            )
            assertTrue(
                "alice never confirmed on bob's answer over the relay (bob on ${bob.session(alice)?.rootHash}, alice on $newRoot)",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.session(bob)?.confirmed == true) 1 else 0 },
            )
            lab.awaitDmScope(bob, alice, notScope = oldScope)
            assertEquals("both sides on one scope", bob.dmScopeStatus(alice)?.scopeHex, alice.dmScopeStatus(bob)?.scopeHex)
            assertTrue(alice.sendDm(bob, "after both resets"))
            assertTrue(bob.sendDm(alice, "heard it"))
            lab.await(
                4,
                timeoutMs = MeshLab.SPOOL_AWAIT_MS,
            ) { minOf(alice.decrypted(alice.dmWith(bob)).size, bob.decrypted(bob.dmWith(alice)).size) }

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { dmThread(alice, bob)(it) }
        }

    /**
     * Finding 1 on ADR 2026-10.j67x, the unsafe order of a relay-only mutual reset. Both reset over the same root;
     * the winner (the smaller node id) is off the relay until the loser has adopted the winner's root and
     * answered. Back on the relay the winner can confirm on that answer first, and then pull the loser's own
     * flagged reset out of the old scope: no longer a race (it is confirmed), newer than its session, and exempt
     * from the remnant guard as a reset (ADR 024), so it is adopted as a replacement while the loser stays on the
     * winner's root. The relay half is closed (a reset of ours we left is pushed nowhere, `ScopeFrames.mayRefill`);
     * the copy already in the old scope is the engine's to refuse.
     */
    @Ignore("#184: a winner adopts the race loser's own flagged reset after confirming (ADR 024's exemption)")
    @Test
    fun aMutualResetWhoseWinnerHearsTheAnswerFirstEndsOnTheWinnersRoot() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val (winner, loser) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice
            val oldScope = checkNotNull(winner.dmScopeStatus(loser)).scopeHex

            val (winnerRoot, winnersScope) = winnerResetsAndLeaves(spool, winner, loser, oldScope)
            val losersReset = loserResetsAdoptsAndAnswers(spool, winner, loser, oldScope, winnerRoot, winnersScope)

            // The unsafe order, fixed: the winner's old-scope round waits on its SUB until the answer has confirmed it.
            spool.holdSubscriptions(oldScope)
            lab.rejoinRelay(winner)
            assertTrue(
                "the winner never confirmed: ${winner.session(loser)?.rootHash}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (winner.session(loser)?.confirmed == true) 1 else 0 },
            )
            val pulledBefore = spool.pulled.size
            spool.releaseSubscriptions(oldScope)
            assertTrue(
                "the winner never pulled the loser's own reset out of the old scope",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (losersReset in spool.pulled.drop(pulledBefore)) 1 else 0 },
            )
            assertTrue(winner.sendDm(loser, "after the race"))
            assertTrue(loser.sendDm(winner, "heard it"))
            lab.await(
                4,
                timeoutMs = MeshLab.SPOOL_AWAIT_MS,
            ) { minOf(alice.decrypted(alice.dmWith(bob)).size, bob.decrypted(bob.dmWith(alice)).size) }
            // Read before any radio: the relay alone must leave both on the winner's root.
            assertEquals("the winner stayed on its root", winnerRoot, winner.session(loser)?.rootHash)
            assertEquals("the loser is on it too", winnerRoot, loser.session(winner)?.rootHash)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { dmThread(alice, bob)(it) }
        }

    /**
     * The mutual scenario's first half: the loser off the relay, the winner resets — its reset in the old scope and
     * its new root's scope derived — and leaves. Returns the winner's new root and that scope.
     */
    private suspend fun winnerResetsAndLeaves(
        spool: FakeSpool,
        winner: LabNode,
        loser: LabNode,
        oldScope: String,
    ): Pair<String?, String> {
        lab.leaveRelay(loser)
        val beforeWinnersReset = spool.liveIds(oldScope).size
        assertEquals(null, winner.resetSession(loser))
        val winnerRoot = checkNotNull(winner.session(loser)).rootHash
        assertTrue(
            "the winner's reset never reached the old scope",
            lab.tryAwait(beforeWinnersReset + 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.liveIds(oldScope).size },
        )
        assertTrue(
            "the winner never derived its new root's scope",
            lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                if (winner.dmScopeStatus(loser)?.scopeHex?.let { it != oldScope } == true) 1 else 0
            },
        )
        val winnersScope = checkNotNull(winner.dmScopeStatus(loser)).scopeHex
        lab.leaveRelay(winner)
        return winnerRoot to winnersScope
    }

    /**
     * The second half: the loser resets while the winner is away, rejoins — its own reset reaching the old scope —
     * pulls the winner's, loses the race, adopts the winner's root and answers into its scope. Returns the blob id the
     * loser's reset added to the old scope.
     */
    @Suppress("LongParameterList") // the scenario's state, handed between its two halves
    private suspend fun loserResetsAdoptsAndAnswers(
        spool: FakeSpool,
        winner: LabNode,
        loser: LabNode,
        oldScope: String,
        winnerRoot: String?,
        winnersScope: String,
    ): String {
        assertEquals(null, loser.resetSession(winner))
        val beforeLosersReset = spool.liveIds(oldScope)
        val beforeAnswer = spool.liveIds(winnersScope).size
        val answersBefore = loser.metrics.keys().introsAnswered
        lab.rejoinRelay(loser)
        assertTrue(
            "the loser's own reset never reached the old scope",
            lab.tryAwait(beforeLosersReset.size + 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.liveIds(oldScope).size },
        )
        val losersReset = (spool.liveIds(oldScope) - beforeLosersReset).single()
        assertTrue(
            "the loser never adopted the winner's root: ${loser.session(winner)?.rootHash} vs $winnerRoot",
            lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                if (loser.session(winner)?.rootHash == winnerRoot && loser.session(winner)?.confirmed == true) 1 else 0
            },
        )
        assertTrue(
            "the loser never answered the winner's init",
            lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (loser.metrics.keys().introsAnswered > answersBefore) 1 else 0 },
        )
        assertTrue(
            "the loser's answer never reached the winner's scope",
            lab.tryAwait(beforeAnswer + 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.liveIds(winnersScope).size },
        )
        return losersReset
    }

    /**
     * Review round 3, finding 1: the init that minted the root our reset replaced, re-pulled while the reset waits,
     * is no race. The smaller-id peer made that root by its own reset, so its reset frame — init attached — sits in
     * that root's scope for the spool's 48 h. A day on it has left both custodies, and the larger-id phone restarts,
     * so nothing remembers it was pulled. That phone resets with the relay off, comes back, and pulls the old frame
     * out of the scope its reset now rides. Raced (the anchor went with the re-root) the smaller id "wins", the phone
     * adopts the root it left, and the peer adopts the reset: two roots. Pass: both on the resetter's root.
     * Follow-up: the re-pull proof reads the shared spool's `pulled` log, not which node pulled.
     */
    @Test
    fun theReplacedErasOwnInitReplayedByTheRelayIsNoRaceAgainstOurReset() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val (small, large) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice
            val firstScope = checkNotNull(small.dmScopeStatus(large)).scopeHex

            assertEquals("the smaller id's reset went out", null, small.resetSession(large))
            val oldRoot = checkNotNull(small.session(large)).rootHash
            assertTrue(
                "the old root never settled on both: ${small.session(large)?.rootHash} / ${large.session(small)?.rootHash}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (large.session(small)?.rootHash == oldRoot && small.session(large)?.confirmed == true) 1 else 0
                },
            )
            lab.awaitDmScope(large, small, notScope = firstScope)
            val oldScope = checkNotNull(large.dmScopeStatus(small)).scopeHex

            // A day on: the old root's opening frames are out of both custodies, still at the spool.
            lab.clock.advance(CUSTODY_PAST_MS)
            lab.leaveRelay(large)
            large.restart()
            assertEquals("the larger id's reset went out", null, large.resetSession(small))
            val newRoot = checkNotNull(large.session(small)).rootHash
            // Proof the old frames are re-pulled, not merely there: an id the old scope held before the rejoin.
            val oldEra = spool.liveIds(oldScope)
            val pulledBefore = spool.pulled.size
            lab.rejoinRelay(large)
            assertTrue(
                "the resetter never re-pulled the old root's frames",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (spool.pulled.drop(pulledBefore).any { it in oldEra }) 1 else 0 },
            )

            assertTrue(
                "the smaller id never adopted the reset: ${small.session(large)?.rootHash} vs $newRoot",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (small.session(large)?.rootHash == newRoot) 1 else 0 },
            )
            assertTrue(
                "the resetter never confirmed on its own root: ${large.session(small)?.rootHash} vs $newRoot",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (large.session(small)?.confirmed == true && large.session(small)?.rootHash == newRoot) 1 else 0
                },
            )
            lab.awaitDmScope(large, small, notScope = oldScope)
            lab.awaitDmScope(small, large, notScope = oldScope)
            assertTrue(large.sendDm(small, "after the reset"))
            assertTrue(small.sendDm(large, "heard it"))
            lab.await(
                4,
                timeoutMs = MeshLab.SPOOL_AWAIT_MS,
            ) { minOf(alice.decrypted(alice.dmWith(bob)).size, bob.decrypted(bob.dmWith(alice)).size) }
            assertEquals("still on the resetter's root", newRoot, large.session(small)?.rootHash)
            assertEquals(newRoot, small.session(large)?.rootHash)
            lab.assertConverged(listOf(alice, bob), atLeast = 4, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { dmThread(alice, bob)(it) }
        }

    /**
     * Review round 4, finding 1: the same init **live** is still a race. The smaller id opened the session and never
     * heard the answer, so it is still that root's unconfirmed initiator; the larger id resets. Its reset reaches the
     * smaller id first, which wins its side of the race and keeps the root; then the smaller id's next DM, sealed
     * since the reset and still carrying the old init, reaches the resetter. That must race — the resetter loses and
     * adopts the root the peer kept — or the pair sits on two roots, each reading the other read-only, with nobody
     * confirming. The smaller id's intro answer to the reset is already such a frame, so the race can end before the
     * scenario's own DM: the re-root is proven off the reset frame, not the resetter's row. Radio, not the relay: one
     * scope carries the answer and the reset alike, unordered, so only a lossy pipe fixes the order this case needs
     * (the answer withheld, the reset let through).
     */
    @Test
    fun theReplacedErasInitSealedSinceOurResetStillRacesAndThePairMeetsOnOneRoot() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            val (small, large) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice
            // Everything sealed the larger id sends the smaller is lost but a flagged reset: the answer is withheld.
            large.transport.lossy(small.transport) { it.isSealedDmTo(small) && !it.isFlaggedReset() }

            assertTrue(small.sendDm(large, "hello"))
            lab.await(1) { large.decrypted(large.dmWith(small)).size }
            val oldRoot = checkNotNull(small.session(large)).rootHash
            assertEquals("the smaller id is still waiting for the answer", false, small.session(large)?.confirmed)

            val resetsBefore = small.custodiedChatsFrom(large, small)
            assertEquals("the larger id's reset went out", null, large.resetSession(small))
            lab.await(resetsBefore + 1) { small.custodiedChatsFrom(large, small) }
            // Read off the reset frame, never the resetter's row: the smaller id answers the reset at once (IntroSync),
            // sealed under the root it kept with its init still attached, so the resetter can lose the race and be back
            // on the old root before any read of its row here. The resetter is the responder on the old root, so its
            // reset is a re-root, whose header carries an init of the resetter's own, not the old root's.
            val headers = small.custodyIds().mapNotNull { small.custodiedSeal(it)?.r }
            val (resets, rest) = headers.partition { it.flags and RatchetHeader.FLAG_RESET != 0 }
            val oldInit = rest.firstNotNullOf { it.init?.eph } // the smaller id's own frames, the old root's init on each
            val resetInit = checkNotNull(resets.single().init) { "the reset carried no init" }.eph
            assertTrue("the reset moved the resetter off the old root", !resetInit.contentEquals(oldInit))
            assertTrue(small.sendDm(large, "still on my init"))
            assertTrue(
                "the resetter never lost the live race to the smaller id: ${large.session(small)?.rootHash} vs $oldRoot",
                lab.tryAwait(1) { if (large.session(small)?.rootHash == oldRoot) 1 else 0 },
            )

            large.transport.lossy(small.transport) // a clean link, and the re-link's digest exchange serves the answer
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { dmThread(alice, bob)(it) }
            assertEquals("one root, the one the smaller id kept", oldRoot, small.session(large)?.rootHash)
            assertEquals(oldRoot, large.session(small)?.rootHash)
        }

    /** Whether this node's thread with [peer] reads covered by the relays, by the rule the chat's notice reads. */
    private fun LabNode.relayCovers(peer: LabNode): Boolean = peer.nodeId in coveredLabelsOf(manager.spoolStatus().filter { it.connected })

    private fun WireEnvelope.isSealedDmTo(node: LabNode): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.group == null && it.recipientId == node.nodeId } == true

    private fun WireEnvelope.isFlaggedReset(): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { env ->
            WireCodec
                .decodePayload<ChatContent>(env.payload)
                ?.enc
                ?.r
                ?.let { it.flags and RatchetHeader.FLAG_RESET != 0 }
        } == true

    private companion object {
        /** Past the mesh custody TTL (24 h), inside the spool's 48 h. */
        const val CUSTODY_PAST_MS = 25 * 60 * 60_000L
    }
}
