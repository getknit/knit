package app.getknit.knit.mesh.crypto.ratchet

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetSeal
import app.getknit.knit.mesh.protocol.EncEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `RatchetSessions.exportedRoots` hands the spool plane, and when `rootChanges` says it moved (ADR
 * 2026-10.j67x, #182). Confirmed sessions export; of the unconfirmed ones, only **our own reset, unanswered,
 * over a root it replaced** does — its new root for the answer, and the replaced root, still the peer's, as
 * the scope the reset rides. Two real session services over the real Room SQL, one per side, so each case is
 * the pair's view: the two sides must end on the same scope roots.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RatchetSessionsExportTest {
    private val sides = mutableListOf<Side>()

    private inner class Side(
        val nodeId: String,
    ) {
        val ik = RatchetCrypto.generateKeyPair()
        val spks = mutableMapOf(1 to RatchetCrypto.generateKeyPair())
        val db: KnitDatabase =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val sessions =
            RatchetSessions(
                store = RatchetRepository(db.ratchetDao(), clock = { T }),
                dhIdentityPriv = { ik.priv },
                spkPrivFor = { spks[it]?.priv },
            )
        val changes = mutableListOf<String>()
        lateinit var peer: Side

        private fun prekey() = RatchetEngine.PeerPrekey(id = 1, pub = peer.spks.getValue(1).pub)

        suspend fun seal(
            text: String,
            now: Long,
        ): EncEnvelope = checkNotNull(sessions.sealDm(peer.nodeId, peer.ik.pub, prekey(), text.toByteArray(), AAD, now))

        suspend fun sealConfirmedOnly(
            text: String,
            now: Long,
        ): EncEnvelope? = sessions.sealDm(peer.nodeId, peer.ik.pub, prekey(), text.toByteArray(), AAD, now, confirmedOnly = true)

        suspend fun reset(now: Long): ResetSeal = sessions.sealResetDm(peer.nodeId, peer.ik.pub, prekey(), "reset".toByteArray(), AAD, now)

        suspend fun open(
            env: EncEnvelope,
            now: Long,
        ) {
            val r = checkNotNull(env.r)
            val outcome = sessions.peekOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now)
            assertTrue("expected Opened, got $outcome", outcome is OpenOutcome.Opened)
            assertTrue(sessions.commitOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now) {})
        }

        suspend fun root(): ByteArray = checkNotNull(sessions.sessionFor(peer.nodeId)).root

        suspend fun export(): RatchetSessions.ExportedRoots? = sessions.exportedRoots().singleOrNull()
    }

    private fun TestScope.pair(): Pair<Side, Side> {
        val a = Side("aaaaaaaa")
        val b = Side("bbbbbbbb")
        a.peer = b
        b.peer = a
        sides += a
        sides += b
        listOf(a, b).forEach { side ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { side.sessions.rootChanges.collect { side.changes += it } }
        }
        return a to b
    }

    @After
    fun closeDbs() {
        sides.forEach { it.db.close() }
    }

    /** A session both sides confirmed, [a] the initiator. */
    private suspend fun TestScope.established(): Pair<Side, Side> {
        val (a, b) = pair()
        b.open(a.seal("hello", T), T)
        a.open(b.seal("hello back", T + 1), T + 1)
        return a to b
    }

    private fun exported(root: ByteArray) = RatchetCrypto.exportRoot(root)

    private fun sealed(seal: ResetSeal): ResetSeal.Sealed {
        assertTrue("expected Sealed, got $seal", seal is ResetSeal.Sealed)
        return seal as ResetSeal.Sealed
    }

    /**
     * The scope config's seal (`MeshManager.sendScopeConfig`, docs/SPOOL_PROTOCOL.md §5): only under a session already
     * confirmed, so it never opens one and never carries an init — not our first contact's, and not our own unanswered
     * reset's, whose unflagged copy the peer would meet under its hour-long floor (#90). Exactly the sessions that
     * export as `confirmed`.
     */
    @Test
    fun aConfirmedOnlySealNeverOpensASessionAndNeverCarriesAnInit() =
        runTest {
            val (a, b) = pair()
            assertNull("no session: nothing is sealed", a.sealConfirmedOnly("config", T))
            assertNull("and none was started", a.sessions.sessionFor(b.nodeId))
            b.open(a.seal("hello", T), T)
            assertNull("our own first-contact init, unanswered", a.sealConfirmedOnly("config", T + 1))
            // The responder is confirmed the moment it opens; its seal carries no init.
            assertNull(checkNotNull(b.sealConfirmedOnly("config", T + 2)).r?.init)
            a.open(b.seal("hello back", T + 3), T + 3)
            val confirmed = checkNotNull(a.sealConfirmedOnly("config", T + 4))
            assertNull(confirmed.r?.init)
            b.open(confirmed, T + 4)

            sealed(a.reset(T + 5))
            assertFalse(checkNotNull(a.export()).confirmed)
            assertNull("our own reset, unanswered", a.sealConfirmedOnly("config", T + 6))
        }

    @Test
    fun ourResetOverAConfirmedSessionExportsTheNewRootAndTheOneItReplacedCarryingTheReset() =
        runTest {
            val (a, b) = established()
            val before = a.root()
            val changesBefore = a.changes.size

            val seal = sealed(a.reset(T1))
            val view = checkNotNull(a.export()) { "the peer stays on the table while our reset waits" }
            assertFalse(view.confirmed)
            assertArrayEquals(exported(a.root()), view.pairwiseRoot)
            assertArrayEquals("the replaced root, the one the peer is still on", exported(before), view.prevPairwiseRoot)
            assertArrayEquals("the reset's own init names the refill", checkNotNull(seal.envelope.r?.init).eph, view.pendingResetInit)
            assertEquals("our reset moved the view", changesBefore + 1, a.changes.size)

            // The peer adopts it (it came out of the scope it holds) and lands on the same two roots, confirmed.
            b.open(seal.envelope, T1 + 1)
            val theirs = checkNotNull(b.export())
            assertTrue(theirs.confirmed)
            assertArrayEquals(view.pairwiseRoot, theirs.pairwiseRoot)
            assertArrayEquals(view.prevPairwiseRoot, theirs.prevPairwiseRoot)

            // Its answer, sealed under the new root, confirms us: back to the ordinary pair, the reset dropped.
            a.open(b.seal("answer", T1 + 2), T1 + 2)
            val after = checkNotNull(a.export())
            assertTrue(after.confirmed)
            assertNull(after.pendingResetInit)
            assertArrayEquals(theirs.pairwiseRoot, after.pairwiseRoot)
            assertArrayEquals(theirs.prevPairwiseRoot, after.prevPairwiseRoot)
            assertEquals("the confirmation moved it again", changesBefore + 2, a.changes.size)
        }

    /** Our first-contact init stays off the table (the pair scope carries it); the peer's answer to it never was. */
    @Test
    fun aFirstContactInitExportsNothingUntilAnswered() =
        runTest {
            val (a, b) = pair()
            val first = a.seal("hello", T)
            assertNull(a.export())
            assertTrue("an initiation from nothing reports nothing", a.changes.isEmpty())

            b.open(first, T)
            assertTrue("the responder holds a confirmed session", checkNotNull(b.export()).confirmed)
            a.open(b.seal("hi", T + 1), T + 1)
            assertTrue(checkNotNull(a.export()).confirmed)
        }

    /** A reset that replaces no root — a restore's, or one toward a peer we never opened — is an unconfirmed init like any other. */
    @Test
    fun aResetFromNoSessionExportsNothing() =
        runTest {
            val (a, _) = established()
            a.sessions.forget(a.peer.nodeId)
            val changes = a.changes.size
            sealed(a.reset(T1))
            assertNull(a.export())
            assertEquals(changes, a.changes.size)
        }

    /** A reset the ratchet declines moves nothing (ADR 2026-09.qerd's floor). */
    @Test
    fun aDeclinedSecondResetLeavesTheViewWhereItWas() =
        runTest {
            val (a, _) = established()
            sealed(a.reset(T1))
            val changes = a.changes.size
            val view = checkNotNull(a.export())
            assertTrue(a.reset(T1 + 1_000) is ResetSeal.Declined)
            assertEquals(changes, a.changes.size)
            assertArrayEquals(view.pendingResetInit, checkNotNull(a.export()).pendingResetInit)
        }

    /**
     * Both sides reset at once (#89's shape over the relay). `aaaaaaaa` wins: it keeps its root and holds the
     * loser's in the drain slot, the root the loser is on until it adopts — so its reset still has a scope to
     * ride (#92 took the pre-reset root out of the slot). The loser adopts the winner's root and stops exporting
     * its own as live: it is the ordinary drain now, carrying nothing. Once the winner hears the answer, both
     * sides hold the same two roots.
     */
    @Test
    fun aResetRaceEndsWithBothSidesOnTheWinnersScopeAndTheLoserNoLongerCarryingItsReset() =
        runTest {
            val (a, b) = established()
            val winning = sealed(a.reset(T1))
            val losing = sealed(b.reset(T1))
            val winnerRoot = a.root()
            val loserRoot = b.root()
            assertNotNull("the loser waited on its own reset", checkNotNull(b.export()).pendingResetInit)

            a.open(losing.envelope, T1 + 1)
            val winner = checkNotNull(a.export())
            assertArrayEquals("the winner keeps its root", exported(winnerRoot), winner.pairwiseRoot)
            assertArrayEquals("and drains the loser's", exported(loserRoot), winner.prevPairwiseRoot)
            assertArrayEquals(checkNotNull(winning.envelope.r?.init).eph, winner.pendingResetInit)

            b.open(winning.envelope, T1 + 2)
            val loser = checkNotNull(b.export())
            assertTrue(loser.confirmed)
            assertNull("the loser's own reset no longer rides anything", loser.pendingResetInit)
            assertArrayEquals("the loser is on the winner's root", exported(winnerRoot), loser.pairwiseRoot)
            assertArrayEquals("its own root is only the drain now", exported(loserRoot), loser.prevPairwiseRoot)

            a.open(b.seal("answer under your root", T1 + 3), T1 + 3)
            val settled = checkNotNull(a.export())
            assertTrue(settled.confirmed)
            assertArrayEquals(loser.pairwiseRoot, settled.pairwiseRoot)
            assertArrayEquals(loser.prevPairwiseRoot, settled.prevPairwiseRoot)
        }

    /**
     * A second reset over our own, unanswered after the one-minute floor: the slot keeps the root the first one
     * replaced — the one the peer is provably on, and whose scope it still drains if it did adopt the first — so
     * the second reset rides a scope the peer pulls. Before, the slot took the first reset's root, which the peer
     * may never have derived, and the pair lost its scope again.
     */
    @Test
    fun aSecondResetOverOurUnansweredOneKeepsTheRootThePeerIsOn() =
        runTest {
            val (a, b) = established()
            val original = a.root()
            sealed(a.reset(T1)) // never reaches the peer
            val second = sealed(a.reset(T1 + RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS))
            assertTrue(second.reRooted)
            val view = checkNotNull(a.export())
            assertArrayEquals("the root the peer is on, not the first reset's", exported(original), view.prevPairwiseRoot)
            assertArrayEquals(checkNotNull(second.envelope.r?.init).eph, view.pendingResetInit)

            b.open(second.envelope, T1 + RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS + 1)
            assertArrayEquals(view.pairwiseRoot, checkNotNull(b.export()).pairwiseRoot)
            assertArrayEquals("both drain the same root", view.prevPairwiseRoot, checkNotNull(b.export()).prevPairwiseRoot)
            a.open(
                b.seal("answer", T1 + RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS + 2),
                T1 + RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS + 2,
            )
            assertTrue(checkNotNull(a.export()).confirmed)
        }

    /**
     * The replaced era's own init, re-pulled while our reset waits, is no race (review round 3). `aaaaaaaa` (the
     * smaller id) opened the old root, and its first frame carried that init; `bbbbbbbb` resets, and the old root's
     * scope it now subscribes still serves the frame. Raced, the smaller id wins and `bbbbbbbb` would adopt the root
     * it left — while the peer adopts the reset: two roots. It reads read-only under the drained root instead.
     */
    @Test
    fun theReplacedErasOwnInitReServedWhileOurResetWaitsIsNoRace() =
        runTest {
            val (a, b) = pair()
            val first = a.seal("hello, with my init", T)
            b.open(first, T)
            a.open(b.seal("hi", T + 1), T + 1)
            val oldRoot = b.root()

            val reset = sealed(b.reset(T1))
            val ours = b.root()
            val r = checkNotNull(first.r)
            // Re-served: its envelope was stamped at T, ten minutes before our reset.
            if (b.sessions.peekOpen(
                    b.nodeId,
                    a.nodeId,
                    a.ik.pub,
                    r,
                    first.nonce,
                    first.ct,
                    AAD,
                    T1 + 1,
                    frameSentAt = T,
                ) is OpenOutcome.Opened
            ) {
                b.sessions.commitOpen(b.nodeId, a.nodeId, a.ik.pub, r, first.nonce, first.ct, AAD, T1 + 1, frameSentAt = T) {}
            }
            assertArrayEquals("still on our reset's root", ours, b.root())
            assertFalse("not the root we left", oldRoot.contentEquals(b.root()))
            assertNotNull("still waiting for the answer", checkNotNull(b.export()).pendingResetInit)

            a.open(reset.envelope, T1 + 2)
            b.open(a.seal("answer", T1 + 3), T1 + 3)
            assertTrue(checkNotNull(b.export()).confirmed)
            assertArrayEquals(a.root(), b.root())
        }

    /**
     * The same init, but **live** (review round 4): the smaller id never heard our answer, so it is still the old
     * root's unconfirmed initiator and keeps sealing with that init after our reset. That is a race, and it must
     * stay one: the peer wins it and keeps the old root, so we adopt it — read only, neither side would confirm.
     */
    @Test
    fun theReplacedErasInitSealedSinceOurResetStillRacesAndThePairMeetsOnOneRoot() =
        runTest {
            val (a, b) = pair()
            b.open(a.seal("hello, with my init", T), T)
            b.seal("hi", T + 1) // lost: a never confirms
            val oldRoot = b.root()

            val reset = sealed(b.reset(T1))
            val live = a.seal("still on my init", T1 + 1)
            val r = checkNotNull(live.r)
            assertTrue(
                b.sessions.peekOpen(
                    b.nodeId,
                    a.nodeId,
                    a.ik.pub,
                    r,
                    live.nonce,
                    live.ct,
                    AAD,
                    T1 + 2,
                    frameSentAt = T1 + 1,
                ) is OpenOutcome.Opened,
            )
            assertTrue(b.sessions.commitOpen(b.nodeId, a.nodeId, a.ik.pub, r, live.nonce, live.ct, AAD, T1 + 2, frameSentAt = T1 + 1) {})
            assertArrayEquals("we lost the race to the smaller id and took its root", oldRoot, b.root())

            a.open(reset.envelope, T1 + 3) // a wins its side of the same race and keeps the root
            a.open(b.seal("answer", T1 + 4), T1 + 4)
            assertTrue(checkNotNull(a.export()).confirmed)
            assertArrayEquals("one root", a.root(), b.root())
        }

    @Test
    fun thePendingResetRuleReadsTheRowAlone() {
        val base =
            RatchetEngine.SessionState(
                peerId = "p",
                confirmed = false,
                weAreInitiator = true,
                root = ByteArray(32),
                prevRoot = ByteArray(32) { 1 },
                establishedAt = 50L,
                initEphPub = ByteArray(32) { 2 },
                lastResetSentAt = 50L,
            )
        assertNotNull(RatchetSessions.pendingResetInit(base))
        assertTrue(RatchetSessions.exports(base))
        assertNull("answered", RatchetSessions.pendingResetInit(base.copy(confirmed = true, initEphPub = null)))
        assertNull("a plain init", RatchetSessions.pendingResetInit(base.copy(lastResetSentAt = 0L)))
        assertNull("a marker over a plain init", RatchetSessions.pendingResetInit(base.copy(lastResetSentAt = 60L)))
        assertNull("no root replaced", RatchetSessions.pendingResetInit(base.copy(prevRoot = null)))
        assertNull("the peer's init", RatchetSessions.pendingResetInit(base.copy(weAreInitiator = false)))
        assertFalse(RatchetSessions.exports(base.copy(prevRoot = null)))
    }

    private companion object {
        const val T = 100_000_000L
        const val T1 = T + 600_000L
        val AAD = "aad".toByteArray()
    }
}
