package app.getknit.knit

import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.nearby.EndpointRegistry
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EndpointRegistry] — the pure endpoint/connection bookkeeping behind
 * `NearbyTransport`. Focus is the reconnection-correctness logic: stale-id pruning on rediscovery,
 * forgetting lost endpoints, the atomic connect gate, and the (lowered) retry backoff schedule.
 */
class EndpointRegistryTest {

    private fun wire(nodeId: String, version: Int = 1, caps: Long = 0L) =
        Protocol.PeerWire(nodeId, version, caps)

    /** Drive an endpoint to the connected state (the path `NearbyTransport` takes). */
    private fun EndpointRegistry.connect(endpointId: String) {
        beginConnecting(endpointId)
        markConnected(endpointId)
    }

    @Test
    fun mapsEndpointToNodeAndBack() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        assertEquals("nodeA", reg.nodeFor("e1"))
        assertEquals("e1", reg.endpointFor("nodeA"))
    }

    @Test
    fun rediscoveryUnderNewIdPrunesOldId() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.map("e2", wire("nodeA")) // same node returns with a fresh ephemeral id
        assertNull("stale id forgotten", reg.nodeFor("e1"))
        assertEquals("nodeA", reg.nodeFor("e2"))
        assertEquals("e2", reg.endpointFor("nodeA"))
    }

    @Test
    fun prunePreservesAStillConnectedOldId() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.connect("e1")
        reg.map("e2", wire("nodeA")) // new id while the old one is still an active connection
        assertEquals("connected old id kept", "nodeA", reg.nodeFor("e1"))
        assertEquals("nodeA", reg.nodeFor("e2"))
    }

    @Test
    fun onLostForgetsUnconnectedEndpointAndClearsBackoff() {
        var now = 0L
        val reg = EndpointRegistry { now }
        reg.map("e1", wire("nodeA"))
        reg.scheduleRetry("e1") // attemptAt = 5_000
        assertFalse("blocked by backoff", reg.beginConnecting("e1"))

        reg.onLost("e1")
        assertNull("mapping forgotten", reg.nodeFor("e1"))
        assertTrue("backoff cleared → immediate retry", reg.beginConnecting("e1"))
    }

    @Test
    fun onLostPreservesAConnectedEndpoint() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.connect("e1")
        reg.onLost("e1") // a discovery "lost" for a peer we're actually connected to
        assertEquals("nodeA", reg.nodeFor("e1"))
        assertTrue(reg.isConnected("e1"))
    }

    @Test
    fun onLostPreservesAStillConnectingEndpoint() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.beginConnecting("e1") // handshake in flight
        reg.onLost("e1") // Nearby drops the discovery beacon mid-bootstrap (GATT outlasts the scan)
        // The mapping must survive so the in-flight connect, if it succeeds, isn't stranded unmapped.
        assertEquals("mapping kept while connecting", "nodeA", reg.nodeFor("e1"))
        reg.markConnected("e1")
        assertEquals(
            "connected peer is a neighbor, not silently unmapped",
            setOf(Peer("nodeA", 1, 0L)),
            reg.neighbors(),
        )
    }

    @Test
    fun prunePreservesAStillConnectingOldId() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.beginConnecting("e1") // mid-handshake on the old id
        reg.map("e2", wire("nodeA")) // same node re-advertised under a fresh ephemeral id
        assertEquals("connecting old id kept", "nodeA", reg.nodeFor("e1"))
        assertTrue("still tracked as in flight", reg.isConnecting())
    }

    @Test
    fun markConnectedMakesItsEndpointAuthoritativeForTheNode() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.beginConnecting("e1")
        reg.map("e2", wire("nodeA")) // newer id repoints nodeToEndpoint at e2 while e1 still connecting
        reg.markConnected("e1") // but e1 is the one that actually connected
        assertEquals("send() resolves the live link", "e1", reg.endpointFor("nodeA"))
    }

    @Test
    fun isConnectingReflectsInFlightRequests() {
        val reg = EndpointRegistry { 0L }
        assertFalse(reg.isConnecting())
        reg.map("e1", wire("nodeA"))
        reg.beginConnecting("e1")
        assertTrue("a request is in flight", reg.isConnecting())
        reg.markConnected("e1")
        assertFalse("cleared once connected", reg.isConnecting())
    }

    @Test
    fun beginConnectingGatesUntilBackoffWindowElapses() {
        var now = 0L
        val reg = EndpointRegistry { now }
        reg.map("e1", wire("nodeA"))
        assertTrue("first attempt allowed", reg.beginConnecting("e1"))
        reg.endConnecting("e1")
        reg.scheduleRetry("e1") // attemptAt = 5_000

        assertFalse(reg.beginConnecting("e1"))
        now = 4_999L
        assertFalse(reg.beginConnecting("e1"))
        now = 5_000L
        assertTrue("window elapsed", reg.beginConnecting("e1"))
    }

    @Test
    fun inFlightGuardPreventsDuplicateRequests() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        assertTrue(reg.beginConnecting("e1"))
        assertFalse("already in flight", reg.beginConnecting("e1"))
        reg.endConnecting("e1")
        assertTrue("freed", reg.beginConnecting("e1"))
    }

    @Test
    fun connectedEndpointIsNotReattempted() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.connect("e1")
        assertFalse(reg.beginConnecting("e1"))
    }

    @Test
    fun successResetsBackoffSoALaterDropReconnectsImmediately() {
        var now = 0L
        val reg = EndpointRegistry { now }
        reg.map("e1", wire("nodeA"))
        reg.beginConnecting("e1")
        reg.scheduleRetry("e1") // earlier failures left a backoff window
        reg.markConnected("e1") // success clears it
        reg.markDisconnected("e1")
        assertTrue("no stale backoff after a clean connect", reg.beginConnecting("e1"))
    }

    @Test
    fun nextBackoffDoublesAndCapsAtOneMinute() {
        assertEquals(5_000L, EndpointRegistry.nextBackoff(0L))
        assertEquals(10_000L, EndpointRegistry.nextBackoff(5_000L))
        assertEquals(20_000L, EndpointRegistry.nextBackoff(10_000L))
        assertEquals(40_000L, EndpointRegistry.nextBackoff(20_000L))
        assertEquals("capped, not 80_000", 60_000L, EndpointRegistry.nextBackoff(40_000L))
        assertEquals("stays at ceiling", 60_000L, EndpointRegistry.nextBackoff(60_000L))
    }

    @Test
    fun neighborsReflectOnlyConnectedPeersWithCapabilities() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA", version = 2, caps = 0x3L))
        reg.map("e2", wire("nodeB", version = 1, caps = 0x1L))
        reg.connect("e1") // nodeB stays merely discovered

        assertEquals(setOf(Peer("nodeA", 2, 0x3L)), reg.neighbors())
        assertEquals(1, reg.connectedCount())
        assertFalse(reg.isIsolated())
    }

    @Test
    fun isolationTracksConnectionCount() {
        val reg = EndpointRegistry { 0L }
        assertTrue(reg.isIsolated())
        assertEquals(0, reg.connectedCount())

        reg.map("e1", wire("nodeA"))
        reg.connect("e1")
        assertFalse(reg.isIsolated())

        reg.markDisconnected("e1")
        assertTrue(reg.isIsolated())
    }

    @Test
    fun unconnectedEndpointsListsKnownButNotConnected() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA")) // discovered, never connected
        reg.map("e2", wire("nodeB"))
        reg.connect("e2")
        assertEquals(listOf("e1"), reg.unconnectedEndpoints())
    }

    @Test
    fun clearEmptiesEverything() {
        val reg = EndpointRegistry { 0L }
        reg.map("e1", wire("nodeA"))
        reg.connect("e1")
        reg.clear()
        assertTrue(reg.isIsolated())
        assertNull(reg.nodeFor("e1"))
        assertNull(reg.endpointFor("nodeA"))
        assertEquals(emptySet<Peer>(), reg.neighbors())
    }
}
