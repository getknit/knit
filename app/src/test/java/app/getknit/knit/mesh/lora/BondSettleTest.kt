package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.power.ElapsedWait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bond read after a close is the settled one: a BONDING the disconnect overtook is waited out, for at most 2 s. */
@OptIn(ExperimentalCoroutinesApi::class)
class BondSettleTest {
    @Test
    fun aBondingReadIsWaitedOutUntilItSettles() =
        runTest {
            val now = { testScheduler.currentTime }
            val read = { if (testScheduler.currentTime < 700) BondState.BONDING else BondState.NONE }
            assertEquals(BondState.NONE, BondSettle.settled(ElapsedWait(now), now, read))
            assertEquals(800L, testScheduler.currentTime) // four 200-ms looks
        }

    @Test
    fun aSettledReadReturnsAtOnce() =
        runTest {
            val now = { testScheduler.currentTime }
            assertEquals(BondState.BONDED, BondSettle.settled(ElapsedWait(now), now) { BondState.BONDED })
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun aPairingThatStaysBondingGivesUpAfterTwoSeconds() =
        runTest {
            val now = { testScheduler.currentTime }
            assertEquals(BondState.BONDING, BondSettle.settled(ElapsedWait(now), now) { BondState.BONDING })
            assertEquals(BondSettle.SETTLE_MS, testScheduler.currentTime)
        }
}
