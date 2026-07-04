package app.getknit.knit.ui.image

import coil3.key.Keyer
import coil3.request.Options

/**
 * Memory-cache key for a [BlobImage]: the content hash. A changed image yields a new hash and so a new
 * key, which is what makes an updated avatar/attachment re-render past Coil's memory cache.
 */
class BlobKeyer : Keyer<BlobImage> {
    override fun key(
        data: BlobImage,
        options: Options,
    ): String = data.hash
}
