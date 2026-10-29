package app.getknit.knit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › Chats: four store-backed settings, each persisted the moment it is touched. */
class ChatSettingsViewModel(
    private val settings: SettingsStore,
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
