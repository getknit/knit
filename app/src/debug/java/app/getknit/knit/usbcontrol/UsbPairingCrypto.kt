package app.getknit.knit.usbcontrol

import com.google.crypto.tink.subtle.Hkdf
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pairing a computer with this phone over USB control (`docs/USB_CONTROL.md`, *Pairing*). Pure: the JDK's PBKDF2 and
 * Tink's HKDF, no Android, so the vectors (`vectors/usb-control-v2.json`) pin it on the JVM and knit-cli checks the same
 * bytes.
 *
 * ```
 * code   = 10 Crockford base32 characters (50 random bits), shown "7KQFM 2XD9R", typed on the computer
 * ss     = X25519(own e, peer e)                                  the pair exchange's ephemerals (UsbSeal.sharedSecret)
 * c      = PBKDF2-HMAC-SHA256(code ASCII, salt = eH ‖ eP, 200,000 iterations, 32)
 * okm    = HKDF-SHA256(ikm = ss, salt = c, info = "knit/usb/v2/pair" ‖ eH ‖ eP ‖ u32be(len client) ‖ client, 112)
 *        = confirmHost (32) ‖ confirmPhone (32) ‖ psk (32) ‖ handle (16)
 * ```
 *
 * Why this shape (the 2026-11-12 security design pass):
 *  - **The code is the only secret the computer is told.** It authenticates both ephemerals, and only a party that saw
 *    it on the unlocked phone can produce `confirmHost`. The phone answers `confirmPhone` only after `confirmHost`
 *    verifies, so a party in the cable learns one check value per exchange, from the computer's side.
 *  - **Stretched, salted by the transcript.** Each guess at that check costs PBKDF2's 200,000 rounds, and the salt is
 *    the exchange's own key shares, so no table is worth building. Both sides give up 120 s after the exchange started,
 *    each on its own clock, so a search has that long and no longer.
 *  - **Everything the phone keeps is bound.** The computer's claimed name (`client`) is in the info, and the handle a
 *    session later names the pairing by comes out of the schedule, so a party in the cable that leaves the keys alone
 *    still can't rename the pairing or hand the computer a handle the phone doesn't hold.
 */
internal object UsbPairingCrypto {
    const val KEY_BYTES = 32
    const val HANDLE_BYTES = 16
    const val CODE_CHARS = 10
    const val PBKDF2_ITERATIONS = 200_000

    /** The longest claimed name, in UTF-8 bytes, a `pair` may carry. */
    const val MAX_CLIENT_BYTES = 64

    /** Crockford's base32: no I, L, O or U, so what the phone shows can't be misread as another symbol. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    const val LABEL = "knit/usb/v2/pair"

    private const val HKDF_MAC = "HMACSHA256"
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val BITS_PER_BYTE = 8
    private const val HALF = CODE_CHARS / 2
    private val LABEL_BYTES = LABEL.toByteArray(Charsets.US_ASCII)
    private const val CONFIRM_PHONE_AT = KEY_BYTES
    private const val PSK_AT = 2 * KEY_BYTES
    private const val HANDLE_AT = 3 * KEY_BYTES
    private const val OKM_BYTES = HANDLE_AT + HANDLE_BYTES

    /** What one pair exchange derives: the two check values, the pairing's psk and its handle. */
    class Outputs(
        val confirmHost: ByteArray,
        val confirmPhone: ByteArray,
        val psk: ByteArray,
        val handle: ByteArray,
    )

    /** A fresh code: ten symbols of [ALPHABET], each five uniform bits from [rng]. */
    fun drawCode(rng: SecureRandom = SecureRandom()): String = String(CharArray(CODE_CHARS) { ALPHABET[rng.nextInt(ALPHABET.length)] })

    /** [code] as the phone shows it: two groups of five. */
    fun display(code: String): String = code.substring(0, HALF) + " " + code.substring(HALF)

    /**
     * What a person typed, as the code it stands for: upper-cased, spaces and dashes dropped, `O` read as `0` and `I`
     * or `L` as `1` (Crockford's own reading). Null when what is left is not ten symbols of [ALPHABET].
     */
    fun normalize(typed: String): String? {
        val code =
            buildString {
                for (ch in typed.uppercase()) {
                    when (ch) {
                        ' ', '-' -> Unit
                        'O' -> append('0')
                        'I', 'L' -> append('1')
                        else -> append(ch)
                    }
                }
            }
        return code.takeIf { it.length == CODE_CHARS && it.all { ch -> ch in ALPHABET } }
    }

    /** `c`, the code stretched over this exchange's key shares. */
    fun stretch(
        code: String,
        eH: ByteArray,
        eP: ByteArray,
    ): ByteArray {
        require(code.length == CODE_CHARS && code.all { it in ALPHABET }) { "not a pairing code" }
        val spec = PBEKeySpec(code.toCharArray(), eH + eP, PBKDF2_ITERATIONS, KEY_BYTES * BITS_PER_BYTE)
        return try {
            SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** The HKDF info: the label, both key shares as they crossed, and the computer's claimed name. */
    fun info(
        eH: ByteArray,
        eP: ByteArray,
        client: ByteArray,
    ): ByteArray {
        require(eH.size == KEY_BYTES && eP.size == KEY_BYTES) { "a key share is $KEY_BYTES bytes" }
        require(client.size in 1..MAX_CLIENT_BYTES) { "a claimed name is 1..$MAX_CLIENT_BYTES bytes" }
        return ByteBuffer
            .allocate(LABEL_BYTES.size + PSK_AT + Int.SIZE_BYTES + client.size)
            .put(LABEL_BYTES)
            .put(eH)
            .put(eP)
            .putInt(client.size)
            .put(client)
            .array()
    }

    /** The exchange's [Outputs] from its shared secret [ss], the [code], both key shares and the [client] name's bytes. */
    fun schedule(
        ss: ByteArray,
        code: String,
        eH: ByteArray,
        eP: ByteArray,
        client: ByteArray,
    ): Outputs {
        val c = stretch(code, eH, eP)
        val okm =
            try {
                Hkdf.computeHkdf(HKDF_MAC, ss, c, info(eH, eP, client), OKM_BYTES)
            } finally {
                c.fill(0)
            }
        try {
            return Outputs(
                confirmHost = okm.copyOfRange(0, CONFIRM_PHONE_AT),
                confirmPhone = okm.copyOfRange(CONFIRM_PHONE_AT, PSK_AT),
                psk = okm.copyOfRange(PSK_AT, HANDLE_AT),
                handle = okm.copyOfRange(HANDLE_AT, OKM_BYTES),
            )
        } finally {
            okm.fill(0)
        }
    }
}
