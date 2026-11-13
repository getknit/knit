package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.CanonicalText
import java.math.BigInteger

/**
 * The one gate a MeshCore board's public key passes before Knit stores, sends or compares it (ADR 2026-11.gsqu, Stage 4):
 * `ProfilePayload.meshcoreKey` on the way in, the bound board's own key (`meshcore_board_pubkey`) on the way out, and every
 * row `MeshManager.meshcoreBoardMap` reads.
 *
 * A key is accepted only as the **canonical** padded standard base64 of exactly [BYTES] bytes
 * ([CanonicalText.base64BytesOrNull], the round trip the v3 encoder already applies), so one key has one spelling and a
 * string comparison of two stored keys is a byte comparison. The bytes must be an Ed25519 point (RFC 8032 §5.1.3: `y`
 * below p, a square root for `x`, no negative zero), and not one of low order: the all-zero key, the identity and the
 * other six points of the 8-torsion all decode, and a board can never hold one (the #172 precedent, where a low-order
 * X25519 key a peer signed as its prekey made every seal against it throw). The board signs with this key and its X25519
 * twin is what two boards agree a DM secret under, so a low-order key could only ever name a board nobody can reach.
 *
 * This is a validity check over field arithmetic, never a signature or an agreement: it adds no primitive (ADR 016) and
 * holds no secret. Pure JVM; never throws.
 */
internal object MeshcoreKey {
    /** A MeshCore board's public key is a raw 32-byte Ed25519 key (`SELF_INFO`, `ADV_PUB_KEY`). */
    const val BYTES = 32

    /** [text] itself when it passes, which is then its canonical spelling; null otherwise. */
    fun canonicalOrNull(text: String?): String? = text?.takeIf { bytesOrNull(it) != null }

    /** The 32 bytes behind [text] when it is a canonical, full-order Ed25519 public key; null otherwise. */
    fun bytesOrNull(text: String): ByteArray? = CanonicalText.base64BytesOrNull(text)?.takeIf(::accepts)

    /** Whether [raw] is exactly [BYTES] bytes of an Ed25519 point that is not of low order. */
    fun accepts(raw: ByteArray): Boolean {
        if (raw.size != BYTES) return false
        val point = decode(raw) ?: return false
        return !isLowOrder(point)
    }

    /** An affine point on edwards25519. */
    private class Point(
        val x: BigInteger,
        val y: BigInteger,
    )

    /** The field prime, 2^255 − 19. (`BigInteger.TWO` is API 33; minSdk is 29.) */
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** The curve constant d = −121665 / 121666. */
    private val D: BigInteger =
        BigInteger.valueOf(-121_665).multiply(BigInteger.valueOf(121_666).modInverse(P)).mod(P)

    /** √−1 = 2^((p − 1) / 4). */
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** (p + 3) / 8, the exponent of RFC 8032's square-root candidate. */
    private val SQRT_EXP: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

    private const val SIGN_BIT = 0x80
    private const val LOW_BITS = 0x7F

    /** The low-order points are exactly those that 8·P sends to the identity: three doublings. */
    private const val COFACTOR_DOUBLINGS = 3

    /** RFC 8032 §5.1.3, step by step; null for anything that is not the canonical encoding of a point. */
    private fun decode(raw: ByteArray): Point? {
        val sign = (raw[BYTES - 1].toInt() and SIGN_BIT) != 0
        val le = raw.copyOf().also { it[BYTES - 1] = (it[BYTES - 1].toInt() and LOW_BITS).toByte() }
        val y = BigInteger(1, le.reversedArray())
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign) null else Point(BigInteger.ZERO, y)
        var x = x2.modPow(SQRT_EXP, P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).mod(P) != x2) return null
        if (x.testBit(0) != sign) x = P.subtract(x)
        return Point(x, y)
    }

    private fun isLowOrder(point: Point): Boolean {
        var q = point
        repeat(COFACTOR_DOUBLINGS) { q = add(q, q) }
        return q.x.signum() == 0 && q.y == BigInteger.ONE
    }

    /** The complete twisted Edwards addition for a = −1 (d is not a square, so no denominator is ever zero). */
    private fun add(
        a: Point,
        b: Point,
    ): Point {
        val t =
            D
                .multiply(a.x)
                .multiply(b.x)
                .multiply(a.y)
                .multiply(b.y)
                .mod(P)
        val x =
            a.x
                .multiply(b.y)
                .add(a.y.multiply(b.x))
                .multiply(BigInteger.ONE.add(t).modInverse(P))
                .mod(P)
        val y =
            a.y
                .multiply(b.y)
                .add(a.x.multiply(b.x))
                .multiply(
                    BigInteger.ONE
                        .subtract(t)
                        .mod(P)
                        .modInverse(P),
                ).mod(P)
        return Point(x, y)
    }
}
