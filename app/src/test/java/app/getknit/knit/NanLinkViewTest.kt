package app.getknit.knit

import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.wifiaware.NanLinkView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NanLinkView] — the one snapshot of `peers` that `WifiAwareTransport.refreshNeighbors` publishes as
 * `neighbors` and `linkSecurity` (ADR 2026-11.8mhs). The transport has no JVM rig, so a map stands in for `peers` and is
 * driven through put, overwrite and remove as register, replace and teardown drive it.
 */
class NanLinkViewTest {
    private val a = Peer("aaaa", protoVersion = 3, capabilities = 1L)
    private val b = Peer("bbbb", protoVersion = 3)

    @Test
    fun ofMapsEachLinkToItsOwnState() {
        val view = NanLinkView.of(listOf(a to LinkSecurity.SEALED, b to LinkSecurity.LEGACY))
        assertEquals(setOf(a, b), view.neighbors)
        assertEquals(mapOf("aaaa" to LinkSecurity.SEALED, "bbbb" to LinkSecurity.LEGACY), view.security)
    }

    /**
     * `peers` as the transport drives it — a put on register, a put over the same id on an R3 replace, a remove on
     * teardown — with the view rebuilt after each change, as `refreshNeighbors` is. Whether the transport calls it after
     * every change is read in the code (the wifi-aware reviewer's), not here: the transport has no JVM rig.
     */
    @Test
    fun aViewOfPeersFollowsAReplaceAndATeardown() {
        val peers = LinkedHashMap<String, Pair<Peer, LinkSecurity>>()

        fun view() = NanLinkView.of(peers.values.toList())

        peers["aaaa"] = a to LinkSecurity.LEGACY
        peers["bbbb"] = b to LinkSecurity.PROVEN
        assertEquals(mapOf("aaaa" to LinkSecurity.LEGACY, "bbbb" to LinkSecurity.PROVEN), view().security)

        // R3: a sealed link displaces the unproven one under the same id; the neighbour set does not move.
        peers["aaaa"] = a to LinkSecurity.SEALED
        assertEquals(LinkSecurity.SEALED, view().security["aaaa"])
        assertEquals(setOf(a, b), view().neighbors)

        // Teardown: the entry goes with the link, never left as a plain state.
        peers.remove("aaaa")
        assertFalse("a torn-down link must leave no entry", view().security.containsKey("aaaa"))
        assertEquals(setOf(b), view().neighbors)
    }

    @Test
    fun stopAndNoLinksPublishEmpty() {
        assertEquals(NanLinkView.EMPTY, NanLinkView.of(emptyList()))
        assertTrue(NanLinkView.EMPTY.security.isEmpty())
        assertTrue(NanLinkView.EMPTY.neighbors.isEmpty())
    }

    @Test
    fun neighborsAndSecurityNameTheSameLinks() {
        val view = NanLinkView.of(listOf(a to LinkSecurity.PROVEN, b to LinkSecurity.SEALED))
        assertEquals(view.neighbors.map { it.nodeId }.toSet(), view.security.keys)
    }
}
