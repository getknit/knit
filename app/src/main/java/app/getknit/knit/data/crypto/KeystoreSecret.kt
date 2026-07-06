package app.getknit.knit.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small secret persisted on disk wrapped (AES-256-GCM) by a hardware-backed [AndroidKeyStore] key.
 * The Keystore key never leaves the device and is excluded from cloud backup, so the wrapped blob is
 * useless if copied elsewhere. Generalizes the wrap/unwrap pattern used by [DatabaseKey] so other
 * long-lived secrets (e.g. the E2E identity private keysets in
 * [app.getknit.knit.data.crypto.IdentityKeyStore]) can reuse it.
 *
 * Each instance owns its own Keystore [alias] and on-disk [fileName] under `filesDir`. Opening is
 * transparent (no user-auth requirement on the Keystore key).
 */
class KeystoreSecret(
    private val context: Context,
    private val alias: String,
    private val fileName: String,
) {
    private val file: File get() = File(context.filesDir, fileName)

    fun exists(): Boolean = file.exists()

    /** Decrypts and returns the stored secret, or null if absent/unrecoverable. */
    fun load(): ByteArray? = if (file.exists()) runCatching { decrypt() }.getOrNull() else null

    /** Wraps [plain] under the Keystore key and writes it, replacing any prior value. */
    @Synchronized
    fun store(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val ciphertext = cipher.doFinal(plain)
        file.writeBytesAtomically(cipher.iv + ciphertext)
    }

    fun delete() {
        file.delete()
    }

    private fun decrypt(): ByteArray {
        val blob = file.readBytes()
        require(blob.size > IV_LENGTH) { "wrapped secret too short" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        val key = existingKeystoreKey() ?: throw GeneralSecurityException("Keystore key missing")
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        return cipher.doFinal(ciphertext)
    }

    private fun keystoreKey(): SecretKey = existingKeystoreKey() ?: generateKeystoreKey()

    private fun existingKeystoreKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    // Prefer a StrongBox (secure-element) backed key; fall back to a TEE-backed key on devices without
    // one. Note we deliberately do NOT set setUnlockedDeviceRequired here: the secret is unwrapped
    // transparently during graph construction, which can happen while the device is locked (the mesh
    // foreground service is START_STICKY and may be restarted by the system screen-off), and an
    // unlocked-device requirement would make that unwrap throw.
    private fun generateKeystoreKey(): SecretKey =
        runCatching { generateKeystoreKey(strongBox = true) }
            .getOrElse { e ->
                if (e is StrongBoxUnavailableException) generateKeystoreKey(strongBox = false) else throw e
            }

    private fun generateKeystoreKey(strongBox: Boolean): SecretKey {
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val IV_LENGTH = 12
    }
}
