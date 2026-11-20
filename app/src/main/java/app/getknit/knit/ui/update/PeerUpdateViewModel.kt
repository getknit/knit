package app.getknit.knit.ui.update

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.message.AppUpdateRecord
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.protocol.AppUpdatePayload
import app.getknit.knit.transfer.OfferOutcome
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.ui.chat.transferRefusalMessage
import app.getknit.knit.update.AndroidAppBuild
import app.getknit.knit.update.AskOutcome
import app.getknit.knit.update.InstallState
import app.getknit.knit.update.PeerUpdates
import app.getknit.knit.update.UpdateHint
import app.getknit.knit.update.UpdateInstaller
import app.getknit.knit.update.UpdateRequest
import app.getknit.knit.update.UpdateStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something the chat screen does once for a peer update: say [Message], or open the "Install unknown apps" screen. */
sealed interface UpdateEvent {
    data class Message(
        @StringRes val text: Int,
    ) : UpdateEvent

    data object OpenInstallGrant : UpdateEvent
}

/** Which tap the direct-transfer disclosure is standing in front of, for a peer update. */
enum class UpdateConsent { Ask, Send }

/**
 * A DM's half of a peer update (ADR 2026-11.29m7), apart from `ChatViewModel` so that class does not grow: the older
 * phone's hint and its Ask, the newer phone's band and its Send or Not now, and the Install and restart on a staged
 * update's card. Every piece is null-safe: in a build without peer updates [updates] and [installer] are null and
 * nothing here shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerUpdateViewModel(
    private val conversationId: String,
    private val updates: PeerUpdates?,
    private val installer: UpdateInstaller?,
    build: AndroidAppBuild?,
    staging: UpdateStaging,
    private val transfers: TransferManager,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _events = MutableSharedFlow<UpdateEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UpdateEvent> = _events.asSharedFlow()

    private val _consent = MutableStateFlow<UpdateConsent?>(null)

    /** The direct-transfer disclosure, when a tap here is waiting on it. */
    val consent: StateFlow<UpdateConsent?> = _consent.asStateFlow()

    /** The hint for this DM: the contact runs a newer Knit that can install here, and the person has not closed it. */
    val hint: StateFlow<UpdateHint?> =
        (updates?.hintFor(conversationId) ?: flowOf(null))
            .combine(settings.peerUpdateDismissedVersion) { hint, dismissed -> hint?.takeIf { it.build.versionCode > dismissed } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** This contact's ask for this phone's Knit, while it waits for the person here. */
    val request: StateFlow<UpdateRequest?> =
        (updates?.requests ?: flowOf(emptyMap()))
            .map { it[conversationId] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** This phone's APK size, for the band: what Send would move. */
    val ownSize: StateFlow<Long> =
        flow { emit(build?.local()?.build?.size ?: 0L) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), 0L)

    /** The transfer ids whose update is staged and may be installed, and those whose install is under way. */
    val staged: StateFlow<Set<String>> =
        combine(transfers.states, installer?.states ?: flowOf(emptyMap())) { _, installs -> installs }
            .mapLatest { installs -> staging.committedIds() - installs.filterValues { it != InstallState.NeedsGrant }.keys }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptySet())

    val installing: StateFlow<Set<String>> =
        (installer?.states ?: flowOf(emptyMap()))
            .map { states -> states.filterValues { it == InstallState.Installing }.keys }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptySet())

    /** The hint's Ask: the disclosure, the install grant and the space, then the request. */
    fun ask() {
        viewModelScope.launch {
            if (!settings.directTransferConsented.first()) {
                _consent.value = UpdateConsent.Ask
                return@launch
            }
            askNow()
        }
    }

    private suspend fun askNow() {
        val updates = updates ?: return
        val installer = installer ?: return
        val build = hint.value?.build ?: return
        if (!installer.mayInstall()) {
            _events.tryEmit(UpdateEvent.OpenInstallGrant)
            return
        }
        if (installer.freeBytes() < build.size) {
            _events.tryEmit(UpdateEvent.Message(R.string.chat_transfer_no_space))
            return
        }
        val message =
            when (updates.ask(conversationId)) {
                AskOutcome.Sent, AskOutcome.Already -> null
                AskOutcome.NotNearby -> R.string.chat_transfer_peer_not_nearby
                AskOutcome.NotSent -> R.string.chat_transfer_no_session
                AskOutcome.NotWorthAsking -> R.string.update_hint_gone
            }
        message?.let { _events.tryEmit(UpdateEvent.Message(it)) }
    }

    /** The hint's close: away for this version and anything older. */
    fun dismiss() {
        val version = hint.value?.build?.versionCode ?: return
        viewModelScope.launch { settings.dismissPeerUpdate(version) }
    }

    /** The band's Send: the disclosure on a first transfer, then this phone's APK over a direct transfer. */
    fun send() {
        viewModelScope.launch {
            if (!settings.directTransferConsented.first()) {
                _consent.value = UpdateConsent.Send
                return@launch
            }
            sendNow()
        }
    }

    private suspend fun sendNow() {
        val updates = updates ?: return
        val request = updates.claim(conversationId) ?: return _events.emitMessage(R.string.update_request_gone)
        val outcome = transfers.offerApp(conversationId, request.requestId, request.versionCode)
        if (outcome is OfferOutcome.Refused) {
            updates.decline(conversationId, AppUpdatePayload.REASON_BUSY, request)
            _events.emitMessage(transferRefusalMessage(outcome.refusal))
        }
    }

    /** The band's Not now. */
    fun notNow() {
        viewModelScope.launch { updates?.decline(conversationId) }
    }

    /** The disclosure was read: record it and carry on with the tap that raised it. */
    fun acceptConsent() {
        val waiting = _consent.value ?: return
        viewModelScope.launch {
            settings.acceptDirectTransferConsent()
            _consent.value = null
            when (waiting) {
                UpdateConsent.Ask -> askNow()
                UpdateConsent.Send -> sendNow()
            }
        }
    }

    fun dismissConsent() {
        _consent.value = null
    }

    /** A staged update's card: Install and restart. */
    fun install(
        transferId: String,
        record: AppUpdateRecord,
    ) {
        val installer = installer ?: return
        viewModelScope.launch {
            when (installer.install(transferId, record)) {
                InstallState.Installing -> Unit
                InstallState.NeedsGrant -> _events.tryEmit(UpdateEvent.OpenInstallGrant)
                InstallState.Failed -> _events.emitMessage(R.string.update_install_failed)
                InstallState.Gone -> _events.emitMessage(R.string.update_install_gone)
            }
        }
    }

    private fun MutableSharedFlow<UpdateEvent>.emitMessage(
        @StringRes text: Int,
    ) {
        tryEmit(UpdateEvent.Message(text))
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
