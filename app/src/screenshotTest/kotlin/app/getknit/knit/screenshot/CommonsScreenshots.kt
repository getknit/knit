package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.commons.CommonsDetailsScreenPreview
import app.getknit.knit.ui.commons.CommonsDetailsStalePreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.commons`.

@PreviewTest
@ScreenShots
@Composable
fun CommonsDetailsScreen() = CommonsDetailsScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun CommonsDetailsStale() = CommonsDetailsStalePreview()
