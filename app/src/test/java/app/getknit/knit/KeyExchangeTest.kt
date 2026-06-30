package app.getknit.knit

import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.KeyExchange
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises [KeyExchange]'s demand-driven key recovery on the JVM with [FakeLoopTransport], the same way
 * [BlobExchangeTest] exercises the blob pull it mirrors. The signature gate ([app.getknit.knit.mesh.MeshManager]'s
 * verifyInbound) is out of scope here — these tests cover the request/serve/recurse logic and that a
 * request is emitted signed and point-to-point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyExchangeTest {

    /** A node: its transport, the keys it has pinned, every frame it received, and a wired [KeyExchange]. */
    private class Node(
        val id: String,
        scope: CoroutineScope,
        val blocked: Set<String> = emptySet(),
        val clock: () -> Long,
    ) {
        val transport = FakeLoopTransport(id)
        val pinned = ConcurrentHashMap<String, WireEnvelope>()
        val received = CopyOnWriteArrayList<InboundFrame>()
        val exchange = KeyExchange(
            transport = transport,
            selfId = { id },
            signRaw = { byteArrayOf(SIG_MARKER) },
            isBlocked = { it in blocked },
            now = clock,
        )
        private val router = MeshRouter(transport, scope) { wire, env, fromNodeId ->
            when (env.type) {
                FrameType.KEY_REQ ->
                    WireCodec.decodePayload<KeyReqContent>(env.payload)?.let { exchange.onRequest(it.nodeIds, fromNodeId) }
                FrameType.PROFILE -> {
                    pinned[env.senderId] = wire
                    exchange.onProfilePinned(env.senderId, wire)
                }
            }
        }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch { transport.inbound.collect { received.add(it) } }
        }

        fun keyRequestsReceived(): List<InboundFrame> = received.filter { it.envelope.type == FrameType.KEY_REQ }
    }

    @Test
    fun wantEmitsSignedPointToPointRequestForMissingKey() = runTest(UnconfinedTestDispatcher()) {
        var clock = 0L
        val r = Node("r", backgroundScope) { clock }
        val n = Node("n", backgroundScope) { clock }
        r.transport.connect(n.transport)
        r.start(backgroundScope); n.start(backgroundScope)

        r.exchange.want("x")

        val reqs = n.keyRequestsReceived()
        assertEquals(1, reqs.size)
        assertFalse("a key request must never be flooded", reqs.first().wire.relay)
        assertArrayEquals("a key request must be signed", byteArrayOf(SIG_MARKER), reqs.first().wire.sig)
        assertEquals(listOf("x"), WireCodec.decodePayload<KeyReqContent>(reqs.first().envelope.payload)!!.nodeIds)
    }

    @Test
    fun repeatedWantsAreCooldownThrottledThenAllowedAfterElapsed() = runTest(UnconfinedTestDispatcher()) {
        var clock = 0L
        val r = Node("r", backgroundScope) { clock }
        val n = Node("n", backgroundScope) { clock }
        r.transport.connect(n.transport)
        r.start(backgroundScope); n.start(backgroundScope)

        r.exchange.want("x")
        r.exchange.want("x") // within the cooldown — suppressed
        assertEquals(1, n.keyRequestsReceived().size)

        clock += 60_000L // past the 30s cooldown
        r.exchange.want("x")
        assertEquals(2, n.keyRequestsReceived().size)
    }

    @Test
    fun selfBlockedAndAlreadyHeldKeysAreNotRequested() = runTest(UnconfinedTestDispatcher()) {
        var clock = 0L
        val r = Node("r", backgroundScope, blocked = setOf("blocked")) { clock }
        val n = Node("n", backgroundScope) { clock }
        r.transport.connect(n.transport)
        r.start(backgroundScope); n.start(backgroundScope)

        r.exchange.want("r")        // our own id
        r.exchange.want("blocked")  // a blocked peer — we drop its frames anyway
        r.exchange.onProfilePinned("cached", profileWire("cached", "K")) // now held
        r.exchange.want("cached")   // already cached

        assertEquals(0, n.keyRequestsReceived().size)
    }

    @Test
    fun missingKeyIsServedByAHoldingNeighbor() = runTest(UnconfinedTestDispatcher()) {
        // Topology: a — b — c. b holds a's profile (it's a's direct neighbor); c, two hops from a, is
        // missing a's key — the exact field gap this recovers.
        var clock = 0L
        val a = Node("a", backgroundScope) { clock }
        val b = Node("b", backgroundScope) { clock }
        val c = Node("c", backgroundScope) { clock }
        a.transport.connect(b.transport)
        b.transport.connect(c.transport)
        a.start(backgroundScope); b.start(backgroundScope); c.start(backgroundScope)

        b.exchange.onProfilePinned("a", profileWire("a", "KEY_A"))

        c.exchange.want("a")

        assertEquals("KEY_A", pinnedKeyOf(c, "a"))
    }

    @Test
    fun missingKeyWalksHopByHopThroughANonHolder() = runTest(UnconfinedTestDispatcher()) {
        // Topology: a — b — c — d. Only b holds a's profile; d (three hops away, via c which also lacks it)
        // recovers it through the same hop-by-hop recursion as a blob pull.
        var clock = 0L
        val a = Node("a", backgroundScope) { clock }
        val b = Node("b", backgroundScope) { clock }
        val c = Node("c", backgroundScope) { clock }
        val d = Node("d", backgroundScope) { clock }
        a.transport.connect(b.transport)
        b.transport.connect(c.transport)
        c.transport.connect(d.transport)
        a.start(backgroundScope); b.start(backgroundScope); c.start(backgroundScope); d.start(backgroundScope)

        b.exchange.onProfilePinned("a", profileWire("a", "KEY_A"))

        d.exchange.want("a")

        assertEquals("d recovers a's key via c→b", "KEY_A", pinnedKeyOf(d, "a"))
        assertEquals("c caches a's key in transit", "KEY_A", pinnedKeyOf(c, "a"))
    }

    @Test
    fun newNeighborIsAskedForEveryOutstandingMissingKey() = runTest(UnconfinedTestDispatcher()) {
        var clock = 0L
        val r = Node("r", backgroundScope) { clock }
        val n = Node("n", backgroundScope) { clock }
        r.start(backgroundScope); n.start(backgroundScope)

        r.exchange.want("x") // no neighbors yet: remembered as missing, nothing sent
        assertEquals(0, n.keyRequestsReceived().size)

        r.transport.connect(n.transport)
        r.exchange.onNeighborAdded(Peer("n"))

        val reqs = n.keyRequestsReceived()
        assertEquals(1, reqs.size)
        assertEquals(listOf("x"), WireCodec.decodePayload<KeyReqContent>(reqs.first().envelope.payload)!!.nodeIds)
    }

    private fun pinnedKeyOf(node: Node, nodeId: String): String? {
        val wire = node.pinned[nodeId] ?: return null
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return null
        return WireCodec.decodePayload<ProfileContent>(env.payload)?.pubKey
    }

    /** A minimal signed profile frame for [nodeId] advertising [pubKey], as a holder would have cached it. */
    private fun profileWire(nodeId: String, pubKey: String): WireEnvelope {
        val env = RelayEnvelope(
            type = FrameType.PROFILE, id = "profile-$nodeId", senderId = nodeId,
            payload = WireCodec.encodePayload(ProfileContent(name = nodeId, status = "", pubKey = pubKey)),
        )
        return WireEnvelope(relay = false, sig = byteArrayOf(9), signed = WireCodec.encodeEnvelope(env))
    }

    private companion object {
        const val SIG_MARKER: Byte = 0x5A
    }
}
