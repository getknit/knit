package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Forgetting a MeshCore board in the settings (ADR 2026-11.gsqu's Stage 7 amendment): the whole binding and the plane switch in
 * one edit, or the claim alone with the binding kept; each only while the address is still the bound one; never Meshtastic's
 * keys, never the other switches.
 */
class MeshcoreForgetPolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val loraEnabled = booleanPreferencesKey("lora_enabled")
    private val loraDmEnabled = booleanPreferencesKey("lora_dm_enabled")
    private val meshcoreAddress = stringPreferencesKey("meshcore_device_address")
    private val meshcoreName = stringPreferencesKey("meshcore_device_name")
    private val meshcoreKey = stringPreferencesKey("meshcore_board_pubkey")
    private val meshcoreBind = stringPreferencesKey("meshcore_board_bind")
    private val meshtasticAddress = stringPreferencesKey("lora_device_address")

    private fun bound() =
        mutablePreferencesOf(
            meshcoreAddress to "CC:CC",
            meshcoreName to "stale",
            meshcoreKey to "key",
            meshcoreBind to "sig",
            loraEnabled to true,
            loraDmEnabled to false,
        )

    @Test
    fun aForgetClearsTheWholeBindingAndSwitchesThePlaneOff() {
        val prefs = bound()
        assertTrue(MeshcoreForgetPolicy.forget(prefs, "CC:CC", loraEnabled))
        // The literal keys, not the policy's own list: a key dropped from that list must still go red here.
        for (key in listOf(meshcoreAddress, meshcoreName, meshcoreKey, meshcoreBind)) assertNull(key.name, prefs[key])
        assertEquals(false, prefs[loraEnabled])
        assertEquals("the DM switch is the user's", false, prefs[loraDmEnabled])
    }

    @Test
    fun aWithdrawalTakesTheClaimAndKeepsTheBinding() {
        val prefs = bound()
        assertTrue(MeshcoreForgetPolicy.withdrawClaim(prefs, "CC:CC"))
        assertNull(prefs[meshcoreKey])
        assertNull(prefs[meshcoreBind])
        assertEquals("CC:CC", prefs[meshcoreAddress])
        assertEquals(true, prefs[loraEnabled])
    }

    @Test
    fun neitherTouchesABoardBoundSince() {
        val prefs = bound().apply { this[meshtasticAddress] = "AA:AA" }
        prefs[meshcoreAddress] = "DD:DD"
        assertFalse(MeshcoreForgetPolicy.forget(prefs, "CC:CC", loraEnabled))
        assertFalse(MeshcoreForgetPolicy.withdrawClaim(prefs, "CC:CC"))
        assertEquals("DD:DD", prefs[meshcoreAddress])
        assertEquals("key", prefs[meshcoreKey])
        assertEquals(true, prefs[loraEnabled])
        assertEquals("AA:AA", prefs[meshtasticAddress])
    }

    @Test
    fun theStoreAppliesEachAsOneEdit() =
        runTest {
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }
            val store = SettingsStore(dataStore)
            val claims = MeshcoreClaimStore(dataStore)
            store.bindBoard(app.getknit.knit.mesh.lora.BoardKind.MESHCORE, "CC:CC", name = "")
            store.setLoraEnabled(true)
            claims.setBoardKey("a-published-key", "its-bind")
            store.withdrawMeshcoreClaim("CC:CC")
            assertNull("the claim is withdrawn", claims.boardKey.first())
            assertNull(claims.board.first().sig)
            assertEquals("CC:CC", store.meshcoreDeviceAddress.first())
            store.forgetMeshcoreBoard("CC:CC")
            assertNull(store.meshcoreDeviceAddress.first())
            assertFalse(store.loraEnabled.first())
        }
}
