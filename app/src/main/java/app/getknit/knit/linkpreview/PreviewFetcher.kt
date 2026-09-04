package app.getknit.knit.linkpreview

/**
 * The HTTP seam under [LinkPreviewService]: two bounded GETs and nothing else. The one implementation that
 * speaks OkHttp (`OkHttpPreviewFetcher`) binds every socket to the validated Internet network and enforces
 * the byte caps, the redirect policy and the private-address guard; everything above it runs against a fake.
 */
interface PreviewFetcher {
    /** The page at [url] (already normalized by [LinkPreviewPolicy]), as bytes the parser can decode. */
    suspend fun fetchPage(url: String): PageFetch

    /** The picture at [url] (already normalized), as raw image bytes for `PreviewImage.shrink`. */
    suspend fun fetchImage(url: String): ImageFetch
}

/** What fetching a page produced. Only [Html] carries a body; every other shape says why there is none. */
sealed interface PageFetch {
    /** [bytes] is the capped body, [contentType] the response header, [finalUrl] where the redirects ended. */
    class Html(
        val bytes: ByteArray,
        val contentType: String?,
        val finalUrl: String,
    ) : PageFetch

    /** No validated Internet route at call time. */
    data object Offline : PageFetch

    /** The response was not an HTML document. */
    data object NotHtml : PageFetch

    /** More hops than the fetcher follows, or a hop the policy refused. */
    data object TooManyRedirects : PageFetch

    /** A definite failure: a non-success status, a refused address, a TLS or protocol error. */
    data class Failed(
        val reason: String,
    ) : PageFetch

    /** The attempt was cut short — cancelled, or the network vanished mid-call — and says nothing about the page. */
    data object Transient : PageFetch
}

/** What fetching a picture produced. */
sealed interface ImageFetch {
    class Image(
        val bytes: ByteArray,
        val contentType: String?,
    ) : ImageFetch

    data object Offline : ImageFetch

    /** Not an image the card renders: a wrong `Content-Type`, an SVG, or bytes that sniff as something else. */
    data object NotImage : ImageFetch

    /** Past the download cap, by header or by count. */
    data object TooLarge : ImageFetch

    data class Failed(
        val reason: String,
    ) : ImageFetch

    data object Transient : ImageFetch
}
