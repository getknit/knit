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
        val AAD = "aad".toByteArray()
    }
}
