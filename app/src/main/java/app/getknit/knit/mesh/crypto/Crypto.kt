package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-time registration of the Tink primitives the E2E layer uses: hybrid encryption (HPKE/ECIES, for
 * wrapping a per-message content key to each recipient) and signatures (Ed25519, for authenticating the
 * sender). AEAD for the message body is done with the JDK [javax.crypto] AES-GCM below, so no Tink AEAD
 * config is needed. Safe to call repeatedly from any thread.
 */
object TinkInit {
    @Volatile private var registered = false

    @Synchronized
    fun ensure() {
        if (registered) return
        HybridConfig.register()
        SignatureConfig.register()
        registered = true
    }
}

/**
 * AES-256-GCM helper mirroring [app.getknit.knit.data.crypto.DatabaseKey]'s parameters (12-byte random
 * IV, 128-bit tag). Pure JDK crypto so it runs unchanged under JVM unit tests. [aad] is authenticated
 * but not encrypted — the E2E layer binds the message header (id/sender/thread) into it.
 */
object AesGcm {
    const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val KEY_BYTES = 32

    fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** Encrypts [plain] under [key], returning the random (iv, ciphertext) pair. */
    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }
        return iv to cipher.doFinal(plain)
    }

    /** Decrypts [ct] under [key]/[iv]; throws on a bad key, tag, or aad. */
    fun decrypt(key: ByteArray, iv: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }
        return cipher.doFinal(ct)
    }
}

internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal fun b64d(value: String): ByteArray = Base64.getDecoder().decode(value)

/** Shared CBOR codec for the binary blobs the crypto layer (de)serializes (key bundles, content, keys). */
@OptIn(ExperimentalSerializationApi::class)
internal val cryptoCbor: Cbor = Cbor { ignoreUnknownKeys = true }
