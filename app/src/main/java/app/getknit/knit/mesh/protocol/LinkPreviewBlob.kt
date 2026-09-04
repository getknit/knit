@file:OptIn(ExperimentalSerializationApi::class) // Cbor + @ByteString are experimental kotlinx APIs

package app.getknit.knit.mesh.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import java.net.URI

/**
 * The container a link-preview attachment blob holds: the card the **sender** fetched for a link in its own
 * message — the page's title, description and a small picture — so that everyone the message reaches can show
 * it without ever contacting the site themselves. It rides the photo path unchanged: a content-addressed blob
 * referenced by the frame's attachment fields, typed by [MIME] (cleartext in the Nearby room, sealed with the
 * key for a DM or group), pulled, carried and relayed like any image. No wire field is spent on it.
 *
 * Two rules from `docs/WIRE_COMPAT.md` shape the layout. It is additive-only — every field after [url] is
 * nullable or defaulted, so a future field is dropped by an old decoder and elided by a new encoder — and
 * [v] is **required and always emitted**: a version that is elided while default cannot gate (the scheme-v3
 * lesson), so a decoder refuses a layout newer than [MAX_SUPPORTED] rather than mis-reading it. A new *form*
 * would mint a new MIME string, the way a new fast-frame tag does.
 *
 * [url] is the link as it stands in the message body (normalized by `LinkPreviewPolicy`), never the page's own
 * `og:url`: a recipient shows a card only when it can find that link in the body, so a card can never be
 * attached to a link it does not describe.
 *
 * Everything in here is sender-supplied text a recipient will draw and, for [url], **open**: it is normalized
 * at the decode boundary ([decodeOrNull] → [normalized]) the way a file's name is (`AttachmentName`), never
 * downstream where one missed call site would be the whole gap. The one refusal is a [url] that is not a
 * plain http(s) address — a `javascript:` or `intent:` link must never reach `openUrl`.
 */
