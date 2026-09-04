package app.getknit.knit.data

import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decoded-card cache over a map-backed blob repository: plaintext and sealed containers, refusals, and the bound. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkCardStoreTest {
    private val stored = HashMap<String, ByteArray>()
    private val blobs =
        mockk<BlobRepository>(relaxed = true).also { repo ->
            coEvery { repo.bytes(any()) } answers { stored[firstArg<String>()] }
        }

    private fun blob(
        url: String = "https://example.com/a",
        image: ByteArray? = ByteArray(16) { 3 },
    ) = LinkPreviewBlob(LinkPreviewBlob.VERSION, url, "Title", "Desc", image, image?.let { "image/webp" })

    private fun store(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) = LinkCardStore(blobs, UnconfinedTestDispatcher(scheduler))

    @Test
    fun aPlaintextCardDecodesIntoTheMapAndItsPictureIsServedForTheLoader() =
        runTest {
            stored["h1"] = blob().encode()
            val cards = store(testScheduler)

            cards.ensure("h1", key = null)

            val card = requireNotNull(cards.cards.value["h1"])
            assertEquals("Title", card.title)
            assertEquals("example.com", card.host)
            assertTrue(card.hasImage)
            val (bytes, mime) = requireNotNull(cards.imageBytes("h1", null))
            assertArrayEquals(ByteArray(16) { 3 }, bytes)
            assertEquals("image/webp", mime)
        }

    @Test
    fun aSealedCardIsDecryptedWithTheRowsKeyAndRefusedWithTheWrongOne() =
        runTest {
            val sealed = AttachmentCrypto.seal(blob().encode())
            stored["ct"] = sealed.blob
            val cards = store(testScheduler)

            cards.ensure("ct", key = b64(sealed.key))
            assertEquals("Title", cards.cards.value["ct"]?.title)

            stored["ct2"] = AttachmentCrypto.seal(blob().encode()).blob
            cards.ensure("ct2", key = b64(AttachmentCrypto.seal(ByteArray(1)).key))
            assertNull(cards.cards.value["ct2"])
            assertNull(cards.imageBytes("ct2", b64(AttachmentCrypto.seal(ByteArray(1)).key)))
        }

    @Test
    fun garbageIsDecodedOnceAndAnAbsentBlobIsLeftForLater() =
        runTest {
            stored["bad"] = byteArrayOf(1, 2, 3)
            val cards = store(testScheduler)

            cards.ensure("bad", null)
            cards.ensure("bad", null)
            coVerify(exactly = 1) { blobs.bytes("bad") }
            assertNull(cards.cards.value["bad"])

            cards.ensure("later", null)
            assertFalse("later" in cards.cards.value)
            stored["later"] = blob(url = "https://example.com/later").encode()
            cards.ensure("later", null)
            assertEquals("example.com", cards.cards.value["later"]?.host)
        }

    @Test
    fun theCacheIsBoundedToTheNewestCards() =
        runTest {
            val cards = store(testScheduler)
            repeat(LinkCardStore.MAX_CARDS + 5) { i ->
                stored["h$i"] = blob(url = "https://example.com/$i", image = null).encode()
                cards.ensure("h$i", null)
            }
            assertEquals(LinkCardStore.MAX_CARDS, cards.cards.value.size)
            assertFalse("the oldest was evicted", "h0" in cards.cards.value)
            assertTrue("h${LinkCardStore.MAX_CARDS + 4}" in cards.cards.value)
        }

    @Test
    fun aTextOnlyCardHasNoPictureToServe() =
        runTest {
            stored["t"] = blob(image = null).encode()
            val cards = store(testScheduler)
            cards.ensure("t", null)
            assertFalse(requireNotNull(cards.cards.value["t"]).hasImage)
            assertNull(cards.imageBytes("t", null))
        }
}
