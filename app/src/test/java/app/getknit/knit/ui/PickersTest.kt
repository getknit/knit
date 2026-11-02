package app.getknit.knit.ui

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

/**
 * [launchOrSay] on a phone with no documents UI: the 2.10.1 crash was `ActivityNotFoundException` thrown out of
 * the direct-transfer picker's `launch`. The registry here is the real `ActivityResultRegistry` with the
 * activity's `onLaunch` replaced by one that refuses the way `startActivityForResult` does, so the last case
 * proves the registry drops a refused launch: a result that turns up for it later never reaches the callback.
 */
@RunWith(AndroidJUnit4::class)
class PickersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Refuses every launch while [missing] holds, as a phone whose Files app is disabled does. */
    private inner class Registry : ActivityResultRegistry() {
        var missing = true
        var requestCode = -1
        val opened = mutableListOf<Intent>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            val intent = contract.createIntent(app, input)
            if (missing) throw ActivityNotFoundException("No Activity found to handle $intent")
            opened += intent
        }
    }

    private val registry = Registry()
    private val launcher = registry.register("file", ActivityResultContracts.OpenDocument()) { }

    @Test
    fun `a phone with no documents UI gets a toast, not a crash`() {
        assertFalse(launcher.launchOrSay(app, arrayOf("*/*")))

        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(app.getString(R.string.picker_missing), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a picker that opens says nothing`() {
        registry.missing = false

        assertTrue(launcher.launchOrSay(app, arrayOf("*/*")))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.opened.single().action)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `a refusal doesn't stop the next launch`() {
        assertFalse(launcher.launchOrSay(app, arrayOf("*/*")))
        registry.missing = false

        assertTrue(launcher.launchOrSay(app, arrayOf("*/*")))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.opened.single().action)
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun `a refused launch takes no result`() {
        var results = 0
        val counted = registry.register("counted", ActivityResultContracts.OpenDocument()) { results++ }
        assertFalse(counted.launchOrSay(app, arrayOf("*/*")))

        registry.dispatchResult(registry.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://x/1")))

        assertEquals(0, results)
    }
}
