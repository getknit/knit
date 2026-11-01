package app.getknit.knit.data.emoji

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Loads the bundled emoji catalog off the main thread, the first time the picker opens: parses [ASSET]
 * through [open], then drops every entry [canRender] refuses — on a device that is `Paint.hasGlyph`
 * ([AndroidGlyphCheck]), so an older phone never offers an emoji its fonts draw as a tofu box — then lays
 * the app language's overlay over it ([overlayTag] names it, [openOverlay] reads it,
 * [EmojiCatalog.localized]). Fonts don't change mid-process, so the base is cached once; the localized
 * catalog is cached for the tag it was built for, so a language change rebuilds it on the next open.
 * Concurrent loads collapse on the mutex. A missing or unreadable base degrades to [EmojiCatalog.EMPTY]
 * (the quick-reaction row still works) and an unreadable overlay to the English catalog; neither failure is
 * cached, so it is retried on the next open. Pure Kotlin — the streams, the tag and the glyph check are
 * injected so the JVM tests need no Android.
 */
class EmojiCatalogLoader(
    private val open: () -> InputStream,
    private val canRender: (String) -> Boolean,
    private val openOverlay: (String) -> InputStream = { throw IOException("no overlays") },
    private val overlayTag: () -> String? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var base: EmojiCatalog? = null

    @Volatile
    private var built: Built? = null
    private val mutex = Mutex()

    private class Built(
        val tag: String?,
        val catalog: EmojiCatalog,
    )

    suspend fun load(): EmojiCatalog {
        val tag = withContext(io) { overlayTag() }
        built?.takeIf { it.tag == tag }?.let { return it.catalog }
        return mutex.withLock {
            built?.takeIf { it.tag == tag }?.let { return@withLock it.catalog }
            val english = base ?: withContext(io) { read() }?.also { base = it } ?: return@withLock EmojiCatalog.EMPTY
            if (tag == null) return@withLock english.also { built = Built(null, it) }
            val overlay = withContext(io) { readOverlay(tag) } ?: return@withLock english
            english.localized(overlay).also { built = Built(tag, it) }
        }
    }

    private fun read(): EmojiCatalog? =
        try {
            val entries = open().bufferedReader().useLines(EmojiCatalogParser::parse)
            EmojiCatalog(entries.filter { canRender(it.emoji) })
        } catch (_: IOException) {
            null
        }

    private fun readOverlay(tag: String): Map<String, LocalizedEmoji>? =
        try {
            openOverlay(tag).bufferedReader().useLines(EmojiCatalogParser::parseOverlay)
        } catch (_: IOException) {
            null
        }

    companion object {
        const val ASSET = "emoji/emoji_en.tsv"
    }
}
