package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for content-addressed image blobs (attachments + avatars) held in the
 * encrypted database. Wraps [BlobDao] and owns the cross-table reference check used to garbage-collect
 * an orphaned blob once nothing points at it.
 */
class BlobRepository(
    private val blobs: BlobDao,
    private val messages: MessageDao,
    private val peers: PeerDao,
    private val settings: SettingsStore,
) {
    suspend fun insert(hash: String, mime: String, bytes: ByteArray) =
        blobs.insert(BlobEntity(hash, mime, bytes))

    suspend fun bytes(hash: String): ByteArray? = blobs.bytes(hash)

    suspend fun mimeFor(hash: String): String? = blobs.mimeFor(hash)

    suspend fun exists(hash: String): Boolean = blobs.exists(hash)

    /** Hashes of all stored blobs; the chat list observes this to flip attachments from loading to shown. */
    fun observeHashes(): Flow<List<String>> = blobs.observeHashes()

    /**
     * Deletes the blob for [hash] only if nothing references it any more — no message attachment, no
     * peer avatar, and not the device's own avatar. Safe to call after deleting a message, swapping an
     * avatar, or discarding a staged-but-unsent attachment; a no-op (and tolerates a null hash) when the
     * blob is still in use.
     */
    suspend fun deleteIfUnreferenced(hash: String?) {
        if (hash == null) return
        if (hash == settings.ownAvatarHash.first()) return
        if (messages.countByAttachmentHash(hash) > 0) return
        if (peers.countByAvatarHash(hash) > 0) return
        blobs.delete(hash)
    }

    /**
     * Deletes every blob no longer referenced by a message, a peer avatar, or the own avatar. Run once
     * on mesh start to reclaim blobs left orphaned by, e.g., an attachment staged but never sent (its
     * compose state doesn't survive a restart, so its blob is safe to drop).
     */
    suspend fun deleteOrphans() {
        val own = settings.ownAvatarHash.first()
        blobs.orphanHashes().filter { it != own }.forEach { blobs.delete(it) }
    }
}
