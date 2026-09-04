package app.getknit.knit.linkpreview

import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.LinkText
import java.net.URI
import java.nio.charset.Charset

/** What a page says about itself, after precedence and hygiene: [title] is never blank. */
data class PageMeta(
    val title: String,
    val description: String?,
    val imageUrl: String?,
)

/**
 * A tolerant scanner for the Open Graph / Twitter-card / `<title>` metadata of an HTML prefix. Not a DOM:
 * real pages are malformed in every way a parser can be strict about, and the only tags that matter are
 * `<meta>` and `<title>`, so it tokenizes just those, quote-aware, and takes the first of each key it sees.
 *
 * What it refuses to be fooled by is deliberate and small: comments, `<script>`, `<style>` and inline
 * `<svg>` are blanked first (fake tags inside a script, a `<title>` inside a drawing), every text is cleaned by
 * [LinkText] (bidi overrides, controls, length), and a picture URL is kept only when it resolves — against the
 * page's **final** URL, so a relative path survives a redirect — to a plain `https` address. It reads the whole
 * prefix it is given rather than stopping at `</head>`: enough sites put their tags in the body.
 *
 * Pure Kotlin, no Android — JVM-tested in `OpenGraphParserTest` against inline fixtures.
 */
object OpenGraphParser {
    /** How much of a page the fetcher hands over: the tags live in the head, which is almost always far smaller. */
    const val MAX_HTML_BYTES = 512 * 1024

    /** A single attribute value longer than this is cut; no real `content` comes close. */
    const val MAX_ATTRIBUTE_CHARS = 4 * 1024

    /** How far into the bytes a `<meta charset>` is looked for, as ASCII, before the document is decoded. */
    const val CHARSET_SNIFF_BYTES = 2 * 1024

    /** How many `og:image` candidates are tried before the card goes without a picture. */
    const val MAX_IMAGE_CANDIDATES = 3

    /**
     * The page's metadata, or null when it yields no usable title (a card with only a host is noise).
     * [finalUrl] is where the fetch ended after redirects; relative picture URLs resolve against it.
     */
    fun parse(
        html: String,
        finalUrl: String,
    ): PageMeta? {
        val visible = BLANKED.replace(html, " ")
        val metas = MetaSet()
        for (match in META_OPEN.findAll(visible)) {
            val attributes = readAttributes(visible, match.range.last + 1)
            if (attributes != null) {
                metas.add(attributes)
                if (metas.complete()) break
            }
        }
        val title =
            firstText(metas.first("og:title"), metas.first("twitter:title"), titleTag(visible), max = LinkPreviewBlob.TITLE_MAX)
                ?: return null
        val description =
            firstText(
                metas.first("og:description"),
                metas.first("twitter:description"),
                metas.first("description"),
                max = LinkPreviewBlob.DESCRIPTION_MAX,
            )?.takeIf { it != title }
        val image =
            metas
                .images()
                .asSequence()
                .take(MAX_IMAGE_CANDIDATES)
                .firstNotNullOfOrNull { resolveImage(it, finalUrl) }
        return PageMeta(title, description, image)
    }

    /**
     * The charset [bytes] should be decoded with: a byte-order mark first, then the `Content-Type` header's
     * parameter, then a `<meta charset>` / `http-equiv` found by an ASCII scan of the first
     * [CHARSET_SNIFF_BYTES], then UTF-8. A name the JVM does not know falls through to the next source.
     */
    fun charsetOf(
        bytes: ByteArray,
        contentType: String?,
    ): Charset =
        bomCharset(bytes)
            ?: contentType?.let { CHARSET_PARAM.find(it) }?.let { charsetOrNull(it.groupValues[1]) }
            ?: metaCharset(bytes)
            ?: Charsets.UTF_8

    /** [bytes] as text under [charsetOf], the byte-order mark dropped and malformed input replaced, never thrown. */
    fun decode(
        bytes: ByteArray,
        contentType: String?,
    ): String {
        val charset = charsetOf(bytes, contentType)
        val skip = bomLength(bytes)
        return String(bytes, skip, bytes.size - skip, charset)
    }

