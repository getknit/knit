package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.devview.MeshHudOpenPreview
import app.getknit.knit.ui.devview.MeshHudPreview
import app.getknit.knit.ui.devview.MessageInspectSectionAttachmentPreview
import app.getknit.knit.ui.devview.MessageInspectSectionPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.devview`.

@PreviewTest
@ComponentShots
@Composable
fun MeshHud() = MeshHudPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshHudOpen() = MeshHudOpenPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageInspectSection() = MessageInspectSectionPreview()

@PreviewTest
@ComponentShots
@Composable
fun MessageInspectSectionAttachment() = MessageInspectSectionAttachmentPreview()
