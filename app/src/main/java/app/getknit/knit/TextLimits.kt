package app.getknit.knit

/**
 * Character limits for user-editable free-text inputs, shared by the input fields that enforce them,
 * their on-screen counters, and the wire-level guards in [app.getknit.knit.mesh.MeshManager].
 * Centralized so the cap and the counter can never drift apart.
 */
object TextLimits {
    /** Profile display name — single line, shown in chat lists, headers, and notifications. */
    const val DISPLAY_NAME = 32

    /** Profile status one-liner. */
    const val STATUS = 100

    /** Group title — single line, shown in the chat header. */
    const val GROUP_NAME = 32

    /** Chat message body. Generous, but bounded so a frame stays well within the transport's payload budget. */
    const val MESSAGE = 2000
}

/**
 * Normalizes a single-line field: trims the ends and collapses internal whitespace runs (including
 * stray newlines/tabs from a paste) down to single spaces. Apply this at commit time, never
 * per-keystroke — trimming the trailing space on every keystroke would stop the user from typing a
 * space between words (the field would reset before the next character).
 */
fun normalizeSingleLine(value: String): String = value.trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")
