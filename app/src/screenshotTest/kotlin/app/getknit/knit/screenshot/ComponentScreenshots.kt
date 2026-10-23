package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.components.AdaptiveRowPreview
import app.getknit.knit.ui.components.AvatarBadgeSizesPreview
import app.getknit.knit.ui.components.AvatarInitialPreview
import app.getknit.knit.ui.components.AvatarLargeEmojiPreview
import app.getknit.knit.ui.components.AvatarPalettePreview
import app.getknit.knit.ui.components.ButtonRowPreview
import app.getknit.knit.ui.components.CharCounterPreview
import app.getknit.knit.ui.components.ChoiceButtonsPreview
import app.getknit.knit.ui.components.ConnectionStatusRowBothPlanesPreview
import app.getknit.knit.ui.components.ConnectionStatusRowConnectedPreview
import app.getknit.knit.ui.components.ConnectionStatusRowDegradedPreview
import app.getknit.knit.ui.components.ConnectionStatusRowDisconnectedPreview
import app.getknit.knit.ui.components.ConnectionStatusRowLoraDownPreview
import app.getknit.knit.ui.components.ConnectionStatusRowLoraLivePreview
import app.getknit.knit.ui.components.ConnectionStatusRowRadioOffPreview
import app.getknit.knit.ui.components.ConnectionStatusRowRelayDownPreview
import app.getknit.knit.ui.components.ConnectionStatusRowRelayOnlyPreview
import app.getknit.knit.ui.components.ConnectionStatusRowRelayWithRadiosOffPreview
import app.getknit.knit.ui.components.ConnectionStatusRowSinglePreview
import app.getknit.knit.ui.components.ConnectionStatusRowThreePlanesPreview
import app.getknit.knit.ui.components.DisplayNameFieldPreview
import app.getknit.knit.ui.components.FullscreenImageViewerPreview
import app.getknit.knit.ui.components.GroupAvatarClusterPreview
import app.getknit.knit.ui.components.GroupAvatarGlyphPreview
import app.getknit.knit.ui.components.GroupAvatarLargePreview
import app.getknit.knit.ui.components.KnitStitchIndicatorPreview
import app.getknit.knit.ui.components.PeerNameTextPlainPreview
import app.getknit.knit.ui.components.PeerNameTextPreview
import app.getknit.knit.ui.components.RoomAvatarPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.components`.

@PreviewTest
@ComponentShots
@Composable
fun AdaptiveRow() = AdaptiveRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun AvatarInitial() = AvatarInitialPreview()

@PreviewTest
@ComponentShots
@Composable
fun AvatarLargeEmoji() = AvatarLargeEmojiPreview()

@PreviewTest
@ComponentShots
@Composable
fun AvatarPalette() = AvatarPalettePreview()

@PreviewTest
@ComponentShots
@Composable
fun AvatarBadgeSizes() = AvatarBadgeSizesPreview()

@PreviewTest
@ComponentShots
@Composable
fun ButtonRow() = ButtonRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun ChoiceButtons() = ChoiceButtonsPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowConnected() = ConnectionStatusRowConnectedPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowSingle() = ConnectionStatusRowSinglePreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowDisconnected() = ConnectionStatusRowDisconnectedPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowRadioOff() = ConnectionStatusRowRadioOffPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowDegraded() = ConnectionStatusRowDegradedPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowBothPlanes() = ConnectionStatusRowBothPlanesPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowRelayOnly() = ConnectionStatusRowRelayOnlyPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowRelayWithRadiosOff() = ConnectionStatusRowRelayWithRadiosOffPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowRelayDown() = ConnectionStatusRowRelayDownPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowLoraLive() = ConnectionStatusRowLoraLivePreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowLoraDown() = ConnectionStatusRowLoraDownPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConnectionStatusRowThreePlanes() = ConnectionStatusRowThreePlanesPreview()

@PreviewTest
@ComponentShots
@Composable
fun DisplayNameField() = DisplayNameFieldPreview()

@PreviewTest
@ComponentShots
@Composable
fun CharCounter() = CharCounterPreview()

@PreviewTest
@ScreenShots
@Composable
fun FullscreenImageViewer() = FullscreenImageViewerPreview()

@PreviewTest
@ComponentShots
@Composable
fun GroupAvatarCluster() = GroupAvatarClusterPreview()

@PreviewTest
@ComponentShots
@Composable
fun GroupAvatarGlyph() = GroupAvatarGlyphPreview()

@PreviewTest
@ComponentShots
@Composable
fun GroupAvatarLarge() = GroupAvatarLargePreview()

@PreviewTest
@ComponentShots
@Composable
fun KnitStitchIndicator() = KnitStitchIndicatorPreview()

@PreviewTest
@ComponentShots
@Composable
fun PeerNameText() = PeerNameTextPreview()

@PreviewTest
@ComponentShots
@Composable
fun PeerNameTextPlain() = PeerNameTextPlainPreview()

@PreviewTest
@ComponentShots
@Composable
fun RoomAvatar() = RoomAvatarPreview()
