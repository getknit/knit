package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.translate.DraftTranslateHintPreview
import app.getknit.knit.ui.translate.DraftTranslatedGuessedPreview
import app.getknit.knit.ui.translate.DraftTranslatedPreview
import app.getknit.knit.ui.translate.TranslationBlockNeedsModelPreview
import app.getknit.knit.ui.translate.TranslationBlockTranslatingPreview
import app.getknit.knit.ui.translate.TranslationToggleGuessedPreview
import app.getknit.knit.ui.translate.TranslationToggleOriginalPreview
import app.getknit.knit.ui.translate.TranslationToggleTranslatedPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.translate`.

@PreviewTest
@ComponentShots
@Composable
fun DraftTranslateHint() = DraftTranslateHintPreview()

@PreviewTest
@ComponentShots
@Composable
fun DraftTranslatedGuessed() = DraftTranslatedGuessedPreview()

@PreviewTest
@ComponentShots
@Composable
fun DraftTranslated() = DraftTranslatedPreview()

@PreviewTest
@ComponentShots
@Composable
fun TranslationToggleTranslated() = TranslationToggleTranslatedPreview()

@PreviewTest
@ComponentShots
@Composable
fun TranslationToggleGuessed() = TranslationToggleGuessedPreview()

@PreviewTest
@ComponentShots
@Composable
fun TranslationToggleOriginal() = TranslationToggleOriginalPreview()

@PreviewTest
@ComponentShots
@Composable
fun TranslationBlockTranslating() = TranslationBlockTranslatingPreview()

@PreviewTest
@ComponentShots
@Composable
fun TranslationBlockNeedsModel() = TranslationBlockNeedsModelPreview()
