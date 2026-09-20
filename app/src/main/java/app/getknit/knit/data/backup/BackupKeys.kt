package app.getknit.knit.data.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.util.SecretBytes
import java.security.SecureRandom

/**
 * The recovery key and the file key it derives.
 *
 * A backup is locked by a **recovery key** the app mints — [DIGITS] decimal digits from a CSPRNG, shown once
 * as six groups of five — rather than a passphrase the user chooses: a uniform 30-digit key carries ~100
 * bits, so no slow KDF is needed to make it hard to guess, and the app never has to judge whether a
 * passphrase was good enough for the file that carries someone's whole identity. The key is the one thing
 * that opens the file; the app does not keep it.
 *
 * File key: `HKDF-SHA256(ikm = the digits as ASCII, salt = header.salt, info = "knit/backup/v1/key")`,
 * 32 bytes, straight into Tink's AES-256-GCM-HKDF streaming AEAD with 1 MiB segments — Tink derives a
 * per-file subkey from its own header nonce, so one recovery key across several backups is fine.
 */
object BackupKeys {
    /** Digits in a recovery key. */
    const val DIGITS = 30

    /** Digits per displayed group ("12345 67890 …"). */
    const val GROUP = 5

    private const val KEY_BYTES = 32
    private const val MAC = "HMACSHA256"
    private val LABEL_FILE_KEY = "knit/backup/v1/key".encodeToByteArray()

    /** A fresh recovery key: [DIGITS] uniform decimal digits, no grouping. */
    fun generate(random: SecureRandom = SecureRandom()): String =
        buildString(DIGITS) { repeat(DIGITS) { append('0' + random.nextInt(RADIX)) } }

    /** "12345 67890 12345 …" — [key] grouped by [GROUP] for the key card and the clipboard. */
    fun display(key: String): String = key.chunked(GROUP).joinToString(" ")

    /**
     * The digits of what the user typed, or null when it is not a recovery key: every non-digit
     * (spaces, dashes, the grouping [display] adds) is dropped first, and the rest must be exactly
     * [DIGITS] long.
     */
    fun parse(typed: String): String? = typed.filter { it.isDigit() }.takeIf { it.length == DIGITS }

    /** A fresh header salt. */
    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(BackupFormat.SALT_BYTES).also { random.nextBytes(it) }

    /** The streaming AEAD for a file whose header carries [salt], under [recoveryKey] (digits only). */
    fun streamingAead(
        recoveryKey: String,
        salt: ByteArray,
    ): StreamingAead {
        require(recoveryKey.length == DIGITS && recoveryKey.all { it.isDigit() }) { "not a recovery key" }
        val ikm = Hkdf.computeHkdf(MAC, recoveryKey.encodeToByteArray(), salt, LABEL_FILE_KEY, KEY_BYTES)
        val key =
            AesGcmHkdfStreamingKey.create(
                PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,
                SecretBytes.copyFrom(ikm, InsecureSecretKeyAccess.get()),
            )
        return AesGcmHkdfStreaming.create(key)
    }

    private const val RADIX = 10
}
