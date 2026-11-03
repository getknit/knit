package app.getknit.knit.crash

import android.content.Context
import android.os.Build
import app.getknit.knit.BuildConfig
import java.io.File

/** Directory name under `noBackupFilesDir` holding captured crash reports. */
private const val CRASH_DIR = "crashes"

/**
 * The device and build facts stamped into a crash report's header, captured as a plain value so the
 * whole capture path ([CrashHandler], [CrashStore]) stays free of Android types and unit-testable on
 * the JVM.
 */
data class CrashEnvironment(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val obfuscated: Boolean,
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val soc: String,
    val sdkInt: Int,
    val release: String,
    val abis: String,
    val fingerprint: String,
) {
    /** `2.5.1 (21) release` — the crash header's `app:` value, shared with the About screen's Copy build info. */
    fun appLine(): String = "$versionName ($versionCode) $buildType"

    /** `Google Pixel 8 (shiba)` — the `device:` value `.github/workflows/needs-info.yml` looks for. */
    fun deviceLine(): String = "$manufacturer $model ($device)"

    /** `16 (SDK 36)` — the `android:` value. */
    fun androidLine(): String = "$release (SDK $sdkInt)"
}

/**
 * Reads this build and device's facts. Deliberately branch-free field copying with no logic beyond the
 * API-31 SoC guard, because `android.os.Build` is stubbed to defaults on the plain JVM and this is the
 * one part of the capture path that cannot be unit-tested without Robolectric.
 */
fun currentCrashEnvironment(): CrashEnvironment =
    CrashEnvironment(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        obfuscated = isObfuscated(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        board = Build.BOARD.orEmpty(),
        hardware = Build.HARDWARE.orEmpty(),
        soc = socName(),
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE.orEmpty(),
        abis = Build.SUPPORTED_ABIS.orEmpty().joinToString(", "),
        fingerprint = Build.FINGERPRINT.orEmpty(),
    )

/**
 * Whether R8 renamed our classes in this artifact — reported instead of the build type, so the header
 * describes the APK actually running rather than a label. R8 does not rewrite string literals unless
 * `-adaptclassstrings` is enabled, which `app/src/main/keepRules/knit-r8.keep` does not do, so the
 * literal survives while the reflective name is mangled.
 *
 * **Adding `-adaptclassstrings` would silently pin this to `false`.** The header prints the build type
 * alongside it so a reader can cross-check.
 */
private fun isObfuscated(): Boolean = CrashEnvironment::class.java.name != "app.getknit.knit.crash.CrashEnvironment"

/** `ro.board.platform` has no public API; `SOC_*` is the closest public equivalent, and only from API 31. */
private fun socName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf<String?>(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
    } else {
        ""
    }

/**
 * The crash store for this app.
 *
 * `noBackupFilesDir`, **not** `filesDir`: backup is allow-by-default across three sections of
 * `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`, so a report under `filesDir`
 * would need three exclude entries that stay correct forever — and one missed entry ships crash traces
 * to cloud backup and device-to-device transfer, real egress in an app whose premise is that it has
 * none. `noBackupFilesDir` is excluded by construction and needs no XML at all.
 *
 * The cost is that `FileProvider` has no `no-backup-files-path` tag, so a stored report cannot be
 * shared directly — [CrashReports] stages a share copy under `cacheDir/crash/` instead. That is a
 * feature: the provider can only ever hand out the one report the user picked.
 *
 * This is the single definition of the path, called both from `KnitApplication.onCreate` (before Koin
 * exists) and from `appModule`, so the two cannot drift apart.
 */
fun crashStore(context: Context): CrashStore = CrashStore(File(context.noBackupFilesDir, CRASH_DIR))
