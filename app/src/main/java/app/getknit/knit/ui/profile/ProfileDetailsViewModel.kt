package app.getknit.knit.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.crypto.VerifyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The result of scanning a peer's identity QR, surfaced once to the screen then consumed. */
enum class VerifyScanResult { MATCH, MISMATCH }

/** This device's identity, loaded once for safety-number/QR rendering. */
private data class MyIdentity(
    val nodeId: String,
    val bundle: String,
)

/** A remote peer's profile as shown on the read-only details screen. */
data class ProfileDetailsUiState(
    val nodeId: String,
    val displayName: String,
    val status: String,
    val avatarHash: String?,
    val online: Boolean,
    val isBlocked: Boolean,
    // E2E verification: whether we hold the peer's key yet, whether the user has verified it, the
    // human-comparable safety number (null until both keys are known), and our own QR payload.
    val hasKey: Boolean = false,
    val verified: Boolean = false,
    val safetyNumber: String? = null,
    val myQrPayload: String? = null,
)

/**
 * Backs the read-only Profile Details screen for another peer (keyed by [nodeId]). It surfaces the
 * peer's cached profile (name/status/avatar from the `peers` table), live presence (from the mesh
 * neighbor set), block state, and the end-to-end key-verification state (safety number + QR + verified
 * flag). A peer we've only just met (no cached profile row yet) still resolves to a friendly alias.
 */
class ProfileDetailsViewModel(
    private val nodeId: String,
    private val peers: PeerRepository,
    meshManager: MeshController,
    private val settings: SettingsStore,
    identity: Identity,
) : ViewModel() {
    private val me = MutableStateFlow<MyIdentity?>(null)

    private val _scanResult = MutableStateFlow<VerifyScanResult?>(null)
    val scanResult: StateFlow<VerifyScanResult?> = _scanResult.asStateFlow()

    // Latest pinned key for the peer, captured for the scan comparison (avoids re-reading the DB).
    @Volatile
    private var peerBundle: String? = null

    // Latest device tag for the peer, captured so block/unblock can keep the block sticky across the
    // peer regenerating its key (and thus its nodeId). See [DeviceTag].
    @Volatile
    private var peerDeviceTag: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            me.value = MyIdentity(identity.nodeId(), identity.publicKeyBundle())
        }
    }

    val state: StateFlow<ProfileDetailsUiState> =
        combine(
            peers.observePeers(),
            meshManager.neighbors,
            settings.blockedNodeIds,
            me,
        ) { peerList, neighbors, blocked, myId ->
            val peer = peerList.firstOrNull { it.nodeId == nodeId }
            peerBundle = peer?.pubKey
            peerDeviceTag = peer?.deviceTag
            val safety =
                if (peer?.pubKey != null && myId != null) {
                    SafetyNumber.compute(myId.nodeId, myId.bundle, nodeId, peer.pubKey)
                } else {
                    null
                }
            ProfileDetailsUiState(
                nodeId = nodeId,
                displayName = displayNameFor(peer?.name, nodeId),
                status = peer?.status.orEmpty(),
                avatarHash = peer?.avatarHash,
                online = neighbors.any { it.nodeId == nodeId },
                isBlocked = nodeId in blocked,
                hasKey = peer?.pubKey != null,
                verified = peer?.verified == true,
                safetyNumber = safety,
                myQrPayload = myId?.let { VerifyPayload.encode(it.nodeId, it.bundle) },
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ProfileDetailsUiState(
                nodeId = nodeId,
                displayName = displayNameFor(null, nodeId),
                status = "",
                avatarHash = null,
                online = false,
                isBlocked = false,
            ),
        )

    /**
     * Accepts this peer's DM (idempotent). Tapping Message means we've chosen to converse, so any
     * pending request from them clears — their thread moves into the main chat list and their messages
     * notify normally. A no-op for a peer that's already accepted, so it's safe on every Message tap.
     */
    fun accept() {
        viewModelScope.launch { settings.accept(nodeId) }
    }

    /** Blocks this peer locally: their messages/reactions stop being stored, shown, and notified. */
    fun block() {
        viewModelScope.launch { settings.block(nodeId, peerDeviceTag) }
    }

    /** Unblocks this peer, restoring their (never-deleted) message history. */
    fun unblock() {
        viewModelScope.launch { settings.unblock(nodeId, peerDeviceTag) }
    }

    /** Marks this peer's pinned key as verified out of band (safety numbers matched / QR scanned). */
    fun markVerified() {
        viewModelScope.launch { peers.setVerified(nodeId, true) }
    }

    /** Clears verification (e.g. user wants to re-check). */
    fun clearVerification() {
        viewModelScope.launch { peers.setVerified(nodeId, false) }
    }

    /** Compares a scanned identity QR with the pinned key; marks verified on an exact match. */
    fun onScanned(payload: String) {
        val matches = scannedMatchesPinned(payload)
        if (matches) markVerified()
        _scanResult.value = if (matches) VerifyScanResult.MATCH else VerifyScanResult.MISMATCH
    }

    private fun scannedMatchesPinned(payload: String): Boolean {
        val parsed = VerifyPayload.parse(payload) ?: return false
        val pinned = peerBundle ?: return false
        return parsed.nodeId == nodeId && parsed.bundle == pinned
    }

    fun consumeScanResult() {
        _scanResult.value = null
    }
}
