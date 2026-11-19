package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.redact.RedactReviewLookingPreview
import app.getknit.knit.ui.redact.RedactReviewPreview
import app.getknit.knit.ui.redact.RedactReviewUnavailablePreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.redact`.

@PreviewTest
@ScreenShots
@Composable
fun RedactReview() = RedactReviewPreview()

@PreviewTest
@ScreenShots
@Composable
fun RedactReviewLooking() = RedactReviewLookingPreview()

@PreviewTest
@ScreenShots
@Composable
fun RedactReviewUnavailable() = RedactReviewUnavailablePreview()
