package app.getknit.knit.mesh.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card container's contract: round trips, the additive rule (an unknown field is ignored, an unknown
 * layout is refused), and the decode-boundary normalization that keeps sender-supplied text and the one
 * openable URL honest.
 */
@OptIn(ExperimentalSerializationApi::class)
class LinkPreviewBlobTest {
    private fun card(
        url: String = "https://www.example.com/article?id=7",
        title: String = "An article",
        description: String? = "About things",
        image: ByteArray? = ByteArray(16) { it.toByte() },
        imageMime: String? = "image/webp",
    ) = LinkPreviewBlob(LinkPreviewBlob.VERSION, url, title, description, image, imageMime)

    @Test
    fun aFullCardRoundTripsThroughItsBytes() {
        val original = card()
        val decoded = requireNotNull(LinkPreviewBlob.decodeOrNull(original.encode()))
        assertEquals(original, decoded)
        assertArrayEquals(original.image, decoded.image)
    }

    @Test
    fun aTextOnlyCardRoundTripsAndCarriesNoImageBytes() {
        val original = card(image = null, imageMime = null)
        val decoded = requireNotNull(LinkPreviewBlob.decodeOrNull(original.encode()))
        assertEquals(original, decoded)
        assertNull(decoded.image)
        assertNull(decoded.imageMime)
        // An absent picture is elided, not written as null — the text-only shape is the smaller one.
        assertTrue(original.encode().size < card().encode().size)
    }

    @Test
    fun theVersionIsAlwaysOnTheWire() {
        // `v` has no default, so `encodeDefaults = false` can never elide it: the bytes carry the text key "v"
        // followed by the layout number, which is what lets a decoder refuse a layout it does not know.
        val encoded = LinkPreviewBlob(LinkPreviewBlob.VERSION, "https://example.com/").encode()
        val key = byteArrayOf('v'.code.toByte(), LinkPreviewBlob.VERSION.toByte())
        assertTrue(encoded.toList().windowed(key.size).any { it.toByteArray().contentEquals(key) })
    }

    /** The shape a future build might write: one more field a current decoder has never heard of. */
    @Serializable
    private class FutureShape(
        val v: Int,
        val url: String,
        val title: String = "",
        val description: String? = null,
        @ByteString val image: ByteArray? = null,
        val imageMime: String? = null,
        val extra: String? = null,
    )

    @Test
    fun anUnknownFieldIsIgnoredButAnUnknownLayoutIsRefused() {
        val withExtra = WireCodec.encodePayload(FutureShape(v = 1, url = "https://example.com/", title = "T", extra = "later"))
        assertEquals("T", requireNotNull(LinkPreviewBlob.decodeOrNull(withExtra)).title)
        val newerLayout = WireCodec.encodePayload(FutureShape(v = 2, url = "https://example.com/", title = "T"))
        assertNull(LinkPreviewBlob.decodeOrNull(newerLayout))
        val zeroLayout = WireCodec.encodePayload(FutureShape(v = 0, url = "https://example.com/", title = "T"))
        assertNull(LinkPreviewBlob.decodeOrNull(zeroLayout))
    }

