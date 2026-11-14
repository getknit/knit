package app.getknit.knit.moderation

import app.getknit.knit.identity.NamePresenter

/**
 * What another person's name, status or group name is drawn as, decided where it is drawn (ADR 2026-11.y4pc). A
 * flagged one is shown blank, so a name takes the alias path a blank name already takes, a group takes its
 * member-derived title, and a status shows nothing. Callers apply it only while Content filtering is on; nothing is
 * stored, and the signed profile and group frames are never touched.
 *
 * The verdicts are cached by text, bounded at [capacity], because a label index is rebuilt for every peer change and
 * asks about every row.
 */
class ShownNames(
    private val screen: NameScreen,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val verdicts =
        object : LinkedHashMap<String, Boolean>(INITIAL, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
        }

    /** Presents a person's name or status: blank when strangers must not see it ([NameScreen.Audience.PUBLIC]). */
    val person: NamePresenter = NamePresenter { if (flagged(it, NameScreen.Audience.PUBLIC)) "" else it }

    /** Presents a group's name: blank when it holds a slur ([NameScreen.Audience.MEMBERS]). */
    val group: NamePresenter = NamePresenter { if (flagged(it, NameScreen.Audience.MEMBERS)) "" else it }

    /** Whether [text] is flagged for [audience]; blank text never is. */
    fun flagged(
        text: String,
        audience: NameScreen.Audience,
    ): Boolean {
        if (text.isBlank()) return false
        val key = "${audience.ordinal}:$text"
        synchronized(verdicts) { verdicts[key]?.let { return it } }
        val flagged = screen.check(text, audience).flagged
        synchronized(verdicts) { verdicts[key] = flagged }
        return flagged
    }

    private companion object {
        // Room for every row of a full peer table (PeerRepository's 2,000) twice over, with statuses, notices and board
        // names beside them, so a rebuild walking the table in order still hits while a flood overshoots the cap.
        const val DEFAULT_CAPACITY = 8192
        const val INITIAL = 64
        const val LOAD = 0.75f
    }
}
