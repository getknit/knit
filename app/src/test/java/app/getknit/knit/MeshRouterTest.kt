package app.getknit.knit

import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.Frame
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
    private class RecordingTransport(neighborIds: Set<String>) : MeshTransport {
        val sent = mutableListOf<Pair<Frame, Peer?>>()
        private val _neighbors = MutableStateFlow(neighborIds.map { Peer(it) }.toSet())
        override val neighbors = _neighbors.asStateFlow()
        override val inbound = MutableSharedFlow<InboundFrame>().asSharedFlow()
        override val incomingFiles = emptyFlow<ReceivedFile>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun heal() = Unit
        override suspend fun send(frame: Frame, to: Peer?) { sent += frame to to }
        override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) = Unit
    }

    private fun chat(id: String, ttl: Int = 8, hops: Int = 0, recipientId: String? = null) =
        ChatFrame(id = id, senderId = "a", sentAt = 0L, body = "hi", recipientId = recipientId, ttl = ttl, hops = hops)

    @Test
    fun deliversNewFrameOnceAndDropsDuplicates() = runTest {
        val transport = RecordingTransport(setOf("b", "c"))
        val delivered = mutableListOf<Frame>()
        val router = MeshRouter(transport, this) { f, _ -> delivered += f }

        val frame = chat("m1")
        router.handleInbound(frame, "b")
        router.handleInbound(frame, "b")

        assertEquals(1, delivered.size)
    }

    @Test
    fun relaysToOtherNeighborsExcludingSourceAndIncrementsHop() = runTest {
        val transport = RecordingTransport(setOf("b", "c"))
        val router = MeshRouter(transport, this, jitter = { 0L }) { _, _ -> }

        router.handleInbound(chat("m1"), fromNodeId = "b")
        advanceUntilIdle() // relay is now scheduled after a jitter window, so let it fire

        assertEquals(1, transport.sent.size)
        val (relayed, to) = transport.sent.single()
        assertEquals("c", to?.nodeId)
        assertEquals(1, relayed.hops)
    }

    @Test
    fun floodsAddressedDmFramesSoTheyReachAnOutOfRangeRecipient() = runTest {
        // DMs have no routing table: an addressed ChatFrame must still flood like room traffic, or it
        // could never reach a recipient that isn't a direct neighbor. (Recipient-only local delivery
        // is enforced in MeshManager, not the router.)
        val transport = RecordingTransport(setOf("b", "c"))
        val router = MeshRouter(transport, this, jitter = { 0L }) { _, _ -> }

        router.handleInbound(chat("dm1", recipientId = "z"), fromNodeId = "b")
        advanceUntilIdle()

        val (relayed, to) = transport.sent.single()
        assertEquals("c", to?.nodeId)
        assertEquals(1, relayed.hops)
    }

    @Test
    fun doesNotRelayOnceTtlReached() = runTest {
        val transport = RecordingTransport(setOf("b", "c"))
        val router = MeshRouter(transport, this) { _, _ -> }

        router.handleInbound(chat("m1", ttl = 3, hops = 3), fromNodeId = "b")

        assertEquals(0, transport.sent.size)
    }

    @Test
    fun clampsForgedOversizedTtlOnRelay() = runTest {
        // A peer forges a huge ttl to make a frame flood forever. The relayer must cap it to the local
        // DEFAULT_TTL so the hop count alone bounds propagation.
        val transport = RecordingTransport(setOf("b", "c"))
        val router = MeshRouter(transport, this, jitter = { 0L }) { _, _ -> }

        router.handleInbound(chat("m1", ttl = Int.MAX_VALUE, hops = 0), fromNodeId = "b")
        advanceUntilIdle()

        val (relayed, _) = transport.sent.single()
        assertEquals(DEFAULT_TTL, relayed.ttl) // forwarded with a sane, bounded ttl
        assertEquals(1, relayed.hops)
    }

    @Test
    fun doesNotRelayForgedTtlOnceHopsReachLocalDefault() = runTest {
        // Even with a forged Int.MAX_VALUE ttl, a frame already at DEFAULT_TTL hops must not relay —
        // the clamp applies to the stop-check, not just the forwarded copy.
        val transport = RecordingTransport(setOf("b", "c"))
        val router = MeshRouter(transport, this, jitter = { 0L }) { _, _ -> }

        router.handleInbound(chat("m1", ttl = Int.MAX_VALUE, hops = DEFAULT_TTL), fromNodeId = "b")
        advanceUntilIdle()

        assertEquals(0, transport.sent.size)
    }

    @Test
    fun deliversButDoesNotRelayNonRelayableControlFrame() = runTest {
        val transport = RecordingTransport(setOf("b", "c"))
        val delivered = mutableListOf<Frame>()
        val router = MeshRouter(transport, this) { f, _ -> delivered += f }

        router.handleInbound(BlobRequestFrame(id = "req1", senderId = "a", hash = "h"), fromNodeId = "b")

        assertEquals(1, delivered.size)      // handled locally
        assertEquals(0, transport.sent.size) // but never flooded onward
    }

    @Test
    fun multiHopRelayReachesNodeOutOfDirectRange() = runTest(UnconfinedTestDispatcher()) {
        // Topology: a — b — c  (a and c are NOT directly connected)
        val a = FakeLoopTransport("a")
        val b = FakeLoopTransport("b")
        val c = FakeLoopTransport("c")
        a.connect(b)
        b.connect(c)

        val deliveredAtC = mutableListOf<Frame>()
        // Unconfined dispatcher: collectors subscribe eagerly, so an originated frame propagates
        // inline through b's relay to c. Zero jitter keeps b's relay immediate.
        val ra = MeshRouter(a, backgroundScope, jitter = { 0L }) { _, _ -> }
        val rb = MeshRouter(b, backgroundScope, jitter = { 0L }) { _, _ -> }
        val rc = MeshRouter(c, backgroundScope, jitter = { 0L }) { f, _ -> deliveredAtC += f }
        ra.start(); rb.start(); rc.start()

        ra.originate(chat("m1"))
        advanceUntilIdle() // let b's jittered relay fire so the frame reaches c

        assertEquals(1, deliveredAtC.size)
        assertEquals("m1", deliveredAtC.single().id)
    }

    @Test
    fun suppressesRelayWhenDuplicateOverheardDuringJitterWindow() = runTest {
        // First sight schedules a relay at +100ms; a duplicate overheard from another neighbor before
        // then pushes the count to the threshold (2), cancelling our redundant rebroadcast.
        val transport = RecordingTransport(setOf("b", "c", "d"))
        val metrics = MeshMetrics()
        val router = MeshRouter(
            transport, this, metrics = metrics,
            jitterWindowMs = 150L, suppressThreshold = 2, jitter = { 100L },
        ) { _, _ -> }

        router.handleInbound(chat("m1"), fromNodeId = "b")
        advanceTimeBy(40) // still inside the 100ms jitter window
        router.handleInbound(chat("m1"), fromNodeId = "c") // overheard duplicate → suppress
        advanceUntilIdle()

        assertEquals(0, transport.sent.size)
        assertEquals(1, metrics.snapshot().framesSuppressed)
    }

    @Test
    fun relaysAfterJitterWhenNoDuplicateOverheard() = runTest {
        // Negative control: with no overhear, jitter only delays — the frame still relays to every
        // non-source neighbor.
        val transport = RecordingTransport(setOf("b", "c", "d"))
        val metrics = MeshMetrics()
        val router = MeshRouter(
            transport, this, metrics = metrics,
            jitterWindowMs = 150L, suppressThreshold = 2, jitter = { 100L },
        ) { _, _ -> }

        router.handleInbound(chat("m1"), fromNodeId = "b")
        advanceUntilIdle()

        assertEquals(setOf("c", "d"), transport.sent.mapNotNull { it.second?.nodeId }.toSet())
        assertTrue(transport.sent.all { it.first.hops == 1 })
        assertEquals(1, metrics.snapshot().framesRelayed)
        assertEquals(0, metrics.snapshot().framesSuppressed)
    }
}
