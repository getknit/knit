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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    // Editable text is held locally and updated synchronously on each keystroke; nothing is persisted
    // until the user taps Save (see [save]). Binding the field directly to the DataStore flow would
    // lag a keystroke behind and reset the field (you could only type one character), and persisting
    // per keystroke used to flood a profile packet for every letter typed.
    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    // The last persisted (normalized) values, used to detect unsaved edits. Updated on load and on Save.
    private val lastSavedName = MutableStateFlow("")
    private val lastSavedStatus = MutableStateFlow("")

    /** True when the editable fields differ from what's stored — drives the Save button's enabled state. */
    val isDirty: StateFlow<Boolean> =
        combine(_displayName, _status, lastSavedName, lastSavedStatus) { name, status, savedName, savedStatus ->
            normalizeSingleLine(name) != savedName || normalizeSingleLine(status) != savedStatus
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // A one-shot signal that a Save finished persisting, so the screen can navigate back only after the
    // write lands (popping the screen cancels viewModelScope, which would otherwise abort the write).
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

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
            val name = normalizeSingleLine(settings.displayName.first()).take(TextLimits.DISPLAY_NAME)
            val status = normalizeSingleLine(settings.status.first()).take(TextLimits.STATUS)
            _displayName.value = name
            _status.value = status
            lastSavedName.value = name
            lastSavedStatus.value = status
        }
    }

    fun setDisplayName(value: String) {
        // Hold exactly what's typed (capped) so a space *between* words isn't eaten mid-keystroke;
        // normalization + persistence happen in [save].
        _displayName.value = value.take(TextLimits.DISPLAY_NAME)
    }

    fun setStatus(value: String) {
        _status.value = value.take(TextLimits.STATUS)
    }

    /**
     * Normalizes both fields, persists them in a single transaction, and signals [saved] so the screen
     * can navigate back once the write has landed. The mesh layer watches DataStore and re-broadcasts the
     * profile once — so a profile packet is sent only here, never per keystroke.
     */
    fun save() {
        val name = normalizeSingleLine(_displayName.value)
        val status = normalizeSingleLine(_status.value)
        _displayName.value = name
        _status.value = status
        lastSavedName.value = name
        lastSavedStatus.value = status
        viewModelScope.launch {
            settings.setProfile(name, status)
            _saved.emit(Unit)
        }
    }

    /**
     * Snaps the *visible* display-name field to its normalized form when it loses focus — just stops the
     * box showing stray whitespace once you tab away; nothing is persisted until [save]. Done on commit,
     * not per keystroke — trimming the trailing space on every keystroke would block typing a space
     * mid-name. A no-op when already normalized.
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
    fun confirmCrop(
        scale: Float,
        offset: Offset,
        diameter: Float,
    ) {
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

    /**
     * Clears the user's avatar photo: drops the stored hash (so [avatarHash] emits null and the screen
     * falls back to the initial), stamps [SettingsStore.setAvatarUpdatedAt] to re-broadcast the now
     * photo-less profile, and deletes the blob if nothing else references it. Also recovers a *dangling*
     * hash whose blob is already gone (deleteIfUnreferenced tolerates the missing blob). Note: peers treat
     * a null advertised hash as "unchanged", so this drops the photo locally and for newly-met peers, but
     * doesn't retroactively evict it from peers that already pinned it — matching existing avatar semantics.
     */
    fun clearAvatar() {
        viewModelScope.launch {
            val oldHash = settings.ownAvatarHash.first() ?: return@launch
            settings.clearOwnAvatarHash()
            settings.setAvatarUpdatedAt(System.currentTimeMillis()) // triggers a profile re-broadcast
            blobs.deleteIfUnreferenced(oldHash)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _cropTarget.value = null
    }
}
