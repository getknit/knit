package app.getknit.knit.data.changes

/**
 * Which stored thing a repository write touched, by id alone — an invalidation, never the new state. A reader that
 * follows these (the debug build's USB control change feed, `usbcontrol/ChangeFeed`) re-reads the thing itself, so a
 * key that outruns its write's commit, or names a write that rolled back, costs a read and changes nothing.
 *
 * Emitted right after the write, in memory only: nothing is persisted, and nothing is emitted in a release build,
 * whose sink is [DataChangeSink.NONE] (`di/DataChangeWiring`).
 */
sealed interface DataChange {
    /** A message row, or its reactions, written or deleted. [conversationId] is null where the write did not know it. */
    data class Message(
        val id: String,
        val conversationId: String?,
    ) : DataChange

    /** What a thread's chat-list row shows changed beyond its messages: its group row was written, or it was left. */
    data class Conversation(
        val id: String,
    ) : DataChange

    /** A thread's rows were deleted in bulk — cleared, or trimmed by retention — rather than one by one. */
    data class ThreadTrimmed(
        val id: String,
    ) : DataChange

    /** A peer row written: their name, avatar, verification or profile. */
    data class Peer(
        val nodeId: String,
    ) : DataChange

    /** Peer rows changed in bulk (a sweep, a cleared avatar): every one may have. */
    data object Peers : DataChange

    /** A blob's bytes stored or deleted: whether every message naming [hash] holds its attachment may have changed. */
    data class Blob(
        val hash: String,
    ) : DataChange
}

/** Where repositories report a [DataChange]. Must not block or throw: it is called on the write path. */
fun interface DataChangeSink {
    fun emit(change: DataChange)

    companion object {
        /** No reader: what every release build and every test that doesn't ask for one gets. */
        val NONE: DataChangeSink = DataChangeSink { }
    }
}
