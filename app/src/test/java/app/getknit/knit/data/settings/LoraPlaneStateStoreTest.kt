package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.mesh.lora.LoraPlaneSnapshot
import app.getknit.knit.mesh.lora.ReprobeStamp
import app.getknit.knit.mesh.lora.SeenStamp
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The LoRa plane's limiters over the settings DataStore: nothing stored and an unreadable blob both read as no
 * snapshot, a snapshot round-trips whole, and one another build wrote with a field this one does not know — a newer
 * build's, or an older one's airtime ledger (ADR 2026-10.ug4a) — still loads what this build does know.
 */
class LoraPlaneStateStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newSettings(): SettingsStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    private val snapshot =
        LoraPlaneSnapshot(
            savedAtWall = 1_700_000_000_000L,
            autoReplied = listOf(SeenStamp("!0a0b0c0d", 1_699_999_100_000L)),
            reprobes = listOf(ReprobeStamp("d4e5f6", dueAtWall = 1_700_000_900_000L, gapMs = 1_800_000L, lastFrameId = "f1")),
        )

    @Test
    fun nothingStoredLoadsAsNoSnapshot() =
        runTest {
            assertNull(LoraPlaneStateStore(newSettings()).load())
        }

    @Test
    fun aSnapshotRoundTripsWhole() =
        runTest {
            val settings = newSettings()
            LoraPlaneStateStore(settings).save(snapshot)

            // A fresh store over the same settings: what is read back is what the DataStore holds, not a cached copy.
            assertEquals(snapshot, LoraPlaneStateStore(settings).load())
        }

    @Test
    fun anUnreadableBlobLoadsAsNoSnapshot() =
        runTest {
            val settings = newSettings()
            // A write cut short: the start of a valid snapshot's JSON, no more.
            settings.setLoraPlaneState("""{"savedAtWall":1700000000000,"reprobes":[{"id":""")

            assertNull(LoraPlaneStateStore(settings).load())
        }

    @Test
    fun aSnapshotANewerBuildWroteStillLoadsWhatThisOneKnows() =
        runTest {
            val settings = newSettings()
            settings.setLoraPlaneState(
                """{"savedAtWall":1700000000000,"autoReplied":[{"id":"!0a0b0c0d","atWall":1699999100000}],""" +
                    """"meshCoreWindows":[{"id":7,"spent":3}]}""",
            )

            assertEquals(
                LoraPlaneSnapshot(
                    savedAtWall = 1_700_000_000_000L,
                    autoReplied = listOf(SeenStamp("!0a0b0c0d", 1_699_999_100_000L)),
                ),
                LoraPlaneStateStore(settings).load(),
            )
        }

    /**
     * The bridge's limiters left the snapshot with the bridge (ADR 2026-10.rp3s), and the ledger left it with ADR
     * 2026-10.ug4a: a blob the bridge build wrote, with its ledger, beacon floor, serve windows and profile re-fan stamps,
     * must still hand back every limiter this build keeps — the auto-reply memory. The probe windows and re-offer hours
     * went with the reach gate (ADR 2026-10.x9fa) and are ignored the same way.
     */
    @Test
    fun aSnapshotTheBridgeBuildWroteKeepsEveryLimiterThisBuildStillHas() =
        runTest {
            val settings = newSettings()
            settings.setLoraPlaneState(
                """{"savedAtWall":1700000000000,""" +
                    """"air":[{"atWall":1699999990000,"ms":412,"bucket":"BRIDGE"},{"atWall":1699999991000,"ms":90,"bucket":"LIVE"}],""" +
                    """"selfProfileAtWall":1699999000000,""" +
                    """"serve":[{"publisher":42,"startWall":1699999500000,"spent":3}],""" +
                    """"profileSeen":[{"id":"profile-a-1","atWall":1699998000000}],""" +
                    """"autoReplied":[{"id":"!0a0b0c0d","atWall":1699999100000}],""" +
                    """"dmProbes":[{"id":"bob","atWall":1699999200000}],""" +
                    """"reoffered":[{"id":"f1","atWall":1699999300000}]}""",
            )

            assertEquals(
                LoraPlaneSnapshot(
                    savedAtWall = 1_700_000_000_000L,
                    autoReplied = listOf(SeenStamp("!0a0b0c0d", 1_699_999_100_000L)),
                ),
                LoraPlaneStateStore(settings).load(),
            )
        }

    /**
     * A blob the reach-gate build wrote (ADR 2026-10.a4pm) carries the probe windows and the re-offer hours this build no
     * longer keeps (ADR 2026-10.x9fa): it still loads, and the re-probe's schedule — kept, under its own keys — comes
     * back whole.
     */
    @Test
    fun aSnapshotTheReachGateBuildWroteKeepsItsReprobeSchedule() =
        runTest {
            val settings = newSettings()
            settings.setLoraPlaneState(
                """{"savedAtWall":1700000000000,"air":[],""" +
                    """"dmProbes":[{"id":"bob","atWall":1699999200000}],""" +
                    """"reoffered":[{"id":"f1","atWall":1699999300000}],""" +
                    """"reprobes":[{"id":"bob","dueAtWall":1700000900000,"gapMs":1800000,"lastFrameId":"f1"}]}""",
            )
            assertEquals(
                LoraPlaneSnapshot(
                    savedAtWall = 1_700_000_000_000L,
                    reprobes = listOf(ReprobeStamp("bob", dueAtWall = 1_700_000_900_000L, gapMs = 1_800_000L, lastFrameId = "f1")),
                ),
                LoraPlaneStateStore(settings).load(),
            )
        }

    /**
     * The blob the compact-DM build wrote (ADR 2026-10.x9fa), its ledger under `air` beside the two limiters this build
     * keeps: it loads, the ledger is ignored — the window starts empty (ADR 2026-10.ug4a) — and the auto-reply memory and
     * the re-probe schedule come back whole, under the same DataStore key.
     */
    @Test
    fun aSnapshotWithALedgerStillLoadsAndTheLedgerIsLeftBehind() =
        runTest {
            val settings = newSettings()
            settings.setLoraPlaneState(
                """{"savedAtWall":1700000000000,""" +
                    """"air":[{"atWall":1699999990000,"ms":412,"bucket":"LIVE"},{"atWall":1699999991000,"ms":90,"bucket":"PUBLIC"}],""" +
                    """"autoReplied":[{"id":"!0a0b0c0d","atWall":1699999100000}],""" +
                    """"reprobes":[{"id":"bob","dueAtWall":1700000900000,"gapMs":1800000,"lastFrameId":"f1"}]}""",
            )
            assertEquals(
                LoraPlaneSnapshot(
                    savedAtWall = 1_700_000_000_000L,
                    autoReplied = listOf(SeenStamp("!0a0b0c0d", 1_699_999_100_000L)),
                    reprobes = listOf(ReprobeStamp("bob", dueAtWall = 1_700_000_900_000L, gapMs = 1_800_000L, lastFrameId = "f1")),
                ),
                LoraPlaneStateStore(settings).load(),
            )
        }
}
