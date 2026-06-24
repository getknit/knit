package app.getknit.knit.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val settings: SettingsStore,
    identity: Identity,
    private val avatars: AvatarStore,
) : ViewModel() {

    val nodeId = MutableStateFlow("")

    /**
     * The auto-generated alias for this device, shown as the display-name field's placeholder until
     * the user sets a name. Derived from [nodeId]; never persisted, so the stored name stays blank
     * (and the profile broadcast stays "unset") until the user actually types one.
     */
    val alias = MutableStateFlow("")

    // Editable text is held locally and updated synchronously on each keystroke; persistence to
    // DataStore happens in the background. Binding the field directly to the DataStore flow would
    // lag a keystroke behind and reset the field (you could only type one character).
    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Path to the current avatar JPEG, recomputed whenever it changes. */
    val avatarPath: StateFlow<String?> =
        settings.avatarUpdatedAt
            .map { avatars.ownAvatarPath() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), avatars.ownAvatarPath())

    init {
        viewModelScope.launch {
            val id = identity.nodeId()
            nodeId.value = id
            alias.value = Alias.aliasFor(id)
            _displayName.value = settings.displayName.first()
            _status.value = settings.status.first()
        }
    }

    fun setDisplayName(value: String) {
        _displayName.value = value
        viewModelScope.launch { settings.setDisplayName(value) }
    }

    fun setStatus(value: String) {
        _status.value = value
        viewModelScope.launch { settings.setStatus(value) }
    }

    fun pickAvatar(uri: Uri) {
        viewModelScope.launch {
            if (avatars.saveOwnAvatar(uri)) {
                settings.setAvatarUpdatedAt(System.currentTimeMillis())
            }
        }
    }
}
