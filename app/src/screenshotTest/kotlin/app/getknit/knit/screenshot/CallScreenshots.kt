package app.getknit.knit.screenshot

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.ui.call.CallBarConnectedPreview
import app.getknit.knit.ui.call.CallBarIncomingPreview
import app.getknit.knit.ui.call.CallBarRingingPreview
import app.getknit.knit.ui.call.CallScreenCallerContextPreview
import app.getknit.knit.ui.call.CallScreenCallerLockedPreview
import app.getknit.knit.ui.call.CallScreenCaptionsBehindPreview
import app.getknit.knit.ui.call.CallScreenCaptionsEarpiecePreview
import app.getknit.knit.ui.call.CallScreenCaptionsNoModelPreview
import app.getknit.knit.ui.call.CallScreenCaptionsOffPreview
import app.getknit.knit.ui.call.CallScreenCaptionsPreview
import app.getknit.knit.ui.call.CallScreenConnectedPreview
import app.getknit.knit.ui.call.CallScreenDiagnosticsPreview
import app.getknit.knit.ui.call.CallScreenDroppedPreview
import app.getknit.knit.ui.call.CallScreenEndedPreview
import app.getknit.knit.ui.call.CallScreenHeadsetPreview
import app.getknit.knit.ui.call.CallScreenIncomingPreview
import app.getknit.knit.ui.call.CallScreenLongNamePreview
import app.getknit.knit.ui.call.CallScreenPoorPreview
import app.getknit.knit.ui.call.CallScreenReconnectingPreview
import app.getknit.knit.ui.call.CallScreenRelayDiagnosticsPreview
import app.getknit.knit.ui.call.CallScreenRingingPreview
import app.getknit.knit.ui.call.CallScreenStrangerPreview
import app.getknit.knit.ui.call.CallScreenUnreachablePreview
import app.getknit.knit.ui.call.CallScreenWalkieDiagnosticsPreview
import app.getknit.knit.ui.call.CallScreenWalkieMeshPreview
import app.getknit.knit.ui.call.CallScreenWalkiePreview
import app.getknit.knit.ui.call.CallScreenWalkieTranscriptDownloadingPreview
import app.getknit.knit.ui.call.CallScreenWalkieTranscriptNoModelPreview
import app.getknit.knit.ui.call.CallScreenWalkieTranscriptPreview
import app.getknit.knit.ui.call.CallScreenWalkieUnlinkedPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.call`.

@PreviewTest
@ScreenShots
@Composable
fun CallScreenIncoming() = CallScreenIncomingPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenLongName() = CallScreenLongNamePreview()

@PreviewTest
@Preview(name = "Light", showBackground = true, device = "spec:width=411dp,height=891dp", locale = ENGLISH)
@Preview(
    name = "Dark",
    showBackground = true,
    device = "spec:width=411dp,height=891dp",
    locale = ENGLISH,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "LongText", showBackground = true, device = "spec:width=411dp,height=891dp", locale = LONG_TEXT)
@Preview(name = "Rtl", showBackground = true, device = "spec:width=411dp,height=891dp", locale = RIGHT_TO_LEFT)
@Preview(name = "Small", showBackground = true, device = "spec:width=360dp,height=640dp", locale = ENGLISH)
@Composable
fun CallScreenCallerContext() = CallScreenCallerContextPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenCallerLocked() = CallScreenCallerLockedPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenStranger() = CallScreenStrangerPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenRinging() = CallScreenRingingPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenConnected() = CallScreenConnectedPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenHeadset() = CallScreenHeadsetPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenPoor() = CallScreenPoorPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenRelayDiagnostics() = CallScreenRelayDiagnosticsPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenDiagnostics() = CallScreenDiagnosticsPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenUnreachable() = CallScreenUnreachablePreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenEnded() = CallScreenEndedPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenReconnecting() = CallScreenReconnectingPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenDropped() = CallScreenDroppedPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkie() = CallScreenWalkiePreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkieUnlinked() = CallScreenWalkieUnlinkedPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkieMesh() = CallScreenWalkieMeshPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkieDiagnostics() = CallScreenWalkieDiagnosticsPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkieTranscript() = CallScreenWalkieTranscriptPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkieTranscriptNoModel() = CallScreenWalkieTranscriptNoModelPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenWalkieTranscriptDownloading() = CallScreenWalkieTranscriptDownloadingPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenCaptions() = CallScreenCaptionsPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenCaptionsOff() = CallScreenCaptionsOffPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenCaptionsEarpiece() = CallScreenCaptionsEarpiecePreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenCaptionsNoModel() = CallScreenCaptionsNoModelPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallScreenCaptionsBehind() = CallScreenCaptionsBehindPreview()

@PreviewTest
@ComponentShots
@Composable
fun CallBarConnected() = CallBarConnectedPreview()

@PreviewTest
@ComponentShots
@Composable
fun CallBarRinging() = CallBarRingingPreview()

@PreviewTest
@ComponentShots
@Composable
fun CallBarIncoming() = CallBarIncomingPreview()
