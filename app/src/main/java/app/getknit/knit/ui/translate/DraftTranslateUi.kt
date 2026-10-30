package app.getknit.knit.ui.translate

import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang

/**
 * What the composer shows of translating the person's own draft before it is sent (ADR 2026-10.t2h6's amendment):
 * the language the chat reads when it differs from the draft's ([hint]), a translation running or failed, a model downloading,
 * and the language the draft was translated from while it holds one.
 */
data class DraftTranslateUi(
    val hint: TranslateDirection? = null,
    // The hint came from what the chat's people declared reading, by [hintBy] (a peer's name) in a one-to-one chat.
    val hintDeclared: Boolean = false,
    val hintBy: String? = null,
    val running: Boolean = false,
    // Null for no failure, true when the model looped (refused), false when it would not run.
    val failedUnusable: Boolean? = null,
    val translatedFrom: TranslateLang? = null,
    // The draft's language was what this person usually writes here, not what its words read: "Not English?" is offered.
    val translatedGuessed: Boolean = false,
    val downloadingPercent: Int? = null,
)
