package app.getknit.knit.identity

import java.security.MessageDigest

/**
 * Derivation of the device's [nodeId][Identity.nodeId].
 *
 * A nodeId is the hash of the device's **public-key bundle** ([fromPublicKeyBundle]) — i.e. identity
 * is *self-certifying*: a peer can only legitimately claim a nodeId for which it actually holds the
 * matching keypair, because deriving a different bundle to the same id is a (computationally
 * infeasible) hash collision. This binds the routing/addressing id to the E2E key and makes the
 * trust-on-first-use key pin race-proof (see `MeshManager.handleProfile`). Losing the keypair (e.g.
 * an app-data wipe that drops `identity.key`) therefore mints a *new* identity — the correct model
 * for a cryptographic id, where your identity is your key.
 *
 * Pure and Android-free so it can be unit-tested and shared with a future iOS port. The output keeps
 * the historical 8-char `[a-z0-9]` shape so every existing consumer (the mesh endpoint-info advert,
 * `"$nodeId.jpg"` avatar files, the friendly [Alias], the profile-frame id) is unaffected.
 */
object NodeId {
    const val LENGTH = 8
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /**
     * The self-certifying nodeId for the public-key [bundle] (its base64 [PublicKeyBundle.encoded]
     * form). Both sides of a conversation compute the same id from the same advertised bundle, and an
     * inbound profile is trusted only if its bundle derives back to the claimed senderId.
     */
    fun fromPublicKeyBundle(bundle: String): String = derive(bundle)

    /** Deterministic 8-char `[a-z0-9]` id: the salted SHA-256 of [seed] mapped into the alphabet. */
    @Suppress("MagicNumber") // 0xFF masks a signed byte to its unsigned 0–255 value before indexing
    fun derive(seed: String): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest((SALT + seed).encodeToByteArray())
        return (0 until LENGTH)
            .map { ALPHABET[(digest[it].toInt() and 0xFF) % ALPHABET.length] }
            .joinToString("")
    }

    /** App-specific salt; provides domain separation and decouples the id from the raw input. */
    private const val SALT = "knit-node-id-v1:"
}
