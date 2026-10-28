package app.getknit.knit.data

/**
 * Removes one message from this phone only: its row, its reactions, its pin, its per-recipient delivery rows, and its
 * attachment blob once nothing else references it. Sends nothing over the mesh and leaves custody alone.
 *
 * The chat's long-press Delete and the debug bridge's `PURGE` both call this, so the list of what a message leaves
 * behind lives in one place. When pins landed (#42) the bridge's own copy of the list missed the pin, which only the
 * startup orphan sweep would have caught.
 *
 * Each step is a delete keyed by the message id that is safe to repeat, so no transaction joins them: a delete cut
 * short leaves rows that the orphan sweeps in `MeshManager` reclaim at the next start. The blob goes last because its
 * reference check counts the message row.
 */
class LocalMessageDelete(
    private val messages: MessageRepository,
    private val reactions: ReactionRepository,
    private val receipts: MessageReceiptRepository,
    private val blobs: BlobRepository,
    // Null only in a test rig that wires no pins, as `ChatViewModel`'s own `pins` is.
    private val pins: PinRepository?,
) {
    /**
     * Deletes [messageId] and what hangs off it. [attachmentHash] is the message's blob, read by the caller from the
     * row before it goes, since the row is the only place that names it; null for a message with no attachment.
     */
    suspend fun delete(
        messageId: String,
        attachmentHash: String?,
    ) {
        messages.delete(messageId)
        reactions.deleteForMessage(messageId)
        pins?.deleteForMessage(messageId)
        receipts.deleteForMessage(messageId)
        blobs.deleteIfUnreferenced(attachmentHash)
    }
}
