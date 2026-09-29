package app.getknit.knit.ui.about

import android.content.Context
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.CrashEnvironment
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.legal.InstallSource
import app.getknit.knit.legal.installSourceOf
import app.getknit.knit.legal.installerPackage

/**
 * The facts the About screen's Build section shows and its Copy button puts on the clipboard: the crash
 * header's environment plus where the app was installed from. Nothing in a release comes from the build
 * machine — no git SHA, no timestamp — because the release APK is byte-reproduced by F-Droid's buildserver.
 * A debug build alone carries [commit], the checkout's short SHA (ADR 2026-09.6eb6's amendment).
 */
data class AboutBuildInfo(
    val environment: CrashEnvironment,
    val installSource: InstallSource,
    /** The short commit a debug build was made from; null in every release-shaped build. */
    val commit: String? = null,
) {
    /**
     * The block a bug report wants, in the crash header's line shapes so a paste into a GitHub issue reads
     * the same way a crash report does. ASCII and unlocalized on purpose — it is for the maintainer.
     */
    fun asText(): String =
        buildString {
            appendLine("app: ${environment.appLine()}")
            if (commit != null) appendLine("commit: $commit")
            appendLine("installed from: ${installSource.name.lowercase()}")
            appendLine("device: ${environment.deviceLine()}")
            appendLine("android: ${environment.androidLine()}")
            append("abis: ${environment.abis}")
        }
}

/** Reads this build's facts once; the About screen remembers the result for its lifetime. */
fun currentAboutBuildInfo(context: Context): AboutBuildInfo =
    AboutBuildInfo(
        environment = currentCrashEnvironment(),
        installSource = installSourceOf(installerPackage(context)),
        commit = BuildConfig.GIT_SHA.ifEmpty { null },
    )
