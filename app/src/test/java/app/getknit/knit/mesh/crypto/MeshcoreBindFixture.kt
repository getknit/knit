package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import com.google.crypto.tink.subtle.Ed25519Sign

/**
 * A MeshCore board's bind signature for tests (ADR 2026-11.gsqu, Stage 5d): what a board signs through `CMD_SIGN_*` over
 * [MeshcoreBind.message], made here with Tink's RFC 8032 signer from a fixed seed. [SEED] is RFC 8032 TEST 1's secret, so its
 * key is [KEY], the full-order point every MeshCore test already claims. Test keys only.
 */
internal object MeshcoreBindFixture {
    /** RFC 8032 TEST 1's secret key. */
    val SEED: ByteArray = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")

    /** RFC 8032 TEST 1's public key, canonical base64. */
    const val KEY = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="

    /** The public key [seed] signs under, raw. */
    fun keyOf(seed: ByteArray = SEED): ByteArray = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey

    /** The board [seed]'s signature binding [nodeId] to its key, raw. */
    fun sigBytes(
        nodeId: String,
        seed: ByteArray = SEED,
    ): ByteArray = Ed25519Sign(seed).sign(checkNotNull(MeshcoreBind.message(NodeId.toBytes(nodeId), keyOf(seed))))

    /** [sigBytes] as the canonical base64 the profile and the settings carry. */
    fun sig(
        nodeId: String,
        seed: ByteArray = SEED,
    ): String = b64(sigBytes(nodeId, seed))

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
