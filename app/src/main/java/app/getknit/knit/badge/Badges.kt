@file:OptIn(ExperimentalSerializationApi::class) // Cbor and @ByteString are experimental kotlinx APIs

package app.getknit.knit.badge

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.BadgeEntry
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * A badge signing key this build trusts (docs/SUPPORTER_BADGE.md §4): its [kid] (the first 8 bytes of SHA-256 of the
 * raw public key, as 16 lowercase hex digits) and its raw 32-byte Ed25519 [publicKey]. Equal by value.
 */
class PinnedBadgeKey(
    val kid: String,
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is PinnedBadgeKey && kid == other.kid && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = kid.hashCode()

    override fun toString(): String = "PinnedBadgeKey($kid)"
}

/**
 * The badge keys this build pins (C-4-1..C-4-3). Adding one is a list append that ships in an app release before the key
 * signs anything, so a second issuer or a rotated key needs no wire change.
 */
object BadgeKeys {
    /**
     * The production key, from the maintainer's `npm run anywhere -- keygen --badge` (2026-10-14, handed over by the
     * coordinator): raw public key `Ky3T0yKKHnX9wCcJ2DGti16wcEyYDKGxmtXNb5A8JUE` (unpadded base64url), kid `ea50a7de77a33985`.
     */
    val PRODUCTION: PinnedBadgeKey =
        PinnedBadgeKey(
            kid = "ea50a7de77a33985",
            publicKey = hex("2b2dd3d3228a1e75fdc02709d831ad8b5eb0704c980ca1b19ad5cd6f903c2541"),
        )

    /**
     * RFC 8032 §7.1 TEST 1's public key: what `npm run anywhere:dev` and the tests sign with. Pinned in debug builds
     * only, since its private half is published in the RFC.
     */
    val RFC8032_TEST1: PinnedBadgeKey =
        PinnedBadgeKey(
            kid = "21fe31dfa154a261",
            publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
        )

    /** Every key a build trusts: production always, the RFC test key only where [debug]. */
    fun forBuild(debug: Boolean): List<PinnedBadgeKey> = if (debug) listOf(PRODUCTION, RFC8032_TEST1) else listOf(PRODUCTION)

    /** A key's id: `SHA-256(raw public key)[0..8]` as lowercase hex, the coordinator's `ticketKid` derivation. */
    fun kidOf(publicKey: ByteArray): String = hexOf(sha256(publicKey).copyOf(Badges.KID_BYTES))
}

/** A badge that passed every check: its id [b] and its `exp` (Unix seconds). Carries nothing secret. */
data class VerifiedBadge(
    val b: String,
    val exp: Long,
)

/**
 * The supporter badge's verifier (docs/SUPPORTER_BADGE.md §3, ADR 2026-10.r99s). Pure: no Android type, no clock of its
 * own, no log. [verify] runs the six checks in order and answers the badge or null; a failure is silent by design
 * (C-3-2), so nothing here says which check failed.
 *
 * The receiver runs it again at display time against the local clock (C-3-3), so a badge lapses on an offline phone too.
 */
object Badges {
    /** The signature's domain label: 13 ASCII bytes, no terminator. Not the ticket's `knit/spool/v1/ticket`. */
    const val SIG_LABEL = "knit/badge/v1"

    /** The subject commitment's domain label. */
    const val SUB_LABEL = "knit/badge/v1/sub"

    /** The text form's prefix: `knit-badge:v1:` and the wrapper as unpadded base64url. */
    const val TEXT_PREFIX = "knit-badge:v1:"

    const val VERSION = 1L
    const val MAX_BC_BYTES = 256
    const val SIG_BYTES = 64
    const val KID_BYTES = 8

    /** The holder's per-request randomness: 16 bytes. */
    const val R_BYTES = 16

    /** How far either clock may be off before a badge is refused: five minutes each side. */
    const val SKEW_S = 300L

    /** The longest window a badge may name: 31 days. */
    const val MAX_WINDOW_S = 2_678_400L

    /** The badge ids this build knows how to show. An unknown one is never shown (check 4). */
    const val SUPPORTER = "supporter"
    val KNOWN: Set<String> = setOf(SUPPORTER)

    /**
     * [wrapper] (the CBOR `{bc, sig}` a sealed profile carries) verified as a badge of [senderId], whose holder committed
     * to [r], at [nowS] (Unix seconds), under [keys]. Null on any failure.
     */
    fun verify(
        wrapper: ByteArray,
        r: ByteArray,
        senderId: String,
        nowS: Long,
        keys: List<PinnedBadgeKey>,
    ): VerifiedBadge? {
        val body = issued(wrapper, nowS, keys) ?: return null
        // 6. sub commits to the sender's node id and r.
        val id = NodeId.toBytesOrNull(senderId) ?: return null
        if (r.size != R_BYTES || !MessageDigest.isEqual(subject(id, r), body.sub)) return null
        return VerifiedBadge(body.b, body.exp)
    }

