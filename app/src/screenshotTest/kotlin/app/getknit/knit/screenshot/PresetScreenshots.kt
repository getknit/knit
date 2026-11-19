package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.preset.PresetChoicesPreview
import app.getknit.knit.ui.preset.StartingPointScreenPreview
import app.getknit.knit.ui.preset.StartingPointSwitchDialogPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.preset`.

@PreviewTest
@ComponentShots
@Composable
fun PresetChoices() = PresetChoicesPreview()

@PreviewTest
@ScreenShots
@Composable
fun StartingPointScreen() = StartingPointScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun StartingPointSwitchDialog() = StartingPointSwitchDialogPreview()
