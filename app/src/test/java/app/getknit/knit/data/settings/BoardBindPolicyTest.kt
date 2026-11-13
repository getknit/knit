package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.mesh.lora.BoardKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cross-kind bind (ADR 2026-11.gsqu, Stage 2: v1 binds one board at a time). Binding one kind clears the other's
 * identity — what feeds the signed profile — in the same edit, never the shared switches, and keeps the other's setup
 * record so that board can still be restored (ADR 045). The backup split is `SettingsSnapshotTest`'s.
 */
class BoardBindPolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    private fun TestScope.newStore(): SettingsStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    private val loraEnabled = booleanPreferencesKey("lora_enabled")
    private val loraDmEnabled = booleanPreferencesKey("lora_dm_enabled")
    private val loraSetup = stringPreferencesKey("lora_setup_address")
    private val loraPrior = intPreferencesKey("lora_prior_node_info_secs")
    private val meshcoreSetup = stringPreferencesKey(SettingsKeys.MESHCORE_SETUP_ADDRESS)

    @Test
    fun bindingMeshcoreClearsMeshtasticsIdentityAndNothingShared() {
        val prefs =
            mutablePreferencesOf(
                BoardKeys.MESHTASTIC.address to "AA:AA",
                BoardKeys.MESHTASTIC.name to "Meshtastic board",
                BoardKeys.LORA_NODE to 0x1234L,
                BoardKeys.LORA_KEY to "board-key",
                loraEnabled to true,
                loraDmEnabled to false,
                loraSetup to "AA:AA",
                loraPrior to 900,
            )

        BoardBindPolicy.bind(prefs, BoardKind.MESHCORE, address = "BB:BB", name = "MeshCore board")

        // The literal keys, not the policy's own list: a key dropped from that list must still go red here.
        assertNull(prefs[stringPreferencesKey("lora_device_address")])
        assertNull(prefs[stringPreferencesKey("lora_device_name")])
        assertNull(prefs[longPreferencesKey("lora_board_node")])
        assertNull(prefs[stringPreferencesKey("lora_board_key")])
        assertEquals("BB:BB", prefs[BoardKeys.MESHCORE.address])
        // Before setup renames it a MeshCore board's name is its user's own advert name: none is kept in plaintext until
        // Stage 5b's SQLCipher record (the security design pass), and nothing is written under `meshcore_setup_*` /
        // `meshcore_prior_*`.
        assertNull(prefs[BoardKeys.MESHCORE.name])
        assertTrue(prefs.asMap().keys.none { it.name.startsWith("meshcore_setup_") || it.name.startsWith("meshcore_prior_") })
        assertEquals("the shared switch is never touched", true, prefs[loraEnabled])
        assertEquals("nor the DM switch", false, prefs[loraDmEnabled])
        assertEquals("the Meshtastic board can still be restored", "AA:AA", prefs[loraSetup])
        assertEquals(900, prefs[loraPrior])
    }

    @Test
    fun bindingMeshtasticClearsMeshcoresIdentityAndKeepsItsSetupRecord() {
        val prefs =
            mutablePreferencesOf(
                BoardKeys.MESHCORE.address to "BB:BB",
                BoardKeys.MESHCORE.name to "MeshCore board",
                BoardKeys.MESHCORE_PUBKEY to "pubkey",
                meshcoreSetup to "BB:BB",
                BoardKeys.LORA_NODE to 7L,
            )

        BoardBindPolicy.bind(prefs, BoardKind.MESHTASTIC, address = "AA:AA", name = "New board")

        assertNull(prefs[stringPreferencesKey("meshcore_device_address")])
        assertNull(prefs[stringPreferencesKey("meshcore_device_name")])
        assertNull(prefs[stringPreferencesKey("meshcore_board_pubkey")])
        assertEquals("BB:BB", prefs[meshcoreSetup])
        assertEquals("AA:AA", prefs[BoardKeys.MESHTASTIC.address])
        assertNull("a new board's node is not the old one's", prefs[BoardKeys.LORA_NODE])
    }

    /** Over the store: the profile's board reads null after a MeshCore bind, and the switches and the setup stay. */
    @Test
    fun aMeshcoreBindLeavesTheProfileNoMeshtasticBoardToAdvertise() =
        runTest {
            val store = newStore()
            val setup = KnitBoardSetup("AA:AA", 900, 1800, true, 3600, 2, "Base camp", "BC", 20)
            store.setLoraEnabled(true)
            store.setLoraDmEnabled(true)
            store.setLoraDevice("AA:AA", "Meshtastic board")
            store.setLoraBoard(node = 0x1234L, key = "board-key")
            store.setLoraBoardSetup(setup)
            assertEquals(BoardKind.MESHTASTIC, store.boundBoardKind.first())

            store.bindBoard(BoardKind.MESHCORE, "BB:BB", "MeshCore board")

            assertNull("loraNode / loraKey are not advertised", store.loraBoard.first())
            assertNull(store.loraDeviceAddress.first())
            assertNull(store.loraDeviceName.first())
            assertEquals("BB:BB", store.meshcoreDeviceAddress.first())
            assertEquals(BoardKind.MESHCORE, store.boundBoardKind.first())
            assertTrue("the plane the user just bound stays on", store.loraEnabled.first())
            assertTrue(store.loraDmEnabled.first())
            assertEquals("the setup record survives", setup, store.loraBoardSetup.first())

            store.setLoraDevice("AA:AA", "Meshtastic board")
            assertNull(store.meshcoreDeviceAddress.first())
            assertEquals(BoardKind.MESHTASTIC, store.boundBoardKind.first())
        }

    /**
     * A Meshtastic handshake that finishes after the board was unbound writes nothing back: the dialled address is
     * compared inside the edit, so the profile never re-advertises a board this phone no longer reads.
     */
    @Test
    fun aLateBoardReportAfterAnUnbindWritesNothingBack() =
        runTest {
            val store = newStore()
            store.setLoraDevice("AA:AA", "Meshtastic board")
            store.setLoraBoard(node = 0x1234L, key = "board-key", dialled = "AA:AA")
            assertEquals(LoraBoard(0x1234L, "board-key"), store.loraBoard.first())

            store.bindBoard(BoardKind.MESHCORE, "BB:BB", "MeshCore board")
            store.setLoraBoard(node = 0x1234L, key = "board-key", dialled = "AA:AA")
            assertNull("the cross-kind bind holds", store.loraBoard.first())

            store.setLoraDevice("CC:CC", "Another board")
            store.setLoraBoard(node = 0x1234L, key = "board-key", dialled = "AA:AA")
            assertNull("the replaced board's late report is refused", store.loraBoard.first())
            store.setLoraBoard(node = 0x5678L, key = null, dialled = "CC:CC")
            assertEquals("the bound board's own report lands", LoraBoard(0x5678L, null), store.loraBoard.first())

            store.clearLoraDevice()
            store.setLoraBoard(node = 0x5678L, key = null, dialled = "CC:CC")
            assertNull("and so after forgetting it", store.loraBoard.first())
        }

    /** One plane-state blob per instance: Meshtastic's under `lora_plane_state` as before, MeshCore's apart. */
    @Test
    fun eachKindsPlaneStateIsItsOwnBlob() =
        runTest {
            val store = newStore()
            store.setPlaneState(BoardKind.MESHTASTIC, "m")
            store.setPlaneState(BoardKind.MESHCORE, "c")
            assertEquals("m", store.loraPlaneState())
            assertEquals("m", store.planeState(BoardKind.MESHTASTIC))
            assertEquals("c", store.planeState(BoardKind.MESHCORE))
            assertEquals("lora_plane_state", BoardKeys.MESHTASTIC.planeState.name)
            assertEquals("meshcore_plane_state", BoardKeys.MESHCORE.planeState.name)
        }
}
