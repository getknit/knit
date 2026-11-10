package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-3.11-3: when [WalkieRoute] says a live call could connect, and that its Wi-Fi Direct half is the leg's own answer. */
class WalkieRouteTest {
    /** A leg that answers [offers] and records whom it was asked about; everything else is [CallDirect.None]'s. */
    private class AskedLeg(
        var offers: Boolean,
    ) : CallDirect by CallDirect.None {
        val asked = mutableListOf<String>()

        override suspend fun canOffer(peerId: String): Boolean {
            asked += peerId
            return offers
        }
    }

    private fun route(
        caps: Long,
        online: Boolean,
        leg: CallDirect,
    ) = WalkieRoute(capabilities = { caps }, online = { online }, direct = leg)

    @Test
    fun offlineALiveCallIsLikelyOnlyWhereTheLegWouldBeOffered() =
        runBlocking {
            // #63: offline, with the Wi-Fi off, the leg refuses; a copy of its gates said yes and the call went live.
            val leg = AskedLeg(offers = false)
            assertFalse(route(LIVE_AND_WALKIE, online = false, leg).liveLikely(PEER))
            assertEquals(listOf(PEER), leg.asked)

            leg.offers = true
            assertTrue(route(LIVE_AND_WALKIE, online = false, leg).liveLikely(PEER))
        }

    @Test
    fun anInternetRouteIsEnoughWithoutAskingTheLeg() =
        runBlocking {
            val leg = AskedLeg(offers = false)
            assertTrue(route(LIVE_AND_WALKIE, online = true, leg).liveLikely(PEER))
            assertTrue(leg.asked.isEmpty())
        }

    @Test
    fun aPeerThatTakesNoLiveCallIsNeverLikelyOrAskedAbout() =
        runBlocking {
            val leg = AskedLeg(offers = true)
            val route = route(Protocol.CAP_WALKIE, online = true, leg)
            assertFalse(route.liveLikely(PEER))
            assertTrue(route.capable(PEER))
            assertTrue(leg.asked.isEmpty())
            assertFalse(route(Protocol.CAP_CALL, online = true, leg).capable(PEER))
        }

    private companion object {
        const val PEER = "peer"
        const val LIVE_AND_WALKIE = Protocol.CAP_CALL or Protocol.CAP_WALKIE
    }
}
