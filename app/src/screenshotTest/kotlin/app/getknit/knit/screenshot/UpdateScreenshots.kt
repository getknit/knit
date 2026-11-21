package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.update.UpdateHintLinePreview
import app.getknit.knit.ui.update.UpdateHintLineWaitingPreview
import app.getknit.knit.ui.update.UpdateRequestCardOutcomesPreview
import app.getknit.knit.ui.update.UpdateRequestCardPreview
import app.getknit.knit.ui.update.UpdateRequestCardSendingPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.update`.

@PreviewTest
@ComponentShots
@Composable
fun UpdateHintLine() = UpdateHintLinePreview()

@PreviewTest
@ComponentShots
@Composable
fun UpdateHintLineWaiting() = UpdateHintLineWaitingPreview()

@PreviewTest
@ComponentShots
@Composable
fun UpdateRequestCard() = UpdateRequestCardPreview()

@PreviewTest
@ComponentShots
@Composable
fun UpdateRequestCardOutcomes() = UpdateRequestCardOutcomesPreview()

@PreviewTest
@ComponentShots
@Composable
fun UpdateRequestCardSending() = UpdateRequestCardSendingPreview()
