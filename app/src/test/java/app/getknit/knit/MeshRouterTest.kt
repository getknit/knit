package app.getknit.knit

import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MeshRouterTest {
    /** Transport double that records what the router sends. */
    private class RecordingTransport(
        neighborIds: Set<String>,
    ) : MeshTransport {
        val sent = mutableListOf<Pair<WireEnvelope, Peer?>>()
        private val _neighbors = MutableStateFlow(neighborIds.map { Peer(it) }.toSet())
        override val neighbors = _neighbors.asStateFlow()
        override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()
        override val inbound = MutableSharedFlow<InboundFrame>().asSharedFlow()
        override val incomingFiles = emptyFlow<ReceivedFile>()

        override fun start() = Unit

        override fun stop() = Unit

        override fun heal() = Unit

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            sent += wire to to
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean = true
    }

    /** Builds a (wrapper, envelope) pair for an addressed-or-broadcast chat frame. */
    private fun frame(
        id: String,
        ttl: Int = DEFAULT_TTL,
        hops: Int = 0,
        recipientId: String? = null,
        relay: Boolean = true,
    ): Pair<WireEnvelope, RelayEnvelope> {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = "a",
                sentAt = 0L,
                recipientId = recipientId,
                payload = ByteArray(0),
            )
        val wire = WireEnvelope(ttl = ttl, hops = hops, relay = relay, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
        return wire to env
    }

    @Test
    fun deliversNewFrameOnceAndDropsDuplicates() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val delivered = mutableListOf<String>()
            val router = MeshRouter(transport, this) { _, env, _ -> delivered += env.id }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, "b")
            router.handleInbound(wire, env, "b")

            assertEquals(1, delivered.size)
        }

    @Test
    fun relaysToOtherNeighborsExcludingSourceAndIncrementsHop() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle() // relay is scheduled after a jitter window, so let it fire

            assertEquals(1, transport.sent.size)
            val (relayed, to) = transport.sent.single()
            assertEquals("c", to?.nodeId)
            assertEquals(1, relayed.hops)
        }

    @Test
    fun floodsAddressedDmFramesSoTheyReachAnOutOfRangeRecipient() =
        runTest {
            // DMs have no routing table: an addressed frame must still flood like room traffic, or it could
            // never reach a recipient that isn't a direct neighbor. (Recipient-only delivery is in MeshManager.)
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _ -> }

            val (wire, env) = frame("dm1", recipientId = "z")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            val (relayed, to) = transport.sent.single()
            assertEquals("c", to?.nodeId)
            assertEquals(1, relayed.hops)
        }

    @Test
    fun doesNotRelayOnceTtlReached() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this) { _, _, _ -> }

            val (wire, env) = frame("m1", ttl = 3, hops = 3)
            router.handleInbound(wire, env, fromNodeId = "b")

            assertEquals(0, transport.sent.size)
        }

    @Test
    fun clampsForgedOversizedTtlOnRelay() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _ -> }

            val (wire, env) = frame("m1", ttl = Int.MAX_VALUE, hops = 0)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            val (relayed, _) = transport.sent.single()
            assertEquals(DEFAULT_TTL, relayed.ttl) // forwarded with a sane, bounded ttl
            assertEquals(1, relayed.hops)
        }

    @Test
    fun doesNotRelayForgedTtlOnceHopsReachLocalDefault() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val router = MeshRouter(transport, this, jitter = { 0L }) { _, _, _ -> }

            val (wire, env) = frame("m1", ttl = Int.MAX_VALUE, hops = DEFAULT_TTL)
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
        }

    @Test
    fun deliversButDoesNotRelayNonRelayableControlFrame() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c"))
            val delivered = mutableListOf<String>()
            val router = MeshRouter(transport, this) { _, env, _ -> delivered += env.id }

            val (wire, env) = frame("req1", relay = false)
            router.handleInbound(wire, env, fromNodeId = "b")

            assertEquals(1, delivered.size) // handled locally
            assertEquals(0, transport.sent.size) // but never flooded onward
        }

    @Test
    fun multiHopRelayReachesNodeOutOfDirectRange() =
        runTest(UnconfinedTestDispatcher()) {
            // Topology: a — b — c  (a and c are NOT directly connected)
            val a = FakeLoopTransport("a")
            val b = FakeLoopTransport("b")
            val c = FakeLoopTransport("c")
            a.connect(b)
            b.connect(c)

            val deliveredAtC = mutableListOf<String>()
            val ra = MeshRouter(a, backgroundScope, jitter = { 0L }) { _, _, _ -> }
            val rb = MeshRouter(b, backgroundScope, jitter = { 0L }) { _, _, _ -> }
            val rc = MeshRouter(c, backgroundScope, jitter = { 0L }) { _, env, _ -> deliveredAtC += env.id }
            ra.start()
            rb.start()
            rc.start()

            val (wire, env) = frame("m1")
            ra.originate(wire, env.id)
            advanceUntilIdle() // let b's jittered relay fire so the frame reaches c

            assertEquals(listOf("m1"), deliveredAtC)
        }

    @Test
    fun suppressesRelayWhenDuplicateOverheardDuringJitterWindow() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceTimeBy(40) // still inside the 100ms jitter window
            router.handleInbound(wire, env, fromNodeId = "c") // overheard duplicate → suppress
            advanceUntilIdle()

            assertEquals(0, transport.sent.size)
            assertEquals(1, metrics.snapshot().framesSuppressed)
        }

    @Test
    fun relaysAfterJitterWhenNoDuplicateOverheard() =
        runTest {
            val transport = RecordingTransport(setOf("b", "c", "d"))
            val metrics = MeshMetrics()
            val router =
                MeshRouter(
                    transport,
                    this,
                    metrics = metrics,
                    jitterWindowMs = 150L,
                    suppressThreshold = 2,
                    jitter = { 100L },
                ) { _, _, _ -> }

            val (wire, env) = frame("m1")
            router.handleInbound(wire, env, fromNodeId = "b")
            advanceUntilIdle()

            assertEquals(setOf("c", "d"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
            assertTrue(transport.sent.all { it.first.hops == 1 })
            assertEquals(1, metrics.snapshot().framesRelayed)
            assertEquals(0, metrics.snapshot().framesSuppressed)
        }
}
