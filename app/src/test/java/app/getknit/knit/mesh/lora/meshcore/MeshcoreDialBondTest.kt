package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.BondSettle
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.PacketIdSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dial whose own subscribe was the first protected op and failed the bond ([NusDialResult.BondRefused], Stage 3c) is
 * terminal, as the session's first write is: never a backoff that would prompt for the passkey again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreDialBondTest {
    private fun TestScope.session(dialer: FakeMeshcoreDialer) =
        MeshcoreSession(
            dialer = dialer,
            scope = backgroundScope,
            backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
            now = { testScheduler.currentTime },
            wallClock = { 1_790_000_000_000L + testScheduler.currentTime },
            rand = { 0.5 },
            ids = PacketIdSource(1000L),
        )

    private fun TestScope.dialer(bond: BondState): FakeMeshcoreDialer {
        val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
        return FakeMeshcoreDialer(air.board("alice"), bond).also { it.dialResult = { NusDialResult.BondRefused(5) } }
    }

    @Test
    fun anUnbondedBoardNeedsPairingAndIsNotRedialled() =
        runTest {
            val dialer = dialer(BondState.NONE)
            val session = session(dialer)
            session.start("AA:BB")
            runCurrent()
            assertEquals(MeshcoreLinkState.NeedsPairing("AA:BB"), (session.state.value as BoardState.Down).detail)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, dialer.modes.size)
            session.stop()
        }

    @Test
    fun aBondedOneIsAStaleBond() =
        runTest {
            val dialer = dialer(BondState.BONDED)
            val session = session(dialer)
            session.start("AA:BB")
            runCurrent()
            assertEquals(MeshcoreLinkState.StaleBond("AA:BB"), (session.state.value as BoardState.Down).detail)
            session.stop()
        }

    /**
     * A write closed under an unbonded pairing while the bond still reads BONDING (the broadcast lags the disconnect) is
     * judged once the bond settles: NONE, so a pairing that never happened, never a redial into a second prompt.
     */
    @Test
    fun aCloseReadWhileStillBondingWaitsForTheBondToSettle() =
        runTest {
            val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
            val dialer = FakeMeshcoreDialer(air.board("alice"), BondState.BONDING)
            dialer.onOpen = { ch ->
                ch.writeResult = {
                    // The user cancelled: the link closes now, and the bond reads NONE half a second later.
                    backgroundScope.launch {
                        delay(500)
                        dialer.bond = BondState.NONE
                    }
                    GattResult.Closed
                }
            }
            val session = session(dialer)
            session.start("AA:BB")
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(MeshcoreLinkState.NeedsPairing("AA:BB"), (session.state.value as BoardState.Down).detail)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, dialer.modes.size)
            session.stop()
        }

    @Test
    fun aBondThatNeverSettlesIsADropAfterTwoSeconds() =
        runTest {
            val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
            val dialer = FakeMeshcoreDialer(air.board("alice"), BondState.BONDING)
            dialer.onOpen = { ch -> ch.writeResult = { GattResult.Closed } }
            val session = session(dialer)
            session.start("AA:BB")
            runCurrent()
            advanceTimeBy(BondSettle.SETTLE_MS + 1)
            runCurrent()
            val down = (session.state.value as BoardState.Down).detail
            assertTrue("bounded: backs off rather than waiting on, but was $down", down is MeshcoreLinkState.Disconnected)
            session.stop()
        }
}
