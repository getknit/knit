package app.getknit.knit.crash

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowBuild

/**
 * The header facts a crash report and About's "Copy build info" print. The three one-line forms are what
 * `.github/workflows/needs-info.yml` and a reader scan for, so their shape is pinned; the capture reads
 * `android.os.Build`, which only Robolectric fills in.
 */
@RunWith(AndroidJUnit4::class)
class CrashEnvironmentTest {
    @Test
    fun theHeaderLinesKeepTheirShape() {
        val env = testEnvironment(versionName = "2.5.1")
        assertEquals("2.5.1 (13) debug", env.appLine())
        assertEquals("Google Pixel 8 (shiba)", env.deviceLine())
        assertEquals("16 (SDK 36)", env.androidLine())
    }

    @Test
    fun theCaptureReadsThisBuildAndThisDevice() {
        ShadowBuild.setManufacturer("Fairphone")
        ShadowBuild.setModel("FP5")
        ShadowBuild.setDevice("FP5")
        ShadowBuild.setFingerprint("Fairphone/FP5/FP5:15/test:user/release-keys")

        val env = currentCrashEnvironment()

        assertEquals(BuildConfig.VERSION_NAME, env.versionName)
        assertEquals(BuildConfig.VERSION_CODE, env.versionCode)
        assertEquals(BuildConfig.BUILD_TYPE, env.buildType)
        assertFalse("a debug unit-test build is never R8-renamed", env.obfuscated)
        assertEquals("Fairphone FP5 (FP5)", env.deviceLine())
        assertEquals(Build.VERSION.SDK_INT, env.sdkInt)
        assertEquals("Fairphone/FP5/FP5:15/test:user/release-keys", env.fingerprint)
        assertEquals(Build.SUPPORTED_ABIS.joinToString(", "), env.abis)
    }
}