    /**
     * Checks 1–5 alone: what the issuer vouches for, before anything binds it to a holder. Only the vector tests call
     * this directly (the coordinator's vector commits to a `sub` no node id opens); showing a badge always goes through
     * [verify].
     */
    internal fun issuerChecks(
        wrapper: ByteArray,
        nowS: Long,
        keys: List<PinnedBadgeKey>,
    ): VerifiedBadge? = issued(wrapper, nowS, keys)?.let { VerifiedBadge(it.b, it.exp) }

    private fun issued(
        wrapper: ByteArray,
        nowS: Long,
        keys: List<PinnedBadgeKey>,
    ): Body? {
        // 1. The wrapper decodes, bc is at most 256 bytes and sig is 64.
        val outer = decode<Wrapper>(wrapper) ?: return null
        if (outer.bc.size > MAX_BC_BYTES || outer.sig.size != SIG_BYTES) return null
        // 2. bc decodes as version 1; unknown keys are ignored.
        val body = decode<Body>(outer.bc)?.takeIf { it.v == VERSION } ?: return null
        // 3. kid names a pinned key, and sig verifies over the label and bc.
        val key = keys.firstOrNull { it.kid == hexOf(body.kid) } ?: return null
        if (!signatureHolds(key.publicKey, outer.sig, SIG_LABEL.encodeToByteArray() + outer.bc)) return null
        // 4. b is a badge this build knows.
        if (body.b !in KNOWN) return null
        // 5. The window holds, with five minutes of skew each side, and is no longer than 31 days.
        if (nowS < body.nbf - SKEW_S || nowS >= body.exp + SKEW_S || body.exp - body.nbf > MAX_WINDOW_S) return null
        return body
    }

    /**
     * Whether a received entry is small enough to keep: an `r` of [R_BYTES] and a wrapper no bigger than a `bc` of
     * [MAX_BC_BYTES] and a [SIG_BYTES] signature can make (C-6-2). Anything else would fail [verify] anyway.
     */
    fun entryFits(entry: BadgeEntry): Boolean = entry.r.size == R_BYTES && entry.w.size <= MAX_WRAPPER_BYTES

    /** `{bc, sig}` with the largest `bc` and the signature: two text keys and two byte-string heads of overhead. */
    const val MAX_WRAPPER_BYTES = 1 + 3 + 3 + MAX_BC_BYTES + 4 + 2 + SIG_BYTES

    /** `SHA-256("knit/badge/v1/sub" ‖ id ‖ r)`: the commitment a holder sends the issuer, [id] being its 16 raw node-id bytes. */
    fun subject(
        id: ByteArray,
        r: ByteArray,
    ): ByteArray = sha256(SUB_LABEL.encodeToByteArray() + id + r)

    /** The wrapper inside a `knit-badge:v1:` text, or null when it is not one. Only the issuer's answer is text. */
    fun wrapperOf(text: String): ByteArray? {
        if (!text.startsWith(TEXT_PREFIX)) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(text.removePrefix(TEXT_PREFIX)) }.getOrNull() ?: return null
        return bytes.takeIf { decode<Wrapper>(it) != null }
    }

    /** The claims of [wrapper] without checking anything but that they decode: the holder's own bookkeeping. */
    fun claimsOf(wrapper: ByteArray): Claims? {
        val outer = decode<Wrapper>(wrapper) ?: return null
        val body = decode<Body>(outer.bc) ?: return null
        return Claims(body.b, body.nbf, body.exp)
    }

    /** What [claimsOf] reads: the badge id and its window, in Unix seconds. */
    data class Claims(
        val b: String,
        val nbf: Long,
        val exp: Long,
    )

    private fun signatureHolds(
        publicKey: ByteArray,
        sig: ByteArray,
        message: ByteArray,
    ): Boolean =
        runCatching {
            Ed25519Verify(publicKey).verify(sig, message)
            true
        }.getOrDefault(false)

    private inline fun <reified T> decode(bytes: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(bytes) }.getOrNull()

    private val cbor =
        Cbor {
            ignoreUnknownKeys = true
            alwaysUseByteString = true
        }

    @Serializable
    private class Wrapper(
        @ByteString val bc: ByteArray,
        @ByteString val sig: ByteArray,
    )

    @Serializable
    private class Body(
        val v: Long,
        @ByteString val kid: ByteArray,
        val b: String,
        @ByteString val sub: ByteArray,
        val nbf: Long,
        val exp: Long,
    )
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

internal fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

private const val HEX_RADIX = 16
