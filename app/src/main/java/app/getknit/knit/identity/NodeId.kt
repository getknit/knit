package app.getknit.knit.identity

import java.security.MessageDigest

/**
 * Derivation of the device's stable [nodeId][Identity.nodeId].
 *
 * The id is a deterministic function of a stable per-device seed (Android's `ANDROID_ID`, supplied
 * via [DeviceIdSource]), so clearing app data regenerates the *same* id rather than minting a fresh
 * random identity. Pure and Android-free so it can be unit-tested and shared with a future iOS port.
 *
 * The output keeps the historical 8-char `[a-z0-9]` shape so every existing consumer (the Nearby
 * endpoint name, `"$nodeId.jpg"` avatar files, the friendly [Alias], the profile-frame id) is
 * unaffected. The seed is salted so the id is not literally the raw device identifier.
 */
object NodeId {
    const val LENGTH = 8
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** Deterministic 8-char `[a-z0-9]` id derived from a stable device [seed]. */
    fun derive(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((SALT + seed).encodeToByteArray())
        return (0 until LENGTH)
            .map { ALPHABET[(digest[it].toInt() and 0xFF) % ALPHABET.length] }
            .joinToString("")
    }

    /** App-specific salt; prevents the id from being the raw `ANDROID_ID` and decouples derivation. */
    private const val SALT = "knit-node-id-v1:"
}
