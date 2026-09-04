package app.getknit.knit.linkpreview

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * The fetcher on a real socket: the caps, the redirect walk and the header hygiene are only observable there.
 * The client is a plain one (no network binding — there is no `Network` on the JVM) and the hop policy is the
 * identity, since the local server lives on a loopback port the production policy would refuse.
 */
class OkHttpPreviewFetcherTest {
    private val server = MockWebServer()
    private var refusedHop: String? = null
    private val fetcher =
        OkHttpPreviewFetcher(
            // The production base (no automatic redirects, no retries) with shorter timeouts; only the network binding is missing.
            clientFor = {
                OkHttpPreviewFetcher
                    .baseClient()
                    .newBuilder()
                    .readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build()
            },
            hopPolicy = { url -> url.takeIf { it != refusedHop } },
        )

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun page(url: String) = runBlocking { fetcher.fetchPage(url) }

    private fun image(url: String) = runBlocking { fetcher.fetchImage(url) }

    private fun html(
        body: String,
        type: String = "text/html; charset=utf-8",
    ) = MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", type)
        .body(body)
        .build()

    @Test
    fun aPageIsFetchedWithNothingIdentifyingInTheRequest() {
        server.enqueue(html("<title>Hi</title>"))
        val result = page(server.url("/page").toString()) as PageFetch.Html
        assertEquals("<title>Hi</title>", String(result.bytes))
        assertEquals("text/html", result.contentType)
        assertEquals(server.url("/page").toString(), result.finalUrl)
        val request = server.takeRequest()
        assertEquals(OkHttpPreviewFetcher.USER_AGENT, request.headers["User-Agent"])
        assertNull(request.headers["Referer"])
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Accept-Language"])
        assertTrue(requireNotNull(request.headers["Accept"]).startsWith("text/html"))
    }

    @Test
    fun aResponseThatIsNotHtmlIsNotAPageAndAServerErrorIsAFailure() {
        server.enqueue(html("{}", type = "application/json"))
        assertEquals(PageFetch.NotHtml, page(server.url("/api").toString()))
        server.enqueue(
            MockResponse
                .Builder()
                .code(500)
                .body("boom")
                .build(),
        )
        assertEquals(PageFetch.Failed("http 500"), page(server.url("/err").toString()))
    }

