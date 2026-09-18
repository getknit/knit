package app.getknit.knit.mesh.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [LinkCrossings] — one write per link per frame, forgotten with the link. */
class LinkCrossingsTest {
    private var now = 1_000L
    private val crossings = LinkCrossings(ttlMillis = 10_000L, maxPerLink = 4, clock = { now })

    @Test
    fun theFirstCrossingWritesAndTheSecondSkips() {
        assertTrue(crossings.firstCrossing("bob", "k1"))
        assertFalse(crossings.firstCrossing("bob", "k1"))
    }

    @Test
    fun linksAreIndependent() {
        // The same frame goes to every linked peer once — a crossing of Bob's link says nothing about Carol's.
        assertTrue(crossings.firstCrossing("bob", "k1"))
        assertTrue(crossings.firstCrossing("carol", "k1"))
        assertFalse(crossings.firstCrossing("carol", "k1"))
    }

    @Test
    fun anInboundMarkSuppressesTheEchoOutbound() {
        // The transport marks a frame on the way in; the re-fan toward the hop it came from is then a repeat.
        assertTrue(crossings.firstCrossing("bob", "k1")) // Bob handed it to us
        assertFalse(crossings.firstCrossing("bob", "k1")) // ...so it never goes back to Bob
    }

    @Test
    fun forgettingALinkStartsItClean() {
        // A fresh stream to the same peer: it may have restarted with an empty SeenSet and a wiped store.
        assertTrue(crossings.firstCrossing("bob", "k1"))
        crossings.forget("bob")
        assertTrue(crossings.firstCrossing("bob", "k1"))
    }

    @Test
    fun aCrossingExpiresWithTheWindow() {
        // The window is the receiver SeenSet's: past it the far end would deliver the frame again, so we may send it.
        assertTrue(crossings.firstCrossing("bob", "k1"))
        now += 9_999L
        assertFalse(crossings.firstCrossing("bob", "k1"))
        now += 1L
        assertTrue(crossings.firstCrossing("bob", "k1"))
    }

    @Test
    fun theOldestCrossingIsEvictedAtTheCap() {
        repeat(4) { assertTrue(crossings.firstCrossing("bob", "k$it")) }
        assertTrue(crossings.firstCrossing("bob", "k4")) // evicts k0
        assertTrue(crossings.firstCrossing("bob", "k0")) // new again
        assertFalse(crossings.firstCrossing("bob", "k4"))
    }
}
