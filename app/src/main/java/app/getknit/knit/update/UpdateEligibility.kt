package app.getknit.knit.update

/**
 * One Knit build as a peer update reads it (ADR 2026-11.29m7): this phone's own, or the one a contact announced.
 * [certPin] is the first eight bytes of the signing certificate's SHA-256, big-endian; [size] the APK's length.
 */
data class AppBuild(
    val versionCode: Long,
    val versionName: String,
    val certPin: Long,
    val size: Long,
    val minSdk: Int,
    val slim: Boolean,
)

/**
 * What an APK says about itself, read by the platform's archive parser (`AndroidAppUpdateFiles`). [signers] are the
 * SHA-256 digests (lowercase hex) of every certificate the archive proves it may sign with: its current signers and,
 * across a key rotation, their lineage.
 */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: Set<String>,
)

/**
 * The version-name grammar `app/build.gradle.kts` derives versionCode from (ADR 2026-11.7t9z): `X.Y.Z`, optionally
 * `-(alpha|beta|rc).N`, then `-slim` for the slim track. Pure, so a contact's announced name is checked against the
 * code it claims rather than shown as sent.
 */
object VersionNames {
    const val SLIM_SUFFIX = "-slim"

    /** The stage digit of a stable build; alpha 0, beta 1, rc 2. */
    const val STABLE_STAGE = 9L

    private val GRAMMAR = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{1,2})(?:-(alpha|beta|rc)\.(\d{1,2}))?""")
    private val STAGES = mapOf("alpha" to 0L, "beta" to 1L, "rc" to 2L)

    /** The versionCode [name] derives, or null when it is not a name this grammar makes. */
    fun codeOf(name: String): Long? {
        val m = GRAMMAR.matchEntire(name.removeSuffix(SLIM_SUFFIX)) ?: return null
        val (major, minor, patch) = (1..3).map { m.groupValues[it].toLong() }
        val stage = STAGES[m.groupValues[4]] ?: STABLE_STAGE
        val n = m.groupValues[5].toLongOrNull() ?: 0L
        return major * MAJOR + minor * MINOR + patch * PATCH + stage * STAGE + n
    }

    /** The name [versionCode] derives from, without a track: null for a code this grammar never makes. */
    fun nameOf(versionCode: Long): String? {
        if (versionCode <= 0L) return null
        val major = versionCode / MAJOR
        val minor = versionCode / MINOR % FIELD
        val patch = versionCode / PATCH % FIELD
        val stage = versionCode / STAGE % STAGES_PER_DIGIT
        val n = versionCode % FIELD
        val base = "$major.$minor.$patch"
        val name =
            when (stage) {
                STABLE_STAGE -> base.takeIf { n == 0L }
                else -> STAGES.entries.firstOrNull { it.value == stage }?.let { "$base-${it.key}.$n" }
            }
        return name?.takeIf { major <= MAX_FIELD && codeOf(it) == versionCode }
    }

    private const val MAX_FIELD = 99L

    // The weights app/build.gradle.kts derives versionCode with (ADR 2026-11.7t9z): two digits a field, one the stage.
    private const val FIELD = 100L
    private const val STAGES_PER_DIGIT = 10L
    private const val STAGE = FIELD
    private const val PATCH = STAGE * STAGES_PER_DIGIT
    private const val MINOR = PATCH * FIELD
    private const val MAJOR = MINOR * FIELD

    fun isSlim(name: String): Boolean = name.endsWith(SLIM_SUFFIX)

    fun isStable(versionCode: Long): Boolean = versionCode / STAGE % STAGES_PER_DIGIT == STABLE_STAGE
}

/**
 * Whether one build may replace another over a peer update (ADR 2026-11.29m7). Pure and JVM-tested
 * (`UpdateEligibilityTest`). The platform refuses another signer and any versionCode at or below the installed one by
 * itself; these rules refuse those early, with a reason, and add the ones the platform does not know: a stable phone
 * never takes a pre-release, a slim phone never takes the full build or the reverse.
 */
object UpdateEligibility {
    /** A sanity bound on an announced or offered APK, not a promise about storage; a full build is about 124 MB. */
    const val MAX_APK_BYTES = 1L shl 30

    /**
     * Whether a phone running [mine] should offer to ask for [theirs]: newer, signed by the same certificate, on the
     * same track, stable when [mine] is stable, installable on API [sdkInt], and of a plausible size.
     */
    fun worthAsking(
        mine: AppBuild,
        theirs: AppBuild,
        sdkInt: Int,
    ): Boolean =
        theirs.versionCode > mine.versionCode &&
            theirs.certPin == mine.certPin &&
            theirs.slim == mine.slim &&
            (!VersionNames.isStable(mine.versionCode) || VersionNames.isStable(theirs.versionCode)) &&
            theirs.minSdk <= sdkInt &&
            theirs.size in 1..MAX_APK_BYTES

    /**
     * Why the archive [facts] may not replace the installed build, or null when it may. [ownPackage] is this app's
     * id, [installed] this phone's build, [installedSigners] the digests of the certificates it is signed with now, and
     * [expectedVersionCode] the version the request asked for and the offer pinned. Only a reason, never the archive's
     * own text, so a caller can log it.
     */
    fun archiveRefusal(
        facts: ArchiveFacts,
        ownPackage: String,
        installed: AppBuild,
        installedSigners: Set<String>,
        expectedVersionCode: Long,
    ): String? {
        val name = facts.versionName
        return when {
            facts.packageName != ownPackage -> "another package"
            facts.versionCode != expectedVersionCode -> "not the version asked for"
            facts.versionCode <= installed.versionCode -> "not newer than the installed build"
            name == null || VersionNames.codeOf(name) != facts.versionCode -> "a version name that does not derive its code"
            VersionNames.isSlim(name) != installed.slim -> "the other track"
            VersionNames.isStable(installed.versionCode) && !VersionNames.isStable(facts.versionCode) -> "a pre-release"
            installedSigners.isEmpty() || !facts.signers.containsAll(installedSigners) -> "another signer"
            else -> null
        }
    }
}
