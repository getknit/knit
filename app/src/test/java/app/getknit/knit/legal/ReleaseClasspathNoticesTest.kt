package app.getknit.knit.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lockfile is the truth about what ships: every `group:artifact` on `releaseRuntimeClasspath` must be
 * claimed by exactly one notice in [ThirdPartyNotices.ALL] or excused by [ThirdPartyNotices.NOT_SHIPPED].
 * This is what a license plugin would do at build time, done at test time instead — so a new dependency
 * fails here with its coordinate until it has a row (in the Kotlin list *and* `THIRD-PARTY-NOTICES.md`), and
 * a removed one fails until its stale row goes. Both directions are asserted: a notice that claims artifacts
 * must still match something, and so must every allowlist prefix. A [Packaging.TRANSLATE] notice's coordinate is on
 * the release runtime classpath only under `-Ptranslate=true`, which the lockfile is never written with, so its claim is
 * checked against `releaseCompileClasspath` instead (`compileOnly` in every build).
 */
class ReleaseClasspathNoticesTest {
    private val coordinates: List<String> by lazy { coordinatesOn(RELEASE_CONFIG) }
    private val compileCoordinates: List<String> by lazy { coordinatesOn(RELEASE_COMPILE_CONFIG) }

    private fun coordinatesOn(config: String): List<String> =
        repoFile("app/gradle.lockfile")
            .readLines()
            .mapNotNull { line -> LOCK_LINE.matchEntire(line) }
            .filter { m -> config in m.groupValues[4].split(',') }
            .map { m -> "${m.groupValues[1]}:${m.groupValues[2]}" }
            .distinct()

    @Test
    fun theLockfileHasAReleaseRuntimeClasspath() {
        assertTrue("no releaseRuntimeClasspath entries parsed — did the lockfile format change?", coordinates.size > 50)
    }

    @Test
    fun everyReleaseCoordinateHasExactlyOneNoticeOrIsExcused() {
        val orphans = mutableListOf<String>()
        val doubleBooked = mutableListOf<String>()
        coordinates.forEach { coordinate ->
            val notice = ThirdPartyNotices.claimant(coordinate)
            val excused = ThirdPartyNotices.NOT_SHIPPED.keys.filter { coordinate.startsWith(it) }
            when {
                notice == null && excused.isEmpty() -> orphans += coordinate
                notice != null && excused.isNotEmpty() -> doubleBooked += "$coordinate (${notice.key} and $excused)"
            }
        }
        assertEquals("release coordinates with no notice and no NOT_SHIPPED reason", emptyList<String>(), orphans)
        assertEquals("coordinates both claimed and excused", emptyList<String>(), doubleBooked)
    }

    @Test
    fun noTranslateOnlyComponentIsOnTheDefaultRuntime() {
        val packagedEverywhere =
            coordinates.mapNotNull { coordinate ->
                ThirdPartyNotices
                    .claimant(coordinate)
                    ?.takeIf { it.packaging == Packaging.TRANSLATE }
                    ?.let { "$coordinate (${it.key})" }
            }
        assertEquals("TRANSLATE notices on releaseRuntimeClasspath — packaged in every build", emptyList<String>(), packagedEverywhere)
    }

    @Test
    fun noTwoNoticesTieForACoordinate() {
        coordinates.forEach { coordinate ->
            val best =
                ThirdPartyNotices.ALL
                    .flatMap { n -> n.artifacts.filter(coordinate::startsWith).map { p -> p.length to n.key } }
                    .groupBy({ it.first }, { it.second })
                    .maxByOrNull { it.key }
                    ?.value ?: return@forEach
            assertEquals("'$coordinate' is claimed by equally long prefixes", 1, best.size)
        }
    }

    @Test
    fun everyClaimingNoticeStillMatchesSomething() {
        ThirdPartyNotices.ALL.filter { it.artifacts.isNotEmpty() }.forEach { notice ->
            val pool = if (notice.packaging == Packaging.ALWAYS) coordinates else compileCoordinates
            val claimed = pool.filter { ThirdPartyNotices.claimant(it) == notice }
            assertTrue("'${notice.key}' claims nothing on the release classpath — stale row?", claimed.isNotEmpty())
        }
    }

    @Test
    fun everyAllowlistPrefixStillMatchesSomething() {
        ThirdPartyNotices.NOT_SHIPPED.keys.forEach { prefix ->
            assertTrue("NOT_SHIPPED '$prefix' matches nothing — drop the line", coordinates.any { it.startsWith(prefix) })
        }
    }

    private companion object {
        // `group:artifact:version=configA,configB,...`; comment and `empty=` lines fall through the match.
        val LOCK_LINE = Regex("""^([^:#=\s]+):([^:=\s]+):([^=\s]+)=(.*)$""")
        const val RELEASE_CONFIG = "releaseRuntimeClasspath"
        const val RELEASE_COMPILE_CONFIG = "releaseCompileClasspath"
    }
}
