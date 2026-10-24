package app.getknit.knit.ui.diagnostics

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The Wi-Fi Aware row under Transports (GitHub #44): resting is idle, not off, and the person's switch is named. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsAwareRowTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setAwareRowContent(
        status: TransportStatus,
        wifiAwareOff: Boolean = false,
    ) {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state =
                        DiagnosticsUiState(myNodeId = "8f3a2b1c9d4e", myName = "Ada Lovelace")
                            .copy(transports = listOf(TransportRow.Live(status))),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                    wifiAwareOff = wifiAwareOff,
                )
            }
        }
    }

    /** A resting plane says it is idle, not off, in place of its (zero) counts. */
    @Test
    fun aDormantWifiAwareRowSaysItIsIdle() {
        setAwareRowContent(TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, linked = 0, nearby = 0, dormant = true))
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_dormant)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_counts, 0, 0)).assertDoesNotExist()
    }

    @Test
    fun anAwakeWifiAwareRowShowsItsCounts() {
        setAwareRowContent(TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, linked = 1, nearby = 2))
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_counts, 2, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_dormant)).assertDoesNotExist()
    }

    @Test
    fun aWifiAwareRowThePersonSwitchedOffSaysSoNotLowPower() {
        val off = TransportStatus(TransportKind.WifiAware, TransportHealth.Unavailable, linked = 0, nearby = 0, switchedOff = true)
        setAwareRowContent(off, wifiAwareOff = true)
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_switched_off)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_low_power)).assertDoesNotExist()
    }

    @Test
    fun aWifiAwareRowSwitchedOffWithThePersonsSwitchOnIsLowPowersDoing() {
        val off = TransportStatus(TransportKind.WifiAware, TransportHealth.Unavailable, linked = 0, nearby = 0, switchedOff = true)
        setAwareRowContent(off, wifiAwareOff = false)
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_low_power)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_switched_off)).assertDoesNotExist()
    }
}
