package app.getknit.knit.ui.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #284: [OutsideUris.of] reads this install's providers off the merged manifest, so a provider a library adds is
 * refused without anyone listing it. androidx.startup's is the witness: nothing in Knit's code names it. Run on
 * both lookups: `PackageInfoFlags` from API 33, and the deprecated int-flag call that API 29–32 phones take.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32, 36])
class OutsideUrisOfTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theRuleForThisInstallRefusesEveryProviderItsManifestDeclares() {
        val rule = OutsideUris.of(context)
        val pkg = context.packageName

        assertFalse(rule.admits(Uri.parse("content://$pkg.fileprovider/fieldlog/knit-fieldlog.txt")))
        assertFalse("a library's provider, from the manifest", rule.admits(Uri.parse("content://$pkg.androidx-startup/x")))
        assertFalse(rule.admits(Uri.fromFile(context.filesDir.resolve("datastore/knit_settings.preferences_pb"))))
        assertTrue(rule.admits(Uri.parse("content://com.android.providers.media.documents/document/image%3A1")))
    }
}
