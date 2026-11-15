package app.getknit.knit.mesh.spool

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.scopeanchor.DmScopeAnchorDao
import app.getknit.knit.data.scopeanchor.DmScopeAnchorEntity
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchor keeper (spec C-3.1-6, ADR 2026-11.dgj2, #324): which rows a backup can carry, and which armed rows a
 * restore turns into scopes. The rows are the backup's whole exposure, so every case that keeps one is a case that
 * ships it in every backup taken from then on.
 */
class DmScopeAnchorsTest : RoomDbTest() {
    private val me = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"

    private var now = 1_000L
    private val dao by lazy { CountingAnchorDao(db.dmScopeAnchorDao()) }
    private val anchors by lazy { DmScopeAnchors(dao, clock = { now }, armWindowMs = WINDOW) }

    private fun root(seed: Byte) = ByteArray(32) { seed }

    private fun both() = listOf(DmScopeAnchors.Confirmed(bob, root(1)), DmScopeAnchors.Confirmed(carol, root(2)))

    private fun pending(
        peer: String,
        seed: Byte,
    ) = DmScopeAnchors.Pending(peer, root(seed), ByteArray(32) { 9 })

    @Test
    fun `a confirmed session of a peer a restore resets keeps its live scope as the anchor`() =
        runTest {
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            val row = dao.rows().getValue(bob)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), row.scopeId)
            assertArrayEquals(ScopeCrypto.dmSealKeys(root(1), me, bob).sealKey, row.sealKey)
            assertArrayEquals(ScopeCrypto.dmSealKeys(root(1), me, bob).nonceKey, row.nonceKey)
            assertEquals(0L, row.armedUntil)
        }

    /** Design pass B2: a stranger whose init we answered, or ourselves, never gets a row a backup would carry. */
    @Test
    fun `a confirmed session outside the restore's set keeps no anchor`() =
        runTest {
            val confirmed = listOf(DmScopeAnchors.Confirmed(bob, root(1)), DmScopeAnchors.Confirmed(me, root(2)))
            anchors.reconcile(me, confirmed, targets = setOf(carol, me))
            assertTrue(dao.rows().isEmpty())
        }

    /**
     * Design pass B2: `ContactRemover` keeps the ratchet session (ADR 2026-09.adgd), so a keeper that wrote every
     * confirmed session would put a removed contact's anchor back at the next start. The removal sticks because the
     * contact has left the restore's set.
     */
    @Test
    fun `a removed contact's anchor does not come back at the next reconcile`() =
        runTest {
            val confirmed = listOf(DmScopeAnchors.Confirmed(bob, root(1)))
            anchors.reconcile(me, confirmed, targets = setOf(bob))
            anchors.remove(bob)
            assertNull(dao.rows()[bob])
            // A restart: the session is still confirmed, the thread is gone, so bob is no longer a target.
            anchors.reconcile(me, confirmed, targets = emptySet())
            assertNull(dao.rows()[bob])
        }

    /**
     * Round-1 finding C3: our own ordinary reset, unanswered, leaves the peer on the root the row names (our slot), so the
     * reconcile its root change runs leaves the row as it is, and a backup taken then still reaches the peer. Once the
     * peer answers, the new scope replaces it. A peer outside the targets is not kept for it.
     */
    @Test
    fun `our own unanswered reset keeps the anchor until the answer replaces it`() =
        runTest {
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            val before = dao.rows().getValue(bob).scopeId
            // Our reset: the session is no longer confirmed, the peer is still on root(1).
            anchors.reconcile(me, emptyList(), targets = setOf(bob), keep = setOf(bob))
            assertArrayEquals("left as it is", before, dao.rows().getValue(bob).scopeId)
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(3))), targets = setOf(bob))
            assertArrayEquals(ScopeCrypto.dmScopeId(root(3), me, bob), dao.rows().getValue(bob).scopeId)
            anchors.reconcile(me, emptyList(), targets = emptySet(), keep = setOf(bob))
            assertNull("kept only for a target", dao.rows()[bob])
        }

    /**
     * Round-2 finding 4: a peer that stops being a contact a restore resets — it left the group, its thread was deleted —
     * loses its armed row at the next reconcile, so the restore's re-sends to it stop; one still a target keeps its arming.
     */
    @Test
    fun `an armed anchor whose peer left the targets is deleted`() =
        runTest {
            anchors.reconcile(me, both(), setOf(bob, carol))
            anchors.arm(setOf(bob, carol))
            anchors.reconcile(me, emptyList(), targets = setOf(carol))
            assertNull(dao.rows()[bob])
            assertTrue("still armed", dao.rows().getValue(carol).armedUntil > now)
        }

    /** Round-2 finding 2: an unchanged unarmed row is not written again; an armed one still is, which disarms it. */
    @Test
    fun `an unchanged anchor is not rewritten, an armed one is disarmed`() =
        runTest {
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            val puts = dao.puts
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            assertEquals("no write for the same row", puts, dao.puts)
            anchors.arm(setOf(bob))
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            assertEquals(0L, dao.rows().getValue(bob).armedUntil)
        }

    /** Round-1 finding S2: a blocked peer's row goes at the next reconcile, armed or not, and is never written again. */
    @Test
    fun `a blocked peer's anchor is dropped armed or not and not written back`() =
        runTest {
            anchors.reconcile(me, both(), setOf(bob, carol))
            anchors.arm(setOf(bob, carol))
            anchors.reconcile(me, emptyList(), setOf(bob, carol), dropped = setOf(bob))
            assertNull("armed, still dropped", dao.rows()[bob])
            assertTrue("the other armed row stays", dao.rows().containsKey(carol))
            anchors.reconcile(me, both(), setOf(bob, carol), dropped = setOf(bob))
            assertNull("a confirmed session does not write it back", dao.rows()[bob])
        }

    /** Design pass N1: an era whose session has left the ratchet keeps no outer keys in the live database. */
    @Test
    fun `an unarmed anchor whose session is gone is deleted, an armed one is kept until its window ends`() =
        runTest {
            anchors.reconcile(me, both(), setOf(bob, carol))
            anchors.arm(setOf(bob))
            // After a restore neither session is confirmed.
            anchors.reconcile(me, emptyList(), targets = setOf(bob, carol))
            assertNull("unarmed, no session: gone", dao.rows()[carol])
            assertEquals(now + WINDOW, dao.rows().getValue(bob).armedUntil)
            now += WINDOW
            anchors.reconcile(me, emptyList(), targets = setOf(bob, carol))
            assertNull("the window closed unanswered: gone", dao.rows()[bob])
        }

    @Test
    fun `the restore's answer replaces the armed anchor with the new scope, unarmed`() =
        runTest {
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), setOf(bob))
            anchors.arm(setOf(bob))
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(3))), setOf(bob))
            val row = dao.rows().getValue(bob)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(3), me, bob), row.scopeId)
            assertEquals(0L, row.armedUntil)
            assertTrue("a confirmed session leaves no restore input", anchors.restored(listOf(pending(bob, 3))).isEmpty())
        }

    @Test
    fun `an armed anchor joined with our pending reset gives the restore's scope inputs`() =
        runTest {
            anchors.reconcile(me, both(), setOf(bob, carol))
            anchors.arm(setOf(bob, carol))
            val restored = anchors.restored(listOf(pending(bob, 4)))
            val entry = restored.single()
            assertEquals(bob, entry.peerId)
            assertArrayEquals(root(4), entry.pairwiseRoot)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), entry.anchorId)
            assertArrayEquals(ScopeCrypto.dmSealKeys(root(1), me, bob).sealKey, entry.anchorKeys.sealKey)
        }

    @Test
    fun `an unarmed or expired anchor gives nothing, and arming disarms the rest`() =
        runTest {
            anchors.reconcile(me, both(), setOf(bob, carol))
            assertTrue("unarmed", anchors.restored(listOf(pending(bob, 4))).isEmpty())
            dao.put(dao.rows().getValue(carol).copy(armedUntil = now + WINDOW))
            anchors.arm(setOf(bob))
            assertEquals("arming disarms every row first", 0L, dao.rows().getValue(carol).armedUntil)
            assertEquals(1, anchors.restored(listOf(pending(bob, 4), pending(carol, 5))).size)
            now += WINDOW
            assertTrue("expired", anchors.restored(listOf(pending(bob, 4))).isEmpty())
        }

    /**
     * Round-4 finding 3: the wall clock steps back after the restore armed a row. Inside [DmScopeAnchors.ARM_CLOCK_SLACK_MS]
     * the row is still this phone's own arming: armed, usable, and kept by a reconcile, so the restore's anchor survives.
     */
    @Test
    fun `a clock stepped back inside the slack keeps the restore's arming`() =
        runTest {
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            anchors.arm(setOf(bob))
            now -= DmScopeAnchors.ARM_CLOCK_SLACK_MS / 2
            assertTrue("still armed", anchors.isArmed(bob))
            assertEquals(1, anchors.restored(listOf(pending(bob, 4))).size)
            anchors.reconcile(me, emptyList(), targets = setOf(bob))
            assertTrue("kept by the reconcile", dao.rows().getValue(bob).armedUntil > now)
        }

    /** The hostile-file guard past the slack: a row armed beyond the window plus the slack is skipped and dropped. */
    @Test
    fun `a row armed past the window and the slack is still skipped and dropped`() =
        runTest {
            anchors.reconcile(me, listOf(DmScopeAnchors.Confirmed(bob, root(1))), targets = setOf(bob))
            dao.put(dao.rows().getValue(bob).copy(armedUntil = now + WINDOW + DmScopeAnchors.ARM_CLOCK_SLACK_MS + 1))
            assertTrue(anchors.restored(listOf(pending(bob, 4))).isEmpty())
            anchors.reconcile(me, emptyList(), targets = setOf(bob))
            assertNull(dao.rows()[bob])
        }

    /** Design pass N2: a restore file is hostile input; a bad row is skipped, never thrown on. */
    @Test
    fun `a malformed or over-armed row from a restore file is skipped, and a reconcile drops the over-armed one`() =
        runTest {
            dao.put(DmScopeAnchorEntity(bob, ByteArray(3), ByteArray(32), ByteArray(32), now + WINDOW, now))
            dao.put(DmScopeAnchorEntity(carol, ByteArray(32), ByteArray(32), ByteArray(32), Long.MAX_VALUE, now))
            assertTrue(anchors.restored(listOf(pending(bob, 4), pending(carol, 5))).isEmpty())
            anchors.reconcile(me, emptyList(), targets = setOf(bob, carol))
            assertNull("armed past any window a restore sets", dao.rows()[carol])
        }

    /**
     * The real DAO over [RoomDbTest]'s in-memory database, so the keeper runs against the SQL itself (`deleteUnarmed`'s
     * `armedUntil <= :now`, `disarmAll`, `arm`), counting [put] to tell a skipped write.
     */
    private class CountingAnchorDao(
        private val real: DmScopeAnchorDao,
    ) : DmScopeAnchorDao by real {
        /** How many rows [put] wrote: whether a reconcile skipped an unchanged one. */
        var puts = 0

        override suspend fun put(anchor: DmScopeAnchorEntity) {
            puts++
            real.put(anchor)
        }

        /** Every row, by peer. */
        suspend fun rows(): Map<String, DmScopeAnchorEntity> = real.all().associateBy { it.peerId }
    }

    private companion object {
        const val WINDOW = 48 * 60 * 60_000L
    }
}
