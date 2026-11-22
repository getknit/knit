package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.media.ChatMediaScreenEmptyPreview
import app.getknit.knit.ui.media.ChatMediaScreenFilesPreview
import app.getknit.knit.ui.media.ChatMediaScreenLinksPreview
import app.getknit.knit.ui.media.ChatMediaScreenMediaPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.media`.

@PreviewTest
@ScreenShots
@Composable
fun ChatMediaScreenMedia() = ChatMediaScreenMediaPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatMediaScreenFiles() = ChatMediaScreenFilesPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatMediaScreenLinks() = ChatMediaScreenLinksPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatMediaScreenEmpty() = ChatMediaScreenEmptyPreview()
