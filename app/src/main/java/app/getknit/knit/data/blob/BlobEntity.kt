package app.getknit.knit.data.blob

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Content-addressed image bytes stored inside the encrypted database, so attachments and avatars are
 * encrypted at rest like the rest of the data (rather than living as plaintext files on disk). [hash]
 * is the SHA-256 hex of [bytes] — the same content key carried on the wire — so identical images
 * dedupe to one row and any holder can serve them. One table backs both message attachments and
 * avatars.
 */
@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey val hash: String,
    val mime: String,
    val bytes: ByteArray,
) {
    // Identity is the content hash. The default data-class equals/hashCode would compare the ByteArray
    // by reference (and Room/detekt flag a ByteArray in a data class); content-addressing makes hash
    // the only field that matters for equality.
    override fun equals(other: Any?): Boolean =
        this === other || (other is BlobEntity && hash == other.hash)

    override fun hashCode(): Int = hash.hashCode()
}
