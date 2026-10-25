package app.getknit.knit.data.draft

import app.getknit.knit.mesh.protocol.StyleSpan

/**
 * What a thread's composer holds: its [text] and the formatting over it ([spans], ADR 2026-10.p95v), both kept by
 * [DraftRepository] (ADR 2026-09.qtg9's 2026-10-24 amendment). Compared whole, so a report differing from the row in
 * its styles alone is an edit, and the restore's echo, which matches both, is not.
 */
data class Draft(
    val text: String,
    val spans: List<StyleSpan> = emptyList(),
) {
    companion object {
        val EMPTY = Draft("")
    }
}
