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
import org.junit.Assert.assertTrue
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
    /** A node: its transport, the keys it has pinned, every frame it received, and a wired [KeyExchange].
     *  The bound overrides default to the production values so existing tests are unaffected; the eviction/
     *  chunk/cap tests shrink them to exercise the bounds. */
    private class Node(
        val id: String,
        scope: CoroutineScope,
        val blocked: Set<String> = emptySet(),
        maxMissing: Int = 256,
        maxWanters: Int = 256,
        maxIdsPerReq: Int = 128,
        maxRequestIds: Int = 128,
        missingTtlMs: Long = 30 * 60_000L,
        val clock: () -> Long,
    ) {
        val transport = FakeLoopTransport(id)
        val pinned = ConcurrentHashMap<String, WireEnvelope>()
        val received = CopyOnWriteArrayList<InboundFrame>()
        val exchange =
            KeyExchange(
                transport = transport,
                selfId = { id },
                signRaw = { byteArrayOf(SIG_MARKER) },
                isBlocked = { it in blocked },
                now = clock,
                maxMissing = maxMissing,
                maxWanters = maxWanters,
                maxIdsPerReq = maxIdsPerReq,
                maxRequestIds = maxRequestIds,
                missingTtlMs = missingTtlMs,
            )
        private val router =
            MeshRouter(transport, scope) { wire, env, fromNodeId ->
                when (env.type) {
                    FrameType.KEY_REQ -> {
                        WireCodec.decodePayload<KeyReqContent>(env.payload)?.let { exchange.onRequest(it.nodeIds, fromNodeId) }
                    }

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
    fun wantEmitsSignedPointToPointRequestForMissingKey() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.transport.connect(n.transport)
            r.start(backgroundScope)
            n.start(backgroundScope)

            r.exchange.want("x")

            val reqs = n.keyRequestsReceived()
            assertEquals(1, reqs.size)
            assertFalse("a key request must never be flooded", reqs.first().wire.relay)
            assertArrayEquals("a key request must be signed", byteArrayOf(SIG_MARKER), reqs.first().wire.sig)
            assertEquals(listOf("x"), WireCodec.decodePayload<KeyReqContent>(reqs.first().envelope.payload)!!.nodeIds)
        }

    @Test
    fun repeatedWantsAreCooldownThrottledThenAllowedAfterElapsed() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.transport.connect(n.transport)
            r.start(backgroundScope)
            n.start(backgroundScope)

            r.exchange.want("x")
            r.exchange.want("x") // within the cooldown — suppressed
            assertEquals(1, n.keyRequestsReceived().size)

            clock += 60_000L // past the 30s cooldown
            r.exchange.want("x")
            assertEquals(2, n.keyRequestsReceived().size)
        }

    @Test
    fun selfBlockedAndAlreadyHeldKeysAreNotRequested() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope, blocked = setOf("blocked")) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.transport.connect(n.transport)
            r.start(backgroundScope)
            n.start(backgroundScope)

            r.exchange.want("r") // our own id
            r.exchange.want("blocked") // a blocked peer — we drop its frames anyway
            r.exchange.onProfilePinned("cached", profileWire("cached", "K")) // now held
            r.exchange.want("cached") // already cached

            assertEquals(0, n.keyRequestsReceived().size)
        }

    @Test
    fun missingKeyIsServedByAHoldingNeighbor() =
        runTest(UnconfinedTestDispatcher()) {
            // Topology: a — b — c. b holds a's profile (it's a's direct neighbor); c, two hops from a, is
            // missing a's key — the exact field gap this recovers.
            var clock = 0L
            val a = Node("a", backgroundScope) { clock }
            val b = Node("b", backgroundScope) { clock }
            val c = Node("c", backgroundScope) { clock }
            a.transport.connect(b.transport)
            b.transport.connect(c.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            c.start(backgroundScope)

            b.exchange.onProfilePinned("a", profileWire("a", "KEY_A"))

            c.exchange.want("a")

            assertEquals("KEY_A", pinnedKeyOf(c, "a"))
        }

    @Test
    fun missingKeyWalksHopByHopThroughANonHolder() =
        runTest(UnconfinedTestDispatcher()) {
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
            a.start(backgroundScope)
            b.start(backgroundScope)
            c.start(backgroundScope)
            d.start(backgroundScope)

            b.exchange.onProfilePinned("a", profileWire("a", "KEY_A"))

            d.exchange.want("a")

            assertEquals("d recovers a's key via c→b", "KEY_A", pinnedKeyOf(d, "a"))
            assertEquals("c caches a's key in transit", "KEY_A", pinnedKeyOf(c, "a"))
        }

    @Test
    fun newNeighborIsAskedForEveryOutstandingMissingKey() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.start(backgroundScope)
            n.start(backgroundScope)

            r.exchange.want("x") // no neighbors yet: remembered as missing, nothing sent
            assertEquals(0, n.keyRequestsReceived().size)

            r.transport.connect(n.transport)
            r.exchange.onNeighborAdded(Peer("n"))

            val reqs = n.keyRequestsReceived()
            assertEquals(1, reqs.size)
            assertEquals(listOf("x"), WireCodec.decodePayload<KeyReqContent>(reqs.first().envelope.payload)!!.nodeIds)
        }

    @Test
    fun missingGlobalCapEvictsOldestFirst() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope, maxMissing = 2) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.start(backgroundScope)
            n.start(backgroundScope)

            r.exchange.want("a") // no neighbors yet — just recorded as missing
            r.exchange.want("b")
            r.exchange.want("c") // over the cap → oldest ("a") evicted

            r.transport.connect(n.transport)
            r.exchange.onNeighborAdded(Peer("n"))

            val reqs = n.keyRequestsReceived()
            assertEquals(1, reqs.size)
            assertEquals(
                "the oldest want is evicted; the newest two survive, oldest-first",
                listOf("b", "c"),
                WireCodec.decodePayload<KeyReqContent>(reqs.first().envelope.payload)!!.nodeIds,
            )
        }

    @Test
    fun missingTtlSweepDropsStaleWantsButKeepsFresh() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope, missingTtlMs = 100) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.start(backgroundScope)
            n.start(backgroundScope)

            r.exchange.want("a") // last-asked at clock 0
            clock = 80
            r.exchange.want("b") // last-asked at clock 80

            clock = 150 // "a" is now 150ms stale (> 100 TTL); "b" is 70ms (fresh)
            assertEquals(1, r.exchange.sweepExpired())

            r.transport.connect(n.transport)
            r.exchange.onNeighborAdded(Peer("n"))
            assertEquals(
                listOf("b"),
                WireCodec
                    .decodePayload<KeyReqContent>(
                        n
                            .keyRequestsReceived()
                            .first()
                            .envelope.payload,
                    )!!
                    .nodeIds,
            )
        }

    @Test
    fun newNeighborBatchIsChunkedUnderThePayloadLimit() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope, maxIdsPerReq = 2) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.start(backgroundScope)
            n.start(backgroundScope)

            val ids = listOf("a", "b", "c", "d", "e")
            ids.forEach { r.exchange.want(it) } // no neighbors — all recorded as missing

            r.transport.connect(n.transport)
            r.exchange.onNeighborAdded(Peer("n"))

            val reqs = n.keyRequestsReceived()
            assertEquals("5 ids at 2 per frame → 3 frames", 3, reqs.size)
            reqs.forEach { req ->
                val batch = WireCodec.decodePayload<KeyReqContent>(req.envelope.payload)!!.nodeIds
                assertTrue("each chunk stays within the cap", batch.size <= 2)
            }
            val union = reqs.flatMap { WireCodec.decodePayload<KeyReqContent>(it.envelope.payload)!!.nodeIds }.toSet()
            assertEquals("every missing id is asked for exactly once across the chunks", ids.toSet(), union)
        }

    @Test
    fun retryMissingChunksBatches() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val r = Node("r", backgroundScope, maxIdsPerReq = 2) { clock }
            val n = Node("n", backgroundScope) { clock }
            r.start(backgroundScope)
            n.start(backgroundScope)

            val ids = listOf("a", "b", "c", "d", "e")
            ids.forEach { r.exchange.want(it) } // recorded while offline
            r.transport.connect(n.transport)

            r.exchange.retryMissing()

            val reqs = n.keyRequestsReceived()
            assertEquals(3, reqs.size)
            reqs.forEach { req ->
                assertTrue(WireCodec.decodePayload<KeyReqContent>(req.envelope.payload)!!.nodeIds.size <= 2)
            }
        }

    @Test
    fun onRequestCapsInboundIds() =
        runTest(UnconfinedTestDispatcher()) {
            // A hostile peer sends more ids than we'll process; only the first maxRequestIds are recursed.
            var clock = 0L
            val r = Node("r", backgroundScope, maxRequestIds = 2) { clock }
            val holder = Node("holder", backgroundScope) { clock }
            r.transport.connect(holder.transport)
            r.start(backgroundScope)
            holder.start(backgroundScope)

            r.exchange.onRequest(listOf("a", "b", "c", "d", "e"), "somePeer")

            // r holds none of them, so it recurses want() for the ones it processed — exactly the first two.
            val recursed =
                holder
                    .keyRequestsReceived()
                    .flatMap { WireCodec.decodePayload<KeyReqContent>(it.envelope.payload)!!.nodeIds }
                    .toSet()
            assertEquals(setOf("a", "b"), recursed)
        }

    @Test
    fun wantersKeyCapEvictsOldest() =
        runTest(UnconfinedTestDispatcher()) {
            // Raw peers (no KeyExchange/router) so r's recursive want() doesn't loop back and re-populate the
            // evicted wanters key — we only observe what r serves once each profile is pinned.
            var clock = 0L
            val r = Node("r", backgroundScope, maxWanters = 2) { clock }
            r.start(backgroundScope)
            val peers =
                listOf("p1", "p2", "p3").associateWith { pid ->
                    val t = FakeLoopTransport(pid)
                    val rx = CopyOnWriteArrayList<InboundFrame>()
                    r.transport.connect(t)
                    backgroundScope.launch { t.inbound.collect { rx.add(it) } }
                    rx
                }

            r.exchange.onRequest(listOf("k1"), "p1")
            r.exchange.onRequest(listOf("k2"), "p2")
            r.exchange.onRequest(listOf("k3"), "p3") // over the cap → wanters["k1"] (oldest) evicted

            r.exchange.onProfilePinned("k1", profileWire("k1", "K1"))
            r.exchange.onProfilePinned("k2", profileWire("k2", "K2"))
            r.exchange.onProfilePinned("k3", profileWire("k3", "K3"))

            fun servedProfile(
                rx: List<InboundFrame>,
                senderId: String,
            ) = rx.any { it.envelope.type == FrameType.PROFILE && it.envelope.senderId == senderId }
            assertFalse("k1's wanter was evicted → never served", servedProfile(peers.getValue("p1"), "k1"))
            assertTrue("k2 survived the cap → served to p2", servedProfile(peers.getValue("p2"), "k2"))
            assertTrue("k3 survived the cap → served to p3", servedProfile(peers.getValue("p3"), "k3"))
        }

    private fun pinnedKeyOf(
        node: Node,
        nodeId: String,
    ): String? {
        val wire = node.pinned[nodeId] ?: return null
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return null
        return WireCodec.decodePayload<ProfileContent>(env.payload)?.pubKey
    }

    /** A minimal signed profile frame for [nodeId] advertising [pubKey], as a holder would have cached it. */
    private fun profileWire(
        nodeId: String,
        pubKey: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = "profile-$nodeId",
                senderId = nodeId,
                payload = WireCodec.encodePayload(ProfileContent(name = nodeId, status = "", pubKey = pubKey)),
            )
        return WireEnvelope(relay = false, sig = byteArrayOf(9), signed = WireCodec.encodeEnvelope(env))
    }

    private companion object {
        const val SIG_MARKER: Byte = 0x5A
    }
}
