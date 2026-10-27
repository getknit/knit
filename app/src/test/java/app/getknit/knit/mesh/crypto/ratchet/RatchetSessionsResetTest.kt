package app.getknit.knit.mesh.crypto.ratchet

import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetCause
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetSeal
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.RatchetHeader
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
 * `RatchetSessions.sealResetDm` never seals a reset the peer would refuse (ADR 2026-09.qerd, #85 and #86).
 *
 * Two real session services over the real Room SQL, one per side, so every case is the pair's view: what we
 * sealed and whether the peer opens it. The peer adopts a `FLAG_RESET` init only a minute after the last
 * replacement it adopted from us; a refused one is dropped as a duplicate after we have already purged the
 * root the peer is on, which is the six-hour blackout both issues end in. [T] sits well past an hour so the
 * peer's first replacement is never floored by the clock's origin.
 */
@RunWith(AndroidJUnit4::class)
class RatchetSessionsResetTest {
    private val sides = mutableListOf<Side>()

    private inner class Side(
        val nodeId: String,
        // Production's transactor: every locked block inside one write transaction, so a commit that throws rolls back.
        transacted: Boolean = false,
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
                transact =
                    if (transacted) {
                        object : SessionTransactor {
                            override suspend fun <R> transact(block: suspend () -> R): R = db.withWriteTransaction { block() }
                        }
                    } else {
                        SessionTransactor.None
                    },
            )
        lateinit var peer: Side

        private fun prekey(id: Int) = RatchetEngine.PeerPrekey(id = id, pub = peer.spks.getValue(id).pub)

        suspend fun seal(
            text: String,
            now: Long,
        ): EncEnvelope = checkNotNull(sessions.sealDm(peer.nodeId, peer.ik.pub, prekey(1), text.toByteArray(), AAD, now))

        suspend fun reset(
            now: Long,
            cause: ResetCause = ResetCause.ON_DEMAND,
            pkid: Int = 1,
        ): ResetSeal = sessions.sealResetDm(peer.nodeId, peer.ik.pub, prekey(pkid), RESET.toByteArray(), AAD, now, cause)

        /** Opens and, on success, commits — the pipeline's two phases back to back. */
        suspend fun open(
            env: EncEnvelope,
            now: Long,
        ): OpenOutcome {
            val r = checkNotNull(env.r)
            val outcome = sessions.peekOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) {
                assertTrue(
                    "commits what it peeked",
                    sessions.commitOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now) {},
                )
            }
            return outcome
        }

        /** The commit phase alone, with a row write that fails: what the pipeline's persist hook does when it throws. */
        suspend fun commitFailing(
            env: EncEnvelope,
            now: Long,
        ): Throwable? {
            val r = checkNotNull(env.r)
            return runCatching {
                sessions.commitOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now) { error("the row write failed") }
            }.exceptionOrNull()
        }

        suspend fun session() = sessions.sessionFor(peer.nodeId)

        /** What a backup restore leaves: every ratchet row for the peer gone, identity and prekeys intact. */
        suspend fun wipe() {
            sessions.forget(peer.nodeId)
        }
    }

    private fun pair(transacted: Boolean = false): Pair<Side, Side> {
        val a = Side("aaaaaaaa", transacted)
        val b = Side("bbbbbbbb", transacted)
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

    /** A session both sides confirmed at [T], [a] the initiator — the pair before [a] restores. */
    private suspend fun established(transacted: Boolean = false): Pair<Side, Side> {
        val (a, b) = pair(transacted)
        assertOpened(b.open(a.seal("hello", T), T))
        assertOpened(a.open(b.seal("hello back", T + 1), T + 1))
        assertTrue(checkNotNull(a.session()).confirmed)
        return a to b
    }

    private fun assertOpened(outcome: OpenOutcome): OpenOutcome.Opened {
        assertTrue("expected Opened, got $outcome", outcome is OpenOutcome.Opened)
        return outcome as OpenOutcome.Opened
    }

    private fun sealed(seal: ResetSeal): ResetSeal.Sealed {
        assertTrue("expected Sealed, got $seal", seal is ResetSeal.Sealed)
        return seal as ResetSeal.Sealed
    }

    private fun declined(seal: ResetSeal): String {
        assertTrue("expected Declined, got $seal", seal is ResetSeal.Declined)
        return (seal as ResetSeal.Declined).reason
    }

    @Test
    fun aResetWithNoSessionIsAFreshFlaggedInit() =
        runTest {
            val (a, b) = established()
            a.wipe()
            val seal = sealed(a.reset(T1))
            assertTrue(seal.reRooted)
            val r = checkNotNull(seal.envelope.r)
            assertNotNull(r.init)
            assertEquals(RatchetHeader.FLAG_RESET, r.flags)
            assertEquals(T1, checkNotNull(a.session()).lastResetSentAt)
            assertArrayEquals(RESET.toByteArray(), assertOpened(b.open(seal.envelope, T1 + 1)).plaintext)
        }

    /** #85: the heuristic's reset got there first, and the restore's own reset must not mint a second root over it. */
    @Test
    fun aSecondResetWhileOursIsUnansweredIsDeclinedAndThePairCarriesOn() =
        runTest {
            val (a, b) = established()
            a.wipe()
            val first = sealed(a.reset(T1))
            assertOpened(b.open(first.envelope, T1 + 1_000))
            val root = checkNotNull(a.session()).root

            declined(a.reset(T1 + 2_000, cause = ResetCause.AFTER_WIPE))
            declined(a.reset(T1 + 2_000))
            assertArrayEquals("the root the peer adopted is still ours", root, checkNotNull(a.session()).root)

            assertOpened(a.open(b.seal("re-sealed under your reset", T1 + 3_000), T1 + 3_000))
            assertOpened(b.open(a.seal("after", T1 + 4_000), T1 + 4_000))
        }

    @Test
    fun aFlaggedResetStillUnansweredAfterTheFloorReRoots() =
        runTest {
            val (a, b) = established()
            a.wipe()
            sealed(a.reset(T1)) // lost on the way
            declined(a.reset(T1 + FLOOR - 1))
            val second = sealed(a.reset(T1 + FLOOR))
            assertTrue(second.reRooted)
            assertOpened(b.open(second.envelope, T1 + FLOOR + 1))
        }

    /** #86: a group post opened the session with a plain init; the reset rides that init instead of replacing it. */
    @Test
    fun aResetOverOurPlainInitMarksItAndThePeerOpensItAsTheSameSession() =
        runTest {
            val (a, b) = established()
            a.wipe()
            val seed = a.seal("seed", T1)
            assertOpened(b.open(seed, T1 + 1_000))
            val before = checkNotNull(a.session())
            val peerRoot = checkNotNull(b.session()).root

            val marker = sealed(a.reset(T1 + 5_000))
            assertFalse(marker.reRooted)
            val r = checkNotNull(marker.envelope.r)
            assertArrayEquals("the same init rides", checkNotNull(seed.r?.init).eph, checkNotNull(r.init).eph)
            assertEquals(RatchetHeader.FLAG_RESET, r.flags)
            val after = checkNotNull(a.session())
            assertArrayEquals("no second root", before.root, after.root)
            assertEquals(T1 + 5_000, after.lastResetSentAt)

            assertArrayEquals(RESET.toByteArray(), assertOpened(b.open(marker.envelope, T1 + 6_000)).plaintext)
            assertArrayEquals("the peer stays on the init it adopted", peerRoot, checkNotNull(b.session()).root)
            declined(a.reset(T1 + 7_000)) // now a flagged init: once is enough inside the floor
            assertOpened(a.open(b.seal("reply", T1 + 8_000), T1 + 8_000))
        }

    @Test
    fun aMarkerIsAdoptedByAPeerThatNeverSawThePlainInit() =
        runTest {
            val (a, b) = established()
            a.wipe()
            a.seal("lost on the way", T1)
            val marker = sealed(a.reset(T1 + 1_000))
            assertFalse(marker.reRooted)
            assertOpened(b.open(marker.envelope, T1 + 2_000))
            assertOpened(a.open(b.seal("back", T1 + 3_000), T1 + 3_000))
            assertTrue(checkNotNull(a.session()).confirmed)
        }

    /**
     * The heuristic's reset: frames that failed to open prove the peer held another session with us, so it took
     * our init as a replacement — no later than it sealed its answer — and a re-root waits out its floor from there.
     */
    @Test
    fun theHeuristicsResetJustAfterOurInitWasAnsweredWaitsOutThePeersFloor() =
        runTest {
            val (a, b) = established()
            a.wipe()
            assertOpened(b.open(a.seal("seed", T1), T1 + 1_000))
            assertOpened(a.open(b.seal("reply", T1 + 2_000), T1 + 3_000))

            declined(a.reset(T1 + 30_000, ResetCause.UNREADABLE))
            val reset = sealed(a.reset(T1 + 3_000 + FLOOR, ResetCause.UNREADABLE))
            assertTrue(reset.reRooted)
            assertOpened(b.open(reset.envelope, T1 + 4_000 + FLOOR))
        }

    /**
     * A replacement whose row write throws rolls back whole, and the floor it would have started goes with it (#141
     * finding 11). The peer's session is still the old one and the reset still opens as new, so the copy custody or
     * the spool re-serves is adopted. A floor stamped ahead of the write outlived the rollback and refused that copy
     * for a minute, as if the replacement had been adopted.
     */
    @Test
    fun aReplacementWhoseRowWriteRollsBackStartsNoFloor() =
        runTest {
            val (a, b) = established(transacted = true)
            val before = checkNotNull(b.session()).root
            a.wipe()
            val reset = sealed(a.reset(T1))
            assertTrue(reset.reRooted)

            assertNotNull("the row write's failure reaches the caller", b.commitFailing(reset.envelope, T1 + 1_000))
            assertArrayEquals("rolled back: still the old root", before, checkNotNull(b.session()).root)
            assertOpened(b.open(reset.envelope, T1 + 2_000))
            assertArrayEquals("the replacement is adopted", checkNotNull(a.session()).root, checkNotNull(b.session()).root)
        }

    /** On first contact the peer *established* the session, which starts no floor: an on-demand reset goes out. */
    @Test
    fun anOnDemandResetRightAfterFirstContactGoesOut() =
        runTest {
            val (a, b) = pair()
            assertOpened(b.open(a.seal("hello", T), T))
            assertOpened(a.open(b.seal("hello back", T + 1_000), T + 1_000))
            val reset = sealed(a.reset(T + 2_000))
            assertTrue(reset.reRooted)
            assertOpened(b.open(reset.envelope, T + 3_000))
        }

    @Test
    fun afterAWipeAConfirmedSessionGetsTheMarkerWithNoInit() =
        runTest {
            val (a, b) = established()
            a.wipe()
            assertOpened(b.open(a.seal("seed", T1), T1 + 1_000))
            assertOpened(a.open(b.seal("reply", T1 + 2_000), T1 + 3_000))
            val root = checkNotNull(a.session()).root

            val marker = sealed(a.reset(T1 + 4_000, cause = ResetCause.AFTER_WIPE))
            assertFalse(marker.reRooted)
            val r = checkNotNull(marker.envelope.r)
            assertNull(r.init)
            assertEquals(0, r.flags)
            assertArrayEquals(root, checkNotNull(a.session()).root)
            assertArrayEquals(RESET.toByteArray(), assertOpened(b.open(marker.envelope, T1 + 5_000)).plaintext)
            declined(a.reset(T1 + 6_000, cause = ResetCause.AFTER_WIPE)) // a crash re-run of the restore sends nothing twice
        }

    @Test
    fun afterAWipeAResponderSessionGetsTheMarker() =
        runTest {
            val (a, b) = pair()
            assertOpened(a.open(b.seal("hello", T), T))
            val marker = sealed(a.reset(T + 1_000, cause = ResetCause.AFTER_WIPE))
            assertFalse(marker.reRooted)
            assertNull(checkNotNull(marker.envelope.r).init)
            assertOpened(b.open(marker.envelope, T + 2_000))
        }

    @Test
    fun afterAWipeAPendingInitAgainstARetiredPrekeyIsReplaced() =
        runTest {
            val (a, b) = pair()
            b.spks[2] = RatchetCrypto.generateKeyPair()
            a.seal("to the old prekey", T)
            val reset = sealed(a.reset(T + 1_000, cause = ResetCause.AFTER_WIPE, pkid = 2))
            assertTrue(reset.reRooted)
            assertEquals(2, checkNotNull(reset.envelope.r?.init).pkid)
            assertOpened(b.open(reset.envelope, T + 2_000))
        }

    // --- #135: an old-era frame drained after a reset never decides the resetter's new era ---

    /**
     * Drives [from] to send epoch [se] and returns that epoch's n=0, which [to] must open; [to] answers every
     * epoch below it, so the healing rule moves [from] one epoch per turnaround.
     */
    private suspend fun advanceTo(
        from: Side,
        to: Side,
        se: Int,
        start: Long,
    ): EncEnvelope {
        var t = start
        while (true) {
            val env = from.seal("epoch at $t", t)
            assertOpened(to.open(env, t))
            t++
            if (checkNotNull(env.r).se == se) return env
            assertOpened(from.open(to.seal("answer at $t", t), t))
            t++
        }
    }

    /**
     * #135 as the P3→P7 pair hit it. After A's reset, custody re-served an old-era frame of A's epoch 4 that B
     * holds no message row for (a receipt), so it reached the ratchet, opened under the kept `prevRoot`, and stored
     * its chain. A's restarted numbering then reached 4: its frames must open, not land on the old era's chain
     * index (DUPLICATE) or its key (AEAD_FAIL).
     */
    @Test
    fun anOldEraFrameDrainedAfterAResetNeverDecidesTheNewEpochOfTheSameNumber() =
        runTest {
            val (a, b) = established()
            val old4 = advanceTo(a, b, se = 4, start = T + 10)
            assertOpened(a.open(b.seal("answer", T + 100), T + 100))

            assertOpened(b.open(sealed(a.reset(T1)).envelope, T1))
            assertOpened(b.open(old4, T1 + 1_000)) // the re-serve, under prevRoot
            assertOpened(a.open(b.seal("answer the reset", T1 + 2_000), T1 + 2_000))

            val new4 = advanceTo(a, b, se = 4, start = T1 + 3_000)
            assertEquals(0, checkNotNull(new4.r).n)
            assertOpened(b.open(a.seal("new era, epoch 4, n=1", T1 + 4_000), T1 + 4_000))
        }

    /**
     * #135 as the P9→P8 pair hit it: the re-served old-era frames sat mid-epoch, so opening them under `prevRoot`
     * banked skipped keys for the indices below. The new era's n=0 then met a stale skipped key, a rung that
     * returned its own AEAD failure without trying anything else.
     */
    @Test
    fun aStaleSkippedKeyFromADrainedOldEraNeverDecidesTheNewEpoch() =
        runTest {
            val (a, b) = established()
            advanceTo(a, b, se = 2, start = T + 10)
            val mid = (1..3).map { a.seal("old era, epoch 2, n=$it", T + 50 + it) }
            mid.forEach { assertOpened(b.open(it, T + 60)) }
            assertOpened(a.open(b.seal("answer", T + 100), T + 100))

            assertOpened(b.open(sealed(a.reset(T1)).envelope, T1))
            mid.drop(1).forEach { assertOpened(b.open(it, T1 + 1_000)) } // n=2 and n=3 re-served: keys banked for 0 and 1
            assertOpened(a.open(b.seal("answer the reset", T1 + 2_000), T1 + 2_000))

            advanceTo(a, b, se = 2, start = T1 + 3_000)
            assertOpened(b.open(a.seal("new era, epoch 2, n=1", T1 + 4_000), T1 + 4_000))
        }

    private companion object {
        const val T = 100_000_000L
        const val T1 = T + 600_000L
        const val FLOOR = RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS
        const val RESET = "reset"
        val AAD = "aad".toByteArray()
    }
}
