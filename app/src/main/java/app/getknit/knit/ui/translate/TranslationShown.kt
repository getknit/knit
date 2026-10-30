package app.getknit.knit.ui.translate

import app.getknit.knit.call.SttModelState
import app.getknit.knit.translate.MessageTranslation
import app.getknit.knit.translate.StoredTranslation
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang

/** What a bubble draws under its body for a translation (ADR 2026-10.t2h6). */
sealed interface TranslationShown {
    data object Running : TranslationShown

    /** [hidden] when Content filtering hides it, until the reader taps; [guessed] when [from] was a prior's guess. */
    data class Text(
        val text: String,
        val from: TranslateLang,
        val to: TranslateLang,
        val hidden: Boolean,
        val guessed: Boolean = false,
    ) : TranslationShown

    /** [direction]'s model is not on the phone; [model] is where its download stands. */
    data class NeedsModel(
        val direction: TranslateDirection,
        val model: SttModelState,
    ) : TranslationShown

    /** Nothing goes from [from] into the reader's language: [already] when it is the reader's language. */
    data class NoTarget(
        val from: TranslateLang,
        val already: Boolean,
    ) : TranslationShown

    data class Failed(
        val unusable: Boolean,
    ) : TranslationShown
}

/**
 * The bubble's view of a message's translation, or null when there is none: what this process holds for it ([state])
 * wins — a translation running, just made, failed or waiting on a model — else the one kept on its row ([stored]).
 * A flagged one is hidden while Content filtering is on, read now rather than when it was made.
 */
fun translationShown(
    state: MessageTranslation?,
    stored: StoredTranslation?,
    models: Map<TranslateDirection, SttModelState>,
    filterOn: Boolean,
    reader: TranslateLang?,
): TranslationShown? =
    when (state) {
        null -> {
            stored?.let { TranslationShown.Text(it.text, it.from, it.to, it.flagged && filterOn, it.guessed) }
        }

        MessageTranslation.Running -> {
            TranslationShown.Running
        }

        is MessageTranslation.Done -> {
            TranslationShown.Text(state.text, state.from, state.to, state.flagged && filterOn, state.guessed)
        }

        is MessageTranslation.NeedsModel -> {
            TranslationShown.NeedsModel(state.direction, models[state.direction] ?: SttModelState.Absent)
        }

        is MessageTranslation.NoTarget -> {
            TranslationShown.NoTarget(state.from, already = state.from == reader)
        }

        is MessageTranslation.Failed -> {
            TranslationShown.Failed(state.reason == MessageTranslation.Failed.Reason.UNUSABLE)
        }
    }
