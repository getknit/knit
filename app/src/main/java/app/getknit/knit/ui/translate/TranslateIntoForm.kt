package app.getknit.knit.ui.translate

import app.getknit.knit.translate.TranslateLang

/**
 * Settings › Chats' "Translate messages into" (ADR 2026-10.t2h6's 2026-10-29 amendment): [selected] is the reader's
 * choice, null for Automatic, which picks [automatic] (what they write, else the phone's language; null when neither
 * says); [choices] is every language a model translates into.
 */
data class TranslateIntoForm(
    val selected: TranslateLang?,
    val automatic: TranslateLang?,
    val choices: List<TranslateLang>,
)
