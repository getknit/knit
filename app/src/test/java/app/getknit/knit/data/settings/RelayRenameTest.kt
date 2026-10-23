package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The house relay's rename on a phone's settings (ADR 2026-10.qpja): everything under `lax` moves to `public-1` or is
 * dropped, the lab under `lax`'s name is never touched, and a second run changes nothing.
 */
class RelayRenameTest {
    private val urls = stringSetPreferencesKey(SettingsKeys.SPOOL_URLS)
    private val disabled = stringSetPreferencesKey(SettingsKeys.SPOOL_URLS_DISABLED)

    private fun string(
        prefix: String,
        host: String,
    ) = stringPreferencesKey(prefix + host)

    private fun renamed(prefs: MutablePreferences): MutablePreferences {
        assertTrue(RelayRename.needed(prefs))
        RelayRename.apply(prefs)
        // Idempotent: nothing is left to move, and a second pass is a no-op.
        assertFalse(RelayRename.needed(prefs))
        val again = prefs.toMutablePreferences()
        RelayRename.apply(again)
        assertEquals(prefs, again)
        return prefs
    }

    @Test
    fun theRelayRowMovesWithItsPathAndTokenAndTheLabStays() {
        val prefs =
            renamed(
                mutablePreferencesOf(
                    urls to setOf("wss://lax.spool.getknit.app/spool/v1?k=t", LAB_URL, OTHER_URL),
                    disabled to setOf(LAB_URL),
                ),
            )
        assertEquals(setOf("wss://public-1.spool.getknit.app/spool/v1?k=t", LAB_URL, OTHER_URL), prefs[urls])
        assertEquals(setOf(LAB_URL), prefs[disabled])
    }

    @Test
    fun aParkedRowStaysParkedAndAMergeStaysInUseIfEitherRowWas() {
        val parked = renamed(mutablePreferencesOf(urls to setOf(LAX_URL), disabled to setOf(LAX_URL)))
        assertEquals(setOf(PUBLIC_URL), parked[urls])
        assertEquals(setOf(PUBLIC_URL), parked[disabled])

        val merged = renamed(mutablePreferencesOf(urls to setOf(LAX_URL, PUBLIC_URL), disabled to setOf(LAX_URL)))
        assertEquals(setOf(PUBLIC_URL), merged[urls])
        assertEquals(emptySet<String>(), merged[disabled])

        val bothParked = renamed(mutablePreferencesOf(urls to setOf(LAX_URL, PUBLIC_URL), disabled to setOf(LAX_URL, PUBLIC_URL)))
        assertEquals(setOf(PUBLIC_URL), bothParked[disabled])
    }

    @Test
    fun theKeysMoveAndTheTicketsAndBackoffsAreDropped() {
        val consent = booleanPreferencesKey(SettingsKeys.SPOOL_FREE_CONSENT_PREFIX + LAX)
        val device = stringPreferencesKey(SettingsKeys.SPOOL_DEVICE_PREFIX + "0123")
        val prefs =
            renamed(
                mutablePreferencesOf(
                    string(SettingsKeys.SPOOL_KEY_PREFIX, LAX) to "customer",
                    string(SettingsKeys.SPOOL_FREE_ISSUER_PREFIX, LAX) to "https://anywhere.getknit.app",
                    consent to false,
                    string(SettingsKeys.SPOOL_TICKET_PREFIX, LAX) to "knit-ticket:v1:x",
                    string(SettingsKeys.SPOOL_TICKET_VERDICT_PREFIX, LAX) to "refused",
                    string(SettingsKeys.SPOOL_KEY_STATE_PREFIX, LAX) to "{}",
                    string(SettingsKeys.SPOOL_FREE_STATE_PREFIX, LAX) to "{}",
                    device to "pair",
                ),
            )
        assertEquals("customer", prefs[string(SettingsKeys.SPOOL_KEY_PREFIX, PUBLIC)])
        assertEquals("https://anywhere.getknit.app", prefs[string(SettingsKeys.SPOOL_FREE_ISSUER_PREFIX, PUBLIC)])
        assertEquals(false, prefs[booleanPreferencesKey(SettingsKeys.SPOOL_FREE_CONSENT_PREFIX + PUBLIC)])
        // The device keys' mark is keyed by slot, not host: the rename leaves it alone.
        assertEquals("pair", prefs[device])
        assertEquals(mapOf(SettingsKeys.SPOOL_KEY_PREFIX + PUBLIC to "customer"), prefs.named(SettingsKeys.SPOOL_KEY_PREFIX))
        listOf(
            SettingsKeys.SPOOL_TICKET_PREFIX,
            SettingsKeys.SPOOL_TICKET_VERDICT_PREFIX,
            SettingsKeys.SPOOL_KEY_STATE_PREFIX,
            SettingsKeys.SPOOL_FREE_STATE_PREFIX,
        ).forEach { assertEquals(it, emptyMap<String, Any>(), prefs.named(it)) }
        assertTrue(prefs.asMap().keys.none { it.name.endsWith(LAX) })
    }

    @Test
    fun aFreeKeyMovesOnItsOwn() {
        val prefs = renamed(mutablePreferencesOf(string(SettingsKeys.SPOOL_FREE_KEY_PREFIX, LAX) to "free"))
        assertEquals("free", prefs[string(SettingsKeys.SPOOL_FREE_KEY_PREFIX, PUBLIC)])
        assertTrue(prefs.asMap().keys.none { it.name.endsWith(LAX) })
    }

    @Test
    fun theCurrentNamesOwnValuesWinAndACustomerKeyClearsAFreeOne() {
        val prefs =
            renamed(
                mutablePreferencesOf(
                    string(SettingsKeys.SPOOL_FREE_KEY_PREFIX, LAX) to "free-lax",
                    string(SettingsKeys.SPOOL_KEY_PREFIX, LAX) to "customer-lax",
                    string(SettingsKeys.SPOOL_KEY_PREFIX, PUBLIC) to "customer-public",
                ),
            )
        assertEquals("customer-public", prefs[string(SettingsKeys.SPOOL_KEY_PREFIX, PUBLIC)])
        assertNull(prefs[string(SettingsKeys.SPOOL_FREE_KEY_PREFIX, PUBLIC)])
        assertNull(prefs[string(SettingsKeys.SPOOL_KEY_PREFIX, LAX)])
    }

    @Test
    fun theLabsKeysAreNeverTouched() {
        val prefs =
            mutablePreferencesOf(
                urls to setOf(LAB_URL),
                string(SettingsKeys.SPOOL_KEY_PREFIX, "lab.lax.spool.getknit.app") to "lab-key",
                string(SettingsKeys.SPOOL_TICKET_PREFIX, "lab.lax.spool.getknit.app") to "knit-ticket:v1:x",
            )
        val before = prefs.toPreferences()
        assertFalse(RelayRename.needed(prefs))
        RelayRename.apply(prefs)
        assertEquals(before, prefs)
    }

    private fun MutablePreferences.named(prefix: String) = asMap().filterKeys { it.name.startsWith(prefix) }.mapKeys { it.key.name }

    private companion object {
        const val LAX = "lax.spool.getknit.app"
        const val PUBLIC = "public-1.spool.getknit.app"
        const val LAX_URL = "wss://lax.spool.getknit.app/spool/v1"
        const val PUBLIC_URL = "wss://public-1.spool.getknit.app/spool/v1"
        const val LAB_URL = "wss://lab.lax.spool.getknit.app/spool/v1"
        const val OTHER_URL = "wss://relay.example.org/spool/v1"
    }
}