    @Test
    fun redirectsAreWalkedByHandUpToTheCapAndCookiesNeverFollow() {
        repeat(OkHttpPreviewFetcher.MAX_PAGE_HOPS) { i ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "/hop${i + 1}")
                    .addHeader("Set-Cookie", "sid=$i; Path=/")
                    .build(),
            )
        }
        server.enqueue(html("<title>End</title>"))
        val result = page(server.url("/hop0").toString()) as PageFetch.Html
        assertEquals("<title>End</title>", String(result.bytes))
        assertEquals(server.url("/hop${OkHttpPreviewFetcher.MAX_PAGE_HOPS}").toString(), result.finalUrl)
        repeat(OkHttpPreviewFetcher.MAX_PAGE_HOPS + 1) { assertNull(server.takeRequest().headers["Cookie"]) }

        repeat(OkHttpPreviewFetcher.MAX_PAGE_HOPS + 1) { i ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(301)
                    .addHeader("Location", "/more${i + 1}")
                    .build(),
            )
        }
        server.enqueue(html("<title>Never</title>"))
        assertEquals(PageFetch.TooManyRedirects, page(server.url("/more0").toString()))
    }

    @Test
    fun aHopThePolicyRefusesEndsTheWalk() {
        refusedHop = server.url("/private").toString()
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", "/private")
                .build(),
        )
        server.enqueue(html("<title>Never</title>"))
        assertEquals(PageFetch.TooManyRedirects, page(server.url("/start").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aLongPageIsCutAtTheCapWhetherPlainChunkedOrGzipped() {
        val cap = OkHttpPreviewFetcher.MAX_HTML_BYTES.toInt()
        val big = "<title>x</title>" + "a".repeat(cap + 100_000)
        server.enqueue(html(big))
        assertEquals(cap, (page(server.url("/plain").toString()) as PageFetch.Html).bytes.size)

        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .chunkedBody(big, 8_192)
                .build(),
        )
        assertEquals(cap, (page(server.url("/chunked").toString()) as PageFetch.Html).bytes.size)

        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(big.toByteArray()) } }.toByteArray()
        assertTrue("a run of one byte must compress far below the cap", gzipped.size < cap / 10)
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .addHeader("Content-Encoding", "gzip")
                .body(Buffer().write(gzipped))
                .build(),
        )
        val decoded = (page(server.url("/gzip").toString()) as PageFetch.Html).bytes
        assertEquals("the cap counts decoded bytes, not wire bytes", cap, decoded.size)
        assertTrue(String(decoded).startsWith("<title>x</title>"))
    }

    @Test
    fun anImageOverTheCapIsRefusedByHeaderBeforeReadingAndByCountWhenThereIsNoHeader() {
        val cap = OkHttpPreviewFetcher.MAX_IMAGE_BYTES.toInt()
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("Content-Length", (cap + 1).toString())
                .body(Buffer().write(jpeg(cap + 1)))
                .build(),
        )
        assertEquals(ImageFetch.TooLarge, image(server.url("/big").toString()))
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .chunkedBody(Buffer().write(jpeg(cap + 1)), 65_536)
                .build(),
        )
        assertEquals(ImageFetch.TooLarge, image(server.url("/bigchunked").toString()))
    }

    @Test
    fun aPictureMustBeATypeTheCardRendersAndMustSniffAsOne() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "image/svg+xml")
                .body("<svg/>")
                .build(),
        )
        assertEquals(ImageFetch.NotImage, image(server.url("/svg").toString()))
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body("<html>not a png</html>")
                .build(),
        )
        assertEquals(ImageFetch.NotImage, image(server.url("/fake").toString()))
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<html/>")
                .build(),
        )
        assertEquals(ImageFetch.NotImage, image(server.url("/page").toString()))
        val bytes = jpeg(512)
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .body(Buffer().write(bytes))
                .build(),
        )
        val fetched = image(server.url("/real").toString()) as ImageFetch.Image
        assertArrayEquals(bytes, fetched.bytes)
        assertEquals("image/jpeg", fetched.contentType)
        assertTrue(requireNotNull(server.takeRequest().headers["Accept"]).startsWith("image/"))
    }

    @Test
    fun withoutABoundClientEverythingIsOffline() {
        val offline = OkHttpPreviewFetcher(clientFor = { null })
        assertEquals(PageFetch.Offline, runBlocking { offline.fetchPage("https://example.com/") })
        assertEquals(ImageFetch.Offline, runBlocking { offline.fetchImage("https://example.com/p.jpg") })
    }

    @Test
    fun theBoundResolverRefusesAWholeNameOnOnePrivateAddress() {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("10.0.0.1")
        val dns =
            OkHttpPreviewFetcher.BoundDns(resolve = { host ->
                if (host ==
                    "mixed.example"
                ) {
                    listOf(public, private)
                } else {
                    listOf(public)
                }
            })
        assertEquals(listOf(public), dns.lookup("clean.example"))
        assertTrue(runCatching { dns.lookup("mixed.example") }.exceptionOrNull() is UnknownHostException)
        val empty = OkHttpPreviewFetcher.BoundDns(resolve = { emptyList() })
        assertTrue(runCatching { empty.lookup("nowhere.example") }.exceptionOrNull() is UnknownHostException)
    }

    private companion object {
        /** [size] bytes that sniff as a JPEG (the SOI marker) — enough for the type check, never decoded here. */
        fun jpeg(size: Int): ByteArray =
            ByteArray(size).also {
                it[0] = 0xFF.toByte()
                it[1] = 0xD8.toByte()
                it[2] = 0xFF.toByte()
            }
    }
}
