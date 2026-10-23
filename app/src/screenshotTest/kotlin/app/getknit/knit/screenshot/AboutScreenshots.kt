package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.about.AboutCapabilitiesPreview
import app.getknit.knit.ui.about.AboutScreenDebugPreview
import app.getknit.knit.ui.about.AboutScreenPreview
import app.getknit.knit.ui.about.LicenseTextScreenPreview
import app.getknit.knit.ui.about.LicensesScreenPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.about`.

@PreviewTest
@ScreenShots
@Composable
fun AboutScreen() = AboutScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun AboutScreenDebug() = AboutScreenDebugPreview()

@PreviewTest
@ComponentShots
@Composable
fun AboutCapabilities() = AboutCapabilitiesPreview()

@PreviewTest
@ScreenShots
@Composable
fun LicenseTextScreen() = LicenseTextScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun LicensesScreen() = LicensesScreenPreview()
