package app.getknit.knit.mesh.crypto.ratchet

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions.ResetSeal
import app.getknit.knit.mesh.protocol.EncEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `RatchetSessions.sealDm` / `sealResetDm` when they cannot seal (#172) and the prekey an unconfirmed session's
 * epochs are based on (#174). Two real session services over the real Room SQL, one per side, as in
 * `RatchetSessionsResetTest`: what one side sealed and whether the other opens it.
 */
@RunWith(AndroidJUnit4::class)
class RatchetSessionsSealTest {
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
        val store = RatchetRepository(db.ratchetDao(), clock = { T })
        val logged = mutableListOf<String>()
        val sessions =
            RatchetSessions(
                store = store,
                dhIdentityPriv = { ik.priv },
                spkPrivFor = { spks[it]?.priv },
                log = { logged += it },
            )
        lateinit var peer: Side

        fun prekey(id: Int) = RatchetEngine.PeerPrekey(id = id, pub = peer.spks.getValue(id).pub)

        suspend fun seal(
            text: String,
            now: Long,
            pkid: Int = 1,
        ): EncEnvelope? = sessions.sealDm(peer.nodeId, peer.ik.pub, prekey(pkid), text.toByteArray(), AAD, now)

        suspend fun open(
            env: EncEnvelope,
            now: Long,
        ): OpenOutcome {
            val r = checkNotNull(env.r)
            val outcome = sessions.peekOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) {
                assertTrue(sessions.commitOpen(nodeId, peer.nodeId, peer.ik.pub, r, env.nonce, env.ct, AAD, now) {})
            }
            return outcome
        }
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

    /**
     * `sealAnswer` (ADR 2026-10.x9fa): a ✓✓ or a reaction to a compact-delivered DM is sealed only under a session the
     * peer holds — never as a fresh init, never under our own init still unanswered — and says so, persisting nothing.
     * Once the peer has answered, or once we hold their init as responder, it seals like `sealDm`.
     */
    @Test
    fun anAnswerIsSealedOnlyUnderASessionThePeerHolds() =
        runTest {
            val (a, b) = pair()

            suspend fun sealAnswer(side: Side) =
                side.sessions.sealAnswer(side.peer.nodeId, side.peer.ik.pub, side.prekey(1), "ack".toByteArray(), AAD, T)

            assertSame("no session: declined", RatchetSessions.AnswerSeal.Declined, sealAnswer(a))
            assertNull("and nothing persisted", a.store.session(b.nodeId))

            val first = checkNotNull(a.seal("hello", T))
            assertSame("our own init unanswered: declined", RatchetSessions.AnswerSeal.Declined, sealAnswer(a))

            assertTrue(b.open(first, T) is OpenOutcome.Opened)
            val fromB = sealAnswer(b) as RatchetSessions.AnswerSeal.Sealed
            assertNull("the responder's answer carries no init", fromB.env.r?.init)
            assertTrue(a.open(fromB.env, T) is OpenOutcome.Opened)
            val fromA = sealAnswer(a) as RatchetSessions.AnswerSeal.Sealed
            assertNull("answered, ours seals without an init", fromA.env.r?.init)
        }

    /**
     * No session and no prekey to start one is still a decline, never `CannotSeal`: every caller has checked the peer
     * reads the ratchet, and a cleartext receipt in its place would purge a compact-first DM's signed copy everywhere.
     */
    @Test
    fun anAnswerWithNoSessionAndNoPrekeyIsDeclined() =
        runTest {
            val (a, b) = pair()
            assertSame(
                RatchetSessions.AnswerSeal.Declined,
                a.sessions.sealAnswer(b.nodeId, b.ik.pub, null, "ack".toByteArray(), AAD, T),
            )
            assertNull(a.store.session(b.nodeId))
        }

    /** Our own reset over a confirmed session is our init unanswered again: an answer waits for the peer's reply. */
    @Test
    fun anAnswerOnOurOwnUnansweredResetIsDeclinedUntilThePeerAnswersIt() =
        runTest {
            val (a, b) = pair()
            assertTrue(b.open(checkNotNull(a.seal("hello", T)), T) is OpenOutcome.Opened)
            assertTrue(a.open(checkNotNull(b.seal("hi", T)), T) is OpenOutcome.Opened)
            assertTrue(checkNotNull(a.store.session(b.nodeId)).confirmed)

            val reset =
                a.sessions.sealResetDm(
                    b.nodeId,
                    b.ik.pub,
                    a.prekey(1),
                    "reset".toByteArray(),
                    AAD,
                    T + RESET_GAP,
                ) as ResetSeal.Sealed
            val after = checkNotNull(a.store.session(b.nodeId))
            assertSame(
                RatchetSessions.AnswerSeal.Declined,
                a.sessions.sealAnswer(b.nodeId, b.ik.pub, a.prekey(1), "ack".toByteArray(), AAD, T + RESET_GAP),
            )
            assertEquals("nothing persisted", after.sendCount, checkNotNull(a.store.session(b.nodeId)).sendCount)

            assertTrue(b.open(reset.envelope, T + RESET_GAP) is OpenOutcome.Opened)
            assertTrue(a.open(checkNotNull(b.seal("answer", T + RESET_GAP)), T + RESET_GAP) is OpenOutcome.Opened)
            val sealed = a.sessions.sealAnswer(b.nodeId, b.ik.pub, a.prekey(1), "ack".toByteArray(), AAD, T + RESET_GAP)
            assertNull((sealed as RatchetSessions.AnswerSeal.Sealed).env.r?.init)
        }

    @After
    fun closeDbs() {
        sides.forEach { it.db.close() }
    }

    private fun text(outcome: OpenOutcome): String {
        assertTrue("expected Opened, got $outcome", outcome is OpenOutcome.Opened)
        return String((outcome as OpenOutcome.Opened).plaintext)
    }

    /** A peer's own signature over 32 zero bytes: it verifies, and X25519 refuses the point. */
    private val lowOrder = RatchetEngine.PeerPrekey(id = 1, pub = ByteArray(RatchetCrypto.KEY_BYTES))

    @Test
    fun aSealToALowOrderPrekeyIsNullAndPersistsNothing() =
        runTest {
            val (a, b) = pair()
            assertNull(a.sessions.sealDm(b.nodeId, b.ik.pub, lowOrder, "hi".toByteArray(), AAD, T))
            assertNull(a.store.session(b.nodeId))
            assertTrue(a.store.debugLocalEpochs(b.nodeId).isEmpty())
            assertEquals(1, a.logged.size)
            // And the next try is the same answer, not a half-written session.
            assertNull(a.sessions.sealDm(b.nodeId, b.ik.pub, lowOrder, "again".toByteArray(), AAD, T + 1))
            assertNull(a.store.session(b.nodeId))
        }

    @Test
    fun aSealToALowOrderIdentityKeyIsNull() =
        runTest {
            val (a, b) = pair()
            assertNull(a.sessions.sealDm(b.nodeId, ByteArray(RatchetCrypto.KEY_BYTES), a.prekey(1), "hi".toByteArray(), AAD, T))
            assertNull(a.store.session(b.nodeId))
        }

    @Test
    fun aResetToALowOrderPrekeyIsNoPrekey() =
        runTest {
            val (a, b) = pair()
            assertSame(ResetSeal.NoPrekey, a.sessions.sealResetDm(b.nodeId, b.ik.pub, lowOrder, "reset".toByteArray(), AAD, T))
            assertNull(a.store.session(b.nodeId))
        }

    /** A legacy unconfirmed row (no base recorded) whose epoch boundary would need the low-order key: null, not a throw. */
    @Test
    fun aLegacyRowsEpochOverALowOrderBaseIsRefusedNotThrown() =
        runTest {
            val (a, b) = pair()
            checkNotNull(a.seal("hello", T))
            val legacy = checkNotNull(a.store.session(b.nodeId)).copy(peerBasePub = null)
            a.store.upsertSession(legacy)
            val later = T + RatchetEngine.MAX_EPOCH_AGE_MS + 1
            assertNull(a.sessions.sealDm(b.nodeId, b.ik.pub, lowOrder, "x".toByteArray(), AAD, later))
            assertEquals(1, checkNotNull(a.store.session(b.nodeId)).sendEpoch)
        }

    /** #174 through the facade: Alice's next epoch after Bob's rotation reaches her opens at Bob. */
    @Test
    fun anUnconfirmedSessionsNextEpochOpensAfterThePeerRotatedItsPrekey() =
        runTest {
            val (alice, bob) = pair()
            val first = checkNotNull(alice.seal("first", T))
            bob.spks[2] = RatchetCrypto.generateKeyPair()
            val later = T + RatchetEngine.MAX_EPOCH_AGE_MS + 1
            val next = checkNotNull(alice.seal("a day later", later, pkid = 2))
            val r = checkNotNull(next.r)
            assertEquals(2, r.se)
            assertEquals(1, checkNotNull(r.init).pkid)
            assertEquals("a day later", text(bob.open(next, later + 1)))
            assertEquals("first", text(bob.open(first, later + 2)))
        }

    /**
     * A row an older build initiated holds no base: it keeps sealing its current epoch toward the rotated prekey,
     * and at the boundary seals nothing (the caller's v1) rather than a frame Bob cannot open.
     */
    @Test
    fun aLegacyRowSealsInEpochThenReturnsNullAtTheBoundary() =
        runTest {
            val (alice, bob) = pair()
            checkNotNull(alice.seal("first", T))
            alice.store.upsertSession(checkNotNull(alice.store.session(bob.nodeId)).copy(peerBasePub = null))
            bob.spks[2] = RatchetCrypto.generateKeyPair()

            val inEpoch = checkNotNull(alice.seal("still epoch 1", T + 1, pkid = 2))
            assertEquals("still epoch 1", text(bob.open(inEpoch, T + 2)))
            val later = T + RatchetEngine.MAX_EPOCH_AGE_MS + 1
            assertNull(alice.seal("a day later", later, pkid = 2))
            // The prekey the init names still serves the legacy row.
            assertEquals("named", text(bob.open(checkNotNull(alice.seal("named", later, pkid = 1)), later + 1)))
        }

    /**
     * The tag a seal returns names the root that very seal committed (ADR 2026-10.8hn6): on first contact the session
     * it initiated, which no read before it could see; the pair's root, so the peer that opens it holds the same; and
     * after a reset re-roots, the new one.
     */
    @Test
    fun aTaggedSealNamesTheRootItCommitted() =
        runTest {
            val (alice, bob) = pair()
            assertNull(alice.sessions.rootTagFor(bob.nodeId))
            val first = checkNotNull(alice.sessions.sealDmTagged(bob.nodeId, bob.ik.pub, alice.prekey(1), "hi".toByteArray(), AAD, T))
            assertEquals(alice.sessions.rootTagFor(bob.nodeId), first.rootTag)
            assertEquals("hi", text(bob.open(first.envelope, T + 1)))
            assertEquals("the pair's root", first.rootTag, bob.sessions.rootTagFor(alice.nodeId))

            val reset = bob.sessions.sealResetDm(alice.nodeId, alice.ik.pub, bob.prekey(1), "reset".toByteArray(), AAD, T + 2)
            assertTrue("re-rooted: $reset", reset is ResetSeal.Sealed)
            val after =
                checkNotNull(bob.sessions.sealDmTagged(alice.nodeId, alice.ik.pub, bob.prekey(1), "again".toByteArray(), AAD, T + 3))
            assertTrue(after.rootTag != first.rootTag)
            assertEquals(bob.sessions.rootTagFor(alice.nodeId), after.rootTag)
        }

    private companion object {
        const val T = 100_000_000L

        /** Past every replacement floor the peer applies, so the reset is adopted, never refused. */
        const val RESET_GAP = 2 * 60 * 60_000L
        val AAD = "aad".toByteArray()
    }
}
