package app.getknit.knit

import app.getknit.knit.mesh.StoreDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [StoreDigest] — the order-independent, restart-stable content fingerprint of the **live**
 * carry set, including the lazy TTL-boundary fold ([StoreDigest.current]) that work item #8 introduced.
 */
class StoreDigestTest {
    // Controllable wall clock; frames "never expire" by default so the pre-existing set-semantics tests
    // stay about the XOR fold, not about time.
    private var now = 0L
    private val far = Long.MAX_VALUE

    private fun digest() = StoreDigest { now }

    @Test
    fun emptyDigestIsZero() {
        assertEquals(0L, digest().version.value)
    }

    @Test
    fun addingAMessageChangesTheVersion() {
        val d = digest()
        d.add("a", far)
        assertNotEquals(0L, d.version.value)
    }

    @Test
    fun addThenRemoveIsIdentity() {
        val d = digest()
        d.add("a", far)
        d.add("b", far)
        d.remove("a")
        d.remove("b")
        assertEquals("removing everything returns to empty", 0L, d.version.value)
    }

    @Test
    fun orderIndependent() {
        val a =
            digest().apply {
                add("x", far)
                add("y", far)
                add("z", far)
            }
        val b =
            digest().apply {
                add("z", far)
                add("x", far)
                add("y", far)
            }
        assertEquals(a.version.value, b.version.value)
    }

    @Test
    fun duplicateAddIsIdempotent() {
        val d = digest()
        d.add("a", far)
        val once = d.version.value
        d.add("a", far)
        assertEquals("a second add of the same id must not corrupt the XOR", once, d.version.value)
        d.remove("a")
        assertEquals("a single remove clears it", 0L, d.version.value)
    }

    @Test
    fun setMessagesMatchesIncrementalAdds() {
        val incremental =
            digest().apply {
                add("a", far)
                add("b", far)
                add("c", far)
            }
        val bulk = digest().apply { setMessages(listOf("c" to far, "a" to far, "b" to far)) }
        assertEquals(incremental.version.value, bulk.version.value)
    }

    @Test
    fun sameContentsAcrossInstancesMatch_restartStable() {
        val before = digest().apply { setMessages(listOf("m1" to far, "m2" to far, "m3" to far)) }
        val afterRestart = digest().apply { setMessages(listOf("m1" to far, "m2" to far, "m3" to far)) }
        assertEquals("a restart to the same store yields the same version", before.version.value, afterRestart.version.value)
    }

    @Test
    fun differentContentsDiffer() {
        val a = digest().apply { setMessages(listOf("a" to far, "b" to far)) }
        val b = digest().apply { setMessages(listOf("a" to far, "c" to far)) }
        assertNotEquals(a.version.value, b.version.value)
    }

    @Test
    fun messageIdsReflectTheSet() {
        val d = digest()
        d.add("a", far)
        d.add("b", far)
        d.remove("a")
        assertEquals(setOf("b"), d.messageIds())
    }

    // ---- live-only membership + the lazy TTL-boundary fold (work item #8) ----

    @Test
    fun currentFlipsExactlyAtTheExpiryBoundary() {
        val d = digest()
        d.add("keep", far)
        d.add("dies", expiresAt = 100L)
        val withBoth = d.current()
        now = 100L // expiresAt == now is still live (mirrors ForwardDao's `expiresAt >= :now`)
        assertEquals("a frame at its exact expiry instant is still live", withBoth, d.current())
        now = 101L
        val after = d.current()
        assertNotEquals("crossing the boundary folds the id out", withBoth, after)
        assertEquals("the fold leaves exactly the live set", digest().apply { add("keep", far) }.current(), after)
    }

    @Test
    fun boundaryFoldIsBatched_oneVersionChangeForManyExpiries() {
        val d = digest()
        d.add("keep", far)
        d.add("dies1", 50L)
        d.add("dies2", 60L)
        val emissions = mutableListOf<Long>()
        now = 200L // both expired before we ever read
        emissions.add(d.current())
        assertEquals("one read folds every lapsed id in one step", digest().apply { add("keep", far) }.current(), emissions.single())
        assertEquals("a second read is a no-op", emissions.single(), d.current())
        assertEquals("version (the push channel) settled on the same value", emissions.single(), d.version.value)
    }

    @Test
    fun deadOnArrivalAddIsRefused() {
        val d = digest()
        now = 500L
        d.add("expired", expiresAt = 499L)
        assertEquals("an already-expired id never enters the fold", 0L, d.current())
        assertEquals(emptySet<String>(), d.messageIds())
    }

    @Test
    fun setMessagesDropsExpiredEntries() {
        now = 500L
        val d = digest().apply { setMessages(listOf("live" to 501L, "dead" to 499L)) }
        assertEquals("rebuild folds only live entries", digest().apply { add("live", 501L) }.current(), d.current())
    }

    @Test
    fun expiryMatchesRemove_sweepIsDigestNeutral() {
        // A node that lazily folded an id out and a node whose sweep deleted the row must agree.
        val lazily =
            digest().apply {
                add("x", 100L)
                add("y", far)
            }
        val swept =
            digest().apply {
                add("x", 100L)
                add("y", far)
            }
        now = 101L
        swept.remove("x") // the sweep path: DAO delete + digest.remove
        assertEquals(swept.current(), lazily.current())
    }

    @Test
    fun skewedClocksConvergeOncePastTheBoundary_restartStableInTime() {
        // Two nodes, clocks skewed by 5: each folds the boundary at its own wall time, but both land on the
        // identical live fingerprint once both clocks have passed it — the work-item-#8 acceptance shape.
        var nowA = 0L
        var nowB = 0L
        val a =
            StoreDigest { nowA }.apply {
                add("m", 100L)
                add("keep", far)
            }
        val b =
            StoreDigest { nowB }.apply {
                add("m", 100L)
                add("keep", far)
            }
        nowA = 103L // A is 5 ahead; it folds first
        nowB = 98L
        assertNotEquals("inside the skew window the digests legitimately differ", a.current(), b.current())
        nowB = 103L
        assertEquals("past the boundary on both clocks they reconverge with no exchange", a.current(), b.current())
    }

    @Test
    fun messageIdsAreLiveOnly() {
        val d = digest()
        d.add("live", far)
        d.add("dies", 10L)
        now = 11L
        assertEquals(setOf("live"), d.messageIds())
    }
}
