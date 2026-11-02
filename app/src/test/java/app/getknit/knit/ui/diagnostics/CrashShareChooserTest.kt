package app.getknit.knit.ui.diagnostics

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * #287: the field log and a crash report are served by Knit's own provider, and the chat refuses every URI on it
 * (ADR 2026-11.vdx4). So the sheet that shares them leaves Knit out rather than offering a route that ends in "couldn't
 * read that file"; Send in a chat is the way in.
 */
@RunWith(AndroidJUnit4::class)
class CrashShareChooserTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun chooserFor(title: Int): Intent {
        val uri = Uri.parse("content://${app.packageName}.fileprovider/fieldlog/knit-fieldlog.jsonl")
        launchCrashShareChooser(app, uri, title)
        val chooser = shadowOf(app).nextStartedActivity
        assertNotNull("no chooser started", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return chooser
    }

    @Test
    fun theDiagnosticsSheetLeavesKnitOut() {
        val excluded =
            IntentCompat.getParcelableArrayExtra(
                chooserFor(R.string.crash_share_chooser_title),
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                ComponentName::class.java,
            )
        assertEquals(listOf(ComponentName(app, MainActivity::class.java)), excluded?.toList())
    }

    @Test
    fun theSheetStillCarriesTheFileWithItsReadGrant() {
        val chooser = chooserFor(R.string.field_log_share_chooser_title)
        val send = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))

        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
