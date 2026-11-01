package app.getknit.knit.data.emoji

import java.text.Normalizer

/**
 * The one folding search applies to a query and to every name and keyword it compares against: lower case,
 * then accents off (NFD, combining marks dropped), so "corazon" finds "corazón" and "Herz" finds "herz".
 * Pure JVM ([Normalizer] is on Android too). It conflates letters a language keeps apart — Ukrainian й
 * folds to и — which only ever widens a match, never hides one.
 */
object EmojiFold {
    private val MARKS = Regex("\\p{Mn}+")

    fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
}
