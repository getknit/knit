package app.getknit.knit.ui.image

import app.getknit.knit.data.blob.BlobDao
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer

/**
 * Coil [Fetcher] that loads a [BlobImage]'s bytes from the encrypted `blobs` table and exposes them as
 * an **in-memory** [ImageSource]. The bytes are never written to disk: the buffer-backed source keeps
 * decoding in RAM and Coil's disk cache is disabled (see `KnitApplication`), so decrypted images don't
 * leak to storage. `fetch()` runs on Coil's IO fetch dispatcher, so the suspending DB read is safe here.
 */
class BlobFetcher(
    private val blobs: BlobDao,
    private val data: BlobImage,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = blobs.bytes(data.hash) ?: return null
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().apply { write(bytes) },
                fileSystem = options.fileSystem,
            ),
            mimeType = data.mime,
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(private val blobs: BlobDao) : Fetcher.Factory<BlobImage> {
        override fun create(data: BlobImage, options: Options, imageLoader: ImageLoader): Fetcher =
            BlobFetcher(blobs, data, options)
    }
}
