package app.getknit.knit.linkpreview

import app.getknit.knit.linkpreview.LinkPreviewService.CardResult
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkPreviewServiceTest {
    private class FakeGate(
        online: Boolean = true,
        var restricted: Boolean = false,
    ) : InternetGate {
        override val online = MutableStateFlow(online)

        override fun isOnline(): Boolean = online.value

        override fun isDataRestricted(): Boolean = restricted
    }

    private class FakeFetcher : PreviewFetcher {
        val pages = ArrayList<String>()
        val images = ArrayList<String>()
        var page: PageFetch =
            html(
                "<meta property='og:title' content='Title'><meta property='og:description' content='Desc'>" +
                    "<meta property='og:image' content='https://cdn.example.com/p.jpg'>",
            )
        var image: ImageFetch = ImageFetch.Image(ByteArray(64) { 1 }, "image/jpeg")
        var pageDelayMs = 0L

        override suspend fun fetchPage(url: String): PageFetch {
            pages += url
            if (pageDelayMs > 0) delay(pageDelayMs)
            return page
        }

        override suspend fun fetchImage(url: String): ImageFetch {
            images += url
            return image
        }
    }

    private class Rig(
        dispatcher: CoroutineDispatcher,
        val gate: FakeGate = FakeGate(),
        val fetcher: FakeFetcher = FakeFetcher(),
    ) {
        var now = 1_000_000L
        val screened = ArrayList<ByteArray>()
        var imageFlagged = false
        val classified = ArrayList<Pair<String, Boolean>>()
        var textFlagged = false
        var shrunk: LinkPreviewService.Shrunk? = LinkPreviewService.Shrunk(ByteArray(8) { 2 }, "image/webp")
        val service =
            LinkPreviewService(
                gate = gate,
                fetcher = fetcher,
                screenImage = { bytes ->
                    screened += bytes
                    imageFlagged
                },
                textFlagged = { text, isRoom ->
                    classified += text to isRoom
                    textFlagged
                },
                shrink = { shrunk },
                clock = { now },
                cpu = dispatcher,
            )
    }

    /** A rig on this test's scheduler, so the fetch budget and the fake site's delay share one virtual clock. */
    private fun TestScope.rig(
        gate: FakeGate = FakeGate(),
        fetcher: FakeFetcher = FakeFetcher(),
    ) = Rig(UnconfinedTestDispatcher(testScheduler), gate, fetcher)

    private val url = "https://example.com/story"

    @Test
    fun aPageWithAPictureBecomesACard() =
        runTest {
            val rig = rig()
            val result = rig.service.fetchCard(url, isRoom = false)
            val card = (result as CardResult.Card).blob
            assertEquals(url, card.url)
            assertEquals("Title", card.title)
            assertEquals("Desc", card.description)
            assertArrayEquals(ByteArray(8) { 2 }, card.image)
            assertEquals("image/webp", card.imageMime)
            assertEquals(listOf(url), rig.fetcher.pages)
            assertEquals(1, rig.screened.size)
            assertEquals(listOf("Title\nDesc" to false), rig.classified)
        }

    @Test
    fun withoutAValidatedRouteNothingIsFetchedAndTheAnswerIsNotRemembered() =
        runTest {
            val rig = rig(gate = FakeGate(online = false))
            assertEquals(CardResult.Offline, rig.service.fetchCard(url, isRoom = false))
            assertTrue(rig.fetcher.pages.isEmpty())
            rig.gate.online.value = true
            assertTrue(rig.service.fetchCard(url, isRoom = false) is CardResult.Card)
            assertEquals(1, rig.fetcher.pages.size)
        }

    @Test
    fun dataSaverDefersTheFetchWithoutTouchingTheNetwork() =
        runTest {
            val rig = rig(gate = FakeGate(restricted = true))
            assertEquals(CardResult.Restricted, rig.service.fetchCard(url, isRoom = false))
            assertTrue(rig.fetcher.pages.isEmpty())
        }

    @Test
    fun aLinkThePolicyRefusesNeverReachesTheGateOrTheNetwork() =
        runTest {
            val rig = rig(gate = FakeGate(online = false))
            assertEquals(CardResult.NoCard, rig.service.fetchCard("https://10.0.0.1/admin", isRoom = false))
            assertEquals(CardResult.NoCard, rig.service.fetchCard("https://example.com:8443/", isRoom = false))
            assertTrue(rig.fetcher.pages.isEmpty())
        }

    @Test
    fun aPageThatIsNotHtmlOrHasNoTitleYieldsNoCard() =
        runTest {
            val rig = rig()
            rig.fetcher.page = PageFetch.NotHtml
            assertEquals(CardResult.NoCard, rig.service.fetchCard(url, isRoom = false))
            rig.fetcher.page = html("<meta property='og:description' content='desc but no title'>")
            assertEquals(CardResult.NoCard, rig.service.fetchCard(url, isRoom = false))
            rig.fetcher.page = PageFetch.Failed("http 404")
            assertEquals(CardResult.NoCard, rig.service.fetchCard(url, isRoom = false))
            assertTrue(rig.fetcher.images.isEmpty())
        }

    @Test
    fun aNetworkThatVanishesMidFetchReadsAsOfflineNotAsNoCard() =
        runTest {
            val rig = rig()
            rig.fetcher.page = PageFetch.Offline
            assertEquals(CardResult.Offline, rig.service.fetchCard(url, isRoom = false))
        }

    @Test
    fun anyPictureFailureLeavesATextOnlyCard() =
        runTest {
            val rig = rig()
            rig.fetcher.image = ImageFetch.TooLarge
            assertNull((rig.service.fetchCard(url, isRoom = false) as CardResult.Card).blob.image)
            rig.fetcher.image = ImageFetch.Image(ByteArray(4), "image/png")
            rig.shrunk = null
            assertNull((rig.service.fetchCard("$url?2", isRoom = false) as CardResult.Card).blob.image)
            assertEquals("both pictures above were fetched before they failed", 2, rig.fetcher.images.size)
            // A picture whose URL the policy refuses is never fetched at all.
            rig.fetcher.page = html("<meta property='og:title' content='T'><meta property='og:image' content='https://10.0.0.7/x.jpg'>")
            rig.service.fetchCard("$url?3", isRoom = false)
            assertEquals(2, rig.fetcher.images.size)
        }

    @Test
    fun aFlaggedPictureIsDroppedButTheCardIsKept() =
        runTest {
            val rig = rig()
            rig.imageFlagged = true
            val card = (rig.service.fetchCard(url, isRoom = false) as CardResult.Card).blob
            assertEquals("Title", card.title)
            assertNull(card.image)
            assertNull(card.imageMime)
        }

    @Test
    fun flaggedTextDropsTheWholeCardInTheScopeItWasClassifiedFor() =
        runTest {
            val rig = rig()
            rig.textFlagged = true
            assertEquals(CardResult.NoCard, rig.service.fetchCard(url, isRoom = true))
            assertEquals(listOf("Title\nDesc" to true), rig.classified)
            // The memo holds the pre-moderation card, so the same link in a DM is classified again, not refused by memory.
            rig.textFlagged = false
            assertTrue(rig.service.fetchCard(url, isRoom = false) is CardResult.Card)
            assertEquals(1, rig.fetcher.pages.size)
            assertEquals(false, rig.classified.last().second)
        }

    @Test
    fun theMemoReusesACardUntilItExpires() =
        runTest {
            val rig = rig()
            rig.service.fetchCard(url, isRoom = false)
            rig.service.fetchCard(url, isRoom = false)
            rig.service.fetchCard("http://example.com/story#frag", isRoom = false)
            assertEquals(1, rig.fetcher.pages.size)
            rig.now += LinkPreviewService.MEMO_TTL_MS + 1
            rig.service.fetchCard(url, isRoom = false)
            assertEquals(2, rig.fetcher.pages.size)
        }

    @Test
    fun aSlowSiteIsGivenUpOnWithinTheBudget() =
        runTest {
            val rig = rig()
            rig.fetcher.pageDelayMs = LinkPreviewService.FETCH_BUDGET_MS + 1
            assertEquals(CardResult.NoCard, rig.service.fetchCard(url, isRoom = false))
        }

    @Test
    fun theCardIsNormalizedBeforeItIsHandedBack() =
        runTest {
            val rig = rig()
            rig.fetcher.page = html("<meta property='og:title' content='  Spaced   out  '>")
            rig.shrunk = LinkPreviewService.Shrunk(ByteArray(LinkPreviewBlob.IMAGE_MAX_BYTES + 1), "image/webp")
            val card = (rig.service.fetchCard(url, isRoom = false) as CardResult.Card).blob
            assertEquals("Spaced out", card.title)
            assertNull("an over-cap picture is dropped by the container's own rule", card.image)
        }

    private companion object {
        fun html(body: String): PageFetch = PageFetch.Html(body.toByteArray(), "text/html; charset=utf-8", "https://example.com/story")
    }
}
