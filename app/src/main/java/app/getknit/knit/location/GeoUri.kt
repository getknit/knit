package app.getknit.knit.location

import java.util.Locale
import kotlin.math.ceil

/**
 * A position as a message carries it: WGS-84 degrees, plus the sender's stated accuracy radius in metres
 * when the fix had one. Value-equal and byte-free, so a row that carries one stays cheap to compare.
 */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val accuracyM: Int? = null,
)

/**
 * The text form a shared location takes inside a message body: an RFC 5870 `geo:` URI —
 * `geo:37.421998,-122.084000;u=12` — the same string a maps app accepts and a person can read.
 *
 * It is carried as ordinary body text on purpose. No wire field is spent, no capability bit gates it, and no
 * blob has to be pulled after the frame: the position arrives *with* the message on every plane, including
 * LoRa, and a build that predates this file shows a legible line instead of a broken attachment. A recipient
 * draws the card only when a token parses under the strict grammar below; anything else is plain text.
 *
 * [format] writes with [Locale.ROOT] — a German locale would otherwise emit `37,421998` and the URI would be
 * junk on every other phone — and clamps into range. [parse] accepts latitude, longitude, an optional
 * altitude it ignores, an optional `;crs=wgs84`, an optional `;u=<metres>`, and tolerates the `?q=` / `?z=`
 * query Android's own maps intents append; a value out of range, a non-finite number, or any other parameter
 * refuses the whole token. Everything here is pure Kotlin and JVM-tested.
 */
object GeoUri {
    /** The stand-in a preview surface shows for a position, mirrored by the notification path's literal. */
    const val LABEL = "📍 Location"

    /**
     * The bytes a formatted position costs beside the body — ASCII, so bytes == chars — reserved from the
     * LoRa body hint while one is staged, before the fix (and so the exact length) is known.
     */
    const val RESERVE_BYTES = 40

    /** Longest token [parse] looks at; the grammar caps well under it, and a longer one is never ours. */
    const val MAX_TOKEN_LENGTH = 96

    private const val LAT_MAX = 90.0
    private const val LON_MAX = 180.0

    // Latitude, longitude, an optional altitude, optional crs, optional uncertainty, optional Android query.
    private val GRAMMAR =
        Regex(
            """geo:(-?\d{1,2}(?:\.\d{1,12})?),(-?\d{1,3}(?:\.\d{1,12})?)""" +
                """(?:,-?\d+(?:\.\d+)?)?(?:;crs=wgs84)?(?:;u=(\d+(?:\.\d+)?))?(?:\?\S*)?""",
            RegexOption.IGNORE_CASE,
        )

    /** Punctuation that trails a token in prose ("meet me at geo:1,2.") and is not part of it. */
    private const val TRAILING_PUNCT = ".,;:!?\"'”’»)]}"

    /** Punctuation that opens on a token ("(geo:1,2)") and is not part of it either. */
    private const val LEADING_PUNCT = "(\"'“‘«[{"

    private val WHITESPACE_RUN = Regex("\\s+")
    private val BLANK_RUN = Regex("[ \\t]{2,}")

    /** The token for [point], clamped into range; `u` rides only when the fix carried an accuracy. */
    fun format(point: GeoPoint): String {
        val core =
            String.format(
                Locale.ROOT,
                "geo:%.6f,%.6f",
                point.lat.coerceIn(-LAT_MAX, LAT_MAX),
                point.lon.coerceIn(-LON_MAX, LON_MAX),
            )
        val accuracy = point.accuracyM?.takeIf { it > 0 }
        return if (accuracy == null) core else "$core;u=$accuracy"
    }

    /** The plain `lat, lon` a person copies into a maps app's search box — never the URI, which those refuse. */
    fun coordinates(point: GeoPoint): String = String.format(Locale.ROOT, "%.6f, %.6f", point.lat, point.lon)

    /** `lat,lon` as an Android maps intent wants it, for `geo:lat,lon?q=lat,lon(label)`. */
    fun mapsQuery(point: GeoPoint): String = String.format(Locale.ROOT, "%.6f,%.6f", point.lat, point.lon)

    /** [text] as exactly one token, or null when it is anything else. Never throws. */
    fun parse(text: String): GeoPoint? {
        if (text.length > MAX_TOKEN_LENGTH) return null
        val match = GRAMMAR.matchEntire(text) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -LAT_MAX..LAT_MAX || lon !in -LON_MAX..LON_MAX) return null
        val accuracy =
            match.groupValues[3].takeIf { it.isNotEmpty() }?.let { raw ->
                val metres = raw.toDoubleOrNull() ?: return null
                if (!metres.isFinite()) return null
                ceil(metres).toInt().coerceAtLeast(1)
            }
        return GeoPoint(lat, lon, accuracy)
    }

    /** The first position in [body], or null when none of its tokens parses. */
    fun find(body: String): GeoPoint? = scan(body).firstOrNull()?.point

    /** Whether [body] carries a position at all — the cheap test the row fold asks first. */
    fun contains(body: String): Boolean = find(body) != null

    /** Where each position in [body] sits, for text that must come through a rewrite untouched (a translation). */
    fun ranges(body: String): List<IntRange> = scan(body).map { it.range }

    /**
     * [body] with every position removed, for the bubble, which draws the card in its place: a line that
     * held only the token goes with it, blank runs it left behind collapse, and the ends are trimmed.
     */
    fun strip(body: String): String {
        if (!body.contains("geo:", ignoreCase = true)) return body
        val kept =
            body.lines().mapNotNull { line ->
                val matches = scan(line)
                if (matches.isEmpty()) return@mapNotNull line
                val rest = remove(line, matches).replace(BLANK_RUN, " ").trim()
                rest.takeIf { it.isNotEmpty() }
            }
        return kept.joinToString("\n").trim()
    }

    /**
     * [body] with every position replaced by [label], flattened to one line — the chat list, the request
     * list, the details screen, a notification and a reply quote all name a position rather than print it.
     */
    fun describe(
        body: String,
        label: String,
    ): String {
        if (!body.contains("geo:", ignoreCase = true)) return body
        val out = StringBuilder()
        var cursor = 0
        for (match in scan(body)) {
            out.append(body, cursor, match.range.first).append(label)
            cursor = match.range.last + 1
        }
        out.append(body, cursor, body.length)
        return out.toString().replace(WHITESPACE_RUN, " ").trim()
    }

    private class Found(
        val range: IntRange,
        val point: GeoPoint,
    )

    /** Every whitespace-bounded token of [text] that parses, with trailing prose punctuation left outside it. */
    private fun scan(text: String): List<Found> {
        if (!text.contains("geo:", ignoreCase = true)) return emptyList()
        val found = ArrayList<Found>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            var tokenStart = i
            while (tokenStart < end && text[tokenStart] in LEADING_PUNCT) tokenStart++
            var tokenEnd = end
            while (tokenEnd > tokenStart && text[tokenEnd - 1] in TRAILING_PUNCT) tokenEnd--
            val token = text.substring(tokenStart, tokenEnd)
            if (token.startsWith("geo:", ignoreCase = true)) {
                parse(token)?.let { found += Found(tokenStart until tokenEnd, it) }
            }
            i = end
        }
        return found
    }

    private fun remove(
        text: String,
        matches: List<Found>,
    ): String {
        val out = StringBuilder()
        var cursor = 0
        for (match in matches) {
            out.append(text, cursor, match.range.first)
            cursor = match.range.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }
}
