package app.getknit.knit.ui.image

import app.getknit.knit.data.LinkCardStore
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.Buffer

/**
 * Coil model for the picture inside a link-preview card: the card's blob [hash] and, for a sealed DM or
 * group card, its base64 [key]. Resolved by [LinkCardFetcher] (the container is opened and its picture served
 * from memory) and keyed by [LinkCardKeyer]. A different type from [BlobImage] on purpose — the same hash
 * handed to [BlobFetcher] would try to decode the container as an image and fail.
 */
data class LinkCardImage(
    val hash: String,
    val key: String? = null,
)

/**
 * Memory-cache key for a [LinkCardImage]: the hash under a `card:` prefix, so it can never collide with a
 * [BlobImage]'s bare hash in the shared memory cache.
 */
class LinkCardKeyer : Keyer<LinkCardImage> {
    override fun key(
        data: LinkCardImage,
        options: Options,
    ): String = "card:${data.hash}"
}

/**
 * Coil [Fetcher] for a card's picture: [LinkCardStore.imageBytes] opens the stored container (decrypting a sealed
 * one) and hands over the picture bytes, exposed as an **in-memory** [ImageSource] exactly as [BlobFetcher]
 * does — nothing decrypted is ever written to disk, and Coil's disk cache is off app-wide.
 */
class LinkCardFetcher(
    private val store: LinkCardStore,
    private val data: LinkCardImage,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val (bytes, mime) = store.imageBytes(data.hash, data.key) ?: return null
        return SourceFetchResult(
            source = ImageSource(source = Buffer().apply { write(bytes) }, fileSystem = options.fileSystem),
            mimeType = mime,
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(
        private val store: LinkCardStore,
    ) : Fetcher.Factory<LinkCardImage> {
        override fun create(
            data: LinkCardImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = LinkCardFetcher(store, data, options)
    }
}
