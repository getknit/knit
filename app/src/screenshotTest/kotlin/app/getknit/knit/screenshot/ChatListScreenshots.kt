package app.getknit.knit.screenshot

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.getknit.knit.ui.chatlist.ChatListScreenClonePreview
import app.getknit.knit.ui.chatlist.ChatListScreenFirstRunPreview
import app.getknit.knit.ui.chatlist.ChatListScreenLoadingPreview
import app.getknit.knit.ui.chatlist.ChatListScreenPopulatedPreview
import app.getknit.knit.ui.chatlist.ChatListScreenQuietPreview
import app.getknit.knit.ui.chatlist.ChatListScreenRadioWarningPreview
import app.getknit.knit.ui.chatlist.ChatListScreenWhatsNewPreview
import app.getknit.knit.ui.chatlist.CloneBannerPreview
import app.getknit.knit.ui.chatlist.ConversationListItemDeliveredPreview
import app.getknit.knit.ui.chatlist.ConversationListItemDmPreview
import app.getknit.knit.ui.chatlist.ConversationListItemGroupPreview
import app.getknit.knit.ui.chatlist.ConversationListItemPendingPreview
import app.getknit.knit.ui.chatlist.ConversationListItemRoomPreview
import app.getknit.knit.ui.chatlist.ConversationListItemSentPreview
import app.getknit.knit.ui.chatlist.GettingStartedCardPreview
import app.getknit.knit.ui.chatlist.MeshOffBannerPausedPreview
import app.getknit.knit.ui.chatlist.MeshOffBannerStoppedPreview
import app.getknit.knit.ui.chatlist.RadioWarningBannerAllOffPreview
import app.getknit.knit.ui.chatlist.RadioWarningBannerBluetoothPreview
import app.getknit.knit.ui.chatlist.RadioWarningBannerWifiPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.chatlist`.

@PreviewTest
@ComponentShots
@Composable
fun ConversationListItemDm() = ConversationListItemDmPreview()

/** The DM row at the largest system font scale, where the title, preview and time compete for one line. */
@PreviewTest
@Preview(name = "Light-2x", showBackground = true, fontScale = 2f, locale = ENGLISH)
@Preview(name = "Dark-2x", showBackground = true, fontScale = 2f, locale = ENGLISH, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ConversationListItemDmLargeText() = ConversationListItemDmPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConversationListItemDelivered() = ConversationListItemDeliveredPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConversationListItemSent() = ConversationListItemSentPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConversationListItemPending() = ConversationListItemPendingPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConversationListItemGroup() = ConversationListItemGroupPreview()

@PreviewTest
@ComponentShots
@Composable
fun ConversationListItemRoom() = ConversationListItemRoomPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenPopulated() = ChatListScreenPopulatedPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenRadioWarning() = ChatListScreenRadioWarningPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenClone() = ChatListScreenClonePreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenLoading() = ChatListScreenLoadingPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenFirstRun() = ChatListScreenFirstRunPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenQuiet() = ChatListScreenQuietPreview()

@PreviewTest
@ScreenShots
@Composable
fun ChatListScreenWhatsNew() = ChatListScreenWhatsNewPreview()

@PreviewTest
@ComponentShots
@Composable
fun CloneBanner() = CloneBannerPreview()

@PreviewTest
@ComponentShots
@Composable
fun GettingStartedCard() = GettingStartedCardPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshOffBannerPaused() = MeshOffBannerPausedPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshOffBannerStopped() = MeshOffBannerStoppedPreview()

@PreviewTest
@ComponentShots
@Composable
fun RadioWarningBannerBluetooth() = RadioWarningBannerBluetoothPreview()

@PreviewTest
@ComponentShots
@Composable
fun RadioWarningBannerWifi() = RadioWarningBannerWifiPreview()

@PreviewTest
@ComponentShots
@Composable
fun RadioWarningBannerAllOff() = RadioWarningBannerAllOffPreview()
