package app.getknit.knit

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.FanoutHint
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // cohesive single-SUT suite over one FakeChild harness; splitting would scatter it, as MeshManagerTest
class CompositeMeshTransportTest {
    /** A controllable child transport that records what the composite routes to it. */
    private class FakeChild(
        override val hasFastPlane: Boolean = false,
        override val kind: TransportKind = TransportKind.Other,
        override val highThroughput: Boolean = false,
        override val shortRange: Boolean = true,
    ) : MeshTransport {
        /** What [expectBulkTransfer] reports — i.e. whether this plane "armed" its on-demand link. */
        var armBulk = false

        /** What [sendFile] reports — false simulates the link dying in the check→enqueue window. */
        var acceptFiles = true

        /** What [feedingLoad] and [slowLinkTo] report. */
        var feeding = 0
        val slow = mutableSetOf<String>()

        override fun feedingLoad(nodeId: String): Int = feeding

        override fun slowLinkTo(nodeId: String): Boolean = nodeId in slow

        /** What [liveLinkFading] reports. */
        val fading = mutableSetOf<String>()

        override fun liveLinkFading(nodeId: String): Boolean = nodeId in fading

        val expectBulkCalls = mutableListOf<String>()
        private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
        override val neighbors = _neighbors.asStateFlow()
        private val _reachable = MutableStateFlow<Set<Peer>>(emptySet())
        override val reachable = _reachable.asStateFlow()
        private val _near = MutableStateFlow<Set<Peer>>(emptySet())
        override val near = _near.asStateFlow()
        private val healthState = MutableStateFlow(TransportHealth.Healthy)
        override val health = healthState.asStateFlow()
        private val heldState = MutableStateFlow(false)
        override val initiatorHeld = heldState.asStateFlow()
        private val offState = MutableStateFlow(false)
        override val switchedOff = offState.asStateFlow()
        private val dormantState = MutableStateFlow(false)
        override val dormant = dormantState.asStateFlow()
        private val candidatesNear = MutableStateFlow<Set<String>>(emptySet())
        override val awareCandidatesNear = candidatesNear.asStateFlow()
        private val candidatesHeard = MutableStateFlow<Set<String>>(emptySet())
        override val awareCandidatesHeard = candidatesHeard.asStateFlow()
        private val watching = MutableStateFlow(false)
        override val seesNewcomers = watching.asStateFlow()

        /** What [onForeignAwareCandidates] was handed, in order: near, heard, seesNewcomers. */
        val candidateCalls = mutableListOf<Triple<Set<String>, Set<String>, Boolean>>()

        override fun onForeignAwareCandidates(
            near: Set<String>,
            heard: Set<String>,
            seesNewcomers: Boolean,
        ) {
            candidateCalls += Triple(near, heard, seesNewcomers)
        }

        fun setCandidates(
            near: Set<String>,
            heard: Set<String>,
            sees: Boolean,
        ) {
            candidatesNear.value = near
            candidatesHeard.value = heard
            watching.value = sees
        }

        fun setDormant(dormant: Boolean) {
            dormantState.value = dormant
        }

        var releases = 0

        override fun releaseInitiatorHold() {
            releases++
        }

        private val inboundFlow = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val inbound = inboundFlow.asSharedFlow()
        private val filesFlow = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 16)
        override val incomingFiles = filesFlow.asSharedFlow()
        private val digestsFlow = MutableSharedFlow<ReceivedDigest>(extraBufferCapacity = 16)
        override val incomingDigests = digestsFlow.asSharedFlow()

