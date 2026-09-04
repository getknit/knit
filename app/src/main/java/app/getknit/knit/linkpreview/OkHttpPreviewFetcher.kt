package app.getknit.knit.linkpreview

import android.util.Log
import app.getknit.knit.data.FileTypes
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The one place link previews speak OkHttp (`rules/mesh.md`): two bounded GETs toward addresses somebody
 * else typed, with every guard that makes that safe applied here rather than trusted from above.
 *
 * - **Bound to the validated network.** [clientFor] returns a client whose sockets and lookups belong to the
 *   default Internet network the gate vouched for, or null when there is none; so a fetch can never ride the
 *   Wi-Fi Aware link, and a phone that is only on the mesh reads as [PageFetch.Offline] without a socket.
 * - **No private addresses, on any hop.** The bound client resolves through [BoundDns], which refuses a whole
 *   hostname when any address it resolves to fails [PublicAddressPolicy]; OkHttp connects only to what the
 *   resolver returned, so the check and the connection cannot diverge, and every redirect re-resolves.
 * - **Redirects are walked by hand**, at most [MAX_PAGE_HOPS] / [MAX_IMAGE_HOPS], and each `Location` is run
 *   through [hopPolicy] (`LinkPreviewPolicy.normalize` in production), which is what refuses a hop to a
 *   non-standard port, a literal, or a scheme that is not https.
 * - **Bodies are read through a cap**, never `bytes()`: transparent gzip is kept and the *decoded* bytes are
 *   counted, so a compressed bomb costs at most the cap. A page over [MAX_HTML_BYTES] is simply cut (its tags
 *   are in the head); a picture over [MAX_IMAGE_BYTES] is refused.
 * - **Nothing identifying goes out.** No cookies (OkHttp's default jar keeps none, and none is ever set), no
 *   `Referer`, no `Accept-Language`, and the `User-Agent` is [USER_AGENT] — the one Signal sends, because sites
 *   serve their Open Graph tags to it, where a browser string gets a script shell and an honest app name gets a
 *   bot challenge and tells every site the user runs Knit. Logs name the host only.
 */
class OkHttpPreviewFetcher(
    private val clientFor: () -> OkHttpClient?,
    private val hopPolicy: (String) -> String? = LinkPreviewPolicy::normalize,
) : PreviewFetcher {
    override suspend fun fetchPage(url: String): PageFetch {
        val client = clientFor() ?: return PageFetch.Offline
        return withContext(Dispatchers.IO) {
            when (val hop = walk(client, url, ACCEPT_PAGE, MAX_PAGE_HOPS, MAX_HTML_BYTES, hardCap = false, ::acceptsPage)) {
                is Walk.Done -> PageFetch.Html(hop.bytes, hop.contentType, hop.url)
                is Walk.Failed -> PageFetch.Failed(hop.reason)
                Walk.TooManyHops -> PageFetch.TooManyRedirects
                Walk.WrongType -> PageFetch.NotHtml
                Walk.TooLarge -> PageFetch.NotHtml
                Walk.Transient -> PageFetch.Transient
            }
        }
    }

    override suspend fun fetchImage(url: String): ImageFetch {
        val client = clientFor() ?: return ImageFetch.Offline
        return withContext(Dispatchers.IO) {
            when (val hop = walk(client, url, ACCEPT_IMAGE, MAX_IMAGE_HOPS, MAX_IMAGE_BYTES, hardCap = true, ::acceptsImage)) {
                is Walk.Done -> {
                    if (FileTypes.imageMimeOf(hop.bytes) in RENDERED_IMAGE_MIMES) {
                        ImageFetch.Image(hop.bytes, hop.contentType)
                    } else {
                        ImageFetch.NotImage
                    }
                }

                is Walk.Failed -> {
                    ImageFetch.Failed(hop.reason)
                }

                Walk.TooManyHops -> {
                    ImageFetch.Failed("redirects")
                }

                Walk.WrongType -> {
                    ImageFetch.NotImage
                }

                Walk.TooLarge -> {
                    ImageFetch.TooLarge
                }

                Walk.Transient -> {
                    ImageFetch.Transient
                }
            }
        }
    }

    private sealed interface Walk {
        class Done(
            val bytes: ByteArray,
            val contentType: String?,
            val url: String,
        ) : Walk

        class Failed(
            val reason: String,
        ) : Walk

        data object TooManyHops : Walk

        data object WrongType : Walk

        data object TooLarge : Walk

        data object Transient : Walk
    }

    /**
     * Follows redirects from [start] under [maxHops] and reads the final body under [cap]. Never throws.
     *
     * One exit per outcome of a hop reads better than a folded result, and the hop loop is the whole policy;
     * splitting it would scatter the guards this class exists to keep in one place — hence the suppressions.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun walk(
        client: OkHttpClient,
        start: String,
        accept: String,
        maxHops: Int,
        cap: Long,
        hardCap: Boolean,
        accepts: (String?) -> Boolean,
    ): Walk {
        var url = start
        var hops = 0
        while (true) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header(USER_AGENT_HEADER, USER_AGENT)
                    .header(ACCEPT_HEADER, accept)
                    .build()
            val response =
                try {
                    client.newCall(request).execute()
                } catch (e: UnknownHostException) {
                    Log.i(TAG, "refused or unresolvable host ${LinkPreviewBlob.hostOf(url)}: ${e.message}")
                    return Walk.Failed("dns")
                } catch (e: InterruptedIOException) {
                    // Both OkHttp timeouts (call and socket) arrive as this; a site that will not answer in time is a
                    // definite failure for the draft, not a reason to wait longer.
                    Log.i(TAG, "fetch timed out for ${LinkPreviewBlob.hostOf(url)}: ${e.javaClass.simpleName}")
                    return Walk.Failed("timeout")
                } catch (e: IOException) {
                    if (e.message == CANCELED) return Walk.Transient
                    Log.i(TAG, "fetch failed for ${LinkPreviewBlob.hostOf(url)}: ${e.javaClass.simpleName}")
                    return Walk.Failed(e.javaClass.simpleName)
                }
            response.use { r ->
                if (r.code in REDIRECT_CODES) {
                    if (++hops > maxHops) return Walk.TooManyHops
                    val location = r.header(LOCATION_HEADER) ?: return Walk.Failed("redirect without location")
                    val resolved =
                        r.request.url
                            .resolve(location)
                            ?.toString() ?: return Walk.Failed("bad redirect")
                    url = hopPolicy(resolved) ?: return Walk.TooManyHops
                    return@use
                }
                if (!r.isSuccessful) return Walk.Failed("http ${r.code}")
                val contentType = r.body.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }
                if (!accepts(contentType)) return Walk.WrongType
                if (hardCap && r.body.contentLength() > cap) return Walk.TooLarge
                val bytes = readCapped(r, cap) ?: return Walk.Failed("read")
                if (bytes.size > cap) {
                    if (hardCap) return Walk.TooLarge
                    return Walk.Done(bytes.copyOf(cap.toInt()), contentType, url)
                }
                return Walk.Done(bytes, contentType, url)
            }
        }
    }

    /**
     * Up to [cap] + 1 decoded bytes of [response]'s body — one past the cap, so the caller can tell a body that
     * fits from one that was cut — read through a bounded loop so a compressed bomb inflates no further than this.
     */
    private fun readCapped(
        response: Response,
        cap: Long,
    ): ByteArray? =
        try {
            val source = response.body.source()
            val buffer = Buffer()
            var remaining = cap + 1
            while (remaining > 0) {
                val read = source.read(buffer, remaining)
                if (read == -1L) break
                remaining -= read
            }
            buffer.readByteArray()
        } catch (e: IOException) {
            Log.i(TAG, "body read failed: ${e.javaClass.simpleName}")
            null
        }

    private fun acceptsPage(contentType: String?): Boolean = contentType in PAGE_TYPES

    private fun acceptsImage(contentType: String?): Boolean = contentType != null && contentType.startsWith("image/") && contentType != SVG

    /**
     * A resolver that answers only with globally routable addresses, and refuses the whole name when any of its
     * addresses is not: mixed public/private records are how a rebinding attack looks from here.
     */
    class BoundDns(
        private val resolve: (String) -> List<InetAddress>,
        private val isPublic: (ByteArray) -> Boolean = PublicAddressPolicy::isPublic,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = resolve(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it.address) }) {
                throw UnknownHostException("$hostname resolves to no public address")
            }
            return addresses
        }
    }

    companion object {
        const val TAG = "LinkPreview"

        /** Signal's choice, for the reasons in the class doc. Fixed, versionless: one less fingerprint bit. */
        const val USER_AGENT = "WhatsApp/2"

        const val MAX_HTML_BYTES = OpenGraphParser.MAX_HTML_BYTES.toLong()
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_PAGE_HOPS = 5
        const val MAX_IMAGE_HOPS = 3
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 10L
        const val CALL_TIMEOUT_S = 15L

        /** OkHttp's message for a call cancelled from outside — the one IOException that says nothing about the site. */
        private const val CANCELED = "Canceled"
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val ACCEPT_HEADER = "Accept"
        private const val LOCATION_HEADER = "Location"
        private const val ACCEPT_PAGE = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1"
        private const val ACCEPT_IMAGE = "image/webp,image/jpeg,image/png,image/gif;q=0.8,*/*;q=0.1"
        private const val SVG = "image/svg+xml"
        private val PAGE_TYPES = setOf("text/html", "application/xhtml+xml")
        private val RENDERED_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * The client every fetch starts from: short timeouts, no automatic redirects (they are walked by hand so
         * each hop can be policed), no retries, no cache, no cookies. A network-bound client is derived from it
         * with [bound].
         */
        fun baseClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()

        /**
         * [base] bound to [network]: its sockets come from the network's own factory and its lookups from the
         * network's resolver, filtered by [BoundDns]. OkHttp keys pooled connections by address — socket factory
         * and resolver included — so a connection made for one network is never reused for another. The deferred
         * Tor toggle would set its proxy here, and here only (note a SOCKS proxy bypasses [Dns], which is why the
         * URL-level literal refusal in `LinkPreviewPolicy` must stay).
         */
        fun bound(
            base: OkHttpClient,
            network: android.net.Network,
        ): OkHttpClient =
            base
                .newBuilder()
                .socketFactory(network.socketFactory)
                .dns(BoundDns(resolve = { host -> network.getAllByName(host).toList() }))
                .build()
    }
}
