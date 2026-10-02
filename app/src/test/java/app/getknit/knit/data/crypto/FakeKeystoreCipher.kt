package app.getknit.knit.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [KeystoreCipher] for the JVM, where Robolectric has no AndroidKeyStore: real JCE AES-GCM over in-memory keys, so a
 * swapped key fails its tag for real, plus scripted answers for the next lookups and unwraps. A scripted lookup of
 * [MISSING] reports no key whatever [keys] holds — a legacy keystore reading a dead daemon as absent.
 */
class FakeKeystoreCipher : KeystoreCipher {
    val keys = mutableMapOf<String, SecretKey>()

    /** What the next [key] calls do instead of looking: throw, or [MISSING]. */
    val keyScript = ArrayDeque<Throwable>()

    /** What the next [open] calls throw instead of unwrapping. */
    val openScript = ArrayDeque<Throwable>()

    var lookups = 0
        private set
    var opens = 0
        private set
    var generated = 0
        private set

    override fun key(alias: String): SecretKey? {
        lookups++
        keyScript.removeFirstOrNull()?.let { if (it === MISSING) return null else throw it }
        return keys[alias]
    }

    override fun generate(alias: String): SecretKey {
        generated++
        return newKey().also { keys[alias] = it }
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
        opens++
        openScript.removeFirstOrNull()?.let { throw it }
        val iv = wrapped.copyOfRange(0, KeystoreCipher.IV_LENGTH)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(KeystoreCipher.GCM_TAG_BITS, iv))
            }
        return cipher.doFinal(wrapped, KeystoreCipher.IV_LENGTH, wrapped.size - KeystoreCipher.IV_LENGTH)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** A scripted lookup that answers "no key". */
        val MISSING = Throwable("scripted: no key")

        fun newKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    }
}
