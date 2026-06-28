package app.getknit.knit.moderation

/**
 * Outcome of moderating a chat message body. [allowed] false means the text should be blocked (on
 * send) or collapsed behind a tap-to-reveal (on receive). [score] is the classifier confidence in
 * `[0, 1]` (1 for a deterministic lexical hit) — for the ML pass it is the highest score among the
 * enforced toxicity categories, reported even when it stayed below threshold (i.e. [allowed] true) so
 * debug logs show near-misses rather than a flat 0. [category] records the coarse class that tripped
 * it; [label] is the specific underlying classifier label when known (e.g. `"sexual_explicit"`), null
 * for a lexical/profanity hit or when no model is bundled.
 */
data class TextVerdict(
    val allowed: Boolean,
    val category: Category = Category.NONE,
    val score: Float = 0f,
    val label: String? = null,
) {
    enum class Category { NONE, PROFANITY, TOXICITY }

    val flagged: Boolean get() = !allowed

    companion object {
        val ALLOWED = TextVerdict(allowed = true)
    }
}

/**
 * Outcome of moderating an image. [allowed] false means the image is explicit and should be blocked
 * (on send) or blurred behind a tap-to-reveal (on receive). [score] is the NSFW confidence in
 * `[0, 1]`.
 */
data class ImageVerdict(
    val allowed: Boolean,
    val score: Float = 0f,
) {
    val flagged: Boolean get() = !allowed

    companion object {
        val ALLOWED = ImageVerdict(allowed = true)
    }
}
