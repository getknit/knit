package app.getknit.knit.ui.donate

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The support screen hands every row to another app: each donation platform and the store listing open in
 * the browser at their own address, and Share offers the listing link through the system chooser. The
 * addresses are pinned because a typo here sends someone's money or goodwill somewhere else.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DonateScreenTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun opened(label: String): Intent {
        compose.onNodeWithText(label).performScrollTo().performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("$label opened nothing", intent)
        return intent
    }

    @Test
    fun eachPlatformOpensItsOwnAddress() {
        compose.setContent { KnitTheme { DonateScreen(onBack = {}, rateUrl = "https://example.com/listing") } }
        val expected =
            mapOf(
                "Ko-fi" to "https://ko-fi.com/knit",
                "Liberapay" to "https://liberapay.com/zaventh/",
                "GitHub Sponsors" to "https://github.com/sponsors/getknit",
            )
        for ((label, url) in expected) {
            val intent = opened(label)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }
    }

    @Test
    fun rateOpensTheListingItWasGiven() {
        compose.setContent { KnitTheme { DonateScreen(onBack = {}, rateUrl = "https://example.com/listing") } }
        assertEquals("https://example.com/listing", opened(app.getString(R.string.review_menu)).dataString)
    }

    @Test
    fun shareGoesThroughTheChooser() {
        compose.setContent { KnitTheme { DonateScreen(onBack = {}) } }
        assertEquals(Intent.ACTION_CHOOSER, opened(app.getString(R.string.share_knit_menu)).action)
    }
}
