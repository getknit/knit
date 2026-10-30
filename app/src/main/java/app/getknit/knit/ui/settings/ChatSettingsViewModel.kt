package app.getknit.knit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.translate.ReaderLanguage
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.ui.translate.LanguageNames
import app.getknit.knit.ui.translate.TranslateIntoForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings › Chats: four store-backed settings, and the language messages are translated into where translation is
 * wired ([reader] non-null), each persisted the moment it is touched.
 */
class ChatSettingsViewModel(
    private val settings: SettingsStore,
    private val reader: ReaderLanguage? = null,
) : ViewModel() {
    /** Whether this phone sends and shows typing indicators on every plane (on by default — see the store's KDoc). */
    val typingIndicatorsEnabled: StateFlow<Boolean> =
        settings.typingIndicatorsEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether links this device sends carry a preview card (off by default — see the store's KDoc). */
    val linkPreviewsEnabled: StateFlow<Boolean> =
        settings.linkPreviewsEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether on-device content moderation is enabled. A Switch can bind straight to the DataStore flow. */
    val contentFilteringEnabled: StateFlow<Boolean> =
        settings.contentFilteringEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** "Ask before large Bluetooth downloads" (ADR 2026-10.js2p). */
    val bluetoothDownloadLimit: StateFlow<BluetoothDownloadLimit> =
        settings.bluetoothDownloadLimit
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BluetoothDownloadLimit.DEFAULT)

    /** Translation on this phone and the languages its messages declare (ADR 2026-10.t2h6); null where not wired. */
    val translationEnabled: StateFlow<Boolean?> =
        reader
            ?.let { settings.translationEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true) }
            ?: MutableStateFlow(null)

    fun setTranslationEnabled(value: Boolean) {
        viewModelScope.launch { settings.setTranslationEnabled(value) }
    }

    /** "Translate messages into" (ADR 2026-10.t2h6's 2026-10-29 amendment), or null where translation is not wired. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val translateInto: StateFlow<TranslateIntoForm?> =
        reader
            ?.let { r ->
                settings.translateTarget
                    .mapLatest { code ->
                        val estimate = r.estimate(code)
                        val locale = Locale.getDefault()
                        val choices = TranslateDirection.targets.sortedBy { LanguageNames.name(it, locale) }
                        TranslateIntoForm(estimate.override, estimate.automatic, choices)
                    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
            } ?: MutableStateFlow(null)

    fun setTranslateInto(lang: TranslateLang?) {
        viewModelScope.launch { settings.setTranslateTarget(lang?.code) }
    }

    fun setTypingIndicatorsEnabled(value: Boolean) {
        viewModelScope.launch { settings.setTypingIndicatorsEnabled(value) }
    }

    fun setLinkPreviewsEnabled(value: Boolean) {
        viewModelScope.launch { settings.setLinkPreviewsEnabled(value) }
    }

    fun setContentFilteringEnabled(value: Boolean) {
        viewModelScope.launch { settings.setContentFilteringEnabled(value) }
    }

    fun setBluetoothDownloadLimit(value: BluetoothDownloadLimit) {
        viewModelScope.launch { settings.setBluetoothDownloadLimit(value) }
    }
}