@Serializable
class LinkPreviewBlob(
    val v: Int,
    val url: String,
    val title: String = "",
    val description: String? = null,
    @ByteString val image: ByteArray? = null,
    val imageMime: String? = null,
) {
    fun encode(): ByteArray = WireCodec.encodePayload(this)

    /** The one string both text gates classify: title and description, one per line, blanks dropped. */
    fun moderationText(): String =
        listOfNotNull(title.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() }).joinToString("\n")

    /** The value-equal, byte-free form the UI holds. A blank title reads as the host. */
    fun toCard(): LinkCard {
        val host = hostOf(url)
        return LinkCard(
            url = url,
            host = host,
            title = title.ifBlank { host },
            description = description,
            hasImage = image != null,
        )
    }

    /**
     * This card with everything that could lie about it removed: control and format characters (the bidi
     * overrides live in the latter), runs of whitespace collapsed, the two texts capped, and an image kept only
     * when it is a type the card renders and within [IMAGE_MAX_BYTES]. Null — the whole card refused — only
     * for a [url] that fails [isSafeHttpUrl].
     */
    internal fun normalized(): LinkPreviewBlob? {
        if (!isSafeHttpUrl(url)) return null
        val keepImage = image != null && image.isNotEmpty() && image.size <= IMAGE_MAX_BYTES && imageMime in IMAGE_MIMES
        return LinkPreviewBlob(
            v = v,
            url = url,
            title = LinkText.clean(title, TITLE_MAX),
            description = description?.let { LinkText.clean(it, DESCRIPTION_MAX) }?.takeIf { it.isNotEmpty() },
            image = image.takeIf { keepImage },
            imageMime = imageMime.takeIf { keepImage },
        )
    }

    override fun equals(other: Any?): Boolean =
        other is LinkPreviewBlob &&
            v == other.v &&
            url == other.url &&
            title == other.title &&
            description == other.description &&
            imageMime == other.imageMime &&
            (image?.contentEquals(other.image) ?: (other.image == null))

    override fun hashCode(): Int {
        var result = v
        result = 31 * result + url.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (image?.contentHashCode() ?: 0)
        result = 31 * result + (imageMime?.hashCode() ?: 0)
        return result
    }

    companion object {
        /** The layout this build writes. */
        const val VERSION = 1

        /** The newest layout this build reads; anything above it decodes to null (dropped locally, still carried). */
        const val MAX_SUPPORTED = 1

        /** The attachment MIME that says "this blob is a card". Rides `attachmentMime` exactly as `image/webp` does. */
        const val MIME = "application/vnd.knit.link-preview"

        /** Longest blob decoded at all — checked before the CBOR parser sees a byte. */
        const val MAX_BYTES = 256 * 1024

        /** Longest inner picture kept, on both ends: the sender's cap and the decoder's bound. */
        const val IMAGE_MAX_BYTES = 128 * 1024

        const val TITLE_MAX = 200
        const val DESCRIPTION_MAX = 400

        /** Longest [url] accepted; matches the message body's own ceiling with room to spare. */
        const val MAX_URL_LENGTH = 2048

        /** The picture types a card renders. The sender re-encodes to one of these; anything else is dropped. */
        val IMAGE_MIMES = setOf("image/jpeg", "image/webp")

        /**
         * Decodes a peer-supplied blob, or null when it is oversize, malformed, a layout newer than
         * [MAX_SUPPORTED], or refused by [normalized]. Never throws.
         */
        fun decodeOrNull(bytes: ByteArray): LinkPreviewBlob? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val blob = WireCodec.decodePayload<LinkPreviewBlob>(bytes) ?: return null
            if (blob.v < VERSION || blob.v > MAX_SUPPORTED) return null
            return blob.normalized()
        }

        /**
         * Whether [url] is a plain http(s) address a recipient may open: a scheme of exactly `http` or `https`,
         * a host, no user-info (`https://bank.com@evil.example/` reads as the bank to a glance), and no control,
         * format or whitespace characters anywhere in it.
         */
        fun isSafeHttpUrl(url: String): Boolean {
            if (url.isEmpty() || url.length > MAX_URL_LENGTH || url.any(::isUnsafeUrlChar)) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            return (scheme == "https" || scheme == "http") && uri.rawUserInfo == null && !uri.host.isNullOrEmpty()
        }

        /** The host a card is labelled with — always derived from [url], never from anything the page claimed. */
        fun hostOf(url: String): String =
            runCatching { URI(url).host }
                .getOrNull()
                ?.lowercase()
                ?.removePrefix("www.")
                .orEmpty()

        private fun isUnsafeUrlChar(c: Char): Boolean = c.isISOControl() || c.category == CharCategory.FORMAT || c.isWhitespace()
    }
}

/** A decoded card as the UI holds it: value-equal and byte-free, so a row that carries one stays cheap to compare. */
data class LinkCard(
    val url: String,
    val host: String,
    val title: String,
    val description: String?,
    val hasImage: Boolean,
)

/** The text hygiene a card's title and description get on both ends (the sender before encoding, the decoder after). */
object LinkText {
    /**
     * [raw] with runs of whitespace collapsed to one space, every control and format character removed
     * (the Unicode format category is where the bidi overrides live — the trick that renders `moc.knab` as
     * the bank), surrounding whitespace trimmed, and the result cut to [max] UTF-16 units without ever
     * splitting a surrogate pair.
     */
    fun clean(
        raw: String,
        max: Int,
    ): String {
        val collapsed =
            raw
                .replace(WHITESPACE, " ")
                .filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }
                .trim()
        if (collapsed.length <= max) return collapsed
        val end = if (Character.isHighSurrogate(collapsed[max - 1])) max - 1 else max
        return collapsed.substring(0, end).trimEnd()
    }

    private val WHITESPACE = Regex("\\s+")
}
