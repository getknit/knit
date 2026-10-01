package app.getknit.knit.ui.chatlist

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The chat list's radio banner: each warning's copy, the tap that opens the radio's settings, the close
 * button only where the warning may be dismissed (never for all-radios-off), and the airplane-mode variant of
 * the all-off copy, read from `Settings.Global`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioWarningBannerTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private var opened = 0
    private var dismissed = 0

    private fun render(
        warning: RadioWarning,
        dismissible: Boolean = true,
    ) {
        compose.setContent {
            KnitTheme {
                RadioWarningBanner(
                    warning = warning,
                    onOpenSettings = { opened++ },
                    onDismiss = if (dismissible) ({ dismissed++ }) else null,
                )
            }
        }
    }

    @Test
    fun bluetoothOffSaysSoAndCanBeDismissed() {
        render(RadioWarning.BluetoothOff)
        compose.onNodeWithTag("chatlist_radio_banner").assertTextContains("Bluetooth is off", substring = true)
        compose.onNodeWithTag("chatlist_radio_banner").performClick()
        compose.onNodeWithTag("chatlist_radio_banner_dismiss").performClick()
        assertEquals(1, opened)
        assertEquals(1, dismissed)
    }

    @Test
    fun wifiOffSaysSo() {
        render(RadioWarning.WifiOff)
        compose.onNodeWithTag("chatlist_radio_banner").assertTextContains("Wi-Fi is off", substring = true)
    }

    @Test
    fun allRadiosOffHasNoCloseButton() {
        render(RadioWarning.AllRadiosOff, dismissible = false)
        compose.onNodeWithTag("chatlist_radio_banner").assertTextContains("Knit can't connect", substring = true)
        compose.onNodeWithTag("chatlist_radio_banner_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("chatlist_radio_banner").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun allRadiosOffNamesAirplaneModeWhenItIsOn() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        render(RadioWarning.AllRadiosOff, dismissible = false)
        compose.onNodeWithTag("chatlist_radio_banner").assertTextContains("Airplane mode is on", substring = true)
    }
}
