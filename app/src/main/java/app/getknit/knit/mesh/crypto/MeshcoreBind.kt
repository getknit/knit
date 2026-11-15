package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.CanonicalText
import com.google.crypto.tink.subtle.Ed25519Verify
import java.math.BigInteger

/**
 * The proof that a sealed profile's `meshcoreKey` names a board its sender's phone drives (ADR 2026-11.gsqu, Stage 5d
 * amendment): the board's own Ed25519 signature ([SIG_BYTES] bytes, `ProfilePayload.meshcoreSig`) over [message] — [DOMAIN]
 * ‖ the sender's 16 raw [NodeId] bytes ‖ the board's 32-byte key, [MESSAGE_BYTES] bytes of fixed lengths. A board makes it
 * through `CMD_SIGN_START` / `SIGN_DATA` / `SIGN_FINISH` (`MyMesh.cpp:1781-1815`), standard RFC 8032 Ed25519, which is
 * deterministic: one board signs one (node, key) to one signature every time.
 *
 * **Domain separation.** The consent proof signs `"knit-meshcore-consent/v1"` ‖ a nonce, which differs from [DOMAIN] at
 * byte 14; a MeshCore advert's signed bytes begin with the board's own key, never this ASCII.
 *
 * **Verify order** ([verifies]): the key first, through [MeshcoreKey] (canonical, an Ed25519 point, not of low order), then
 * `S < L`, then Tink. Tink's `Ed25519Verify` is cofactorless and checks no key order: under a low-order key, `R` = the
 * identity and `S = 0` verifies every message, so a key that skipped [MeshcoreKey] would let anyone "prove" it. Every
 * caller uses this, the MeshCore session's check of the board's answer included, never a verifier of its own.
 *
 * **What it does not prove.** Only that some client the board accepted over BLE (or USB) asked it to sign, once: a board
 * on the default PIN signs for anyone in range while its owner's phone is away, the signature never expires and nothing
 * revokes it short of re-keying the board. So two proven claimants of one key stay possible, and the contested rule
 * (`MeshcoreBoardMap`) is unchanged: a proof never breaks a tie. The signature is a transferable, permanent statement that
 * node N runs MeshCore key K, sent to direct contacts only (`SECURITY.md`).
 *
 * Pure; adds no primitive (ADR 016): Tink's Ed25519, already the badge's and the consent proof's. Never throws.
 */
internal object MeshcoreBind {
    /** `"knit-meshcore-bind/v1"`, 21 ASCII bytes. */
    val DOMAIN: ByteArray = "knit-meshcore-bind/v1".encodeToByteArray()

    /** An Ed25519 signature: `R` (32) ‖ `S` (32, little-endian). */
    const val SIG_BYTES = 64

    /** [DOMAIN] (21) ‖ node id (16) ‖ key (32). */
    val MESSAGE_BYTES: Int = DOMAIN.size + NodeId.BYTES + MeshcoreKey.BYTES

    /** The bytes a board signs to bind [key] to [nodeId]; null unless both are their exact lengths. */
    fun message(
        nodeId: ByteArray,
        key: ByteArray,
    ): ByteArray? = if (nodeId.size != NodeId.BYTES || key.size != MeshcoreKey.BYTES) null else DOMAIN + nodeId + key

    /**
     * Whether [sig] is [key]'s binding of [nodeId]: [key] passes [MeshcoreKey.accepts], [sig] is [SIG_BYTES] with `S`
     * below the group order, and Tink verifies it over [message]. False for anything else, never a throw.
     */
    fun verifies(
        nodeId: ByteArray,
        key: ByteArray,
        sig: ByteArray,
    ): Boolean {
        if (!MeshcoreKey.accepts(key)) return false
        return verifiesChecked(nodeId, key, sig)
    }

    /**
     * [verifies] over the text forms the profile and the settings carry: [nodeId] a node id, [key] and [sig] canonical
     * padded base64 ([MeshcoreKey.bytesOrNull], whose point verdict is memoized; [sigBytesOrNull]).
     */
    fun verifies(
        nodeId: String,
        key: String,
        sig: String,
    ): Boolean {
        val node = NodeId.toBytesOrNull(nodeId) ?: return false
        val keyBytes = MeshcoreKey.bytesOrNull(key) ?: return false
        val sigBytes = sigBytesOrNull(sig) ?: return false
        return verifiesChecked(node, keyBytes, sigBytes)
    }

    /**
     * The key a receiver stores from a sealed profile [sender] sent (ADR 2026-11.gsqu, Stage 5d): [key] when it is canonical
     * and full-order ([MeshcoreKey]) and [sig] is the board's signature over [sender]'s bind ([verifies]); null otherwise,
     * which drops the field alone and clears a stored key. [sender] must be the frame's authenticated sender: a key copied
     * from another contact's profile, signature and all, never verifies, since the signature names that contact's node.
     */
    fun provenKey(
        sender: String,
        key: String?,
        sig: String?,
    ): String? {
        val canonical = MeshcoreKey.canonicalOrNull(key) ?: return null
        return canonical.takeIf { sig != null && verifies(sender, it, sig) }
    }

    /** The [SIG_BYTES] bytes behind [text] when it is their canonical padded standard base64; null otherwise. */
    fun sigBytesOrNull(text: String): ByteArray? = CanonicalText.base64BytesOrNull(text)?.takeIf { it.size == SIG_BYTES }

    /** [verifies] past the key check. */
    private fun verifiesChecked(
        nodeId: ByteArray,
        key: ByteArray,
        sig: ByteArray,
    ): Boolean {
        val message = message(nodeId, key) ?: return false
        if (sig.size != SIG_BYTES || !sBelowOrder(sig)) return false
        return runCatching {
            Ed25519Verify(key).verify(sig, message)
            true
        }.getOrDefault(false)
    }

    /**
     * RFC 8032 §5.1.7 step 1: `S` (the signature's last 32 bytes, little-endian) must be below `L`, or `S + L` also verifies.
     * The Tink on the classpath checks this too; this check keeps the rule ours whatever a Tink upgrade does.
     */
    internal fun sBelowOrder(sig: ByteArray): Boolean = BigInteger(1, sig.copyOfRange(SIG_BYTES / 2, SIG_BYTES).reversedArray()) < L

    /** The order of the prime subgroup, L = 2^252 + 27742317777372353535851937790883648493. */
    private val L: BigInteger =
        BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))
}
