package app.getknit.knit.mesh.spool

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.settings.HomeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * When the home refresher reads the directory and what a read leaves (ADR 2026-10.8kfx): never with the plane off,
 * at most daily, an hour after a failure, and the home settled after every read. Over the real [HomeStore], so the
 * settle rule is the phone's own.
 */
class HomeRefresherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_800_000_000_000L

    private fun TestScope.homes() =
        HomeStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") })

    private val planeOn = MutableStateFlow(true)
    private var online = true
    private val reads = mutableListOf<Long>()
    private var answer: () -> DirectoryRead = { DirectoryRead.Failed("unscripted") }
    private val changes = mutableListOf<Pair<String?, String>>()

    private fun refresher(
        homes: HomeStore,
        source: PublicSpoolSource? =
            PublicSpoolSource {
                reads += now
                answer()
            },
    ) = HomeRefresher(
        journal = homes,
        source = source,
        selfId = { NODE },
        planeOn = planeOn as StateFlow<Boolean>,
        online = { online },
        onHomeChanged = { previous, current -> changes += previous to current },
        clock = { now },
    )

    private val threeOpen =
        """{"v":1,"spools":[{"host":"public-1.spool.getknit.app","state":"open","weight":1},
           {"host":"public-2.spool.getknit.app","state":"open","weight":3},
           {"host":"public-3.spool.getknit.app","state":"open","weight":5}]}"""

    @Test
    fun theHomeIsSettledWithNoNetworkAndNothingIsReadWithThePlaneOff() =
        runTest {
            planeOn.value = false
            assertNull(refresher(homes()).runOnce())
            assertEquals(listOf(null to PublicSpools.LEGACY_HOST), changes)
            assertEquals(emptyList<Long>(), reads)
        }

    @Test
    fun aBuildWithNoDirectoryReadsNothing() =
        runTest {
            val homes = homes()
            assertNull(refresher(homes, source = null).runOnce())
            assertEquals(PublicSpools.LEGACY_HOST, homes.home.first())
        }

    @Test
    fun aReadIsDailyAndSettlesTheHomeAgain() =
        runTest {
            answer = { DirectoryRead.Document(threeOpen) }
            val homes = homes()
            val r = refresher(homes)
            assertEquals(now + HomeRefresher.READ_INTERVAL_MS, r.runOnce())
            assertEquals(1, reads.size)
            // The fresh install settled on the shipped copy, then on the directory just read.
            assertEquals(listOf(null to PublicSpools.LEGACY_HOST), changes)
            assertEquals(PublicSpools.LEGACY_HOST, homes.home.first())
            now += 60_000
            assertEquals(now - 60_000 + HomeRefresher.READ_INTERVAL_MS, r.runOnce())
            assertEquals(1, reads.size)
        }

    @Test
    fun aDrainingHomeIsLeftOnTheNextRead() =
        runTest {
            val homes = homes()
            homes.putDirectory(PublicSpools.parse(threeOpen)!!, at = now)
            assertEquals(null to "public-3.spool.getknit.app", homes.settle(NODE))
            now += HomeRefresher.READ_INTERVAL_MS
            answer = { DirectoryRead.Document(threeOpen.replace("\"open\",\"weight\":5", "\"draining\",\"weight\":5")) }
            refresher(homes).runOnce()
            assertEquals(listOf("public-3.spool.getknit.app" to "public-2.spool.getknit.app"), changes)
        }

    @Test
    fun aFailedReadIsTriedAgainAfterAnHourAndOfflineWaits() =
        runTest {
            online = false
            val homes = homes()
            val r = refresher(homes)
            assertEquals(now + HomeRefresher.MAX_SLEEP_MS, r.runOnce())
            assertEquals(emptyList<Long>(), reads)
            online = true
            answer = { DirectoryRead.Failed("http 503") }
            assertEquals(now + HomeRefresher.RETRY_MS, r.runOnce())
            assertEquals(now + HomeRefresher.RETRY_MS, r.runOnce())
            assertEquals(1, reads.size)
            now += HomeRefresher.RETRY_MS
            answer = { DirectoryRead.Document("""{"v":9}""") }
            // A schema this build cannot read counts as a read: the held copy stays, and the next is a day off.
            assertEquals(now + HomeRefresher.READ_INTERVAL_MS, r.runOnce())
            assertEquals(2, reads.size)
            assertEquals(now, homes.directoryReadAt())
            assertEquals(PublicSpools.BAKED, homes.directory.first())
        }

    private companion object {
        const val NODE = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
