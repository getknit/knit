package app.getknit.knit.mesh.crypto.ratchet

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.AnswerSeal
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetSeal
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.Protocol
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
 * Rule C through the facade (ADR 2026-10.4mjm, #189): a race loser whose `prevRoot` is its own init, abandoned
 * unconfirmed, moves back onto it when the peer seals under it as responder after the init it lost to. Two real session
 * services over the real Room SQL, one per side, so the flag goes through the v29 column and survives a reload, and
 * each case ends on the pair's view: one root. [a] is the smaller id, so it wins every race.
 */
@RunWith(AndroidJUnit4::class)
class RatchetSessionsReclaimTest {
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
        val reclaims = mutableListOf<String>()
        var sessions = build()
        lateinit var peer: Side

        /** A process restart: a new service over the same rows, every in-memory floor and stamp gone. */
        fun reload() {
            sessions = build()
        }

        /** A backup restore: the ratchet tables come back empty (they are TRANSIENT), then the process restarts. */
        suspend fun restore() {
            sessions.forget(peer.nodeId)
            reload()
        }

        private fun build() =
            RatchetSessions(
                store = RatchetRepository(db.ratchetDao(), clock = { T }),
                dhIdentityPriv = { ik.priv },
                spkPrivFor = { spks[it]?.priv },
                onReclaimed = { reclaims += it },
            )

        private fun prekey() = RatchetEngine.PeerPrekey(id = 1, pub = peer.spks.getValue(1).pub)

        suspend fun seal(
            text: String,
            now: Long,
        ): EncEnvelope = checkNotNull(sessions.sealDm(peer.nodeId, peer.ik.pub, prekey(), text.toByteArray(), AAD, now))

        suspend fun reset(now: Long): EncEnvelope {
            val seal = sessions.sealResetDm(peer.nodeId, peer.ik.pub, prekey(), "reset".toByteArray(), AAD, now)
            assertTrue("expected Sealed, got $seal", seal is ResetSeal.Sealed)
            return (seal as ResetSeal.Sealed).envelope
        }

        /** Opens [env] stamped [sentAt] (its envelope's, the sender's clock), peek then commit, as the pipeline does. */
        suspend fun open(
            env: EncEnvelope,
            now: Long,
            sentAt: Long,
        ) {
            val r = checkNotNull(env.r)
            val outcome = sessions.peekOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now, sentAt)
            assertTrue("expected Opened, got $outcome", outcome is OpenOutcome.Opened)
            assertTrue(sessions.commitOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now, sentAt) {})
        }

        suspend fun session(): RatchetEngine.SessionState = checkNotNull(sessions.sessionFor(peer.nodeId))
    }

    private fun pair(): Pair<Side, Side> {
        val a = Side("aaaaaaaa")
        val b = Side("bbbbbbbb")
        a.peer = b
        b.peer = a
        sides += a
        sides += b
        return a to b
    }

    @After
    fun closeDbs() {
        sides.forEach { it.db.close() }
    }

    /**
     * #189's trace, door 1 (the winner's intro answer is any frame carrying its init). [a] initiates and [b] answers;
     * [b] restores and resets; [a] takes the reset and answers under it. The re-served init then reaches [b] first: it
     * loses the race against its own reset, and the flag goes to disk. A reload later, [a]'s answer moves [b] back, and
     * what the reclaim wrote survives another reload.
     */
    @Test
    fun aRestoredLoserReclaimsItsResetAcrossAReloadAndThePairEndsOnOneRoot() =
        runTest {
            val (a, b) = pair()
            val init = a.seal("hello, with my init", T)
            b.open(init, T, sentAt = T)
            a.open(b.seal("hi", T + 1), T + 1, sentAt = T + 1)

            b.restore()
            a.open(b.reset(T1), T1, sentAt = T1)
            val answer = a.seal("answer under the reset", T1 + 1)
            assertFalse("a adopted the reset", a.session().weAreInitiator)

            b.open(init, T1 + 2, sentAt = T)
            b.reload()
            val lost = b.session()
            assertFalse("b lost the race to a's old init (#189)", a.session().root.contentEquals(lost.root))
            assertEquals("the flag is on disk: its own reset's stamp", T1, lost.prevRootOwnInitAt)
            assertEquals("the reset floor's stamp stays", T1, lost.lastResetSentAt)

            b.open(answer, T1 + 3, sentAt = T1 + 1)
            assertEquals("counted once", listOf(a.nodeId), b.reclaims)
            assertArrayEquals("one root", a.session().root, b.session().root)

            b.reload()
            val back = b.session()
            assertTrue(back.confirmed)
            assertTrue(back.weAreInitiator)
            assertEquals("our own clock again (ADR 026)", T1, back.establishedAt)
            assertNull(back.prevRootOwnInitAt)
            assertNull(back.initEphPub)
            assertEquals(T1, back.lastResetSentAt)
            assertTrue("it exports as confirmed", checkNotNull(b.sessions.exportedRoots().single()).confirmed)

            // Both ways under the one root, after the reload.
            a.open(b.seal("b after", T1 + 5), T1 + 5, sentAt = T1 + 5)
            b.open(a.seal("a after", T1 + 6), T1 + 6, sentAt = T1 + 6)
            assertArrayEquals(a.session().root, b.session().root)
        }

    /**
     * The reclaim's purge, through the real SQL: it drops the abandoned era's rows and spares the reclaimed root's. A
     * frame of the answer's epoch read inside the margin banks a skipped key under the reset's root; the reclaim then
     * runs on another index. Read through the DAO by root tag, the banked key survives and the adopted root's chain is
     * gone — and the banked key still opens its frame. A purge of everything would leave that frame a duplicate.
     */
    @Test
    fun aReclaimsPurgeSparesTheReclaimedRootsRowsInRoom() =
        runTest {
            val (a, b) = pair()
            val init = a.seal("hello", T)
            b.open(init, T, sentAt = T)
            a.open(b.seal("hi", T + 1), T + 1, sentAt = T + 1)
            b.restore()
            a.open(b.reset(T1), T1, sentAt = T1)
            val first = a.seal("first", T1 + 1)
            val second = a.seal("second", T1 + 2)
            val third = a.seal("third", T1 + 3)
            b.open(init, T1 + 4, sentAt = T)
            val adoptedTag = RatchetCrypto.rootTag(b.session().root)
            val resetTag = RatchetCrypto.rootTag(checkNotNull(b.session().prevRoot))
            b.open(third, T1 + 5, sentAt = T)
            assertTrue("no reclaim yet", b.reclaims.isEmpty())
            val dao = b.db.ratchetDao()
            val se = checkNotNull(first.r).se
            assertEquals(1, dao.skippedKeys(a.nodeId, se, 0).count { it.rootTag == resetTag })
            assertTrue("the adopted root has a row", dao.recvEpochs(a.nodeId, checkNotNull(init.r).se).any { it.rootTag == adoptedTag })
            b.open(second, T1 + 6, sentAt = T1 + 2)
            assertEquals(listOf(a.nodeId), b.reclaims)
            assertEquals(
                "index 0 banked under the reclaimed root survives",
                1,
                dao.skippedKeys(a.nodeId, se, 0).count {
                    it.rootTag ==
                        resetTag
                },
            )
            assertTrue("no row of the adopted root", dao.recvEpochs(a.nodeId, checkNotNull(init.r).se).none { it.rootTag == adoptedTag })
            // The key banked under the reclaimed root is still there to open its frame.
            b.open(first, T1 + 7, sentAt = T1 + 1)
        }

    /** Rule C's own reset floor: the reclaim stamps the answer, so the heuristic's reset inside the minute is declined. */
    @Test
    fun theHeuristicsResetInsideTheMinuteAfterAReclaimIsDeclined() =
        runTest {
            val (a, b) = pair()
            val init = a.seal("hello", T)
            b.open(init, T, sentAt = T)
            a.open(b.seal("hi", T + 1), T + 1, sentAt = T + 1)
            b.restore()
            a.open(b.reset(T1), T1, sentAt = T1)
            val answer = a.seal("answer", T1 + 1)
            b.open(init, T1 + 2, sentAt = T)
            b.open(answer, T1 + 3, sentAt = T1 + 1)

            val prekey = RatchetEngine.PeerPrekey(id = 1, pub = a.spks.getValue(1).pub)
            val inside =
                b.sessions.sealResetDm(
                    a.nodeId,
                    a.ik.pub,
                    prekey,
                    "r".toByteArray(),
                    AAD,
                    T1 + 30_000,
                    RatchetSessions.ResetCause.UNREADABLE,
                )
            assertTrue("declined: a adopted our reset under a minute ago, got $inside", inside is ResetSeal.Declined)
        }

    /**
     * Design-pass finding 6, the second door: an exists-gate re-ack the race winner sealed on its own unanswered init
     * (`sealAnswer` riding its first contact, x9fa's 2026-10-29 amendment) carries that init exactly as the intro answer
     * does. The swap does not care which door brought it.
     */
    @Test
    fun theSecondDoorAReAckRidingTheWinnersInitIsReclaimedPastToo() =
        runTest {
            val (a, b) = pair()
            val fromA = a.seal("first from a", T)
            val fromB = b.seal("first from b", T)
            b.open(fromA, T, sentAt = T)
            assertEquals("b lost the first race", T, b.session().prevRootOwnInitAt)
            a.open(fromB, T, sentAt = T)
            val prekey = RatchetEngine.PeerPrekey(id = 1, pub = b.spks.getValue(1).pub)
            val door = a.sessions.sealAnswer(b.nodeId, b.ik.pub, prekey, "re-ack".toByteArray(), AAD, T + 1, rideOwnFirstContact = true)
            assertTrue("sealed, got $door", door is AnswerSeal.Sealed)
            val reAck = (door as AnswerSeal.Sealed).env
            assertNotNull("the re-ack rides the winner's init", reAck.r?.init)
            a.open(b.seal("answer", T + 2), T + 2, sentAt = T + 2)
            assertTrue(a.session().confirmed)

            b.restore()
            a.open(b.reset(T1), T1, sentAt = T1)
            val answer = a.seal("answer under the reset", T1 + 1)
            b.open(reAck, T1 + 2, sentAt = T + 1)
            assertEquals("b lost the race again, on its reset", T1, b.session().prevRootOwnInitAt)

            b.open(answer, T1 + 3, sentAt = T1 + 1)
            assertEquals(listOf(a.nodeId), b.reclaims)
            assertArrayEquals("one root", a.session().root, b.session().root)
        }

    /**
     * #189 as filed: three eras on [b]'s side — [a]'s init, [b]'s answered reset, [b]'s second reset still pending —
     * and [a]'s first init re-served. No anchor survives for that era, so the race still runs and [b] lands on it
     * (the open half of the issue). Rule C's swap restores the pending reset once [a] answers it, which [a] does for a
     * flagged reset it adopts: the pair ends on the third era's root.
     */
    @Test
    fun issue189sThreeErasAnInitOlderThanPrevRootIsHealedByTheReclaim() =
        runTest {
            val (a, b) = pair()
            val eraOne = a.seal("hello, era one", T)
            b.open(eraOne, T, sentAt = T)
            a.open(b.seal("hi", T + 1), T + 1, sentAt = T + 1)

            a.open(b.reset(T1), T1, sentAt = T1)
            b.open(a.seal("era two answered", T1 + 1), T1 + 1, sentAt = T1 + 1)
            assertTrue(b.session().confirmed)
            val eraTwo = b.session().root

            val third = b.reset(T2)
            assertArrayEquals("era three pending over era two", eraTwo, b.session().prevRoot)
            a.open(third, T2, sentAt = T2)
            val answer = a.seal("era three answered", T2 + 1)

            b.open(eraOne, T2 + 2, sentAt = T)
            assertEquals("the old init raced and won (no anchor): b sits on era one", T2, b.session().prevRootOwnInitAt)
            assertFalse(b.session().weAreInitiator)

            b.open(answer, T2 + 3, sentAt = T2 + 1)
            assertEquals(listOf(a.nodeId), b.reclaims)
            assertArrayEquals("one root: era three's", a.session().root, b.session().root)
            assertTrue(b.session().weAreInitiator)
        }

    /**
     * Our own reset after a lost race abandons that init for good: a reset's fresh initiation never carries the flag,
     * whatever `prevRoot` it keeps, and the row says so after a reload.
     */
    @Test
    fun aResetAfterALostRaceClearsTheFlag() =
        runTest {
            val (a, b) = pair()
            val fromA = a.seal("first from a", T)
            b.seal("first from b", T)
            b.open(fromA, T, sentAt = T)
            assertEquals(T, b.session().prevRootOwnInitAt)
            b.reset(T1)
            val reset = b.session()
            assertNull(reset.prevRootOwnInitAt)
            assertNotNull("the root we had adopted drains", reset.prevRoot)
            b.reload()
            assertNull(b.session().prevRootOwnInitAt)
        }

    /** The pure freshness rule: the flag, a stamp, and strictly more than the skew bound past the winning init's `at`. */
    @Test
    fun sealedSinceLostRaceReadsTheFlagAndTheStampAlone() {
        val lost =
            RatchetEngine.SessionState(
                "p",
                true,
                false,
                ByteArray(32),
                ByteArray(32),
                true,
                T2,
                prevRootOwnInitAt = T1,
                establishedAt = T,
            )
        assertFalse(RatchetSessions.sealedSinceLostRace(null, T2))
        assertFalse("no stamp", RatchetSessions.sealedSinceLostRace(lost, null))
        assertFalse("no flag", RatchetSessions.sealedSinceLostRace(lost.copy(prevRootOwnInitAt = null), T2))
        assertFalse("at the bound", RatchetSessions.sealedSinceLostRace(lost, T + Protocol.MAX_FUTURE_SKEW_MS))
        assertTrue("past it", RatchetSessions.sealedSinceLostRace(lost, T + Protocol.MAX_FUTURE_SKEW_MS + 1))
    }

    private companion object {
        const val T = 1_700_000_000_000L

        /** The restore's (or the first) reset: ten minutes on, past the skew bound. */
        const val T1 = T + 10 * 60_000L

        /** A second reset, past the peer's one-minute floor for the first. */
        const val T2 = T1 + 10 * 60_000L
        val AAD = "id|sender|1700000000000|thread".toByteArray()
    }
}
