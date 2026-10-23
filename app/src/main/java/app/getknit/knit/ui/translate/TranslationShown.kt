package app.getknit.knit.ui.translate

import app.getknit.knit.call.SttModelState
import app.getknit.knit.translate.MessageTranslation
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang

/** What a bubble draws under its body for a translation (ADR 2026-10.t2h6). */
sealed interface TranslationShown {
    data object Running : TranslationShown

    /** [hidden] when Content filtering hides it, until the reader taps. */
    data class Text(
        val text: String,
        val from: TranslateLang,
        val hidden: Boolean,
    ) : TranslationShown

    /** [direction]'s model is not on the phone; [model] is where its download stands. */
    data class NeedsModel(
        val direction: TranslateDirection,
        val model: SttModelState,
    ) : TranslationShown

    data class Failed(
        val unusable: Boolean,
    ) : TranslationShown
}

/** The bubble's view of [state], or null when nothing was asked for this message. */
fun translationShown(
    state: MessageTranslation?,
    models: Map<TranslateDirection, SttModelState>,
    filterOn: Boolean,
): TranslationShown? =
    when (state) {
        null -> null
        MessageTranslation.Running -> TranslationShown.Running
        is MessageTranslation.Done -> TranslationShown.Text(state.text, state.from, state.flagged && filterOn)
        is MessageTranslation.NeedsModel -> TranslationShown.NeedsModel(state.direction, models[state.direction] ?: SttModelState.Absent)
        is MessageTranslation.Failed -> TranslationShown.Failed(state.reason == MessageTranslation.Failed.Reason.UNUSABLE)
    }
