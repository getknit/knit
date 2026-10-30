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
     * implied: its language is the one the composer translated it into ([translatedInto]), else the one it was typed in
     * ([typedIn], the keyboard's, null for a pasted or shared message or a keyboard that does not say) — never the
     * words' own read, which the receiver makes itself; and the language its author reads is [reads].
     */
    fun declaration(
        translatedInto: TranslateLang?,
        typedIn: String?,
        reads: String?,
    ): Pair<String?, String?> = (translatedInto?.code ?: typedIn) to reads

    /**
     * Whether a message whose language resolved to [resolution] offers Translate to a reader of [reader] (the
     * 2026-10-29 amendment): not when its words, or the language it was typed in, name a language with nowhere to go
     * or no model here; a language only a prior settled, or none, keeps the offer, since the reader may know better.
     */
    fun offers(
        resolution: Resolution,
        reader: TranslateLang?,
    ): Boolean {
        // A language no model here translates from: nothing to offer, however it was known.
        if (resolution.pick is LanguagePick.Other) return false
        val lang = (resolution.pick as? LanguagePick.Picked)?.lang ?: return true
        return when (resolution.source) {
            LanguageSource.TEXT, LanguageSource.DECLARED -> of(lang, reader) != null
            else -> true
        }
    }

    /** Whether a message already translated ([kept]) offers Translate again: only into a target it is not in yet. */
    fun offersAgain(
        kept: StoredTranslation,
        reader: TranslateLang?,
    ): Boolean = of(kept.from, reader).let { it != null && it != kept.to }
}
