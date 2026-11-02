package app.getknit.knit.mesh.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The rotating tag a blinding phone writes in bytes 1–16 of its Bluetooth presence advert in place of its node id
 * (ADR 2026-11.xp6d, knit-next #285; the iOS port mirrors it byte for byte). A direct contact holding the sender's
 * presence key resolves it; anyone else sees 16 bytes that change at every rotation. Pure: JCA's HMAC-SHA256, no
 * Android, so it runs unchanged on the JVM.
 *
 * ```
 * bits   0..19  11001 11001 11001 11001         (bytes 0xCE 0x73, high nibble of byte 2 = 0x9)
 * bits  20..63  nonce, 44 bits from SecureRandom (low nibble of byte 2 ‖ bytes 3..7, big-endian)
 * bytes  8..15  first 8 bytes of HMAC-SHA256(presenceKey, "knit/advert/v1/tag" (18 ASCII) ‖ bytes 0..7)
 * ```
 *
 * Why this shape:
 *  - **The `zzzz` prefix.** `11001` is base32 `z`, so the tag read as a node id ([app.getknit.knit.identity.NodeId.fromBytes])
 *    starts `zzzz` and sorts above all but about 2⁻²⁰ of real ids. A shipped build dials only a smaller advertised
 *    id (`DialPlan.ordinaryCandidates`), so it leaves the dial to the blinding phone and the advert needs no new
 *    service UUID. The fixed bits also let [matches] refuse most clear ids before any HMAC.
 *  - **A nonce, not a time slot.** The resolver compares no clock: a tag is recognised whatever the two phones think
 *    the time is. 44 bits collide by birthday only after about 2²² tags under one key, and a key lives a week at
 *    one tag per 10–15 min.
 *  - **64 MAC bits.** A forger without the key lands one tag in 2⁶⁴ per try; a resolver trying every contact's key
 *    against one tag mistakes another's tag for a contact's at that rate per key.
 *  - **Domain separation.** The presence key is used for nothing but this MAC, and `knit/advert/v1/tag` shares no
 *    prefix with the other labels (`knit/link/v1/…`, `knit/dm/v2/…`, `knit/group/v1/…`, `knit/card/v1`). The message
 *    is fixed-length (18 + 8 bytes), so no two inputs concatenate alike.
 *
 * [matches] is total: a key or tag of the wrong size, or a tag without the prefix, answers false and never throws,
 * because the tag is whatever a stranger's radio put on the air.
 */
object BlindTag {
    /** A tag fills the node id's slot in the advert, so it is exactly `NodeId.BYTES` (pinned by `BlindTagTest`). */
    const val BYTES = 16

    /** A presence key: 32 bytes from [SecureRandom], one per generation. */
    const val KEY_BYTES = 32

    const val LABEL = "knit/advert/v1/tag"

    /** The bytes the MAC covers (prefix and nonce) and the bytes of the truncated MAC. */
    private const val HEAD_BYTES = 8
    private const val MAC_BYTES = BYTES - HEAD_BYTES

    /** 44 nonce bits: the low nibble of byte 2 and bytes 3..7. */
    internal const val NONCE_BITS = 44
    internal const val NONCE_MASK = (1L shl NONCE_BITS) - 1

    /** `11001` ×4 left-aligned in bytes 0..2: 0xCE 0x73 0x9_. */
    private const val PREFIX_0 = 0xCE.toByte()
    private const val PREFIX_1 = 0x73.toByte()
    private const val PREFIX_2_HIGH = 0x90
    private const val HIGH_NIBBLE = 0xF0

    /** Byte 2: the prefix's last nibble above the nonce's first. */
    private const val NONCE_FIRST_BYTE = 2

    private const val HMAC = "HmacSHA256"

    private val LABEL_BYTES = LABEL.toByteArray(Charsets.US_ASCII)

    /** A fresh presence key. */
    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** A fresh tag under [key], with a nonce from [random]. Throws on a key that is not [KEY_BYTES]: a caller's bug. */
    fun mint(
        key: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray = mint(key, random.nextLong() and NONCE_MASK)

    /**
     * The tag under [key] for the 44-bit [nonce] (`0 ≤ nonce < 2⁴⁴`). The deterministic half of [mint], for tests and
     * the vectors; production draws the nonce.
     */
    internal fun mint(
        key: ByteArray,
        nonce: Long,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "a presence key is $KEY_BYTES bytes" }
        require(nonce in 0..NONCE_MASK) { "a tag's nonce is $NONCE_BITS bits" }
        val tag = ByteArray(BYTES)
        tag[0] = PREFIX_0
        tag[1] = PREFIX_1
        // Bytes 2..7 take the nonce's low 48 bits big-endian; below 2⁴⁴, byte 2's high nibble is clear for the prefix.
        for (i in NONCE_FIRST_BYTE until HEAD_BYTES) tag[i] = (nonce ushr (Byte.SIZE_BITS * (HEAD_BYTES - 1 - i))).toByte()
        tag[NONCE_FIRST_BYTE] = (tag[NONCE_FIRST_BYTE].toInt() or PREFIX_2_HIGH).toByte()
        mac(newMac(key), tag).copyInto(tag, destinationOffset = HEAD_BYTES)
        return tag
    }

    /** [tag] is [BYTES] long and carries the 20 fixed bits. Says nothing about whose it is. */
    fun hasPrefix(tag: ByteArray): Boolean =
        tag.size == BYTES &&
            tag[0] == PREFIX_0 &&
            tag[1] == PREFIX_1 &&
            (tag[NONCE_FIRST_BYTE].toInt() and HIGH_NIBBLE) == PREFIX_2_HIGH

    /**
     * [tag] was minted under [key]. False, never a throw, for a key that is not [KEY_BYTES], a tag that is not
     * [BYTES] or one without the prefix; the prefix is checked before any HMAC. For many tags against one key, hold
     * a [Matcher].
     */
    fun matches(
        key: ByteArray,
        tag: ByteArray,
    ): Boolean = hasPrefix(tag) && key.size == KEY_BYTES && Matcher(key).matches(tag)

    /**
     * One key's matcher, its [Mac] initialised once, for a resolver that tries every contact's key against each new
     * tag (ADR 2026-11.xp6d, `AdvertAliases`). Not thread-safe: a [Mac] holds state across `update` / `doFinal`, so
     * one matcher belongs to one thread at a time. A key that is not [KEY_BYTES] makes a matcher that matches
     * nothing, so a malformed key a peer delivered can never throw out of a resolve sweep.
     */
    class Matcher(
        key: ByteArray,
    ) {
        private val mac: Mac? = if (key.size == KEY_BYTES) runCatching { newMac(key) }.getOrNull() else null

        /** Same rules as [BlindTag.matches]. */
        fun matches(tag: ByteArray): Boolean {
            val mac = mac ?: return false
            if (!hasPrefix(tag)) return false
            val expected = runCatching { mac(mac, tag) }.getOrNull() ?: return false
            return MessageDigest.isEqual(expected, tag.copyOfRange(HEAD_BYTES, BYTES))
        }
    }

    private fun newMac(key: ByteArray): Mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }

    /** The first [MAC_BYTES] of HMAC(label ‖ tag[0..8)). */
    private fun mac(
        mac: Mac,
        tag: ByteArray,
    ): ByteArray {
        mac.reset() // a matcher whose last call threw mid-update must not carry its bytes into this one
        mac.update(LABEL_BYTES)
        mac.update(tag, 0, HEAD_BYTES)
        return mac.doFinal().copyOf(MAC_BYTES)
    }
}
