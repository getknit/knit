package app.getknit.knit.ui.call

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The call screen's route pill and quality line, worded by string resources: Robolectric reads the app's English ones. */
@RunWith(RobolectricTestRunner::class)
class CallWordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theRoutePillNamesTheNetwork() {
        val wifi = CallStats(networkType = "wifi")
        assertEquals(RouteView("Relayed · Wi-Fi", RouteKind.RELAYED), routeView(context, "relay/srflx", wifi))
        assertEquals(
            RouteView("Direct · Cellular", RouteKind.DIRECT),
            routeView(context, "srflx/prflx", CallStats(networkType = "cellular")),
        )
        assertEquals(RouteView("Direct", RouteKind.DIRECT), routeView(context, "srflx/srflx", null))
        assertEquals(RouteView("Direct · same network", RouteKind.DIRECT), routeView(context, "host/host", wifi))
        assertEquals(RouteView("Wi-Fi Direct", RouteKind.WIFI_DIRECT), routeView(context, "host/host/wifi-direct", wifi))
    }

    @Test
    fun anUnknownNetworkIsNamedAsTheMediaReportsIt() {
        assertEquals("Direct · bluetooth", routeView(context, "srflx/srflx", CallStats(networkType = "bluetooth")).label)
        assertNull(networkLabel(context, CallStats()))
    }

    @Test
    fun theQualityLineSaysWhichWayItBreaks() {
        assertEquals("Your audio is breaking up for Ada", issueText(context, QualityIssue.HEARD_BREAKS, "Ada"))
        assertEquals("Ada's audio is breaking up", issueText(context, QualityIssue.HEARING_BREAKS, "Ada"))
    }
}
