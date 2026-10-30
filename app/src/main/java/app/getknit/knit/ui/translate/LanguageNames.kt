package app.getknit.knit.ui.translate

import app.getknit.knit.translate.TranslateLang
import java.util.Locale

/**
 * A language's name in the reader's language, from the platform's own locale data rather than a string per language
 * (ADR 2026-10.t2h6's 2026-10-29 amendment): a new model brings its name with it. Every shipped template carries the
 * name uninflected ("Übersetzt aus: %1$s", "Оригінал: %1$s"), so the plain form fits all of them.
 */
object LanguageNames {
    /** As a sentence carries it: "Spanish", "español", "Spanisch". A script code is named with its script. */
    fun name(
        lang: TranslateLang,
        locale: Locale,
    ): String {
        val named = Locale.forLanguageTag(lang.code.replace('_', '-'))
        return named.getDisplayName(locale).takeIf { it.isNotBlank() && it != lang.code } ?: lang.code
    }

    /** On its own, as a button or a list item: the first letter capitalised where the language lowercases names. */
    fun title(
        lang: TranslateLang,
        locale: Locale,
    ): String = name(lang, locale).replaceFirstChar { it.titlecase(locale) }
}
