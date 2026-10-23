package app.getknit.knit.screenshot

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.ui.onboarding.NamePagePreview
import app.getknit.knit.ui.onboarding.OnboardingNamePreview
import app.getknit.knit.ui.onboarding.OnboardingPermissionsPreview
import app.getknit.knit.ui.onboarding.OnboardingWelcomePreview
import app.getknit.knit.ui.onboarding.PermissionsPageDeniedPreview
import app.getknit.knit.ui.onboarding.PermissionsPageFamilyLinkPreview
import app.getknit.knit.ui.onboarding.PermissionsPageGrantedPreview
import app.getknit.knit.ui.onboarding.PermissionsPageLocationTierPreview
import app.getknit.knit.ui.onboarding.PermissionsPagePreview
import app.getknit.knit.ui.onboarding.PermissionsPageUnsupportedPreview
import app.getknit.knit.ui.onboarding.WelcomePagePreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.onboarding`.

@PreviewTest
@ScreenShots
@Composable
fun WelcomePage() = WelcomePagePreview()

@PreviewTest
@ScreenShots
@Composable
fun NamePage() = NamePagePreview()

@PreviewTest
@ScreenShots
@Composable
fun PermissionsPage() = PermissionsPagePreview()

/** The permissions page at 1.5x, the scale main's own preview checks. */
@PreviewTest
@Preview(name = "Light-1.5x", showBackground = true, fontScale = 1.5f, locale = ENGLISH)
@Preview(name = "Dark-1.5x", showBackground = true, fontScale = 1.5f, locale = ENGLISH, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PermissionsPageLargeText() = PermissionsPagePreview()

@PreviewTest
@ScreenShots
@Composable
fun PermissionsPageGranted() = PermissionsPageGrantedPreview()

@PreviewTest
@ScreenShots
@Composable
fun PermissionsPageDenied() = PermissionsPageDeniedPreview()

@PreviewTest
@ScreenShots
@Composable
fun PermissionsPageLocationTier() = PermissionsPageLocationTierPreview()

@PreviewTest
@ScreenShots
@Composable
fun PermissionsPageUnsupported() = PermissionsPageUnsupportedPreview()

@PreviewTest
@ScreenShots
@Composable
fun PermissionsPageFamilyLink() = PermissionsPageFamilyLinkPreview()

@PreviewTest
@ScreenShots
@Composable
fun OnboardingWelcome() = OnboardingWelcomePreview()

@PreviewTest
@ScreenShots
@Composable
fun OnboardingName() = OnboardingNamePreview()

@PreviewTest
@ScreenShots
@Composable
fun OnboardingPermissions() = OnboardingPermissionsPreview()
