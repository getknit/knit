package app.getknit.knit.ui.preview

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Collections

/**
 * Which string resources each `@Preview` reads, for `scripts/weblate-screenshots.py`: it links every preview's
 * English reference render on Weblate to the strings in it, so a translator sees where a string lands.
 *
 * Not a check. It runs only when `-Pknit.previewStrings=<dir>` names a directory holding the manifest that
 * `scripts/gen-screenshot-tests.py --manifest` writes (`app/build.gradle.kts` excludes it otherwise), and writes
 * `<dir>/strings/<wrapper>.txt`, one resource name per line. A zero-argument `@Composable` compiles to a static
 * `(Composer, int)` method, so each preview is invoked by reflection, under a [Context] whose [Resources] records
 * every id the tree asks for: `stringResource` reads `LocalResources`, while Material3 and the Context helpers
 * (`callSentence`, `routeLabel`) read `LocalContext`, so both are provided. Two frames, like the screenshot
 * tests' single render, at the phone size their `@ScreenShots` use, which also holds every component.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class PreviewStringsTest(
    private val wrapper: String,
    private val owner: String,
    private val method: String,
) {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val read: MutableSet<Int> = Collections.synchronizedSet(LinkedHashSet())

    @Test
    fun recordStringsRead() {
        compose.mainClock.autoAdvance = false
        val composed =
            runCatching {
                compose.setContent {
                    val base = LocalContext.current
                    val resources = RecordingResources(base.resources, read)
                    val context =
                        object : ContextWrapper(base) {
                            override fun getResources(): Resources = resources
                        }
                    CompositionLocalProvider(LocalContext provides context, LocalResources provides resources) {
                        InvokePreview(owner, method)
                    }
                }
                repeat(2) { compose.mainClock.advanceTimeByFrame() }
            }
        // A preview that composed but never settles (RenameGroupDialog's field in a dialog, under Robolectric) has read
        // its strings: keep them, and fail so the run names it. Any other failure leaves no file, so the script reports
        // the preview as not recorded and leaves its screenshot as it is.
        if (composed.isSuccess || composed.exceptionOrNull()?.javaClass?.name == NOT_IDLE) write()
        composed.getOrThrow()
    }

    private fun write() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        // The platform's own (`android.R.string.cancel`) are no translator's; the libraries' share the app's package and
        // are dropped by the script, which keeps only Weblate's source strings.
        val names =
            synchronized(read) { read.toList() }
                .filter { resources.getResourcePackageName(it) != "android" && resources.getResourceTypeName(it) in TEXT_TYPES }
                .map { resources.getResourceEntryName(it) }
                .toSortedSet()
        File(outputDir(), "strings").apply { mkdirs() }.resolve("$wrapper.txt").writeText(names.joinToString("") { "$it\n" })
    }

    /** Hands every text lookup to the real resources and notes its id. */
    @Suppress("DEPRECATION") // the only way to stand in for a Resources is to subclass it
    private class RecordingResources(
        base: Resources,
        private val read: MutableSet<Int>,
    ) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getText(id: Int): CharSequence = super.getText(id).also { read += id }

        override fun getText(
            id: Int,
            def: CharSequence?,
        ): CharSequence? = super.getText(id, def).also { read += id }

        override fun getString(id: Int): String = super.getString(id).also { read += id }

        override fun getString(
            id: Int,
            vararg formatArgs: Any?,
        ): String = super.getString(id, *formatArgs).also { read += id }

        override fun getQuantityText(
            id: Int,
            quantity: Int,
        ): CharSequence = super.getQuantityText(id, quantity).also { read += id }

        override fun getQuantityString(
            id: Int,
            quantity: Int,
        ): String = super.getQuantityString(id, quantity).also { read += id }

        override fun getQuantityString(
            id: Int,
            quantity: Int,
            vararg formatArgs: Any?,
        ): String = super.getQuantityString(id, quantity, *formatArgs).also { read += id }

        override fun getStringArray(id: Int): Array<String> = super.getStringArray(id).also { read += id }
    }

    companion object {
        private const val PROPERTY = "knit.previewStrings"
        private const val NOT_IDLE = "androidx.test.espresso.AppNotIdleException" // on the runtime classpath only
        private val TEXT_TYPES = setOf("string", "plurals", "array")

        private fun outputDir() =
            File(requireNotNull(System.getProperty(PROPERTY)) { "run through scripts/weblate-screenshots.py, which sets $PROPERTY" })

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> =
            Json.parseToJsonElement(File(outputDir(), "manifest.json").readText()).jsonArray.map { entry ->
                val e = entry.jsonObject
                arrayOf(
                    e.getValue("wrapper").jsonPrimitive.content,
                    e.getValue("class").jsonPrimitive.content,
                    e.getValue("method").jsonPrimitive.content,
                )
            }
    }
}

@Composable
private fun InvokePreview(
    owner: String,
    method: String,
) {
    Class
        .forName(owner)
        .getDeclaredMethod(method, Composer::class.java, Int::class.javaPrimitiveType)
        .invoke(null, currentComposer, 0)
}
