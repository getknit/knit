package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The speech model's choices — the walkie's transcript, a live call's captions and a chat voice note's text
 * (experimental, docs/VOICE_CALLS.md C-3.11-9 and C-3.7-5, ADRs 2026-10.nz3g and 2026-10.am8d) — over the settings
 * DataStore rather than grown into [SettingsStore]. Bound only where calls are wired; all ride a Knit backup like every
 * other choice the person made.
 */
class TranscriptStore(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * The Calls screen's "Transcripts and captions" switch, on by default (the model is still a download the person asks
     * for). Off, `WalkieTranscriber` takes no burst, `CallCaptioner` no audio, and the call screen draws neither the
     * walkie's transcript card nor a live call's CC button, and no voice bubble offers Transcribe or shows a stored text.
     */
    val enabled: Flow<Boolean> = dataStore.data.map { it[KEY_ENABLED] ?: true }

    /**
     * Whether the person dismissed the walkie card's offer of the speech model: once true, a walkie call draws no card
     * while the model is not on the phone, and the download is the Calls screen's alone. Never cleared.
     */
    val offerDismissed: Flow<Boolean> = dataStore.data.map { it[KEY_OFFER_DISMISSED] ?: false }

    /**
     * The live call screen's CC button, off by default and remembered from call to call: on, a live call is captioned
     * from the moment its media is up. Without [enabled] it does nothing and is not drawn.
     */
    val liveCaptions: Flow<Boolean> = dataStore.data.map { it[KEY_LIVE_CAPTIONS] ?: false }

    suspend fun setEnabled(value: Boolean) = dataStore.edit { it[KEY_ENABLED] = value }

    suspend fun setLiveCaptions(value: Boolean) = dataStore.edit { it[KEY_LIVE_CAPTIONS] = value }

    suspend fun dismissOffer() = dataStore.edit { it[KEY_OFFER_DISMISSED] = true }

    internal companion object {
        val KEY_ENABLED = booleanPreferencesKey("calls_transcripts")
        val KEY_OFFER_DISMISSED = booleanPreferencesKey("calls_transcript_offer_dismissed")
        val KEY_LIVE_CAPTIONS = booleanPreferencesKey("calls_live_captions")
    }
}
