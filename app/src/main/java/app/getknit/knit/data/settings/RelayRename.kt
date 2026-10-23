package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.getknit.knit.mesh.spool.SpoolUrl

/**
 * Moves a renamed house relay's settings from its former host name to its current one (ADR 2026-10.qpja,
 * knit-spool-coordinator ADR 2026-10.ftp9): `lax.spool.getknit.app` is `public-1.spool.getknit.app`, the same spool and
 * the same rooms, and `lax` is retired once a build carrying this is widely installed.
 *
 * Idempotent, and run on every start rather than behind a marker: a restore brings back a backup's `spool_urls` and
 * `spool_key:` after any marker would have been set, and a second run finds nothing to move. Every host is matched
 * exactly ([SpoolUrl.canonical], [SpoolUrl.currentHost]), so the lab under the former name is never touched.
 *
 * - `spool_urls` and `spool_urls_disabled` go through [SpoolUrl.canonical], which keeps the path and any `?k=`. A URL
 *   the rename lands on one already listed merges into one row, parked only when every row it came from was parked.
 * - The customer key (`spool_key:`, which rides a backup) and the free key, consent and issuer move, unless the
 *   current name already holds its own; a customer key at the current name clears a free key there, as every write
 *   of one does. The device keys do not move (a Keystore alias cannot be renamed): a customer key moved here makes
 *   a new pair on its next ask, and an issuer that bound the old one answers `bound-elsewhere` until the person
 *   moves the account.
 * - The held ticket and its verdict, and both backoff states, are dropped: a ticket names the former host, and the
 *   refresher rebuilds the rest for the current one.
 */
object RelayRename {
    private val KEY_SPOOL_URLS = stringSetPreferencesKey(SettingsKeys.SPOOL_URLS)
    private val KEY_SPOOL_DISABLED = stringSetPreferencesKey(SettingsKeys.SPOOL_URLS_DISABLED)

    private val MOVED =
        listOf(
            SettingsKeys.SPOOL_KEY_PREFIX,
            SettingsKeys.SPOOL_FREE_KEY_PREFIX,
            SettingsKeys.SPOOL_FREE_CONSENT_PREFIX,
            SettingsKeys.SPOOL_FREE_ISSUER_PREFIX,
        )

    private val DROPPED =
        listOf(
            SettingsKeys.SPOOL_TICKET_PREFIX,
            SettingsKeys.SPOOL_TICKET_VERDICT_PREFIX,
            SettingsKeys.SPOOL_KEY_STATE_PREFIX,
            SettingsKeys.SPOOL_FREE_STATE_PREFIX,
        )

    /** Whether [prefs] holds anything under a former name; the cheap read that lets a start skip the write. */
    fun needed(prefs: Preferences): Boolean =
        (prefs[KEY_SPOOL_URLS].orEmpty() + prefs[KEY_SPOOL_DISABLED].orEmpty()).any { SpoolUrl.canonical(it) != it } ||
            prefs.asMap().keys.any { key -> hostIn(key.name)?.let { SpoolUrl.currentHost(it) != it } == true }

    /** Moves everything under a former name in [prefs]. */
    fun apply(prefs: MutablePreferences) {
        renameUrls(prefs)
        prefs
            .asMap()
            .keys
            .toList()
            .forEach { key -> renameKey(prefs, key) }
        // One slot per host: a customer key at the current name clears a free key there (TicketKeyStore's rule).
        prefs
            .asMap()
            .keys
            .map { it.name }
            .filter { it.startsWith(SettingsKeys.SPOOL_KEY_PREFIX) }
            .map { it.removePrefix(SettingsKeys.SPOOL_KEY_PREFIX) }
            .filter { SpoolUrl.formerHost(it) != it }
            .forEach { prefs.remove(stringPreferencesKey(SettingsKeys.SPOOL_FREE_KEY_PREFIX + it)) }
    }

    // Drops or moves one per-host key under a former name; leaves every other key alone.
    private fun renameKey(
        prefs: MutablePreferences,
        key: Preferences.Key<*>,
    ) {
        val prefix = (MOVED + DROPPED).firstOrNull { key.name.startsWith(it) } ?: return
        val host = key.name.removePrefix(prefix)
        val current = SpoolUrl.currentHost(host)
        if (current == host) return
        val value = prefs.asMap()[key]
        prefs.remove(key)
        val name = prefix + current
        // The current name's own value, if any, wins.
        if (prefix in DROPPED || prefs.asMap().keys.any { it.name == name }) return
        // Every moved key is a string but the consent.
        when (value) {
            is String -> {
                prefs[stringPreferencesKey(name)] = value
            }

            is Boolean -> {
                prefs[booleanPreferencesKey(name)] = value
            }

            else -> {}
        }
    }

    private fun renameUrls(prefs: MutablePreferences) {
        val urls = prefs[KEY_SPOOL_URLS].orEmpty()
        val disabled = prefs[KEY_SPOOL_DISABLED].orEmpty()
        if ((urls + disabled).all { SpoolUrl.canonical(it) == it }) return
        val sources = urls.groupBy(SpoolUrl::canonical)
        // A merged row stays in use when any row it came from was: parking it would switch off a relay in use.
        val inUse = sources.filterValues { from -> from.any { it !in disabled } }.keys
        prefs[KEY_SPOOL_URLS] = sources.keys
        prefs[KEY_SPOOL_DISABLED] = disabled.map(SpoolUrl::canonical).toSet() - inUse
    }

    // The host a per-host key names, or null for a key this does not move.
    private fun hostIn(name: String): String? = (MOVED + DROPPED).firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) }
}
