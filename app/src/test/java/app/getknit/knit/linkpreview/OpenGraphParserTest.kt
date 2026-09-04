package app.getknit.knit.linkpreview

import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class OpenGraphParserTest {
    private val page = "https://example.com/articles/one"

    private fun parse(html: String) = OpenGraphParser.parse(html, page)

    @Test
    fun openGraphOutranksTwitterWhichOutranksTheTitleTag() {
        val html =
            """
            <html><head><title>Tag title</title>
            <meta name="twitter:title" content="Twitter title">
            <meta property="og:title" content="OG title">
            <meta name="twitter:description" content="Twitter desc">
            <meta property="og:description" content="OG desc">
            <meta name="description" content="Plain desc">
            </head><body></body></html>
            """.trimIndent()
        val meta = requireNotNull(parse(html))
        assertEquals("OG title", meta.title)
        assertEquals("OG desc", meta.description)
        assertEquals("Twitter title", requireNotNull(parse(html.replace("""<meta property="og:title" content="OG title">""", ""))).title)
        val onlyTag = requireNotNull(parse("<title>Tag title</title><meta name=description content='Plain desc'>"))
        assertEquals("Tag title", onlyTag.title)
        assertEquals("Plain desc", onlyTag.description)
    }

    @Test
    fun aPageWithoutATitleYieldsNoCardAndADescriptionEqualToTheTitleIsDropped() {
        assertNull(parse("<html><head><meta property=\"og:description\" content=\"desc only\"></head></html>"))
        assertNull(parse("<title>   </title>"))
        val same = requireNotNull(parse("<meta property='og:title' content='Same'><meta property='og:description' content='Same'>"))
        assertNull(same.description)
    }

    @Test
    fun attributesAreReadInAnyOrderQuoteStyleAndCase() {
        val html =
            """
            <META CONTENT="Reversed" PROPERTY="og:title"/>
            <meta content='single "quoted" desc' property='og:description'>
            <meta property=og:image content=https://cdn.example.com/bare.jpg>
            """.trimIndent()
        val meta = requireNotNull(parse(html))
        assertEquals("Reversed", meta.title)
        assertEquals("single \"quoted\" desc", meta.description)
        assertEquals("https://cdn.example.com/bare.jpg", meta.imageUrl)
    }

    @Test
    fun aGreaterThanInsideAQuotedValueDoesNotEndTheTag() {
        val meta = requireNotNull(parse("""<meta property="og:title" content="a > b > c"><meta property="og:description" content="d">"""))
        assertEquals("a > b > c", meta.title)
        assertEquals("d", meta.description)
    }

    @Test
    fun theFirstOccurrenceOfAKeyWinsAndAnUnclosedTrailingTagIsDropped() {
        val meta =
            requireNotNull(
                parse("""<meta property="og:title" content="First"><meta property="og:title" content="Second"><meta property="og:desc"""),
            )
        assertEquals("First", meta.title)
        assertNull(meta.description)
    }

    @Test
    fun scriptsStylesCommentsAndInlineSvgAreNeverReadAsTags() {
        val html =
            """
            <script>var s = '<meta property="og:title" content="from script">';</script>
            <style>/* <meta property="og:title" content="from style"> */</style>
            <!-- <meta property="og:title" content="from comment"> -->
            <svg><title>drawing title</title></svg>
            <title>Real title</title>
            """.trimIndent()
        assertEquals("Real title", requireNotNull(parse(html)).title)
    }

    @Test
    fun entitiesAreDecodedAndUnknownNamesStayLiteral() {
        val html = """<meta property="og:title" content="Fish &amp; Chips &#39;n&#x27; &quot;more&quot; &nbsp;&mdash; &bogus; &#128512;">"""
        assertEquals("Fish & Chips 'n' \"more\" — &bogus; 😀", requireNotNull(parse(html)).title)
        assertEquals("<>", OpenGraphParser.decodeEntities("&lt;&gt;"))
        assertEquals("&#xD800;&#0;", OpenGraphParser.decodeEntities("&#xD800;&#0;"))
    }

    @Test
    fun textIsCleanedAndCappedLikeTheContainerDoes() {
        val bidi = "‮"
        val html = "<meta property=\"og:title\" content=\"  Read ${bidi}this​ now\t\tplease  \">"
        assertEquals("Read this now please", requireNotNull(parse(html)).title)
        val long = "<meta property=\"og:title\" content=\"${"t".repeat(LinkPreviewBlob.TITLE_MAX + 10)}\">"
        assertEquals(LinkPreviewBlob.TITLE_MAX, requireNotNull(parse(long)).title.length)
    }

    @Test
    fun aPictureResolvesAgainstThePagesFinalUrlAndMustBeHttps() {
        val relative = requireNotNull(parse("""<title>T</title><meta property="og:image" content="../img/pic.jpg">"""))
        assertEquals("https://example.com/img/pic.jpg", relative.imageUrl)
        val protocolRelative = requireNotNull(parse("""<title>T</title><meta property="og:image" content="//cdn.example.com/p.png">"""))
        assertEquals("https://cdn.example.com/p.png", protocolRelative.imageUrl)
        val http = requireNotNull(parse("""<title>T</title><meta property="og:image" content="http://cdn.example.com/p.png">"""))
        assertNull(http.imageUrl)
        val data = requireNotNull(parse("""<title>T</title><meta property="og:image" content="data:image/png;base64,AAAA">"""))
        assertNull(data.imageUrl)
    }

    @Test
    fun theSecureImageIsPreferredThenOpenGraphThenTwitterAndOnlyAFewCandidatesAreTried() {
        val html =
            """
            <title>T</title>
            <meta name="twitter:image" content="https://cdn.example.com/tw.jpg">
            <meta property="og:image" content="http://cdn.example.com/insecure.jpg">
            <meta property="og:image:secure_url" content="https://cdn.example.com/secure.jpg">
            """.trimIndent()
        assertEquals("https://cdn.example.com/secure.jpg", requireNotNull(parse(html)).imageUrl)
        val fallthrough =
            """
            <title>T</title>
            <meta property="og:image" content="http://a.example/1.jpg">
            <meta property="og:image" content="data:x">
            <meta property="og:image" content="ftp://a.example/3.jpg">
            <meta property="og:image" content="https://a.example/4.jpg">
            """.trimIndent()
        assertNull(requireNotNull(parse(fallthrough)).imageUrl)
        val third =
            """
            <title>T</title>
            <meta property="og:image" content="http://a.example/1.jpg">
            <meta property="og:image" content="data:x">
            <meta property="og:image" content="https://a.example/3.jpg">
            """.trimIndent()
        assertEquals("https://a.example/3.jpg", requireNotNull(parse(third)).imageUrl)
    }

    @Test
    fun tagsInTheBodyAndAfterALongPreambleAreStillFound() {
        val preamble = "<div>" + "x".repeat(300 * 1024) + "</div>"
        val html = "<html><head></head><body>$preamble<meta property=\"og:title\" content=\"Late\"></body></html>"
        assertEquals("Late", requireNotNull(parse(html)).title)
    }

    @Test
    fun charsetComesFromTheBomThenTheHeaderThenTheMetaThenUtf8() {
        val utf8 = "<meta charset=\"utf-8\"><title>Grüße</title>".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, OpenGraphParser.charsetOf(utf8, null))
        val latin1 = "<title>Grüße</title>".toByteArray(Charsets.ISO_8859_1)
        assertEquals(Charsets.ISO_8859_1, OpenGraphParser.charsetOf(latin1, "text/html; charset=ISO-8859-1"))
        assertEquals("Grüße", requireNotNull(parse(OpenGraphParser.decode(latin1, "text/html; charset=iso-8859-1"))).title)
        val metaLatin = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\"><title>x</title>".toByteArray()
        assertEquals(Charset.forName("windows-1252"), OpenGraphParser.charsetOf(metaLatin, "text/html"))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<title>Bom</title>".toByteArray()
        assertEquals(Charsets.UTF_8, OpenGraphParser.charsetOf(bom, "text/html; charset=ISO-8859-1"))
        assertEquals("Bom", requireNotNull(parse(OpenGraphParser.decode(bom, null))).title)
        val unknown = "<title>x</title>".toByteArray()
        assertEquals(Charsets.UTF_8, OpenGraphParser.charsetOf(unknown, "text/html; charset=no-such-charset"))
    }

    @Test
    fun malformedBytesDecodeWithReplacementRatherThanThrowing() {
        val broken = "<title>ok</title>".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)
        assertNotNull(OpenGraphParser.decode(broken, "text/html; charset=utf-8"))
        assertEquals("ok", requireNotNull(parse(OpenGraphParser.decode(broken, "text/html; charset=utf-8"))).title)
    }
}
