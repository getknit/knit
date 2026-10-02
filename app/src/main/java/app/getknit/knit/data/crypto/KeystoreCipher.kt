package app.getknit.knit.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The four AndroidKeyStore operations a wrapped secret needs, behind one seam. The decisions around them —
 * what a failed unwrap proves, when a key may be generated — live in [KeystoreSecret], where a JVM test can
 * reach them: Robolectric has no AndroidKeyStore. [AndroidKeystoreCipher] is the real one. ADR 2026-10.47rw.
 */
interface KeystoreCipher {
    /** The key under [alias], or null when the Keystore reports none there. A refusal throws. */
    fun key(alias: String): SecretKey?

    /** Generates a fresh key under [alias], **replacing** any key already there. */
    fun generate(alias: String): SecretKey

    /** `iv ‖ AES-256-GCM(plain)` under [key], the IV chosen by the provider. */
    fun seal(
        key: SecretKey,
        plain: ByteArray,
    ): ByteArray

    /** Opens what [seal] wrote; throws whatever `Cipher.init` or `doFinal` threw. */
    fun open(
        key: SecretKey,
        wrapped: ByteArray,
    ): ByteArray

    companion object {
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128

        /** The shortest wrap that can be one: an IV and a GCM tag around an empty plaintext. */
        const val MIN_WRAP_BYTES = IV_LENGTH + GCM_TAG_BITS / 8
    }
}

/**
 * The AndroidKeyStore behind [KeystoreCipher]: AES-256-GCM keys that never leave the device, StrongBox-backed
 * where the phone has one and TEE-backed where it doesn't.
 *
 * The lookup is `KeyStore.getKey`, never `getEntry`: on keystore2 (API 31+) `getEntry` runs the base class's
 * `engineContainsAlias` first, which swallows every backend error and answers null — a busy Keystore read as a
 * missing key. `getKey` answers null only for KEY_NOT_FOUND and throws `UnrecoverableKeyException` for the rest.
 * Below API 31 the legacy keystore still reads a dead keystore daemon as "no key"; [KeystoreSecret] re-asks.
 *
 * No user-authentication or unlocked-device requirement on the key: it is unwrapped while the graph is built,
 * which a START_STICKY restart or a boot does screen-off and locked.
 */
object AndroidKeystoreCipher : KeystoreCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256

    override fun key(alias: String): SecretKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

    // Prefer a StrongBox (secure-element) backed key; fall back to a TEE-backed key on devices without one.
    override fun generate(alias: String): SecretKey =
        runCatching { generate(alias, strongBox = true) }
            .getOrElse { e ->
                if (e is StrongBoxUnavailableException) generate(alias, strongBox = false) else throw e
            }

    override fun seal(
        key: SecretKey,
        plain: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(plain)
        return cipher.iv + ciphertext
    }

    override fun open(
        key: SecretKey,
        wrapped: ByteArray,
    ): ByteArray {
        val iv = wrapped.copyOfRange(0, KeystoreCipher.IV_LENGTH)
        val ciphertext = wrapped.copyOfRange(KeystoreCipher.IV_LENGTH, wrapped.size)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(KeystoreCipher.GCM_TAG_BITS, iv))
            }
        return cipher.doFinal(ciphertext)
    }

    private fun generate(
        alias: String,
        strongBox: Boolean,
    ): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
