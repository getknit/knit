package app.getknit.knit.mesh.crypto.ratchet

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetSeal
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.RatchetHeader
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

        /** A reset against the peer's prekey [pkid], for [cause]: a restore's, or one after the peer rotated. */
        suspend fun reset(
            now: Long,
            cause: RatchetSessions.ResetCause,
            pkid: Int = 1,
        ): ResetSeal {
            val spk = RatchetEngine.PeerPrekey(id = pkid, pub = peer.spks.getValue(pkid).pub)
            return sessions.sealResetDm(peer.nodeId, peer.ik.pub, spk, "reset".toByteArray(), AAD, now, cause)
        }

        suspend fun ownPending(): RatchetSessions.ExportedRoots? = sessions.ownPendingResets().singleOrNull()

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

    /**
     * A restore's reset (ADR 2026-11.dgj2, #324): from no session it replaces no root, so [RatchetSessions.exportedRoots]
     * still exports nothing for it (C-3.1-4), and [RatchetSessions.ownPendingResets] is the read the restore's anchor
     * joins: the reset's root, its init, no slot.
     */
    @Test
    fun aRestoreResetIsAnOwnPendingResetAndStillExportsNothing() =
        runTest {
            val (a, _) = established()
            a.sessions.forget(a.peer.nodeId)
            val reset = sealed(a.reset(T1, RatchetSessions.ResetCause.AFTER_WIPE))
            assertTrue(reset.reRooted)
            assertNull(a.export())
            val pending = checkNotNull(a.ownPending())
            assertArrayEquals(exported(a.root()), pending.pairwiseRoot)
            assertArrayEquals(checkNotNull(reset.envelope.r?.init).eph, pending.pendingResetInit)
            assertNull("the anchor stands in for the slot", pending.prevPairwiseRoot)
        }

    /** Our first-contact init is no reset: neither read carries it. The AFTER_WIPE marker over it is one. */
    @Test
    fun aFirstContactInitIsNoOwnPendingResetUntilARestoreMarksIt() =
        runTest {
            val (a, _) = pair()
            a.seal("hello", T)
            assertNull(a.export())
            assertNull(a.ownPending())

            val marker = sealed(a.reset(T + 1_000, RatchetSessions.ResetCause.AFTER_WIPE))
            assertFalse("the marker keeps the init", marker.reRooted)
            assertNull("still nothing exported", a.export())
            assertArrayEquals(checkNotNull(marker.envelope.r?.init).eph, checkNotNull(a.ownPending()).pendingResetInit)
        }

    /**
     * Design-pass R1: a second reset over the restore's — here the prekey trigger's, after the peer rotated — re-roots and
     * puts the never-derived restore root in the slot, so [RatchetSessions.exportedRoots] starts exporting it. The own
     * pending read follows the new reset whatever the slot holds, so the anchor still carries it; the peer's answer
     * ends both.
     */
    @Test
    fun aResetOverTheRestoreResetStaysAnOwnPendingResetUntilAnswered() =
        runTest {
            val (a, b) = established()
            a.sessions.forget(b.nodeId)
            val first = sealed(a.reset(T1, RatchetSessions.ResetCause.AFTER_WIPE))
            val restoreRoot = a.root()
            val past = 1 + RatchetSessions.PEER_PREKEYS_KEPT
            b.spks[past] = RatchetCrypto.generateKeyPair()
            assertFalse(
                "a pin inside the peer's retention owes nothing",
                RatchetSessions.restoreResetOwed(a.sessions.sessionFor(b.nodeId), pinnedPkid = past - 1),
            )
            assertTrue("owed on a pin past it", RatchetSessions.restoreResetOwed(a.sessions.sessionFor(b.nodeId), pinnedPkid = past))
            val second = sealed(a.reset(T1 + 120_000, RatchetSessions.ResetCause.AFTER_WIPE, pkid = past))
            assertTrue(second.reRooted)
            assertFalse(
                "and never again once on it",
                RatchetSessions.restoreResetOwed(a.sessions.sessionFor(b.nodeId), pinnedPkid = past),
            )

            val pending = checkNotNull(a.ownPending())
            val secondInit = checkNotNull(second.envelope.r?.init).eph
            assertArrayEquals(secondInit, pending.pendingResetInit)
            assertFalse(secondInit.contentEquals(checkNotNull(first.envelope.r?.init).eph))
            val exportedNow = checkNotNull(a.export())
            assertArrayEquals("the slot holds the restore root", exported(restoreRoot), exportedNow.prevPairwiseRoot)
            assertArrayEquals("and the own pending read carries it", exported(restoreRoot), pending.prevPairwiseRoot)

            // Round-1 finding C2: the re-send ends pendingResetInit, so exportedRoots drops both scopes; the own pending read
            // keeps the init and the slot, which the peer may be on.
            val resend = sealed(a.reset(T1 + 130_000, RatchetSessions.ResetCause.RESEND_OWN, pkid = past))
            assertFalse("a marker, no new root", resend.reRooted)
            assertArrayEquals("under the same init", secondInit, checkNotNull(resend.envelope.r?.init).eph)
            assertNull("exportedRoots no longer carries it", a.export())
            val afterResend = checkNotNull(a.ownPending())
            assertArrayEquals(secondInit, afterResend.pendingResetInit)
            assertArrayEquals(exported(restoreRoot), afterResend.prevPairwiseRoot)

            b.open(second.envelope, T1 + 131_000)
            a.open(b.seal("answer", T1 + 132_000), T1 + 132_000)
            assertNull("answered", a.ownPending())
            assertTrue(checkNotNull(a.export()).confirmed)
            assertArrayEquals("one root", a.root(), b.root())
        }

    /**
     * A restore's re-send (ADR 2026-11.dgj2) after the peer rotated within its retention: the marker under the same init
     * goes whatever the pin says ([RatchetSessions.ResetCause.RESEND_OWN]), where [RatchetSessions.ResetCause.REMARK_OWN],
     * which needs the pinned prekey to be the init's, declines. The peer, still holding the old prekey, adopts the marker
     * and its answer confirms us: one root, no re-root.
     */
    @Test
    fun aRestoreResendGoesUnderItsInitAfterThePeerRotatedWithinItsRetention() =
        runTest {
            val (a, b) = established()
            a.sessions.forget(b.nodeId)
            val restore = sealed(a.reset(T1, RatchetSessions.ResetCause.AFTER_WIPE))
            val restoreRoot = a.root()
            b.spks[2] = RatchetCrypto.generateKeyPair()

            assertTrue(a.reset(T1 + 1_000, RatchetSessions.ResetCause.REMARK_OWN, pkid = 2) is ResetSeal.Declined)
            val resend = sealed(a.reset(T1 + 2_000, RatchetSessions.ResetCause.RESEND_OWN, pkid = 2))
            assertFalse(resend.reRooted)
            assertArrayEquals(checkNotNull(restore.envelope.r?.init).eph, checkNotNull(resend.envelope.r?.init).eph)
            assertTrue("flagged", (checkNotNull(resend.envelope.r).flags and RatchetHeader.FLAG_RESET) != 0)
            assertArrayEquals("no new root", restoreRoot, a.root())

            b.open(resend.envelope, T1 + 3_000)
            a.open(b.seal("answer", T1 + 4_000), T1 + 4_000)
            assertNull("answered", a.ownPending())
            assertArrayEquals("one root", a.root(), b.root())
            assertTrue(a.reset(T1 + 5_000, RatchetSessions.ResetCause.RESEND_OWN, pkid = 2) is ResetSeal.Declined)
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

        // The restore's read (ADR 2026-11.dgj2): any own unanswered flagged reset, whatever the slot holds.
        assertNotNull(RatchetSessions.ownPendingReset(base))
        assertNotNull("no root replaced", RatchetSessions.ownPendingReset(base.copy(prevRoot = null)))
        assertNotNull("a marker over a plain init", RatchetSessions.ownPendingReset(base.copy(lastResetSentAt = 60L)))
        assertNull("a plain init", RatchetSessions.ownPendingReset(base.copy(lastResetSentAt = 0L)))
        assertNull("answered", RatchetSessions.ownPendingReset(base.copy(confirmed = true)))
        assertNull("the peer's init", RatchetSessions.ownPendingReset(base.copy(weAreInitiator = false)))

        // The prekey trigger: no session, or our own pending reset on a prekey the peer no longer keeps (C1).
        val onPkid3 = base.copy(prevRoot = null, initPkid = 3)
        val gone = 3 + RatchetSessions.PEER_PREKEYS_KEPT
        assertTrue("no session", RatchetSessions.restoreResetOwed(null, pinnedPkid = 3))
        assertFalse("on the pinned prekey", RatchetSessions.restoreResetOwed(onPkid3, pinnedPkid = 3))
        assertFalse("rotated, still kept by the peer", RatchetSessions.restoreResetOwed(onPkid3, pinnedPkid = gone - 1))
        assertTrue("rotated past the peer's retention", RatchetSessions.restoreResetOwed(onPkid3, pinnedPkid = gone))
        assertFalse("confirmed", RatchetSessions.restoreResetOwed(onPkid3.copy(confirmed = true), pinnedPkid = gone))
        assertFalse("a plain init", RatchetSessions.restoreResetOwed(onPkid3.copy(lastResetSentAt = 0L), pinnedPkid = gone))

        // Its bound (S1): one re-root per floor since our last reset, a few per process, a clock moved back not floored.
        val sentAt = onPkid3.lastResetSentAt
        val floor = RatchetSessions.RESTORE_REROOT_FLOOR_MS

        fun reRoot(
            now: Long,
            soFar: Int = 0,
            state: RatchetEngine.SessionState? = onPkid3,
        ) = RatchetSessions.restoreReRoot(state, gone, now, soFar)
        assertEquals(RatchetSessions.RestoreReRoot.FLOORED, reRoot(sentAt + floor - 1))
        assertEquals(RatchetSessions.RestoreReRoot.SEND, reRoot(sentAt + floor))
        assertEquals(RatchetSessions.RestoreReRoot.SEND, reRoot(sentAt - 1))
        // Measured from our init's stamp, not the last copy's: a marker just sent holds back no re-root (round-2 finding 1).
        assertEquals(
            RatchetSessions.RestoreReRoot.SEND,
            reRoot(sentAt + floor, state = onPkid3.copy(lastResetSentAt = sentAt + floor - 1)),
        )
        assertEquals(
            RatchetSessions.RestoreReRoot.CAPPED,
            reRoot(sentAt + floor, soFar = RatchetSessions.RESTORE_REROOT_CAP),
        )
        assertEquals(RatchetSessions.RestoreReRoot.SEND, reRoot(sentAt, state = null))
        assertEquals(
            RatchetSessions.RestoreReRoot.NOT_OWED,
            RatchetSessions.restoreReRoot(onPkid3, gone - 1, sentAt + floor, reRootsSoFar = 0),
        )
    }

    private companion object {
        const val T = 100_000_000L
        const val T1 = T + 600_000L
        val AAD = "aad".toByteArray()
    }
}
