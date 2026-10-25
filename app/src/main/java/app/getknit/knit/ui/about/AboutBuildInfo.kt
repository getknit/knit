package app.getknit.knit.ui.about

import android.content.Context
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.CrashEnvironment
import app.getknit.knit.crash.CrashIssueUrl
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.di.CALL_CAPABILITIES
import app.getknit.knit.legal.InstallSource
import app.getknit.knit.legal.installSourceOf
import app.getknit.knit.legal.installerPackage
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.ui.ISSUES_URL

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
    /** The feature flags this build has on, by their `BuildConfig` names (see [enabledBuildFlags]). */
    val flags: List<String> = emptyList(),
    /** The capability bits this build advertises in its profile and HELLO (see [currentAboutBuildInfo]). */
    val capabilities: Long = 0L,
) {
    /** [capabilities]' bits with their `Protocol` names, lowest first. */
    val capabilityBits: List<Pair<String, Long>> get() = capabilityBits(capabilities)

    /**
     * The block a bug report wants, in the crash header's line shapes so a paste into a GitHub issue reads
     * the same way a crash report does. ASCII and unlocalized on purpose — it is for the maintainer.
     */
    fun asText(): String =
        buildString {
            appendLine("app: ${environment.appLine()}")
            if (commit != null) appendLine("commit: $commit")
            appendLine("installed from: ${installSource.name.lowercase()}")
            if (flags.isNotEmpty()) appendLine("flags: ${flags.joinToString(", ")}")
            if (capabilities != 0L) appendLine("capabilities: ${hexBits(capabilities)} (${capabilityBits.joinToString(", ") { it.first }})")
            appendLine("device: ${environment.deviceLine()}")
            appendLine("board: ${environment.board}  hardware: ${environment.hardware}  soc: ${environment.soc}")
            appendLine("android: ${environment.androidLine()}")
            appendLine("abis: ${environment.abis}")
            append("fingerprint: ${environment.fingerprint}")
        }

    /**
     * Report a problem's link: the bug form with the version, the device and [asText] filled in, so the reporter
     * writes only what went wrong. The commit rides the version field, which the form labels "version / commit".
     */
    fun issueUrl(): String =
        CrashIssueUrl.forProblem(
            issuesBase = "$ISSUES_URL/new",
            version = listOfNotNull(environment.appLine(), commit?.let { "commit $it" }).joinToString(", "),
            devices = "${environment.deviceLine()}, ${environment.androidLine()}",
            facts = asText(),
        )
}

/** Reads this build's facts once; the About screen remembers the result for its lifetime. */
fun currentAboutBuildInfo(context: Context): AboutBuildInfo =
    AboutBuildInfo(
        environment = currentCrashEnvironment(),
        installSource = installSourceOf(installerPackage(context)),
        commit = BuildConfig.GIT_SHA.ifEmpty { null },
        flags = enabledBuildFlags(),
        capabilities = Protocol.LOCAL_CAPABILITIES or CALL_CAPABILITIES,
    )

/**
 * Every boolean feature flag `app/build.gradle.kts` writes into `BuildConfig`, in its order. Each is a `-P` switch
 * with a per-build-type default, so which ones a given APK has on is a fact a bug report wants. A new flag is a
 * line here too.
 */
internal val BUILD_FLAGS: List<Pair<String, Boolean>> =
    listOf(
        "INTERNET_PLANE" to BuildConfig.INTERNET_PLANE,
        "LORA_PLANE" to BuildConfig.LORA_PLANE,
        "COMMONS" to BuildConfig.COMMONS,
        "DEV_VIEW" to BuildConfig.DEV_VIEW,
        "SPOOL_DIRECTORY" to BuildConfig.SPOOL_DIRECTORY,
        "BLE_SIDE_PLANE" to BuildConfig.BLE_SIDE_PLANE,
        "BLE_GATT_PEERS" to BuildConfig.BLE_GATT_PEERS,
        "BLOB_QUERY" to BuildConfig.BLOB_QUERY,
        "BLE_CODED_PHY" to BuildConfig.BLE_CODED_PHY,
        "BLE_LINK_BOOST" to BuildConfig.BLE_LINK_BOOST,
        "BLE_FEED_WINDOW" to BuildConfig.BLE_FEED_WINDOW,
        "WEAR_STATUS" to BuildConfig.WEAR_STATUS,
        "LIVE_CALLS" to BuildConfig.LIVE_CALLS,
        "MESH_CALLS" to BuildConfig.MESH_CALLS,
        "STT" to BuildConfig.STT,
        "TRANSLATE" to BuildConfig.TRANSLATE,
        "KNIT_ANYWHERE" to BuildConfig.KNIT_ANYWHERE,
        "SEED_DEMO" to BuildConfig.SEED_DEMO,
        "DEMO_DIRECTOR" to BuildConfig.DEMO_DIRECTOR,
    )

/** The names of [flags] that are on, in their order. */
internal fun enabledBuildFlags(flags: List<Pair<String, Boolean>> = BUILD_FLAGS): List<String> = flags.filter { it.second }.map { it.first }

/**
 * Every capability bit `Protocol` defines, by its constant's name, lowest first. `Protocol`'s bits are append-only,
 * so a new bit is a line here too. Some are never this build's own (`CAP_DOORBELL` is the iPhone's); they are listed
 * so a mask always names every bit it holds.
 */
internal val CAPABILITY_BITS: List<Pair<String, Long>> =
    listOf(
        "CAP_E2E" to Protocol.CAP_E2E,
        "CAP_GROUPS" to Protocol.CAP_GROUPS,
        "CAP_REACTIONS" to Protocol.CAP_REACTIONS,
        "CAP_STORE_FORWARD" to Protocol.CAP_STORE_FORWARD,
        "CAP_RATCHET" to Protocol.CAP_RATCHET,
        "CAP_FAST_COMPACT" to Protocol.CAP_FAST_COMPACT,
        "CAP_INLINE_ACK" to Protocol.CAP_INLINE_ACK,
        "CAP_FRAME_TRANSCODE" to Protocol.CAP_FRAME_TRANSCODE,
        "CAP_CRYPTO_V3" to Protocol.CAP_CRYPTO_V3,
        "CAP_FILES" to Protocol.CAP_FILES,
        "CAP_LINK_PREVIEW" to Protocol.CAP_LINK_PREVIEW,
        "CAP_DIRECT_TRANSFER" to Protocol.CAP_DIRECT_TRANSFER,
        "CAP_DOORBELL" to Protocol.CAP_DOORBELL,
        "CAP_BLOB_HAVE" to Protocol.CAP_BLOB_HAVE,
        "CAP_CALL" to Protocol.CAP_CALL,
        "CAP_WALKIE" to Protocol.CAP_WALKIE,
    )

/** The bits [mask] holds with their names, lowest first; a bit with no name is named by its hex value. */
internal fun capabilityBits(mask: Long): List<Pair<String, Long>> =
    (0 until Long.SIZE_BITS)
        .map { 1L shl it }
        .filter { mask and it != 0L }
        .map { bit -> (CAPABILITY_BITS.firstOrNull { it.second == bit }?.first ?: hexBits(bit)) to bit }

/** A bit or a mask the way the HELLO and the logs print it: `0x` and lowercase hex. */
internal fun hexBits(value: Long): String = "0x${java.lang.Long.toHexString(value)}"
