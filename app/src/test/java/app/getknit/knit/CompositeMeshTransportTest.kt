package app.getknit.knit

import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeMeshTransportTest {

    /** A controllable child transport that records what the composite routes to it. */
    private class FakeChild(
        override val hasFastPlane: Boolean = false,
        override val kind: TransportKind = TransportKind.Other,
    ) : MeshTransport {
        private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
        override val neighbors = _neighbors.asStateFlow()
        private val _reachable = MutableStateFlow<Set<Peer>>(emptySet())
        override val reachable = _reachable.asStateFlow()
        private val healthState = MutableStateFlow(TransportHealth.Healthy)
        override val health = healthState.asStateFlow()
        private val inboundFlow = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val inbound = inboundFlow.asSharedFlow()
        private val filesFlow = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 16)
        override val incomingFiles = filesFlow.asSharedFlow()
        private val digestsFlow = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = 16)
        override val incomingDigests = digestsFlow.asSharedFlow()

        val sends = mutableListOf<Pair<WireEnvelope, Peer?>>()
        val sentFiles = mutableListOf<Peer>()
        val sentDigests = mutableListOf<Pair<Peer, List<String>>>()
        val fastFanouts = mutableListOf<WireEnvelope>()
        val fastSends = mutableListOf<Peer>()
        val suppressCalls = mutableListOf<Set<String>>()
        val foreignCalls = mutableListOf<Set<String>>()
        var starts = 0
        var stops = 0
        var heals = 0

        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun heal() { heals++ }
        override fun suppressDataPath(peers: Set<String>) { suppressCalls += peers }
        override fun onForeignReachable(peers: Set<String>) { foreignCalls += peers }
        override suspend fun send(wire: WireEnvelope, to: Peer?) { sends += wire to to }
        override fun fastFanout(wire: WireEnvelope) { fastFanouts += wire }
        override fun fastSend(wire: WireEnvelope, to: Peer) { fastSends += to }
        override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) { sentFiles += to }
        override suspend fun sendDigest(to: Peer, ids: List<String>) { sentDigests += to to ids }

        fun setNeighbors(vararg peers: Peer) { _neighbors.value = peers.toSet() }
        fun setReachable(vararg peers: Peer) { _reachable.value = peers.toSet() }
        fun setHealth(h: TransportHealth) { healthState.value = h }
        fun emitInbound(frame: InboundFrame) { inboundFlow.tryEmit(frame) }
        fun emitFile(file: ReceivedFile) { filesFlow.tryEmit(file) }
        fun emitDigest(digest: ReceivedDigest) { digestsFlow.tryEmit(digest) }
    }

    private fun wire() = WireEnvelope(sig = ByteArray(0), signed = ByteArray(0))

    @Test
    fun mergedNeighborsUnionDedupsByNodeIdRicherWins() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild(hasFastPlane = true)
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p", protoVersion = 2, capabilities = 3), Peer("q"))
        nan.setNeighbors(Peer("p", protoVersion = 1, capabilities = 1), Peer("r"))
        advanceUntilIdle()

        val ids = composite.neighbors.value.map { it.nodeId }.toSet()
        assertEquals(setOf("p", "q", "r"), ids)
        assertEquals("richer Peer wins on protoVersion", 2, composite.neighbors.value.first { it.nodeId == "p" }.protoVersion)
    }

    @Test
    fun mergedReachableIsUnion() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setReachable(Peer("a"))
        nan.setReachable(Peer("b"))
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), composite.reachable.value.map { it.nodeId }.toSet())
    }

    @Test
    fun healthIsHealthyIfAnyChildHealthy() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setHealth(TransportHealth.Degraded)
        nan.setHealth(TransportHealth.Healthy)
        advanceUntilIdle()
        assertEquals(TransportHealth.Healthy, composite.health.value)

        nan.setHealth(TransportHealth.Degraded)
        advanceUntilIdle()
        assertEquals("degraded only when every plane is down", TransportHealth.Degraded, composite.health.value)
    }

    @Test
    fun healthIsUnavailableOnlyWhenEveryRadioIsOff() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)

        // Both radios switched off -> the actionable "turn on a radio" state.
        bt.setHealth(TransportHealth.Unavailable)
        nan.setHealth(TransportHealth.Unavailable)
        advanceUntilIdle()
        assertEquals(TransportHealth.Unavailable, composite.health.value)

        // One radio on-but-failing outranks the other being off: "radio busy" is the better message.
        nan.setHealth(TransportHealth.Degraded)
        advanceUntilIdle()
        assertEquals("degraded outranks unavailable", TransportHealth.Degraded, composite.health.value)

        // Any healthy radio still wins.
        bt.setHealth(TransportHealth.Healthy)
        advanceUntilIdle()
        assertEquals(TransportHealth.Healthy, composite.health.value)
    }

    @Test
    fun sendToPeerPrefersBluetoothWhenBothHoldTheLink() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p"))
        nan.setNeighbors(Peer("p"))
        advanceUntilIdle()
        composite.send(wire(), Peer("p"))
        assertEquals(1, bt.sends.size)
        assertEquals(0, nan.sends.size)
    }

    @Test
    fun sendToPeerFallsBackWhenPreferredLacksLink() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        nan.setNeighbors(Peer("p")) // only NAN holds the link
        advanceUntilIdle()
        composite.send(wire(), Peer("p"))
        assertEquals(0, bt.sends.size)
        assertEquals(1, nan.sends.size)
    }

    @Test
    fun sendToUnknownPeerNoOps() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        composite.send(wire(), Peer("nobody"))
        assertEquals(0, bt.sends.size)
        assertEquals(0, nan.sends.size)
    }

    @Test
    fun broadcastSendsEachNeighborOnceOverPreferredChild() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p"), Peer("q"))
        nan.setNeighbors(Peer("p"), Peer("r")) // p overlaps → should go over BT only
        advanceUntilIdle()
        composite.send(wire(), to = null)
        assertEquals(setOf("p", "q"), bt.sends.map { it.second!!.nodeId }.toSet())
        assertEquals(setOf("r"), nan.sends.map { it.second!!.nodeId }.toSet())
    }

    @Test
    fun inboundFilesAndDigestsMergeFromBothChildren() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        val frames = mutableListOf<InboundFrame>()
        val files = mutableListOf<ReceivedFile>()
        val digests = mutableListOf<ReceivedDigest>()
        backgroundScope.launch { composite.inbound.collect { frames += it } }
        backgroundScope.launch { composite.incomingFiles.collect { files += it } }
        backgroundScope.launch { composite.incomingDigests.collect { digests += it } }
        advanceUntilIdle()

        bt.emitInbound(InboundFrame(wire(), envelope("m1", "s1"), "s1"))
        nan.emitInbound(InboundFrame(wire(), envelope("m2", "s2"), "s2"))
        bt.emitFile(ReceivedFile("s1", "/tmp/a", FileKind.ATTACHMENT, "k1", "image/jpeg"))
        nan.emitDigest(ReceivedDigest("s2", listOf("x")))
        advanceUntilIdle()

        assertEquals(setOf("m1", "m2"), frames.map { it.envelope.id }.toSet())
        assertEquals(listOf("k1"), files.map { it.key })
        assertEquals(listOf(listOf("x")), digests.map { it.ids })
    }

    @Test
    fun sendFileAndDigestRouteToTheLinkHolder() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        nan.setNeighbors(Peer("p"))
        advanceUntilIdle()
        composite.sendFile(File("x"), Peer("p"), FileMeta(FileKind.ATTACHMENT, "k", "image/jpeg"))
        composite.sendDigest(Peer("p"), listOf("i1"))
        assertEquals(listOf("p"), nan.sentFiles.map { it.nodeId })
        assertEquals(listOf(listOf("i1")), nan.sentDigests.map { it.second })
        assertTrue(bt.sentFiles.isEmpty() && bt.sentDigests.isEmpty())
    }

    @Test
    fun fastFanoutBlastsFastPlaneAndFansOverLinkChild() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild(hasFastPlane = false)
        val nan = FakeChild(hasFastPlane = true)
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p"))
        advanceUntilIdle()
        composite.fastFanout(wire())
        advanceUntilIdle()
        assertEquals("NAN gets the coordination-plane blast", 1, nan.fastFanouts.size)
        assertEquals("BT gets a normal flood instead", listOf<Peer?>(null), bt.sends.map { it.second })
    }

    @Test
    fun fastSendUsesFastPlaneOrTheLinkChild() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild(hasFastPlane = false)
        val nan = FakeChild(hasFastPlane = true)
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p"))
        advanceUntilIdle()
        composite.fastSend(wire(), Peer("p"))
        advanceUntilIdle()
        assertEquals(listOf("p"), nan.fastSends.map { it.nodeId })
        assertEquals("BT sends over its live link to p", listOf("p"), bt.sends.map { it.second!!.nodeId })
    }

    @Test
    fun startStopHealFanOutToAllChildren() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        composite.start(); composite.heal(); composite.stop()
        assertEquals(1, bt.starts); assertEquals(1, bt.heals); assertEquals(1, bt.stops)
        assertEquals(1, nan.starts); assertEquals(1, nan.heals); assertEquals(1, nan.stops)
    }

    @Test
    fun emptyChildrenIsInertAndDegraded() = runTest(UnconfinedTestDispatcher()) {
        val composite = CompositeMeshTransport(emptyList(), backgroundScope)
        assertEquals(TransportHealth.Degraded, composite.health.value)
        assertEquals(emptySet<Peer>(), composite.neighbors.value)
        // No child to route to → no throw.
        composite.send(wire(), Peer("p"))
        composite.send(wire(), to = null)
        composite.sendFile(File("x"), Peer("p"), FileMeta(FileKind.ATTACHMENT, "k", "m"))
        composite.start(); composite.stop(); composite.heal()
    }

    @Test
    fun singletonChildDelegates() = runTest(UnconfinedTestDispatcher()) {
        val only = FakeChild()
        val composite = CompositeMeshTransport(listOf(only), backgroundScope)
        only.setNeighbors(Peer("p"))
        advanceUntilIdle()
        assertEquals(setOf("p"), composite.neighbors.value.map { it.nodeId }.toSet())
        composite.send(wire(), Peer("p"))
        assertEquals(1, only.sends.size)
    }

    @Test
    fun suppressesLowerPreferenceSyncForPeersHeldByHigherPreferenceChild() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild() // index 0 — higher preference
        val nan = FakeChild() // index 1 — lower preference
        CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p"), Peer("q")) // BLE holds live links to p and q
        advanceUntilIdle()
        assertEquals("NAN suppresses the BLE-covered peers", setOf("p", "q"), nan.suppressCalls.last())
        assertTrue("nothing is higher-preference than BT, so it suppresses nothing", bt.suppressCalls.all { it.isEmpty() })

        bt.setNeighbors(Peer("p")) // q drops off BLE
        advanceUntilIdle()
        assertEquals("q resumes on NAN once it leaves the preferred plane", setOf("p"), nan.suppressCalls.last())
    }

    @Test
    fun pushesForeignReachableFromTheOtherChildren() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild()
        val nan = FakeChild()
        CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        // The early-warning is keyed off the *other* children's reachable sets, both directions.
        nan.setReachable(Peer("x"))
        advanceUntilIdle()
        assertEquals("BT learns what NAN can see", setOf("x"), bt.foreignCalls.last())

        bt.setReachable(Peer("y"))
        advanceUntilIdle()
        assertEquals("NAN learns what BT can see", setOf("y"), nan.foreignCalls.last())
    }

    @Test
    fun statusesReportPerChildKindHealthAndCounts() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild(kind = TransportKind.Bluetooth)
        val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        bt.setNeighbors(Peer("p"), Peer("q")) // 2 live links
        bt.setReachable(Peer("p"), Peer("q"), Peer("r")) // 3 nearby
        nan.setNeighbors(Peer("x")) // 1 live link
        nan.setReachable(Peer("x"), Peer("y")) // 2 nearby
        nan.setHealth(TransportHealth.Degraded)
        advanceUntilIdle()

        val statuses = composite.statuses.value
        assertEquals(
            "one entry per child, in preference order",
            listOf(TransportKind.Bluetooth, TransportKind.WifiAware),
            statuses.map { it.kind },
        )
        assertEquals(
            TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 2, nearby = 3),
            statuses.first { it.kind == TransportKind.Bluetooth },
        )
        assertEquals(
            TransportStatus(TransportKind.WifiAware, TransportHealth.Degraded, linked = 1, nearby = 2),
            statuses.first { it.kind == TransportKind.WifiAware },
        )
    }

    @Test
    fun peerTransportsTagEachNodeByTheRadiosItIsReachableOver() = runTest(UnconfinedTestDispatcher()) {
        val bt = FakeChild(kind = TransportKind.Bluetooth)
        val nan = FakeChild(kind = TransportKind.WifiAware)
        val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
        // Keyed off reachable (not neighbors), matching the "directly connected" classification.
        bt.setReachable(Peer("both"), Peer("bleOnly"))
        nan.setReachable(Peer("both"), Peer("nanOnly"))
        advanceUntilIdle()

        val map = composite.peerTransports.value
        assertEquals(setOf(TransportKind.Bluetooth, TransportKind.WifiAware), map["both"])
        assertEquals(setOf(TransportKind.Bluetooth), map["bleOnly"])
        assertEquals(setOf(TransportKind.WifiAware), map["nanOnly"])
    }

    @Test
    fun statusesAndPeerTransportsAreEmptyWithNoChildren() = runTest(UnconfinedTestDispatcher()) {
        val composite = CompositeMeshTransport(emptyList(), backgroundScope)
        assertEquals(emptyList<TransportStatus>(), composite.statuses.value)
        assertEquals(emptyMap<String, Set<TransportKind>>(), composite.peerTransports.value)
    }

    private fun envelope(id: String, senderId: String) =
        app.getknit.knit.mesh.protocol.RelayEnvelope(
            type = app.getknit.knit.mesh.protocol.FrameType.CHAT,
            id = id,
            senderId = senderId,
            payload = ByteArray(0),
        )
}
