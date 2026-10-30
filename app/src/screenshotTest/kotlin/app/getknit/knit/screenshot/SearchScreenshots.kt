package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.search.SearchScreenIdlePreview
import app.getknit.knit.ui.search.SearchScreenInChatPreview
import app.getknit.knit.ui.search.SearchScreenNoResultsPreview
import app.getknit.knit.ui.search.SearchScreenResultsPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.search`.

@PreviewTest
@ScreenShots
@Composable
fun SearchScreenIdle() = SearchScreenIdlePreview()

@PreviewTest
@ScreenShots
@Composable
fun SearchScreenResults() = SearchScreenResultsPreview()

@PreviewTest
@ScreenShots
@Composable
fun SearchScreenInChat() = SearchScreenInChatPreview()

@PreviewTest
@ScreenShots
@Composable
fun SearchScreenNoResults() = SearchScreenNoResultsPreview()
