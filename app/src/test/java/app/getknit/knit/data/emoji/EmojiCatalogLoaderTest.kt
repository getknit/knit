package app.getknit.knit.data.emoji

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiCatalogLoaderTest {
    private val asset = "# c\n😀\t0\t0\tgrinning face\n🦄\t2\t0\tunicorn\n👍🏻\t1\t1\tthumbs up: light skin tone\n"

    @Test
    fun loadsOnceAndCachesTheCatalog() =
        runTest {
            var opens = 0
            val loader =
                EmojiCatalogLoader(
                    open = {
                        opens++
                        asset.byteInputStream()
                    },
                    canRender = { true },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            val first = loader.load()
            val second = loader.load()

            assertSame(first, second)
            assertEquals(1, opens)
            assertEquals(listOf("😀", "🦄", "👍🏻"), first.entries.map { it.emoji })
        }

    @Test
    fun dropsWhatTheDeviceCannotRenderFromBrowseAndSearchAlike() =
        runTest {
            val loader =
                EmojiCatalogLoader(
                    open = { asset.byteInputStream() },
                    canRender = { it != "🦄" },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            val catalog = loader.load()

            assertEquals(listOf("😀"), catalog.browse.map { it.emoji })
            assertTrue(catalog.search("unicorn").isEmpty())
            assertEquals("👍🏻", catalog.search("light skin").single().emoji)
        }

    @Test
    fun theAppLanguageOverlayIsLaidOverAndRebuiltWhenTheLanguageChanges() =
        runTest {
            var tag: String? = "pt"
            var opens = 0
            var overlayOpens = 0
            val loader =
                EmojiCatalogLoader(
                    open = {
                        opens++
                        asset.byteInputStream()
                    },
                    canRender = { true },
                    openOverlay = { t ->
                        overlayOpens++
                        check(t == "pt") { "unexpected overlay $t" }
                        "# c\n😀\trosto risonho\tsorriso\n".byteInputStream()
                    },
                    overlayTag = { tag },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            val pt = loader.load()
            assertEquals("rosto risonho", pt.entries.first().name)
            assertEquals("unicorn", pt.entries[1].name)
            assertSame(pt, loader.load())
            assertEquals(1, overlayOpens)

            tag = null
            assertEquals(
                "grinning face",
                loader
                    .load()
                    .entries
                    .first()
                    .name,
            )
            tag = "pt"
            assertEquals(
                "rosto risonho",
                loader
                    .load()
                    .entries
                    .first()
                    .name,
            )
            assertEquals(2, overlayOpens)
            assertEquals("the English base is read once, whatever the language", 1, opens)
        }

    @Test
    fun anUnreadableOverlayFallsBackToEnglishAndIsRetriedNextTime() =
        runTest {
            var fail = true
            val loader =
                EmojiCatalogLoader(
                    open = { asset.byteInputStream() },
                    canRender = { true },
                    openOverlay = { if (fail) throw IOException("no overlay") else "😀\trosto risonho\n".byteInputStream() },
                    overlayTag = { "pt" },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            assertEquals(
                "grinning face",
                loader
                    .load()
                    .entries
                    .first()
                    .name,
            )
            fail = false
            assertEquals(
                "rosto risonho",
                loader
                    .load()
                    .entries
                    .first()
                    .name,
            )
        }

    @Test
    fun anUnreadableAssetDegradesToEmptyAndIsRetriedNextTime() =
        runTest {
            var fail = true
            val loader =
                EmojiCatalogLoader(
                    open = { if (fail) throw IOException("no asset") else asset.byteInputStream() },
                    canRender = { true },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            assertSame(EmojiCatalog.EMPTY, loader.load())
            fail = false
            assertEquals(3, loader.load().entries.size)
        }

    @Test
    fun aStreamThatFailsMidReadAlsoDegrades() =
        runTest {
            val broken =
                object : InputStream() {
                    override fun read(): Int = throw IOException("torn")
                }
            val loader = EmojiCatalogLoader(open = { broken }, canRender = { true }, io = UnconfinedTestDispatcher(testScheduler))

            assertSame(EmojiCatalog.EMPTY, loader.load())
        }
}
