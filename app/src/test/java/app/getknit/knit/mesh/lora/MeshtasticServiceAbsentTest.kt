package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Service not found" at a Meshtastic dial is terminal on its second reading in a row (ADR 2026-11.gsqu, Stage 3d): a
 * MeshCore board bound as Meshtastic, or a cache from before a reflash, used to be redialled on the backoff forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticServiceAbsentTest {
    private fun session(
        dialer: FakeGattDialer,
        scope: CoroutineScope,
        now: () -> Long,
    ) = MeshtasticSession(
        dialer = dialer,
        scope = scope,
        backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
        now = now,
        rand = { 0.5 },
        nonce = { 0x11u },
        ids = PacketIdSource(1000L),
    )

    @Test
    fun theFirstReadingBacksOffAndASecondInARowEndsStaleBond() =
        runTest {
            val dialer = FakeGattDialer(FakeGattChannel())
            repeat(2) { dialer.dialResults.addLast(DialResult.ServiceAbsent) }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val first = session.state.value
            assertTrue("one reading backs off like the failed discovery it was, but was $first", first is LinkState.Disconnected)
            assertEquals(1, (first as LinkState.Disconnected).streak)
            advanceTimeBy(5_001) // the base backoff: one more direct dial
            runCurrent()
            assertEquals(LinkState.StaleBond("AA"), session.state.value)
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals("never redialled after the second", 2, dialer.dials)
            session.stop()
        }

    @Test
    fun anUnbondedOneNeedsPairingOnTheSecondReading() =
        runTest {
            val dialer = FakeGattDialer(FakeGattChannel())
            dialer.bond = BondState.NONE
            repeat(2) { dialer.dialResults.addLast(DialResult.ServiceAbsent) }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(LinkState.NeedsPairing("AA"), session.state.value)
            session.stop()
        }

    @Test
    fun readingsThatAreNotInARowNeverEndIt() =
        runTest {
            val dialer = FakeGattDialer(FakeGattChannel())
            dialer.dialResults.addLast(DialResult.ServiceAbsent)
            dialer.dialResults.addLast(DialResult.Failed(status = 133, phase = "connect"))
            dialer.dialResults.addLast(DialResult.ServiceAbsent)
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(3, dialer.dials)
            val st = session.state.value
            assertTrue("a broken run starts over, but was $st", st is LinkState.Disconnected)
            session.stop()
        }

    @Test
    fun aDiscoveryThatFailedStillBacksOff() =
        runTest {
            val dialer = FakeGattDialer(FakeGattChannel())
            dialer.dialResults.addLast(DialResult.Failed(status = -1, phase = "service"))
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(1, (session.state.value as LinkState.Disconnected).streak)
            session.stop()
        }
}
