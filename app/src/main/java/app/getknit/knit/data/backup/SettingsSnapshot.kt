package app.getknit.knit.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.getknit.knit.data.settings.SettingsKeys
import java.io.InputStream
import java.io.OutputStream

/**
 * The settings entry: the one preferences DataStore, re-serialized by DataStore's own file serializer
 * so the restored phone opens it as the file it always had, minus what is about this phone rather than
 * this person ([SettingsKeys.TRANSIENT_PREFIXES]), plus the two keys a restore has to set —
 * [SettingsKeys.ONBOARDING_SEEN] (or the name page would save its empty field over the restored name) and
 * [SettingsKeys.RESTORE_PENDING] (the mesh's first-start hooks). Every other key is carried as it is, so a
 * setting added later is never silently lost from backups.
 */
object SettingsSnapshot {
    /** [prefs] minus the transient keys, as the bytes of a preferences file. */
    suspend fun export(
        prefs: Preferences,
        out: OutputStream,
    ) {
        PreferencesFileSerializer.writeTo(strip(prefs), out)
    }

    /** The backup's settings, read from its entry and marked as a restore — what [export] wrote, plus the two keys. */
    suspend fun forRestore(input: InputStream): Preferences {
        val restored = PreferencesFileSerializer.readFrom(input).toMutablePreferences()
        restored[booleanPreferencesKey(SettingsKeys.ONBOARDING_SEEN)] = true
        restored[booleanPreferencesKey(SettingsKeys.RESTORE_PENDING)] = true
        return restored.toPreferences()
    }

    suspend fun write(
        prefs: Preferences,
        out: OutputStream,
    ) = PreferencesFileSerializer.writeTo(prefs, out)

    internal fun strip(prefs: Preferences): Preferences {
        val kept: MutablePreferences = mutablePreferencesOf()
        for ((key, value) in prefs.asMap()) {
            if (isTransient(key.name)) continue
            @Suppress("UNCHECKED_CAST")
            kept[key as Preferences.Key<Any>] = value
        }
        return kept.toPreferences()
    }

    internal fun isTransient(name: String): Boolean = SettingsKeys.TRANSIENT_PREFIXES.any { name.startsWith(it) }
}
