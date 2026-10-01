package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.BuildConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The LoRa board's settings over a real Preferences DataStore (the [SettingsStoreTest] rig). What is pinned is
 * the binding's lifecycle: a new board forgets the old one's node and key (or the profile would advertise a
 * board this phone no longer holds), the board's report is one edit, the setup record keeps the user's own
 * board settings for the restore, and forgetting the board clears all of it and turns the plane off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreLoraTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newStore(): SettingsStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    private val setup =
        KnitBoardSetup(
            address = "AA:BB:CC:DD:EE:FF",
            nodeInfoSecs = 900,
            positionSecs = 1800,
            smartPosition = true,
            telemetrySecs = 3600,
            rebroadcastMode = 2,
            longName = "Base camp",
            shortName = "BC",
            channelNum = 20,
        )

    @Test
    fun thePlaneTogglesDefaultOffForThePlaneAndOnForItsParts() =
        runTest {
            val store = newStore()
            assertFalse(store.loraEnabled.first())
            assertEquals(BuildConfig.LORA_PLANE, store.loraDmEnabled.first())
            assertEquals(BuildConfig.LORA_PLANE, store.loraBridgeEnabled.first())
            assertEquals(BuildConfig.LORA_PLANE, store.loraRoomEnabled.first())

            store.setLoraEnabled(true)
            store.setLoraDmEnabled(false)
            store.setLoraBridgeEnabled(false)
            store.setLoraRoomEnabled(false)
            assertEquals(BuildConfig.LORA_PLANE, store.loraEnabled.first())
            assertFalse(store.loraDmEnabled.first())
            assertFalse(store.loraBridgeEnabled.first())
            assertFalse(store.loraRoomEnabled.first())
        }

    @Test
    fun aNewBoardForgetsTheOldBoardsNodeAndKey() =
        runTest {
            val store = newStore()
            store.setLoraDevice("11:11:11:11:11:11", "Old board")
            store.setLoraBoard(node = 0x1234L, key = "old-key")
            assertEquals(LoraBoard(0x1234L, "old-key"), store.loraBoard.first())

            store.setLoraDevice("22:22:22:22:22:22", "New board")

            assertEquals("22:22:22:22:22:22", store.loraDeviceAddress.first())
            assertEquals("New board", store.loraDeviceName.first())
            assertNull("the old board's node must not be advertised for the new one", store.loraBoard.first())
        }

    @Test
    fun aBoardThatStopsSigningDropsItsKey() =
        runTest {
            val store = newStore()
            store.setLoraBoard(node = 7L, key = "signing-key")
            store.setLoraBoard(node = 7L, key = null)
            assertEquals(LoraBoard(7L, null), store.loraBoard.first())
        }

    @Test
    fun theSetupRecordRoundTripsAndClears() =
        runTest {
            val store = newStore()
            assertNull(store.loraBoardSetup.first())
            store.setLoraBoardSetup(setup)
            assertEquals(setup, store.loraBoardSetup.first())
            store.clearLoraBoardSetup()
            assertNull(store.loraBoardSetup.first())
        }

    @Test
    fun forgettingTheBoardClearsTheBindingTheSetupAndThePlane() =
        runTest {
            val store = newStore()
            store.setLoraEnabled(true)
            store.setLoraDevice(setup.address, "Base camp")
            store.setLoraBoard(node = 99L, key = "k")
            store.setLoraBoardSetup(setup)

            store.clearLoraDevice()

            assertNull(store.loraDeviceAddress.first())
            assertNull(store.loraDeviceName.first())
            assertNull(store.loraBoard.first())
            assertNull("a restore must never be offered for a forgotten board", store.loraBoardSetup.first())
            assertFalse(store.loraEnabled.first())
        }

    @Test
    fun thePlaneSnapshotSurvivesAsWritten() =
        runTest {
            val store = newStore()
            assertNull(store.loraPlaneState())
            store.setLoraPlaneState("""{"tokens":3}""")
            assertEquals("""{"tokens":3}""", store.loraPlaneState())
            assertEquals(0, store.loraChannelIndex.first())
            store.setLoraChannelIndex(2)
            assertEquals(2, store.loraChannelIndex.first())
        }

    @Test
    fun theNanInitiatorLatchAndGiveUpStampPersist() =
        runTest {
            val store = newStore()
            assertEquals(NanInitiatorLatch(stamp = "", probedAt = 0L), store.initiatorLatch())
            assertEquals("", store.awareGiveUpStamp())
            store.setInitiatorLatch(NanInitiatorLatch(stamp = "build+rom", probedAt = 1_700_000_000_000L))
            store.setAwareGiveUpStamp("build+rom")
            assertEquals(NanInitiatorLatch(stamp = "build+rom", probedAt = 1_700_000_000_000L), store.initiatorLatch())
            assertEquals("build+rom", store.awareGiveUpStamp())
        }

    @Test
    fun theOneTimeConsentsAndDismissalsStick() =
        runTest {
            val store = newStore()
            assertFalse(store.meshtasticPostConsented.first())
            assertFalse(store.locationShareConsented.first())
            assertFalse(store.directTransferConsented.first())
            assertFalse(store.relayRoomNoticeDismissed.first())
            assertEquals(0L, store.cloneDismissedAt.first())

            store.acceptMeshtasticPostConsent()
            store.acceptLocationShareConsent()
            store.acceptDirectTransferConsent()
            store.dismissRelayRoomNotice()
            store.setCloneDismissedAt(42L)

            assertEquals(true, store.meshtasticPostConsented.first())
            assertEquals(true, store.locationShareConsented.first())
            assertEquals(true, store.directTransferConsented.first())
            assertEquals(true, store.relayRoomNoticeDismissed.first())
            assertEquals(42L, store.cloneDismissedAt.first())
        }
}