    @Test
    fun oversizeOrMalformedBytesDecodeToNull() {
        assertNull(LinkPreviewBlob.decodeOrNull(ByteArray(0)))
        assertNull(LinkPreviewBlob.decodeOrNull(ByteArray(LinkPreviewBlob.MAX_BYTES + 1)))
        assertNull(LinkPreviewBlob.decodeOrNull(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun onlyAPlainHttpAddressSurvivesAsTheCardsUrl() {
        listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "https://bank.example@evil.example/",
            "https://exam ple.com/",
            "https:///nohost",
            "ftp://example.com/",
            "https://example.com/‮path",
            "",
        ).forEach { bad ->
            assertFalse(bad, LinkPreviewBlob.isSafeHttpUrl(bad))
            assertNull(bad, LinkPreviewBlob.decodeOrNull(card(url = bad).encode()))
        }
        assertTrue(LinkPreviewBlob.isSafeHttpUrl("https://example.com/a?b=c#d"))
        assertTrue(LinkPreviewBlob.isSafeHttpUrl("http://example.com/"))
        assertTrue(LinkPreviewBlob.isSafeHttpUrl("https://xn--bcher-kva.example/"))
    }

    @Test
    fun anImageOfTheWrongTypeOrSizeIsDroppedNotRefused() {
        val gif = requireNotNull(LinkPreviewBlob.decodeOrNull(card(imageMime = "image/gif").encode()))
        assertNull(gif.image)
        assertNull(gif.imageMime)
        assertEquals("An article", gif.title)
        val big = card(image = ByteArray(LinkPreviewBlob.IMAGE_MAX_BYTES + 1))
        val decodedBig = requireNotNull(LinkPreviewBlob.decodeOrNull(big.encode()))
        assertNull(decodedBig.image)
        assertNull(decodedBig.imageMime)
        val empty = requireNotNull(LinkPreviewBlob.decodeOrNull(card(image = ByteArray(0)).encode()))
        assertNull(empty.image)
    }

    @Test
    fun textIsCleanedAtTheDecodeBoundary() {
        val decoded =
            requireNotNull(
                LinkPreviewBlob
                    .decodeOrNull(
                        card(
                            title = "  Read ‮this​ now\t\tplease ",
                            description = "line one\n\nline two   ",
                        ).encode(),
                    ),
            )
        assertEquals("Read this now please", decoded.title)
        assertEquals("line one line two", decoded.description)
        val blankDescription = requireNotNull(LinkPreviewBlob.decodeOrNull(card(description = " \n ").encode()))
        assertNull(blankDescription.description)
    }

    @Test
    fun textIsCappedWithoutSplittingASurrogatePair() {
        val longTitle = "a".repeat(LinkPreviewBlob.TITLE_MAX + 50)
        assertEquals(LinkPreviewBlob.TITLE_MAX, requireNotNull(LinkPreviewBlob.decodeOrNull(card(title = longTitle).encode())).title.length)
        val emojiAtTheEdge = "b".repeat(LinkPreviewBlob.TITLE_MAX - 1) + "😀"
        val cut = requireNotNull(LinkPreviewBlob.decodeOrNull(card(title = emojiAtTheEdge).encode())).title
        assertEquals(LinkPreviewBlob.TITLE_MAX - 1, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
        val longDescription = "c".repeat(LinkPreviewBlob.DESCRIPTION_MAX + 1)
        assertEquals(
            LinkPreviewBlob.DESCRIPTION_MAX,
            requireNotNull(LinkPreviewBlob.decodeOrNull(card(description = longDescription).encode())).description?.length,
        )
    }

    @Test
    fun theCardIsLabelledByItsOwnHostNeverByThePage() {
        val decoded = requireNotNull(LinkPreviewBlob.decodeOrNull(card().encode()))
        val asCard = decoded.toCard()
        assertEquals("example.com", asCard.host)
        assertEquals("An article", asCard.title)
        assertTrue(asCard.hasImage)
        val untitled = card(title = "", image = null, imageMime = null).toCard()
        assertEquals("example.com", untitled.title)
        assertFalse(untitled.hasImage)
        assertEquals("xn--bcher-kva.example", LinkPreviewBlob.hostOf("https://XN--BCHER-KVA.example/x"))
        assertEquals("", LinkPreviewBlob.hostOf("not a url"))
    }

    @Test
    fun theModerationTextJoinsTitleAndDescriptionAndSkipsBlanks() {
        assertEquals("An article\nAbout things", card().moderationText())
        assertEquals("An article", card(description = null).moderationText())
        assertEquals("About things", card(title = "").moderationText())
        assertNotNull(card(title = "", description = null).moderationText())
        assertEquals("", card(title = "", description = null).moderationText())
    }
}
