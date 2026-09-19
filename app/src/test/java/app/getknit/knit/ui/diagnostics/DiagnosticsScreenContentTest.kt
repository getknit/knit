package app.getknit.knit.ui.diagnostics

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the stateless `DiagnosticsScreenContent` on the JVM. The screen has no testTags, so assertions
 * target the self-identity text (top of the LazyColumn, always composed) and the resolved control-button
 * strings. Follows the Compose-on-Robolectric pattern in `ChatListScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state() = DiagnosticsUiState(myNodeId = "8f3a2b1c9d4e", myName = "Ada Lovelace")

    @Test
    fun rendersSelfIdentity() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
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
                )
            }
        }

        compose.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        // A plain phone has nobody administering it, so the self section has no such line.
        compose.onNodeWithTag("diagnostics_supervised").assertDoesNotExist()
    }

    /** An administered phone is named in the self section — the line a "greyed-out permission" report needs. */
    @Test
    fun aFamilyLinkPhoneIsNamedInTheSelfSection() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    supervision = DeviceSupervision.FamilyLink,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                )
            }
        }

        compose.onNodeWithTag("diagnostics_supervised").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_supervised_family_link)).assertIsDisplayed()
    }

    @Test
    fun tappingRestartInvokesTheCallback() {
        var restarts = 0
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = { restarts++ },
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostics_restart_mesh)).performClick()
        assertEquals(1, restarts)
    }

    @Test
    fun crashRowIsAbsentWhenNothingWasCaptured() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
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
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.crash_last_label)).assertDoesNotExist()
    }

    @Test
    fun tappingTheCrashRowOpensTheLog() {
        var opened = 0
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    lastCrash = crashRef(),
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = { opened++ },
                    moderationLatched = false,
                    onResetModeration = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.crash_last_label)).performClick()
        assertEquals(1, opened)
    }

    /**
     * The pairing that matters: a native crash captures **no** report, so a latched phone usually has
     * `lastCrash == null`. Hanging the "Problem reports" header off `lastCrash` would hide this row.
     */
    @Test
    fun latchedModelShowsItsRowEvenWithNoCrashReport() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = true,
                    onResetModeration = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.crash_section)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_moderation_latched_label)).assertIsDisplayed()
    }

    @Test
    fun tappingTheModerationResetInvokesTheCallback() {
        var resets = 0
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = true,
                    onResetModeration = { resets++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostics_moderation_reset_action)).performClick()
        assertEquals(1, resets)
    }

    @Test
    fun anUnlatchedModelShowsNoModerationRow() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
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
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostics_moderation_latched_label)).assertDoesNotExist()
    }

    /** Work item 18: a plane the phone cannot run is a row that says so, not a row that is missing. */
    @Test
    fun anAbsentPlaneIsListedWithItsReason() {
        val bleOnly = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NoHardware)
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state =
                        state().copy(
                            transports =
                                listOf(
                                    TransportRow.Live(
                                        TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 1, nearby = 2),
                                    ),
                                    TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NoHardware),
                                    TransportRow.Live(
                                        TransportStatus(TransportKind.LoRa, TransportHealth.Unavailable, linked = 0, nearby = 0),
                                        lora = LoraPlane.Off,
                                    ),
                                ),
                            radios = bleOnly,
                        ),
                    // Bluetooth off on a phone with no Wi-Fi Aware: the hint must not say "turn on Wi-Fi".
                    health = TransportHealth.Unavailable,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                )
            }
        }

        // The section sits below the controls, past Robolectric's small viewport: composed, so `assertExists`
        // is the honest check (`assertIsDisplayed` would need a scroll the real screen never asks for).
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_wifi_aware)).assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_unsupported)).assertExists()
        compose.onNodeWithText(context.getString(R.string.lora_status_off)).assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_status_unavailable_hint_ble_only)).assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_status_unavailable_hint)).assertDoesNotExist()
    }

    /** ADR 2026-09.535d: the Wi-Fi search refused off screen is named as the OS's rule, never as a seized radio. */
    @Test
    fun aSearchRefusedOffScreenIsNamedAsTheRule() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state =
                        state().copy(
                            transports =
                                listOf(
                                    TransportRow.Live(
                                        TransportStatus(TransportKind.Bluetooth, TransportHealth.Unavailable, linked = 0, nearby = 0),
                                    ),
                                    TransportRow.Live(
                                        TransportStatus(TransportKind.WifiAware, TransportHealth.ForegroundOnly, linked = 0, nearby = 0),
                                    ),
                                ),
                        ),
                    health = TransportHealth.ForegroundOnly,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostics_status_foreground_only)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_status_foreground_only_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diagnostics_status_degraded_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.diagnostics_status_unavailable_hint)).assertDoesNotExist()
    }

    @Test
    fun aPhoneBelowTheWifiAwareFloorIsToldTheAndroidVersion() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state =
                        state().copy(
                            transports = listOf(TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NeedsAndroid12)),
                            radios = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NeedsAndroid12),
                        ),
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
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_needs_android_12)).assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_unsupported)).assertDoesNotExist()
    }

    private fun crashRef() =
        CrashReportRef(
            at = 0L,
            summary = "IllegalStateException at MeshRouter.kt:91",
            appVersion = "2.3.0 (13) debug",
            device = "Google Pixel 8 (shiba)",
            androidVersion = "16 (SDK 36)",
            file = File("crash-1700000000000-deadbeef.txt"),
        )
}