    /** HTML character references in [text] decoded: the common named ones and every numeric one; unknown names stay literal. */
    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) -> codePointOrNull(name.substring(2), radix = 16)
                name.startsWith("#") -> codePointOrNull(name.substring(1), radix = 10)
                else -> NAMED_ENTITIES[name]
            } ?: m.value
        }
    }

    /** The first of [candidates] that has text once cleaned, or null. */
    private fun firstText(
        vararg candidates: String?,
        max: Int,
    ): String? =
        candidates
            .asSequence()
            .filterNotNull()
            .map { LinkText.clean(decodeEntities(it), max) }
            .firstOrNull { it.isNotEmpty() }

    private fun titleTag(html: String): String? = TITLE.find(html)?.groupValues?.get(1)

    /** [raw] resolved against [base] and kept only as a plain `https` address (never `data:`, never `http`). */
    private fun resolveImage(
        raw: String,
        base: String,
    ): String? {
        val candidate = decodeEntities(raw).trim()
        if (candidate.isEmpty() || candidate.length > LinkPreviewBlob.MAX_URL_LENGTH) return null
        val resolved = runCatching { URI(base).resolve(candidate).toString() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("https://", ignoreCase = true) && LinkPreviewBlob.isSafeHttpUrl(it) }
    }

    /**
     * The attributes of the tag whose `<meta` ends just before [from], or null when the tag never closes before
     * the next `<` or the end of the text (a tag cut at the prefix boundary is dropped, never guessed at).
     */
    private fun readAttributes(
        html: String,
        from: Int,
    ): Map<String, String>? {
        val attributes = LinkedHashMap<String, String>()
        var i = from
        while (i < html.length) {
            val c = html[i]
            when {
                c == '>' -> {
                    return attributes
                }

                c == '<' -> {
                    return null
                }

                c == '/' || c.isWhitespace() -> {
                    i++
                }

                else -> {
                    val m = ATTRIBUTE.matchAt(html, i)
                    if (m == null) {
                        i++
                    } else {
                        val name = m.groupValues[1].lowercase()
                        val value = (m.groups[2] ?: m.groups[3] ?: m.groups[4])?.value.orEmpty().take(MAX_ATTRIBUTE_CHARS)
                        attributes.putIfAbsent(name, value)
                        i = m.range.last + 1
                    }
                }
            }
        }
        return null
    }

    private fun bomCharset(bytes: ByteArray): Charset? =
        when {
            bytes.size >= UTF8_BOM.size && bytes.copyOf(UTF8_BOM.size).contentEquals(UTF8_BOM) -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            else -> null
        }

    private fun bomLength(bytes: ByteArray): Int =
        when (bomCharset(bytes)) {
            Charsets.UTF_8 -> UTF8_BOM.size
            Charsets.UTF_16BE, Charsets.UTF_16LE -> 2
            else -> 0
        }

    private fun metaCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), Charsets.ISO_8859_1)
        return META_CHARSET.find(head)?.let { charsetOrNull(it.groupValues[1]) }
    }

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name.trim()) }.getOrNull()

    private fun codePointOrNull(
        digits: String,
        radix: Int,
    ): String? {
        val cp = digits.toIntOrNull(radix) ?: return null
        if (cp !in 1..MAX_CODE_POINT || cp in SURROGATES) return null
        return String(Character.toChars(cp))
    }

    /** The metas seen so far, first occurrence per key; picture candidates keep their order and precedence. */
    private class MetaSet {
        private val first = HashMap<String, String>()
        private val secureImages = ArrayList<String>()
        private val images = ArrayList<String>()
        private val twitterImages = ArrayList<String>()

        fun add(attributes: Map<String, String>) {
            val key = (attributes["property"] ?: attributes["name"])?.trim()?.lowercase() ?: return
            val content = attributes["content"] ?: return
            when (key) {
                "og:image:secure_url" -> secureImages += content
                "og:image", "og:image:url" -> images += content
                "twitter:image", "twitter:image:src" -> twitterImages += content
                else -> first.putIfAbsent(key, content)
            }
        }

        fun first(key: String): String? = first[key]

        fun images(): List<String> = secureImages + images + twitterImages

        /** Once the three top-precedence keys are all present nothing later can change the outcome. */
        fun complete(): Boolean = "og:title" in first && "og:description" in first && (secureImages.isNotEmpty() || images.isNotEmpty())
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATES = 0xD800..0xDFFF

    /** Blocks whose insides must never be read as tags: comments, scripts, styles, inline drawings. */
    private val BLANKED =
        Regex(
            """<!--.*?-->|<script\b[^>]*>.*?</script\s*>|<style\b[^>]*>.*?</style\s*>|<svg\b[^>]*>.*?</svg\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val META_OPEN = Regex("""<meta\b""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** One attribute: a name, then optionally `=` and a double-quoted, single-quoted or bare value. */
    private val ATTRIBUTE = Regex("""([A-Za-z_:][-\w:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
    private val CHARSET_PARAM = Regex("""charset\s*=\s*["']?([\w.:-]+)""", RegexOption.IGNORE_CASE)
    private val META_CHARSET = Regex("""<meta\b[^>]*charset\s*=\s*["']?([\w.:-]+)""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""")

    private val NAMED_ENTITIES =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "mdash" to "—",
            "ndash" to "–",
            "hellip" to "…",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "laquo" to "«",
            "raquo" to "»",
            "ldquo" to "“",
            "rdquo" to "”",
            "lsquo" to "‘",
            "rsquo" to "’",
            "bull" to "•",
            "middot" to "·",
            "ensp" to " ",
            "emsp" to " ",
            "thinsp" to " ",
            "shy" to "",
            "euro" to "€",
            "pound" to "£",
            "yen" to "¥",
            "deg" to "°",
            "times" to "×",
        )
}
