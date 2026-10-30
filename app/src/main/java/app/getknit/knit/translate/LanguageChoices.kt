package app.getknit.knit.translate

/**
 * The order languages are offered in when the reader names one (ADR 2026-10.t2h6's 2026-10-29 amendment): what the
 * identifier and the priors thought likeliest first, then the rest by name. With many languages a short line is where
 * the identifier guesses between neighbours, and the answer is usually among its own top reads.
 */
object LanguageChoices {
    /** [likely] in its order, then the rest of [all] by [name]; [exclude] (the one the reader said it is not) left out. */
    fun order(
        likely: List<TranslateLang?>,
        all: Set<TranslateLang>,
        name: (TranslateLang) -> String,
        exclude: TranslateLang? = null,
    ): List<TranslateLang> {
        val first = likely.filterNotNull().filter { it in all && it != exclude }.distinct()
        return first + (all - first.toSet() - setOfNotNull(exclude)).sortedBy(name)
    }
}