        val sends = mutableListOf<Pair<WireEnvelope, Peer?>>()
        val sentFiles = mutableListOf<Pair<Peer, FileMeta>>()
        val sentDigests = mutableListOf<Pair<Peer, List<String>>>()
        val fastFanouts = mutableListOf<WireEnvelope>()
        val longRangeFanouts = mutableListOf<WireEnvelope>()
        val longRangeHints = mutableListOf<FanoutHint>()
        val fastSends = mutableListOf<Peer>()
        val suppressCalls = mutableListOf<Set<String>>()
        val foreignCalls = mutableListOf<Set<String>>()
        val nearCalls = mutableListOf<Set<String>>()
        val internetCalls = mutableListOf<Set<String>>()
        var starts = 0
        var stops = 0
        var heals = 0

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }

        override fun heal() {
            heals++
        }

        var pauses = 0
        var resumes = 0

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun suppressDataPath(peers: Set<String>) {
            suppressCalls += peers
        }

        override fun coveredByInternet(peers: Set<String>) {
            internetCalls += peers
        }

        override fun onForeignReachable(peers: Set<String>) {
            foreignCalls += peers
        }

        override fun onForeignNear(peers: Set<String>) {
            nearCalls += peers
        }

        override fun expectBulkTransfer(nodeId: String): Boolean {
            expectBulkCalls += nodeId
            return armBulk
        }

        /** What this plane's links report streaming in. */
        var arriving: Map<String, ArrivingFile> = emptyMap()

        override fun arrivingFiles(): Map<String, ArrivingFile> = arriving

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            sends += wire to to
        }

        override fun fastFanout(wire: WireEnvelope) {
            fastFanouts += wire
        }

        override fun longRangeFanout(
            wire: WireEnvelope,
            hint: FanoutHint,
        ) {
            longRangeFanouts += wire
            longRangeHints += hint
        }

        override fun fastSend(
            wire: WireEnvelope,
            to: Peer,
        ) {
            fastSends += to
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean {
            sentFiles += to to meta
            return acceptFiles
        }

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) {
            sentDigests += to to ids
        }

        fun setNeighbors(vararg peers: Peer) {
            _neighbors.value = peers.toSet()
        }

        fun setReachable(vararg peers: Peer) {
            _reachable.value = peers.toSet()
        }

        fun setNear(vararg peers: Peer) {
            _near.value = peers.toSet()
        }

        fun setHealth(h: TransportHealth) {
            healthState.value = h
        }

        fun setInitiatorHeld(held: Boolean) {
            heldState.value = held
        }

        fun setSwitchedOff(off: Boolean) {
            offState.value = off
        }

        fun emitInbound(frame: InboundFrame) {
            inboundFlow.tryEmit(frame)
        }

        fun emitFile(file: ReceivedFile) {
            filesFlow.tryEmit(file)
        }

        fun emitDigest(digest: ReceivedDigest) {
            digestsFlow.tryEmit(digest)
        }
    }

    private fun wire() = WireEnvelope(sig = ByteArray(0), signed = ByteArray(0))

    @Test
    fun arrivingFilesIsTheUnionAndTheCopyFurthestAlongCounts() =
        runTest(UnconfinedTestDispatcher()) {
            // A blob arriving on any plane is arriving (what BlobExchange reads); when two planes stream the same
            // one, the chat's ring follows the copy furthest along (#115).
            val bt = FakeChild()
            val nan = FakeChild(hasFastPlane = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.arriving = mapOf("a" to ArrivingFile("a", 10, 100), "b" to ArrivingFile("b", 5, null))
            nan.arriving = mapOf("a" to ArrivingFile("a", 70, 100), "c" to ArrivingFile("c", 1, 9))
            assertEquals(
                mapOf("a" to ArrivingFile("a", 70, 100), "b" to ArrivingFile("b", 5, null), "c" to ArrivingFile("c", 1, 9)),
                composite.arrivingFiles(),
            )
        }

    @Test
    fun pauseAndResumeReachEveryChild() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(hasFastPlane = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            composite.pause()
            composite.resume()
            composite.resume()
            assertEquals(listOf(1, 1), listOf(bt.pauses, nan.pauses))
            assertEquals(listOf(2, 2), listOf(bt.resumes, nan.resumes))
        }

    @Test
    fun mergedNeighborsUnionDedupsByNodeIdRicherWins() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(hasFastPlane = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p", protoVersion = 2, capabilities = 3), Peer("q"))
            nan.setNeighbors(Peer("p", protoVersion = 1, capabilities = 1), Peer("r"))
            advanceUntilIdle()

            val ids =
                composite.neighbors.value
                    .map { it.nodeId }
                    .toSet()
            assertEquals(setOf("p", "q", "r"), ids)
            assertEquals(
                "richer Peer wins on protoVersion",
                2,
                composite.neighbors.value
                    .first { it.nodeId == "p" }
                    .protoVersion,
            )
        }

    @Test
    fun mergedReachableIsUnion() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setReachable(Peer("a"))
            nan.setReachable(Peer("b"))
            advanceUntilIdle()
            assertEquals(
                setOf("a", "b"),
                composite.reachable.value
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    @Test
    fun healthIsHealthyIfAnyChildHealthy() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun healthIsUnavailableOnlyWhenEveryRadioIsOff() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun healthForegroundOnlySitsBetweenHealthyAndDegraded() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)

            // The Wi-Fi search refused off screen (ADR 2026-09.535d) outranks a seized or switched-off sibling:
            // "open Knit" is a cure the user holds.
            bt.setHealth(TransportHealth.Unavailable)
            nan.setHealth(TransportHealth.ForegroundOnly)
            advanceUntilIdle()
            assertEquals(TransportHealth.ForegroundOnly, composite.health.value)
            bt.setHealth(TransportHealth.Degraded)
            advanceUntilIdle()
            assertEquals("foreground-only outranks degraded", TransportHealth.ForegroundOnly, composite.health.value)

            // ...but never a plane that works: Bluetooth up means the node meshes, whatever Wi-Fi Aware is refused.
            bt.setHealth(TransportHealth.Healthy)
            advanceUntilIdle()
            assertEquals(TransportHealth.Healthy, composite.health.value)
        }

    @Test
    fun sendToPeerPrefersBluetoothWhenBothHoldTheLink() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun sendToPeerFallsBackWhenPreferredLacksLink() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun aLiveFrameRidesTheHighThroughputLinkAndFallsBackToBluetooth() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"), Peer("q"))
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()
            // A walkie chunk to a peer both radios link: the NDP, not Bluetooth's first claim (§3.11).
            composite.sendLive(wire(), Peer("p"))
            assertEquals(0, bt.sends.size)
            assertEquals(1, nan.sends.size)
            // Only Bluetooth links q: the live frame goes the ordinary way.
            composite.sendLive(wire(), Peer("q"))
            assertEquals(1, bt.sends.size)
            assertEquals(1, nan.sends.size)
        }

    @Test
    fun aCallsHoldReachesEveryChildAndHoldsIfAnyDoes() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            // The fakes keep the default hold, which is their bulk hint.
            assertFalse(composite.holdLink("p"))
            nan.armBulk = true
            assertTrue(composite.holdLink("p"))
            assertEquals(listOf("p", "p"), bt.expectBulkCalls)
            assertEquals(listOf("p", "p"), nan.expectBulkCalls)
        }

    @Test
    fun sendToUnknownPeerNoOps() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            composite.send(wire(), Peer("nobody"))
            assertEquals(0, bt.sends.size)
            assertEquals(0, nan.sends.size)
        }

    @Test
    fun broadcastSendsEachNeighborOnceOverPreferredChild() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun inboundFramesAreStampedWithTheirChildsKind() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val lora = FakeChild(kind = TransportKind.LoRa)
            val composite = CompositeMeshTransport(listOf(bt, lora), backgroundScope)
            val frames = mutableListOf<InboundFrame>()
            backgroundScope.launch { composite.inbound.collect { frames += it } }
            advanceUntilIdle()

            // A child constructs its frames without a kind (FramedLink is shared by BLE and NAN and cannot
            // know); the composite is the one place that knows which child a frame came off.
            bt.emitInbound(InboundFrame(wire(), envelope("m1", "s1"), "s1"))
            lora.emitInbound(InboundFrame(wire(), envelope("m2", "s2"), "s2"))
            advanceUntilIdle()

            assertEquals(
                mapOf("m1" to TransportKind.Bluetooth, "m2" to TransportKind.LoRa),
                frames.associate { it.envelope.id to it.kind },
            )
        }

    @Test
    fun inboundFilesAndDigestsMergeFromBothChildren() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun sendFileAndDigestRouteToTheLinkHolder() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()
            composite.sendFile(File("x"), Peer("p"), FileMeta(FileKind.ATTACHMENT, "k", "image/jpeg"))
            composite.sendDigest(Peer("p"), listOf("i1"))
            assertEquals(listOf("p"), nan.sentFiles.map { it.first.nodeId })
            assertEquals(listOf(listOf("i1")), nan.sentDigests.map { it.second })
            assertTrue(bt.sentFiles.isEmpty() && bt.sentDigests.isEmpty())
        }

    // --- Plane-aware attachment routing (the ≥ BULK_MIN_BYTES fast-plane preference) ---

    /** A real file of [bytes] length (sparse — setLength allocates no data) so sendFile's size gate reads it. */
    private fun fileOfSize(bytes: Long): File =
        File.createTempFile("blob", ".bin").also { f ->
            f.deleteOnExit()
            RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        }

    private fun bigAttachment() = FileMeta(FileKind.ATTACHMENT, "k", "image/jpeg")

    @Test
    fun aFilesPlaneIsTheHighThroughputChildsLinkThenItsReachThenNone() =
        runTest(UnconfinedTestDispatcher()) {
            // BlobExchange ranks its first ask by this (ADR 2026-10.f7gg): a peer Bluetooth links but Wi-Fi Aware only
            // sees is Reachable, never Linked — the Bluetooth link carries the ask, and the holder raises the NDP.
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("linked"), Peer("seen"), Peer("bt"))
            nan.setNeighbors(Peer("linked"))
            nan.setReachable(Peer("linked"), Peer("seen"))
            advanceUntilIdle()
            assertEquals(BulkRoute.Linked, composite.bulkRouteTo("linked"))
            assertEquals(BulkRoute.Reachable, composite.bulkRouteTo("seen"))
            assertEquals(BulkRoute.None, composite.bulkRouteTo("bt"))
            val btOnly = CompositeMeshTransport(listOf(bt), backgroundScope)
            assertEquals("Bluetooth alone answers None", BulkRoute.None, btOnly.bulkRouteTo("linked"))
        }

    @Test
    fun aHoldersLoadIsThePlaneTheFileWouldRideAndASlowLinkIsTheLinkHolders() =
        runTest(UnconfinedTestDispatcher()) {
            // A `blobhas` answer names the load of the plane its attachment would go out on (ADR 2026-10.6e5t): a live
            // Wi-Fi Aware link takes it, else the Bluetooth link holder; "slow" is the link a frame rides.
            val bt = FakeChild().apply { feeding = 3 }
            val nan = FakeChild(highThroughput = true).apply { feeding = 1 }
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("both"), Peer("bt"))
            nan.setNeighbors(Peer("both"))
            bt.slow += "bt"
            advanceUntilIdle()
            assertEquals(1, composite.feedingLoad("both"))
            assertEquals(3, composite.feedingLoad("bt"))
            assertEquals(0, composite.feedingLoad("nobody"))
            assertTrue(composite.slowLinkTo("bt"))
            assertTrue(!composite.slowLinkTo("both"))
        }

    @Test
    fun aLiveLinksFadeIsTheAnswerOfTheChildSendLiveUses() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild().apply { fading += setOf("both", "bt") }
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("both"), Peer("bt"))
            nan.setNeighbors(Peer("both"))
            advanceUntilIdle()
            assertTrue("only Bluetooth holds it, and its link fades", composite.liveLinkFading("bt"))
            assertTrue("a live chunk rides the Wi-Fi Aware link, which does not", !composite.liveLinkFading("both"))
            assertTrue("no link, no fade", !composite.liveLinkFading("nobody"))
        }

    @Test
    fun bigAttachmentPrefersTheHighThroughputPlaneWhenBothLinked() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()
            assertTrue(composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), bigAttachment()))
            assertEquals("the fast plane carries the big blob", 1, nan.sentFiles.size)
            assertTrue("Bluetooth is not double-sent", bt.sentFiles.isEmpty())
        }

    @Test
    fun bigAttachmentWaitsForTheArmedFastLinkThenRidesIt() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p")) // steady state: only BLE holds the link
            advanceUntilIdle()

            assertTrue(composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), bigAttachment()))
            assertEquals("the fast plane was armed for the peer", listOf("p"), nan.expectBulkCalls)
            assertTrue("nothing sent while the grace waits", nan.sentFiles.isEmpty() && bt.sentFiles.isEmpty())

            advanceTimeBy(1_000)
            nan.setNeighbors(Peer("p")) // the on-demand NDP came up inside the grace
            advanceUntilIdle()
            assertEquals("the blob rode the fast link", 1, nan.sentFiles.size)
            assertTrue("exactly one route fires — no BLE copy", bt.sentFiles.isEmpty())
        }

    @Test
    fun bigAttachmentFallsBackToBluetoothWhenTheGraceExpires() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val metrics = MeshMetrics()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope, metrics)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()

            composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), bigAttachment())
            advanceTimeBy(CompositeMeshTransport.BULK_GRACE_MS + 1)
            advanceUntilIdle()
            assertEquals("the fast link never formed → BLE fallback", 1, bt.sentFiles.size)
            assertTrue(nan.sentFiles.isEmpty())
            assertEquals(1L, metrics.nan().nanBulkGraceTimeouts)
        }

    @Test
    fun fastLinkedIsTheHighThroughputPlanesLinksAloneSoABluetoothNeighbourGainingWifiAwareShows() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-10.js2p's review: the merged neighbours do not move when a Bluetooth neighbour gains Wi-Fi Aware.
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()
            assertEquals(emptySet<String>(), composite.fastLinked.value)
            val merged = composite.neighbors.value

            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()
            assertEquals(setOf("p"), composite.fastLinked.value)
            assertEquals("the merged set did not move", merged.map { it.nodeId }, composite.neighbors.value.map { it.nodeId })
        }

    // --- Fast-only serves: Wi-Fi Aware or nothing (ADR 2026-10.js2p) ---

    private fun fastOnlyAttachment(onDropped: () -> Unit = {}) =
        FileMeta(FileKind.ATTACHMENT, "k", "image/jpeg", fastOnly = true, onDropped = onDropped)

    @Test
    fun aFastOnlyFileRidesALiveFastLinkAndIsNeverHandedToBluetoothWhenItRefuses() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val metrics = MeshMetrics()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope, metrics)
            bt.setNeighbors(Peer("p"))
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()
            // Small as it is, a live fast link carries it.
            assertTrue(composite.sendFile(fileOfSize(1_000), Peer("p"), fastOnlyAttachment()))
            assertEquals(1, nan.sentFiles.size)
            assertTrue(bt.sentFiles.isEmpty())

            nan.acceptFiles = false // the link died in the check→enqueue window
            assertTrue("refused, not fallen back", !composite.sendFile(fileOfSize(1_000), Peer("p"), fastOnlyAttachment()))
            assertTrue("Bluetooth carries no fast-only copy", bt.sentFiles.isEmpty())
            assertEquals(1L, metrics.files().fastOnlyDropped)
        }

    @Test
    fun aFastOnlyFileArmsWhateverItsSizeAndIsDroppedWhenTheGraceExpires() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val metrics = MeshMetrics()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope, metrics)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()
            var dropped = 0

            assertTrue("scheduled", composite.sendFile(fileOfSize(1_000), Peer("p"), fastOnlyAttachment { dropped++ }))
            assertEquals("armed below the size gate too", listOf("p"), nan.expectBulkCalls)
            advanceTimeBy(CompositeMeshTransport.BULK_GRACE_MS + 1)
            advanceUntilIdle()
            assertTrue("no Bluetooth copy at the grace's end", bt.sentFiles.isEmpty())
            assertTrue(nan.sentFiles.isEmpty())
            assertEquals("the holder is told the copy went nowhere", 1, dropped)
            assertEquals(1L, metrics.files().fastOnlyDropped)
        }

    @Test
    fun aFastOnlyFileRidesTheArmedLinkWhenItComesUp() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()
            var dropped = 0

            composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), fastOnlyAttachment { dropped++ })
            advanceTimeBy(1_000)
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()
            assertEquals(1, nan.sentFiles.size)
            assertTrue(bt.sentFiles.isEmpty())
            assertEquals(0, dropped)
        }

    @Test
    fun aFastOnlyFileTheFastPlaneWontArmForIsRefusedAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true) // armBulk = false
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()

            assertTrue(!composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), fastOnlyAttachment()))
            assertTrue(bt.sentFiles.isEmpty())
            assertTrue(nan.sentFiles.isEmpty())
        }

    @Test
    fun bigAttachmentSkipsTheWaitWhenTheFastPlaneWontArm() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true) // armBulk = false: peer not fresh / cooling down
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()

            composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), bigAttachment())
            assertEquals("declined arm → immediate BLE, no grace", 1, bt.sentFiles.size)
            assertEquals(listOf("p"), nan.expectBulkCalls)
            assertTrue(nan.sentFiles.isEmpty())
        }

    @Test
    fun smallAttachmentRidesAnAlreadyLiveFastLink() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            nan.setNeighbors(Peer("p")) // a link that's already up costs nothing — any size rides it
            advanceUntilIdle()

            composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES - 1), Peer("p"), bigAttachment())
            assertEquals("live fast link carries any attachment size", 1, nan.sentFiles.size)
            assertTrue(bt.sentFiles.isEmpty() && nan.expectBulkCalls.isEmpty())
        }

    @Test
    fun smallAttachmentNeverArmsTheFastPlane() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p")) // fast plane not linked — a small blob must not raise an NDP for it
            advanceUntilIdle()

            composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES - 1), Peer("p"), bigAttachment())
            assertEquals("below the arm gate BLE finishes before an NDP would even form", 1, bt.sentFiles.size)
            assertTrue(nan.sentFiles.isEmpty() && nan.expectBulkCalls.isEmpty())
        }

    @Test
    fun aResumeWhoseTailIsSmallNeverArmsTheFastPlane() =
        runTest(UnconfinedTestDispatcher()) {
            // A big blob cut near its end (#116): only the tail goes out, and a tail under the gate is BLE's to finish
            // before an NDP would even form — the gate reads the bytes still to send, not the file's.
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            advanceUntilIdle()

            val size = CompositeMeshTransport.BULK_MIN_BYTES * 4
            val resume = bigAttachment().copy(offset = size - CompositeMeshTransport.BULK_MIN_BYTES + 1)
            composite.sendFile(fileOfSize(size), Peer("p"), resume)
            assertEquals(1, bt.sentFiles.size)
            assertTrue(nan.sentFiles.isEmpty() && nan.expectBulkCalls.isEmpty())

            composite.sendFile(fileOfSize(size), Peer("p"), bigAttachment().copy(offset = 1))
            assertEquals("a big tail still arms it", listOf("p"), nan.expectBulkCalls)
        }

    @Test
    fun avatarKeepsTheBluetoothFirstRoute() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { armBulk = true }
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()

            composite.sendFile(
                fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES),
                Peer("p"),
                FileMeta(FileKind.AVATAR, "p", "image/jpeg"),
            )
            assertEquals("avatars keep today's path byte-for-byte", 1, bt.sentFiles.size)
            assertTrue(nan.sentFiles.isEmpty() && nan.expectBulkCalls.isEmpty())
        }

    @Test
    fun fastEnqueueRefusalFallsBackToBluetooth() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild(highThroughput = true).apply { acceptFiles = false } // link died in the TOCTOU window
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            bt.setNeighbors(Peer("p"))
            nan.setNeighbors(Peer("p"))
            advanceUntilIdle()

            assertTrue(composite.sendFile(fileOfSize(CompositeMeshTransport.BULK_MIN_BYTES), Peer("p"), bigAttachment()))
            assertEquals("refused fast enqueue is not a silent loss", 1, bt.sentFiles.size)
        }

    // The link-only child in the three tests below is a child with no fast plane — since ADR 2026-09.sjaa no
    // production plane is one (Bluetooth declares its side channel), but the fallback is still the route a bare
    // test transport (`FakeLoopTransport`, the lab's `LabTransport` without pages) takes.
    @Test
    fun fastFanoutBlastsFastPlaneAndFansOverLinkChild() =
        runTest(UnconfinedTestDispatcher()) {
            val linkOnly = FakeChild(hasFastPlane = false)
            val nan = FakeChild(hasFastPlane = true)
            val composite = CompositeMeshTransport(listOf(linkOnly, nan), backgroundScope)
            linkOnly.setNeighbors(Peer("p"))
            advanceUntilIdle()
            composite.fastFanout(wire())
            advanceUntilIdle()
            assertEquals("NAN gets the coordination-plane blast", 1, nan.fastFanouts.size)
            assertEquals("a child with no fast plane gets a normal flood instead", listOf<Peer?>(null), linkOnly.sends.map { it.second })
        }

    @Test
    fun longRangeFanoutReachesEveryChildAndNeverFallsBackToSend() =
        runTest(UnconfinedTestDispatcher()) {
            val linkOnly = FakeChild(hasFastPlane = false)
            val nan = FakeChild(hasFastPlane = true)
            val composite = CompositeMeshTransport(listOf(linkOnly, nan), backgroundScope)
            linkOnly.setNeighbors(Peer("p"))
            advanceUntilIdle()
            composite.longRangeFanout(wire())
            composite.longRangeFanout(wire(), FanoutHint.TICK)
            advanceUntilIdle()
            // Every child is offered the frame and decides for itself (only a no-data-path plane acts on it) —
            // there is no send() fallback: the router's flood already carries a DM over a link child's links.
            assertEquals(2, linkOnly.longRangeFanouts.size)
            assertEquals(2, nan.longRangeFanouts.size)
            // The originator's hint reaches every child unchanged (ADR 054).
            assertEquals(listOf(FanoutHint.CONTENT, FanoutHint.TICK), nan.longRangeHints)
            assertTrue("no flood duplicate over the link child", linkOnly.sends.isEmpty())
            assertTrue("the coordination-plane blast is a different path", nan.fastFanouts.isEmpty())
        }

    @Test
    fun fastSendUsesFastPlaneOrTheLinkChild() =
        runTest(UnconfinedTestDispatcher()) {
            val linkOnly = FakeChild(hasFastPlane = false)
            val nan = FakeChild(hasFastPlane = true)
            val composite = CompositeMeshTransport(listOf(linkOnly, nan), backgroundScope)
            linkOnly.setNeighbors(Peer("p"))
            advanceUntilIdle()
            composite.fastSend(wire(), Peer("p"))
            advanceUntilIdle()
            assertEquals(listOf("p"), nan.fastSends.map { it.nodeId })
            assertEquals("a child with no fast plane sends over its live link to p", listOf("p"), linkOnly.sends.map { it.second!!.nodeId })
        }

    @Test
    fun startStopHealFanOutToAllChildren() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild()
            val nan = FakeChild()
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            composite.start()
            composite.heal()
            composite.stop()
            assertEquals(1, bt.starts)
            assertEquals(1, bt.heals)
            assertEquals(1, bt.stops)
            assertEquals(1, nan.starts)
            assertEquals(1, nan.heals)
            assertEquals(1, nan.stops)
        }

    @Test
    fun emptyChildrenIsInertAndDegraded() =
        runTest(UnconfinedTestDispatcher()) {
            val composite = CompositeMeshTransport(emptyList(), backgroundScope)
            assertEquals(TransportHealth.Degraded, composite.health.value)
            assertEquals(emptySet<Peer>(), composite.neighbors.value)
            // No child to route to → no throw.
            composite.send(wire(), Peer("p"))
            composite.send(wire(), to = null)
            composite.sendFile(File("x"), Peer("p"), FileMeta(FileKind.ATTACHMENT, "k", "m"))
            composite.start()
            composite.stop()
            composite.heal()
        }

    @Test
    fun singletonChildDelegates() =
        runTest(UnconfinedTestDispatcher()) {
            val only = FakeChild()
            val composite = CompositeMeshTransport(listOf(only), backgroundScope)
            only.setNeighbors(Peer("p"))
            advanceUntilIdle()
            assertEquals(
                setOf("p"),
                composite.neighbors.value
                    .map { it.nodeId }
                    .toSet(),
            )
            composite.send(wire(), Peer("p"))
            assertEquals(1, only.sends.size)
        }

    @Test
    fun suppressesLowerPreferenceSyncForPeersHeldByHigherPreferenceChild() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun internetCoverIsForwardedToEveryChild() =
        runTest(UnconfinedTestDispatcher()) {
            // The spool's present set is a cover hint for a plane with no data path (ADR 2026-09.y5f3); the
            // composite has no opinion on which child acts on it, so every child hears it as given.
            val bt = FakeChild()
            val lora = FakeChild(hasFastPlane = true, kind = TransportKind.LoRa, shortRange = false)
            val composite = CompositeMeshTransport(listOf(bt, lora), backgroundScope)
            composite.coveredByInternet(setOf("p", "q"))
            assertEquals(listOf(setOf("p", "q")), bt.internetCalls)
            assertEquals(listOf(setOf("p", "q")), lora.internetCalls)
            assertTrue("a cover is never a link", bt.suppressCalls.all { it.isEmpty() } && lora.suppressCalls.all { it.isEmpty() })
        }

    @Test
    fun pushesForeignReachableFromTheOtherChildren() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun foreignReachableExcludesLongRangeChildren() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val lora = FakeChild(hasFastPlane = true, kind = TransportKind.LoRa, shortRange = false)
            CompositeMeshTransport(listOf(bt, nan, lora), backgroundScope)
            // A peer only LoRa can see must NOT be pushed to BLE/NAN as foreign-reachable (no scan chase, no
            // wedge-watchdog corroboration for a peer kilometres away).
            lora.setReachable(Peer("far"))
            nan.setReachable(Peer("near"))
            advanceUntilIdle()
            assertTrue("BT never learns the LoRa-only peer as foreign", bt.foreignCalls.all { "far" !in it })
            assertTrue("NAN never learns the LoRa-only peer as foreign", nan.foreignCalls.all { "far" !in it })
            assertTrue("the short-range NAN sighting still propagates to BT", bt.foreignCalls.any { "near" in it })
        }

    @Test
    fun pushesForeignNearFromTheOtherShortRangeChildrenOnly() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val lora = FakeChild(hasFastPlane = true, kind = TransportKind.LoRa, shortRange = false)
            CompositeMeshTransport(listOf(bt, nan, lora), backgroundScope)
            // BT reaches "far" (a Coded advert) but hears only "close" near: NAN's corroboration gets "close" alone.
            bt.setReachable(Peer("close"), Peer("far"))
            bt.setNear(Peer("close"))
            lora.setNear(Peer("board"))
            advanceUntilIdle()
            assertEquals("NAN learns what BT hears near, not what it reaches", setOf("close"), nan.nearCalls.last())
            assertEquals("the reachable hint is untouched", setOf("close", "far"), nan.foreignCalls.last())
            assertEquals("LoRa learns BT's near too, as it learns its reachable", setOf("close"), lora.nearCalls.last())
            assertTrue("BT never hears its own near back", bt.nearCalls.all { "close" !in it })
            assertTrue("a LoRa sighting is never near", (bt.nearCalls + nan.nearCalls).all { "board" !in it })

            bt.setNear()
            advanceUntilIdle()
            assertEquals("a peer leaving BT's near leaves NAN's", emptySet<String>(), nan.nearCalls.last())
        }

    /** GitHub #44: what wakes, keeps and lets a dormant Wi-Fi Aware plane sleep comes from the other short-range planes. */
    @Test
    fun pushesTheOtherShortRangeChildrensAwareCandidatesAndTheirNewcomerWatch() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val lora = FakeChild(hasFastPlane = true, kind = TransportKind.LoRa, shortRange = false)
            CompositeMeshTransport(listOf(bt, nan, lora), backgroundScope)
            advanceUntilIdle()
            assertEquals(
                "the defaults: nobody named, no watch — so a dormancy never fires on them",
                Triple(emptySet<String>(), emptySet<String>(), false),
                nan.candidateCalls.last(),
            )

            bt.setCandidates(near = setOf("a"), heard = setOf("a", "b"), sees = true)
            lora.setCandidates(near = setOf("board"), heard = setOf("board"), sees = true)
            advanceUntilIdle()
            assertEquals(Triple(setOf("a"), setOf("a", "b"), true), nan.candidateCalls.last())
            assertEquals(
                "LoRa learns Bluetooth's too, as it learns its near",
                Triple(setOf("a"), setOf("a", "b"), true),
                lora.candidateCalls.last(),
            )
            assertTrue("a plane never hears its own candidates back", bt.candidateCalls.all { "a" !in it.first })
            assertTrue(
                "a long-range plane names no candidate and watches for no newcomer",
                nan.candidateCalls.all { "board" !in it.first && "board" !in it.second },
            )

            bt.setCandidates(near = emptySet(), heard = setOf("b"), sees = false)
            advanceUntilIdle()
            assertEquals(Triple(emptySet<String>(), setOf("b"), false), nan.candidateCalls.last())
        }

    @Test
    fun aSingleChildIsPushedNoCandidates() =
        runTest(UnconfinedTestDispatcher()) {
            // A NAN-only phone has nothing to watch for newcomers, so nothing is pushed and dormancy never fires.
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            CompositeMeshTransport(listOf(nan), backgroundScope)
            advanceUntilIdle()
            assertTrue(nan.candidateCalls.isEmpty())
        }

    @Test
    fun shortRangeReachableExcludesTheLongRangeChild() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val lora = FakeChild(hasFastPlane = true, kind = TransportKind.LoRa, shortRange = false)
            val composite = CompositeMeshTransport(listOf(bt, lora), backgroundScope)
            bt.setReachable(Peer("near"))
            lora.setReachable(Peer("far"))
            advanceUntilIdle()
            assertEquals(
                "only the short-range peer counts as short-range-reachable",
                setOf(
                    "near",
                ),
                composite.shortRangeReachable.value
                    .map {
                        it.nodeId
                    }.toSet(),
            )
            assertEquals(
                "but both are in the merged reachable set",
                setOf("near", "far"),
                composite.reachable.value
                    .map { it.nodeId }
                    .toSet(),
            )
        }

    @Test
    fun shortRangeKindsNamesTheProximityPlanes() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val lora = FakeChild(hasFastPlane = true, kind = TransportKind.LoRa, shortRange = false)
            val composite = CompositeMeshTransport(listOf(bt, nan, lora), backgroundScope)
            // Read off each child's own `shortRange`, never restated, so a UI telling "this peer's radio was
            // seen" from "somebody carried its frames" can't drift from what the transports declare.
            assertEquals(
                setOf(TransportKind.Bluetooth, TransportKind.WifiAware),
                composite.shortRangeKinds,
            )
        }

    @Test
    fun statusesReportPerChildKindHealthAndCounts() =
        runTest(UnconfinedTestDispatcher()) {
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

    /** ADR 2026-10.d44b: a plane Knit switched off says so on its status line, so the banner can tell it from Wi-Fi off. */
    @Test
    fun statusesCarryAPlaneSwitchedOff() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            nan.setHealth(TransportHealth.Unavailable)
            nan.setSwitchedOff(true)
            advanceUntilIdle()

            val statuses = composite.statuses.value
            assertTrue(statuses.first { it.kind == TransportKind.WifiAware }.switchedOff)
            assertTrue(!statuses.first { it.kind == TransportKind.Bluetooth }.switchedOff)
            assertFalse(statuses.any { it.dormant })
            assertEquals("Bluetooth still meshes", TransportHealth.Healthy, composite.health.value)

            nan.setSwitchedOff(false)
            advanceUntilIdle()
            assertTrue(
                !composite.statuses.value
                    .first { it.kind == TransportKind.WifiAware }
                    .switchedOff,
            )
        }

    /** GitHub #44: a dormant plane says so on its status line, and is neither switched off nor unhealthy for it. */
    @Test
    fun statusesCarryADormantPlane() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            nan.setDormant(true)
            advanceUntilIdle()
            val aware = composite.statuses.value.first { it.kind == TransportKind.WifiAware }
            assertEquals(TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, 0, 0, dormant = true), aware)
            nan.setDormant(false)
            advanceUntilIdle()
            assertFalse(
                composite.statuses.value
                    .first { it.kind == TransportKind.WifiAware }
                    .dormant,
            )
        }

    /** ADR 2026-09.m8kc: a child's initiator hold rides its status line, and the release fans out to every child. */
    @Test
    fun statusesCarryTheInitiatorHoldAndTheReleaseFansOut() =
        runTest(UnconfinedTestDispatcher()) {
            val bt = FakeChild(kind = TransportKind.Bluetooth)
            val nan = FakeChild(hasFastPlane = true, kind = TransportKind.WifiAware)
            val composite = CompositeMeshTransport(listOf(bt, nan), backgroundScope)
            nan.setInitiatorHeld(true)
            advanceUntilIdle()

            val statuses = composite.statuses.value
            assertTrue(statuses.first { it.kind == TransportKind.WifiAware }.initiatorHeld)
            assertTrue(!statuses.first { it.kind == TransportKind.Bluetooth }.initiatorHeld)
            assertEquals(
                "the hold is a flag, never a health",
                TransportHealth.Healthy,
                statuses.first { it.kind == TransportKind.WifiAware }.health,
            )

            composite.releaseInitiatorHold()
            assertEquals(1, bt.releases)
            assertEquals(1, nan.releases)

            nan.setInitiatorHeld(false)
            advanceUntilIdle()
            assertTrue(composite.statuses.value.none { it.initiatorHeld })
        }

    @Test
    fun peerTransportsTagEachNodeByTheRadiosItIsReachableOver() =
        runTest(UnconfinedTestDispatcher()) {
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
    fun statusesAndPeerTransportsAreEmptyWithNoChildren() =
        runTest(UnconfinedTestDispatcher()) {
            val composite = CompositeMeshTransport(emptyList(), backgroundScope)
            assertEquals(emptyList<TransportStatus>(), composite.statuses.value)
            assertEquals(emptyMap<String, Set<TransportKind>>(), composite.peerTransports.value)
        }

    private fun envelope(
        id: String,
        senderId: String,
    ) = app.getknit.knit.mesh.protocol.RelayEnvelope(
        type = app.getknit.knit.mesh.protocol.FrameType.CHAT,
        id = id,
        senderId = senderId,
        payload = ByteArray(0),
    )
}
