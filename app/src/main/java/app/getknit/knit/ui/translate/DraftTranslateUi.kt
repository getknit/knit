package app.getknit.knit.ui.translate

import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang

/**
 * What the composer shows of translating the person's own draft before it is sent (ADR 2026-10.t2h6's amendment):
 * the chat's language when it differs from the draft's ([hint]), a translation running or failed, a model downloading,
 * and the language the draft was translated from while it holds one.
 */
data class DraftTranslateUi(
    val hint: TranslateDirection? = null,
    val running: Boolean = false,
    // Null for no failure, true when the model looped (refused), false when it would not run.
    val failedUnusable: Boolean? = null,
    val translatedFrom: TranslateLang? = null,
    // The draft's language was what this person usually writes here, not what its words read: "Not English?" is offered.
    val translatedGuessed: Boolean = false,
    val downloadingPercent: Int? = null,
)
