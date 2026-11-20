package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.update.UpdateHintLinePreview
import app.getknit.knit.ui.update.UpdateHintLineWaitingPreview
import app.getknit.knit.ui.update.UpdateRequestBandPreview
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
fun UpdateRequestBand() = UpdateRequestBandPreview()
