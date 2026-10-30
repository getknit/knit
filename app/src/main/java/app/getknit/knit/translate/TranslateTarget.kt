package app.getknit.knit.translate

/**
 * Which language a translation goes into (ADR 2026-10.t2h6's 2026-10-29 amendment). Every model has English on one side
 * and nothing pivots through it, so a message goes into the reader's language when a model reaches it, else into English,
 * the one language every model touches; a message already in the reader's language, or in English for a reader no model
 * reaches, has none, and Translate is not offered on it.
 */
object TranslateTarget {
    /** Where a message in [from] goes for a reader of [reader] (null: unknown), or null for nowhere. */
    fun of(
        from: TranslateLang,
        reader: TranslateLang?,
    ): TranslateLang? =
        when {
            from == reader -> null
            reader != null && TranslateDirection.of(from, reader) != null -> reader
            from != TranslateLang.EN && TranslateDirection.of(from, TranslateLang.EN) != null -> TranslateLang.EN
            else -> null
        }

    /**
     * Where a draft in [from] goes in a chat whose readers read [chat]: their language when a model reaches it, else
     * English for a draft that is not; null when the person has to choose ([draftChoices]).
     */
    fun forDraft(
        from: TranslateLang,
        chat: TranslateLang?,
    ): TranslateLang? =
        when {
            chat != null && chat != from && TranslateDirection.of(from, chat) != null -> chat
            from != TranslateLang.EN && TranslateDirection.of(from, TranslateLang.EN) != null -> TranslateLang.EN
            else -> draftChoices(from).singleOrNull()
        }

    /** Every language a draft in [from] can be translated into, by one model. */
    fun draftChoices(from: TranslateLang): List<TranslateLang> = TranslateDirection.ALL.filter { it.from == from }.map { it.to }

    /**
     * What a message declares (ADR 2026-10.t2h6's 2026-10-29 amendment), as codes before [DeclaredLangs.of] drops what is
     * implied: its language is the one the composer translated it into ([translatedInto]), else what its words read as
     * ([words], the identifier with no prior, so a guess is never declared); and the language its author reads is
     * [reader].
     */
    fun declaration(
        translatedInto: TranslateLang?,
        words: LanguagePick,
        reader: TranslateLang?,
    ): Pair<String?, String?> = (translatedInto ?: (words as? LanguagePick.Picked)?.lang)?.code to reader?.code
}
