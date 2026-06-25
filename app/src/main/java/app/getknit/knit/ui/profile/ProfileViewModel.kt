package app.getknit.knit.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.util.computeAvatarCrop
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

    // The picked image awaiting crop. Held here (not in SavedStateHandle — a Bitmap is large and not
    // parcel-friendly) so the crop survives configuration changes without re-decoding. Not recycled:
    // the crop dialog wraps these same pixels via asImageBitmap(), so we just drop the reference.
    private val _cropTarget = MutableStateFlow<Bitmap?>(null)
    val cropTarget: StateFlow<Bitmap?> = _cropTarget.asStateFlow()

    /** Picks an image and shows the crop UI; the actual save happens in [confirmCrop]. */
    fun pickAvatar(uri: Uri) {
        viewModelScope.launch {
            _cropTarget.value = avatars.loadForCrop(uri)
        }
    }

    /** Persists the cropped avatar. [scale]/[offset]/[diameter] come from the crop dialog's transform. */
    fun confirmCrop(scale: Float, offset: Offset, diameter: Float) {
        val source = _cropTarget.value ?: return
        _cropTarget.value = null
        viewModelScope.launch {
            val crop = computeAvatarCrop(source.width, source.height, diameter, scale, offset.x, offset.y)
            if (avatars.saveOwnAvatar(source, crop)) {
                settings.setAvatarUpdatedAt(System.currentTimeMillis())
            }
        }
    }

    fun cancelCrop() {
        _cropTarget.value = null
    }

    override fun onCleared() {
        super.onCleared()
        _cropTarget.value = null
    }
}
