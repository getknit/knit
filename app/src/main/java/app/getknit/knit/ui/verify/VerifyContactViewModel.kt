package app.getknit.knit.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.VerifyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Outcome of scanning an identity QR on the standalone Verify-contact screen, shown once then consumed. */
enum class VerifyResult {
    /** The code is a valid, self-consistent Knit identity; its key is now pinned and marked verified. */
    VERIFIED,

    /** The code's key differs from the one already pinned for that node id (impersonation / collision). */
    MISMATCH,

    /** The scanned code is our own identity. */
    SELF,

    /** Not a Knit identity code, or its key doesn't derive to its claimed node id (forged / corrupt). */
    INVALID,
}

/**
 * Backs the standalone "Verify contact" screen reached from the chat-list overflow menu. It shows this
 * device's own identity QR (so a peer can scan it) and turns a scanned peer code into a **verified
 * contact** — even one we've never chatted with and hold no cached profile for yet. This is the only way
 * to add someone to the New Message picker without a prior DM/group or a Nearby profile tap.
 *
 * Pinning mirrors [app.getknit.knit.mesh.InboundPipeline]'s `handleProfile` invariants: the code must be
 * self-certifying (its key derives back to its node id) and a peer's pinned key is immutable, so a
 * differing key for a known node id is refused rather than swapped in.
 */
class VerifyContactViewModel(
    private val peers: PeerRepository,
    private val identity: Identity,
) : ViewModel() {
    private val _myQrPayload = MutableStateFlow<String?>(null)
    val myQrPayload: StateFlow<String?> = _myQrPayload.asStateFlow()

    private val _scanResult = MutableStateFlow<VerifyResult?>(null)
    val scanResult: StateFlow<VerifyResult?> = _scanResult.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _myQrPayload.value = VerifyPayload.encode(identity.nodeId(), identity.publicKeyBundle())
        }
    }

    /**
     * Parses and validates a scanned identity code, then pins + verifies the peer. Sets [scanResult] to
     * the outcome for a one-shot snackbar (cleared via [consumeScanResult]).
     */
    fun onScanned(payload: String) {
        val parsed = VerifyPayload.parse(payload)
        // Self-certifying identity: reject a code whose key doesn't derive back to its claimed node id
        // (the same check handleProfile applies to an advertised key), so a forged code can't pin a key
        // for a node id whose keypair the sender doesn't actually hold.
        if (parsed == null || NodeId.fromPublicKeyBundle(parsed.bundle) != parsed.nodeId) {
            _scanResult.value = VerifyResult.INVALID
            return
        }
        viewModelScope.launch {
            if (parsed.nodeId == identity.nodeId()) {
                _scanResult.value = VerifyResult.SELF
                return@launch
            }
            val existing = peers.find(parsed.nodeId)
            _scanResult.value =
                when (existing?.pubKey) {
                    // No key pinned yet (a brand-new contact, or a bare avatar-only row): pin it and mark
                    // verified. Leave updatedAt at its default 0 so a later real profile frame still wins
                    // handleProfile's last-writer-wins check and fills in the name/status/avatar this code
                    // doesn't carry.
                    null -> {
                        peers.upsert(
                            (existing ?: PeerEntity(parsed.nodeId)).copy(
                                pubKey = parsed.bundle,
                                verified = true,
                            ),
                        )
                        VerifyResult.VERIFIED
                    }

                    // Same pinned key: just record the out-of-band verification.
                    parsed.bundle -> {
                        peers.setVerified(parsed.nodeId, true)
                        VerifyResult.VERIFIED
                    }

                    // A different key for this node id would require a hash collision (impossible for a
                    // self-consistent code, checked above) — refuse rather than overwrite the pin.
                    else -> {
                        VerifyResult.MISMATCH
                    }
                }
        }
    }

    fun consumeScanResult() {
        _scanResult.value = null
    }
}
