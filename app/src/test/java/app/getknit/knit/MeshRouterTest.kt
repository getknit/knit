package app.getknit.knit

import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        override suspend fun sendFile(file: File, to: Peer) = Unit
    }

    private fun chat(id: String, ttl: Int = 8, hops: Int = 0) =
        ChatFrame(id = id, senderId = "a", sentAt = 0L, body = "hi", ttl = ttl, hops = hops)

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
        val router = MeshRouter(transport, this) { _, _ -> }

        router.handleInbound(chat("m1"), fromNodeId = "b")

        assertEquals(1, transport.sent.size)
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
    fun multiHopRelayReachesNodeOutOfDirectRange() = runTest(UnconfinedTestDispatcher()) {
        // Topology: a — b — c  (a and c are NOT directly connected)
        val a = FakeLoopTransport("a")
        val b = FakeLoopTransport("b")
        val c = FakeLoopTransport("c")
        a.connect(b)
        b.connect(c)

        val deliveredAtC = mutableListOf<Frame>()
        // Unconfined dispatcher: collectors subscribe eagerly, so an originated frame propagates
        // inline through b's relay to c.
        val ra = MeshRouter(a, backgroundScope) { _, _ -> }
        val rb = MeshRouter(b, backgroundScope) { _, _ -> }
        val rc = MeshRouter(c, backgroundScope) { f, _ -> deliveredAtC += f }
        ra.start(); rb.start(); rc.start()

        ra.originate(chat("m1"))

        assertEquals(1, deliveredAtC.size)
        assertEquals("m1", deliveredAtC.single().id)
    }
}
