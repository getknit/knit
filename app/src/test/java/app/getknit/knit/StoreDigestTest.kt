package app.getknit.knit

import app.getknit.knit.mesh.StoreDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Unit tests for [StoreDigest] — the order-independent, restart-stable content fingerprint of the carry store. */
class StoreDigestTest {

    @Test
    fun emptyDigestIsZero() {
        assertEquals(0L, StoreDigest().version.value)
    }

    @Test
    fun addingAMessageChangesTheVersion() {
        val d = StoreDigest()
        d.add("a")
        assertNotEquals(0L, d.version.value)
    }

    @Test
    fun addThenRemoveIsIdentity() {
        val d = StoreDigest()
        d.add("a")
        d.add("b")
        d.remove("a")
        d.remove("b")
        assertEquals("removing everything returns to empty", 0L, d.version.value)
    }

    @Test
    fun orderIndependent() {
        val a = StoreDigest().apply { add("x"); add("y"); add("z") }
        val b = StoreDigest().apply { add("z"); add("x"); add("y") }
        assertEquals(a.version.value, b.version.value)
    }

    @Test
    fun duplicateAddIsIdempotent() {
        val d = StoreDigest()
        d.add("a")
        val once = d.version.value
        d.add("a")
        assertEquals("a second add of the same id must not corrupt the XOR", once, d.version.value)
        d.remove("a")
        assertEquals("a single remove clears it", 0L, d.version.value)
    }

    @Test
    fun setMessagesMatchesIncrementalAdds() {
        val incremental = StoreDigest().apply { add("a"); add("b"); add("c") }
        val bulk = StoreDigest().apply { setMessages(listOf("c", "a", "b")) }
        assertEquals(incremental.version.value, bulk.version.value)
    }

    @Test
    fun sameContentsAcrossInstancesMatch_restartStable() {
        val before = StoreDigest().apply { setMessages(listOf("m1", "m2", "m3")) }
        val afterRestart = StoreDigest().apply { setMessages(listOf("m1", "m2", "m3")) }
        assertEquals("a restart to the same store yields the same version", before.version.value, afterRestart.version.value)
    }

    @Test
    fun differentContentsDiffer() {
        val a = StoreDigest().apply { setMessages(listOf("a", "b")) }
        val b = StoreDigest().apply { setMessages(listOf("a", "c")) }
        assertNotEquals(a.version.value, b.version.value)
    }

    @Test
    fun messageIdsReflectTheSet() {
        val d = StoreDigest()
        d.add("a")
        d.add("b")
        d.remove("a")
        assertEquals(setOf("b"), d.messageIds())
    }
}
