package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Wi-Fi Aware switch's wrapper (low-power mode, a debug build's switch): the inner runs while the mesh runs and the switch is on. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableTransportTest {
    /** Records every call that reaches it, in order. */
    private class RecordingInner : MeshTransport {
        val calls = mutableListOf<String>()
        val healthState = MutableStateFlow(TransportHealth.Healthy)
        val dormantState = MutableStateFlow(false)
        var armBulk = true

        override val dormant = dormantState.asStateFlow()

        override fun onForeignAwareCandidates(
            near: Set<String>,
            heard: Set<String>,
            seesNewcomers: Boolean,
        ) {
            calls += "candidates"
        }

        override val neighbors = MutableStateFlow<Set<Peer>>(emptySet()).asStateFlow()
        override val health = healthState.asStateFlow()
        override val kind = TransportKind.WifiAware
        override val inbound: Flow<InboundFrame> = emptyFlow()
        override val incomingFiles: Flow<ReceivedFile> = emptyFlow()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun heal() {
            calls += "heal"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun releaseInitiatorHold() {
            calls += "release"
        }

        override fun suppressDataPath(peers: Set<String>) {
            calls += "suppress"
        }

        override fun expectBulkTransfer(nodeId: String): Boolean {
            calls += "expectBulk"
            return armBulk
        }

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            calls += "send"
        }

        override fun fastFanout(wire: WireEnvelope) {
            calls += "fastFanout"
        }

        override fun fastSend(
            wire: WireEnvelope,
            to: Peer,
        ) {
            calls += "fastSend"
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean {
            calls += "sendFile"
            return true
        }

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) {
            calls += "sendDigest"
        }
    }

    private val inner = RecordingInner()
    private val peer = Peer("peer0001")
    private val wire = WireEnvelope(sig = ByteArray(0), signed = ByteArray(0))
    private val meta = FileMeta(FileKind.ATTACHMENT, "hash", "image/webp")

    // No floors: these cases are about what the switch and the mesh want, not when.
    private fun TestScope.switchable(off: Flow<Boolean>) =
        SwitchableTransport(inner, off, backgroundScope, clock = { testScheduler.currentTime }, restartFloorMs = 0, upFloorMs = 0)

    // The shipped floors on the scheduler's virtual clock.
    private fun TestScope.floored(off: Flow<Boolean>) =
        SwitchableTransport(inner, off, backgroundScope, clock = { testScheduler.currentTime })

    @Test
    fun aResumeWhileOffStillReachesTheInnerSoItsHandOverHoldLifts() =
        runTest(UnconfinedTestDispatcher()) {
            // The Aware transport keeps a hand-over pause across its own stop and only its resume lifts it: a call that
            // ends while low-power mode has the plane off must still hand the radio back (the advisory review).
            val off = MutableStateFlow(false)
            val t = switchable(off)
            t.start()
            t.pause()
            off.value = true
            t.resume()
            off.value = false
            assertEquals(listOf("start", "pause", "stop", "resume", "start"), inner.calls)
        }

    @Test
    fun aSwitchedStopWaitsOutTheUpFloorAndTheMeshsOwnStopDoesNot() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val t = floored(off)
            t.start()
            advanceTimeBy(1_000)
            off.value = true
            advanceTimeBy(SwitchableTransport.UP_FLOOR_MS - 1_000 - 1)
            assertEquals("an attach in flight is left to land", listOf("start"), inner.calls)
            // The plane still runs and sends, so it reads as it is, not as the switch wants it.
            assertFalse(t.switchedOff.value)
            assertEquals(TransportHealth.Healthy, t.health.value)
            advanceTimeBy(2)
            assertEquals(listOf("start", "stop"), inner.calls)
            assertTrue(t.switchedOff.value)
            assertEquals(TransportHealth.Unavailable, t.health.value)

            off.value = false
            advanceTimeBy(SwitchableTransport.RESTART_FLOOR_MS / 2)
            // A deferred start: the plane is down and Knit is keeping it so, whatever its last health said.
            assertTrue(t.switchedOff.value)
            assertEquals(TransportHealth.Unavailable, t.health.value)
            advanceTimeBy(SwitchableTransport.RESTART_FLOOR_MS)
            assertEquals(listOf("start", "stop", "start"), inner.calls)
            assertFalse(t.switchedOff.value)
            t.stop()
            assertEquals("the mesh's own stop is never floored", listOf("start", "stop", "start", "stop"), inner.calls)
        }

    @Test
    fun aFlappingSwitchRestartsTheInnerOnceTheRestartFloorIsOut() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val t = floored(off)
            t.start()
            advanceTimeBy(SwitchableTransport.UP_FLOOR_MS + 1)
            off.value = true
            assertEquals(listOf("start", "stop"), inner.calls)

            repeat(5) {
                off.value = false
                advanceTimeBy(300)
                off.value = true
                advanceTimeBy(300)
            }
            off.value = false
            assertEquals("no start inside the floor", listOf("start", "stop"), inner.calls)
            advanceTimeBy(SwitchableTransport.RESTART_FLOOR_MS)
            assertEquals("one start, once the floor is out", listOf("start", "stop", "start"), inner.calls)
        }

    @Test
    fun startWhileOffNeverStartsTheInnerTransport() =
        runTest(UnconfinedTestDispatcher()) {
            val t = switchable(MutableStateFlow(true))
            t.start()
            assertEquals(emptyList<String>(), inner.calls)
            assertEquals(TransportHealth.Unavailable, t.health.value)
        }

    @Test
    fun switchingOffWhileRunningStopsTheInnerOnceAndOnStartsItAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val t = switchable(off)
            t.start()
            assertEquals(listOf("start"), inner.calls)

            off.value = true
            assertEquals(listOf("start", "stop"), inner.calls)
            t.stop() // the mesh going down while already off stops nothing twice
            t.start()
            assertEquals(listOf("start", "stop"), inner.calls)

            off.value = false
            assertEquals(listOf("start", "stop", "start"), inner.calls)
        }

    @Test
    fun switchedOffFollowsTheSwitchSoTheBannerKnowsTheRadioIsOn() =
        runTest(UnconfinedTestDispatcher()) {
            // Low-power mode (ADR 2026-10.d44b): an off plane reads Unavailable like a radio the user turned off, and
            // switchedOff is what keeps the chat list from asking them to turn Wi-Fi on.
            val off = MutableStateFlow(false)
            val t = switchable(off)
            t.start()
            assertFalse(t.switchedOff.value)

            off.value = true
            assertTrue(t.switchedOff.value)
            assertEquals(TransportHealth.Unavailable, t.health.value)

            off.value = false
            assertFalse(t.switchedOff.value)
            assertEquals(TransportHealth.Healthy, t.health.value)
        }

    @Test
    fun switchingOnWhileTheMeshIsStoppedStartsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(true)
            val t = switchable(off)
            off.value = false
            assertEquals(emptyList<String>(), inner.calls)

            t.start()
            t.stop()
            off.value = true
            assertEquals(listOf("start", "stop"), inner.calls)
        }

    @Test
    fun aStartBeforeTheSwitchsFirstReadWaitsForIt() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableSharedFlow<Boolean>(replay = 1)
            val t = switchable(off)
            t.start()
            assertEquals(emptyList<String>(), inner.calls)

            off.emit(false)
            assertEquals(listOf("start"), inner.calls)
        }

    @Test
    fun aStartBeforeAnOffFirstReadNeverTouchesTheInner() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableSharedFlow<Boolean>(replay = 1)
            val t = switchable(off)
            t.start()
            off.emit(true)
            assertEquals(emptyList<String>(), inner.calls)
        }

    @Test
    fun healthReadsUnavailableWhileOffAndTheInnersOwnOtherwise() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            inner.healthState.value = TransportHealth.Degraded
            val t = switchable(off)
            assertEquals(TransportHealth.Degraded, t.health.value)

            off.value = true
            assertEquals(TransportHealth.Unavailable, t.health.value)
            inner.healthState.value = TransportHealth.Healthy
            assertEquals(TransportHealth.Unavailable, t.health.value)

            off.value = false
            assertEquals(TransportHealth.Healthy, t.health.value)
        }

    @Test
    fun whileOffNoSendAndNoBulkArmReachesTheInner() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(true)
            val t = switchable(off)
            t.start()

            t.send(wire, null)
            t.send(wire, peer)
            t.fastFanout(wire)
            t.fastSend(wire, peer)
            t.sendDigest(peer, listOf("id"))
            t.heal()
            assertFalse(t.sendFile(File("unused"), peer, meta))
            // Declining is what sends the composite's file route straight to the link holder, with no bulk grace.
            assertFalse(t.expectBulkTransfer(peer.nodeId))
            assertEquals(emptyList<String>(), inner.calls)

            off.value = false
            assertTrue(t.expectBulkTransfer(peer.nodeId))
            t.fastFanout(wire)
            assertEquals(listOf("start", "expectBulk", "fastFanout"), inner.calls)
        }

    @Test
    fun aHandOverPauseHeldAcrossAnOffSpellGoesBackOnWithTheRestart() =
        runTest(UnconfinedTestDispatcher()) {
            val off = MutableStateFlow(false)
            val t = switchable(off)
            t.start()
            off.value = true
            t.pause() // the Wi-Fi Direct hand-over begins while the plane is off: nothing to pause yet
            assertEquals(listOf("start", "stop"), inner.calls)

            off.value = false
            assertEquals(listOf("start", "stop", "start", "pause"), inner.calls)
            t.resume()
            assertEquals(listOf("start", "stop", "start", "pause", "resume"), inner.calls)
        }

    @Test
    fun theCrossPlaneHintsAndTheInitiatorHoldPassThroughWhileOff() =
        runTest(UnconfinedTestDispatcher()) {
            val t = switchable(MutableStateFlow(true))
            t.suppressDataPath(setOf(peer.nodeId))
            t.releaseInitiatorHold()
            t.onForeignAwareCandidates(setOf(peer.nodeId), setOf(peer.nodeId), seesNewcomers = true)
            assertEquals(listOf("suppress", "release", "candidates"), inner.calls)
            assertEquals(TransportKind.WifiAware, t.kind)
        }

    @Test
    fun dormancyIsTheInnersOwnAndNeverReadsAsSwitchedOff() =
        runTest(UnconfinedTestDispatcher()) {
            // GitHub #44: a dormant plane is idle, not off — delegated as it is, with no switch presentation.
            val t = switchable(MutableStateFlow(false))
            t.start()
            assertFalse(t.dormant.value)
            inner.dormantState.value = true
            assertTrue(t.dormant.value)
            assertFalse(t.switchedOff.value)
            assertEquals(TransportHealth.Healthy, t.health.value)
        }
}
