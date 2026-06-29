package app.getknit.knit.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.ui.util.computeAvatarCrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val settings: SettingsStore,
    identity: Identity,
    private val avatars: AvatarStore,
    private val blobs: BlobRepository,
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

    /** Content hash of the current own avatar (keys the blob Coil renders), or null if none is set. */
    val avatarHash: StateFlow<String?> =
        settings.ownAvatarHash
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether on-device content moderation is enabled. A Switch can bind straight to the DataStore flow
     * (unlike a TextField — see the editable-text note above), since toggling has no per-keystroke lag.
     */
    val contentFilteringEnabled: StateFlow<Boolean> =
        settings.contentFilteringEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        viewModelScope.launch {
            val id = identity.nodeId()
            nodeId.value = id
            alias.value = Alias.aliasFor(id)
            _displayName.value = normalizeSingleLine(settings.displayName.first()).take(TextLimits.DISPLAY_NAME)
            _status.value = normalizeSingleLine(settings.status.first()).take(TextLimits.STATUS)
        }
    }

    fun setDisplayName(value: String) {
        val capped = value.take(TextLimits.DISPLAY_NAME)
        _displayName.value = capped
        // The field holds exactly what's typed (so a space *between* words isn't eaten mid-keystroke),
        // but everything that reads the name — the wire, notifications, diagnostics — gets the trimmed
        // form. Persisting the normalized value here means leaving by Back (which fires no focus event,
        // so onFocusChanged/commit never runs) still leaves a clean value in DataStore.
        viewModelScope.launch { settings.setDisplayName(normalizeSingleLine(capped)) }
    }

    fun setStatus(value: String) {
        val capped = value.take(TextLimits.STATUS)
        _status.value = capped
        viewModelScope.launch { settings.setStatus(normalizeSingleLine(capped)) }
    }

    /**
     * Snaps the *visible* display-name field to its normalized form when it loses focus (DataStore was
     * already kept clean by [setDisplayName]; this just stops the box showing stray whitespace once
     * you tab away). Done on commit, not per keystroke — trimming the trailing space on every keystroke
     * would block typing a space mid-name. A no-op when already normalized.
     */
    fun commitDisplayName() {
        _displayName.value = normalizeSingleLine(_displayName.value)
    }

    /** Status counterpart of [commitDisplayName]. */
    fun commitStatus() {
        _status.value = normalizeSingleLine(_status.value)
    }

    fun setContentFilteringEnabled(value: Boolean) {
        viewModelScope.launch { settings.setContentFilteringEnabled(value) }
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
            val oldHash = settings.ownAvatarHash.first()
            val newHash = avatars.saveOwnAvatar(source, crop) ?: return@launch
            settings.setOwnAvatarHash(newHash)
            settings.setAvatarUpdatedAt(System.currentTimeMillis()) // triggers a profile re-broadcast
            if (oldHash != newHash) blobs.deleteIfUnreferenced(oldHash)
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
