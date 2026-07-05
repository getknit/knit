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
 * The id is **128 bits** of the salted SHA-256, [RFC4648 base32][base32Encode]-encoded (lowercase,
 * unpadded) to a 26-char `[a-z2-7]` string. 128 bits puts a colliding bundle out of reach (`2^64`
 * birthday, `2^128` second-preimage), which is what the self-certifying claim above actually rests
 * on — the historical 8-char `[a-z0-9]` id was only ~41 bits and did not. base32 keeps the id
 * filesystem- and delimiter-safe (no `:` `/` `-`), so the `"$nodeId.jpg"` avatar files, the `:`-split
 * verify payload, and the `g-…` group-id namespace all stay disjoint from it. The BLE advert carries
 * the raw 16 bytes ([toBytes]/[fromBytes]); everything else carries the 26-char string.
 *
 * Pure and Android-free so it can be unit-tested and shared with a future iOS port.
 */
object NodeId {
    /** The id's width in bytes of digest material (128 bits). */
    const val BYTES = 16

    /** The base32-encoded id length in chars (`ceil(128 / 5)`). */
    const val LENGTH = 26

    /** RFC4648 base32 alphabet, lowercased. */
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /**
     * The self-certifying nodeId for the public-key [bundle] (its base64 [PublicKeyBundle.encoded]
     * form). Both sides of a conversation compute the same id from the same advertised bundle, and an
     * inbound profile is trusted only if its bundle derives back to the claimed senderId.
     */
    fun fromPublicKeyBundle(bundle: String): String = derive(bundle)

    /** Deterministic 26-char `[a-z2-7]` id: base32 of the first [BYTES] bytes of the salted SHA-256 of [seed]. */
    fun derive(seed: String): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest((SALT + seed).encodeToByteArray())
        return base32Encode(digest.copyOf(BYTES))
    }

    /** The 16 raw id bytes for the BLE advert (the base32 string decoded). Inverse of [fromBytes]. */
    fun toBytes(nodeId: String): ByteArray = base32Decode(nodeId)

    /** The 26-char id string for [BYTES] raw advert bytes (the bytes base32-encoded). Inverse of [toBytes]. */
    fun fromBytes(id: ByteArray): String = base32Encode(id)

    /**
     * RFC4648 base32 (lowercase, unpadded). Only the low `bits` positions of the accumulator are ever
     * read (`ushr … and 0x1F`), so Int overflow of the unused high bits across the loop is harmless.
     */
    @Suppress("MagicNumber") // 5 = bits/symbol, 8 = bits/byte, 0x1F/0xFF = 5-/8-bit masks
    fun base32Encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var value = 0
        var bits = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(value ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(value shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /** Inverse of [base32Encode]; throws on an out-of-alphabet char (only ever called on our own ids). */
    @Suppress("MagicNumber") // 5 = bits/symbol, 8 = bits/byte, 0xFF = 8-bit mask
    fun base32Decode(s: String): ByteArray {
        val out = ByteArray(s.length * 5 / 8)
        var value = 0
        var bits = 0
        var i = 0
        for (c in s) {
            val idx = ALPHABET.indexOf(c)
            require(idx >= 0) { "invalid base32 char '$c'" }
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out[i++] = ((value ushr (bits - 8)) and 0xFF).toByte()
                bits -= 8
            }
        }
        return out
    }

    /** App-specific salt; provides domain separation and decouples the id from the raw input. */
    private const val SALT = "knit-node-id-v2:"
}
