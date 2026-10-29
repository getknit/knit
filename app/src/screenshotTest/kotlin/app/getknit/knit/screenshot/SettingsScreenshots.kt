package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.settings.BatteryOptimizationRowPreview
import app.getknit.knit.ui.settings.ChatSettingsScreenPreview
import app.getknit.knit.ui.settings.SettingsScreenClonePreview
import app.getknit.knit.ui.settings.SettingsScreenNewUserPreview
import app.getknit.knit.ui.settings.SettingsScreenPreview
import app.getknit.knit.ui.settings.SupervisionRowPreview
import app.getknit.knit.ui.settings.ThemeModeRowPreview
import app.getknit.knit.ui.settings.ToggleRowPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.settings`.

@PreviewTest
@ScreenShots
@Composable
fun ChatSettingsScreen() = ChatSettingsScreenPreview()

@PreviewTest
@ComponentShots
@Composable
fun ToggleRow() = ToggleRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun ThemeModeRow() = ThemeModeRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun BatteryOptimizationRow() = BatteryOptimizationRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun SupervisionRow() = SupervisionRowPreview()

@PreviewTest
@ScreenShots
@Composable
fun SettingsScreen() = SettingsScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun SettingsScreenClone() = SettingsScreenClonePreview()

@PreviewTest
@ScreenShots
@Composable
fun SettingsScreenNewUser() = SettingsScreenNewUserPreview()
