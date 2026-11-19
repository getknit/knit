package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.lora.LoraRadioMeshcoreNamePreview
import app.getknit.knit.ui.lora.LoraRadioScreenPreview
import app.getknit.knit.ui.lora.MeshcoreRenameDialogFlaggedPreview
import app.getknit.knit.ui.lora.MeshcoreRenameDialogPositionPreview
import app.getknit.knit.ui.lora.MeshcoreRenameDialogPreview
import app.getknit.knit.ui.lora.MeshcoreRenameDialogTooLongPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.lora`.

@PreviewTest
@ScreenShots
@Composable
fun LoraRadioScreen() = LoraRadioScreenPreview()

@PreviewTest
@ComponentShots
@Composable
fun LoraRadioMeshcoreName() = LoraRadioMeshcoreNamePreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshcoreRenameDialog() = MeshcoreRenameDialogPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshcoreRenameDialogPosition() = MeshcoreRenameDialogPositionPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshcoreRenameDialogTooLong() = MeshcoreRenameDialogTooLongPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshcoreRenameDialogFlagged() = MeshcoreRenameDialogFlaggedPreview()
