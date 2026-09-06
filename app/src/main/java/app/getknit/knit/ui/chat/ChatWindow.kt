package app.getknit.knit.ui.chat

/**
 * How much of a thread the chat screen reads at once.
 *
 * A conversation runs to its retention cap — 5,000 messages on an accepted thread, 2,000 in a room — and the
 * screen used to select all of them, fold every one into a row, and redo both on every write to the messages
 * table. It now reads the newest [INITIAL] and grows by [PAGE] as the reader scrolls back into history.
 */
object ChatWindow {
    /** Enough to fill a tall phone several times over, so the reader can fling before a page is fetched. */
    const val INITIAL = 60

    /** Added per [ChatViewModel.loadOlder]. */
    const val PAGE = 100

    /**
     * Ceiling on the window, so following a reply quote deep into history can't quietly restore the
     * unbounded read. Kept level with `MessageRepository.DEFAULT_MAX_PER_ACCEPTED_THREAD` — the retention cap
     * — so a thread can never hold more than this anyway; that constant is private to the repository, so the
     * two are matched by hand rather than shared.
     */
    const val MAX = 5_000

    /**
     * Fire [ChatViewModel.loadOlder] once the reader is this close to the oldest loaded row. It is a
     * prefetch, but it is also what hides the window's one visible artifact: a full window drops its oldest
     * row when a new message lands, so a reader parked exactly at the top would watch a bubble disappear.
     * Growing before they get there means there is never a row at the top to lose.
     */
    const val LOAD_AHEAD = 10
}
