package app.getknit.knit.data.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.getknit.knit.data.settings.SettingsKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SettingsSnapshotTest {
    private val name = stringPreferencesKey("display_name")
    private val blocked = stringSetPreferencesKey("blocked_node_ids")
    private val version = longPreferencesKey("profile_version")
    private val spoolUrls = stringSetPreferencesKey("spool_urls")

    @Test
    fun carriesTheUsersKeysAndDropsThePhonesOwn() =
        runTest {
            val prefs =
                preferencesOf(
                    name to "Ada",
                    blocked to setOf("x"),
                    version to 7L,
                    spoolUrls to setOf("wss://relay.example/spool/v1?k=token"),
                    stringPreferencesKey("aware_give_up_stamp") to "rom-stamp",
                    stringPreferencesKey("nan_initiator_hold_stamp") to "s",
                    longPreferencesKey("nan_initiator_hold_probed_at") to 1L,
                    stringPreferencesKey("model_load_stamp_toxicity") to "m",
                    stringPreferencesKey("lora_device_address") to "AA:BB",
                    stringPreferencesKey("lora_prior_long_name") to "board",
                    stringPreferencesKey("lora_plane_state") to "{}",
                    longPreferencesKey("clone_seen_at") to 5L,
                    longPreferencesKey("clone_dismissed_at") to 3L,
                    longPreferencesKey("mesh_pause_until") to 9L,
                    intPreferencesKey("debug_ble_link_cap") to 2,
                    booleanPreferencesKey("debug_nan_off") to true,
                    // ADR 2026-10.d44b: low-power mode is about this phone's battery.
                    booleanPreferencesKey("low_power_mode") to true,
                    stringPreferencesKey("spool_ticket:relay.example") to "knit-ticket:v1:…",
                    stringPreferencesKey("spool_ticket_verdict:relay.example") to "retry:early",
                    // ADR 2026-10.3sk7: the customer key rides, as the relay's token does; this phone's ask schedule does not.
                    stringPreferencesKey("spool_key:relay.example") to "{\"i\":\"https://anywhere.example\",\"k\":\"kak1_…\"}",
                    stringPreferencesKey("spool_key_state:relay.example") to "{\"a\":\"ok\",\"nb\":1}",
                    longPreferencesKey("lora_board_node") to 42L,
                    booleanPreferencesKey("lora_enabled") to true,
                )
            val out = ByteArrayOutputStream()
            SettingsSnapshot.export(prefs, out)
            val restored = SettingsSnapshot.forRestore(ByteArrayInputStream(out.toByteArray()))
            assertEquals("Ada", restored[name])
            assertEquals(setOf("x"), restored[blocked])
            assertEquals(7L, restored[version])
            assertEquals(setOf("wss://relay.example/spool/v1?k=token"), restored[spoolUrls])
            assertEquals(42L, restored[longPreferencesKey("lora_board_node")])
            assertTrue(restored[stringPreferencesKey("spool_key:relay.example")]!!.contains("kak1_"))
            assertTrue(restored[booleanPreferencesKey("lora_enabled")]!!)
            for (dropped in listOf(
                "aware_give_up_stamp",
                "nan_initiator_hold_stamp",
                "nan_initiator_hold_probed_at",
                "model_load_stamp_toxicity",
                "lora_device_address",
                "lora_prior_long_name",
                "lora_plane_state",
                "clone_seen_at",
                "clone_dismissed_at",
                "mesh_pause_until",
                "debug_ble_link_cap",
                "debug_nan_off",
                "spool_ticket:relay.example",
                "spool_ticket_verdict:relay.example",
                "spool_key_state:relay.example",
                "low_power_mode",
            )) {
                assertFalse(dropped, restored.asMap().keys.any { it.name == dropped })
            }
            assertTrue(restored[booleanPreferencesKey(SettingsKeys.ONBOARDING_SEEN)]!!)
            assertTrue(restored[booleanPreferencesKey(SettingsKeys.RESTORE_PENDING)]!!)
        }

    /** The backup split: this phone's binding, setup record and limiters stay; the key and the person's choices ride. */
    @Test
    fun meshcoresKeysSplitAcrossABackupAsTheirLoraPeersDo() {
        for (stays in listOf(
            "meshcore_device_address",
            "meshcore_device_name",
            "meshcore_setup_address",
            "meshcore_setup_anything",
            "meshcore_prior_channel",
            "meshcore_plane_state",
            "lora_device_address",
            "lora_device_name",
            "lora_setup_address",
            "lora_prior_long_name",
            "lora_plane_state",
        )) {
            assertTrue("$stays would ride a backup", SettingsSnapshot.isTransient(stays))
        }
        for (rides in listOf(
            "meshcore_board_pubkey",
            // The board's bind signature (Stage 5d) rides with the key it proves: forgotten with it, restored with it.
            SettingsKeys.MESHCORE_BOARD_BIND,
            "meshcore_room_enabled",
            "meshcore_post_consented",
            // Whether that consent was given with the sheet's position line (Stage 7): it qualifies the consent it rides with.
            SettingsKeys.MESHCORE_POST_CONSENT_POSITION,
            // Whether it was given with a custom name shown (ADR 2026-11.f4d3): it qualifies the consent too.
            SettingsKeys.MESHCORE_POST_CONSENT_CUSTOM_NAME,
            "lora_board_key",
            "lora_board_node",
            "lora_room_enabled",
            "meshtastic_post_consented",
            "lora_enabled",
            "lora_dm_enabled",
        )) {
            assertFalse("$rides would not ride a backup", SettingsSnapshot.isTransient(rides))
        }
        assertFalse("never one bare meshcore_ prefix", SettingsKeys.TRANSIENT_PREFIXES.contains("meshcore_"))
    }

    /**
     * The sealed MeshCore claim's version and fingerprint ride a backup (ADR 2026-11.gsqu, Stage 4), as the badge's pair
     * does: a restored phone must never publish below a version its peers hold as its watermark.
     */
    @Test
    fun theMeshcoreClaimsSealedVersionRidesABackup() {
        for (rides in listOf(SettingsKeys.MESHCORE_SEALED_VERSION, SettingsKeys.MESHCORE_PUBLISHED, SettingsKeys.BADGE_SEALED_VERSION)) {
            assertFalse("$rides would not ride a backup", SettingsSnapshot.isTransient(rides))
        }
    }

    /** GitHub #44: whether this phone's Wi-Fi can spare Wi-Fi Aware's airtime is about this phone, and so is its move. */
    @Test
    fun dropsTheWifiAwareSwitchAndItsMove() =
        runTest {
            val prefs =
                preferencesOf(
                    booleanPreferencesKey("wifi_aware_enabled") to false,
                    booleanPreferencesKey("wifi_aware_migrated") to true,
                    booleanPreferencesKey("lora_enabled") to true,
                )
            val out = ByteArrayOutputStream()
            SettingsSnapshot.export(prefs, out)
            val restored = SettingsSnapshot.forRestore(ByteArrayInputStream(out.toByteArray()))
            assertTrue(restored[booleanPreferencesKey("lora_enabled")]!!)
            for (dropped in listOf("wifi_aware_enabled", "wifi_aware_migrated")) {
                assertFalse(dropped, restored.asMap().keys.any { it.name == dropped })
            }
        }

    /** The calls this phone saw end are its own (C-3.4-3); "Relay all calls" is the person's choice, and rides. */
    @Test
    fun dropsTheCallsTombstonesAndCarriesRelayAllCalls() =
        runTest {
            val prefs =
                preferencesOf(
                    stringPreferencesKey("call_tombs") to "[{\"i\":\"c1\",\"p\":5,\"t\":1}]",
                    booleanPreferencesKey("calls_relay_all") to true,
                )
            val out = ByteArrayOutputStream()
            SettingsSnapshot.export(prefs, out)
            val restored = SettingsSnapshot.forRestore(ByteArrayInputStream(out.toByteArray()))
            assertTrue(restored[booleanPreferencesKey("calls_relay_all")]!!)
            assertNull(restored[stringPreferencesKey("call_tombs")])
        }

    /**
     * The profile frame this phone signed last is its own (#335): a restored phone mints a fresh stamp before it reads one.
     * The capabilities key beside it rides, so the transient prefix must not reach it.
     */
    @Test
    fun dropsTheKeptProfileFrameAndCarriesTheProfileCapabilities() =
        runTest {
            val prefs =
                preferencesOf(
                    byteArrayPreferencesKey("own_profile_frame_sig") to byteArrayOf(1, 2),
                    byteArrayPreferencesKey("own_profile_frame_signed") to byteArrayOf(3, 4),
                    longPreferencesKey("own_profile_capabilities") to 5L,
                )
            val out = ByteArrayOutputStream()
            SettingsSnapshot.export(prefs, out)
            val restored = SettingsSnapshot.forRestore(ByteArrayInputStream(out.toByteArray()))
            assertEquals(5L, restored[longPreferencesKey("own_profile_capabilities")])
            for (dropped in listOf("own_profile_frame_sig", "own_profile_frame_signed")) {
                assertFalse(dropped, restored.asMap().keys.any { it.name == dropped })
            }
        }

    @Test
    fun theExportItselfCarriesNoRestoreMark() =
        runTest {
            val out = ByteArrayOutputStream()
            SettingsSnapshot.export(preferencesOf(name to "Ada"), out)
            val asWritten =
                androidx.datastore.preferences.core.PreferencesFileSerializer
                    .readFrom(ByteArrayInputStream(out.toByteArray()))
            assertNull(asWritten[booleanPreferencesKey(SettingsKeys.RESTORE_PENDING)])
            assertNull(asWritten[booleanPreferencesKey(SettingsKeys.ONBOARDING_SEEN)])
        }
}
