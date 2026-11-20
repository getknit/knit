package app.getknit.knit.legal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.getknit.knit.BuildConfig

/**
 * Where this copy of Knit came from, as far as the installer package can say. The three channels carry
 * three different signing keys (Play App Signing, our release key on GitHub and F-Droid, the share key an
 * "Install offline" copy is re-signed with), so this is the first thing a bug report needs and the fact the
 * review prompt routes on. [SIDELOADED] covers the GitHub APK, a shared copy and every other store alike —
 * the installer of a sideload is the package installer, which says nothing about origin. [PEER] is Knit itself: a peer
 * update installs through Knit's own session (ADR 2026-11.29m7), so the installer of record becomes this package.
 */
enum class InstallSource { PLAY, FDROID, SIDELOADED, PEER }

/** Maps an installer package name (null when unknown) to the channel it stands for. Pure, for the JVM tests. */
fun installSourceOf(installer: String?): InstallSource =
    when (installer) {
        PLAY_STORE_PACKAGE -> InstallSource.PLAY
        in FDROID_PACKAGES -> InstallSource.FDROID
        BuildConfig.APPLICATION_ID -> InstallSource.PEER
        else -> InstallSource.SIDELOADED
    }

/** The package that installed us, or null when the system can't say. The app's one installer read. */
fun installerPackage(context: Context): String? =
    try {
        // getInstallSourceInfo is API 30; on 29 the deprecated getInstallerPackageName gives the installer.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

private const val PLAY_STORE_PACKAGE = "com.android.vending"

// The classic client, the Basic client, and the privileged-extension install path.
private val FDROID_PACKAGES = setOf("org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged")
