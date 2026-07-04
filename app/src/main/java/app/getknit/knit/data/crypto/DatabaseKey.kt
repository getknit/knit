package app.getknit.knit.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the SQLCipher passphrase for [app.getknit.knit.data.KnitDatabase].
 *
 * A random 256-bit passphrase is generated on first run and stored on disk wrapped (AES-256-GCM)
 * by a hardware-backed key held in the [AndroidKeyStore]. The Keystore key never leaves the device
 * and is excluded from cloud backup, so the encrypted DB cannot be decrypted off-device even if the
 * wrapped passphrase file and the database are copied elsewhere.
 *
 * Opening is transparent — no user authentication is required ([KeyGenParameterSpec] does not set
 * `setUserAuthenticationRequired`). If the wrapped passphrase cannot be recovered (Keystore key lost
 * or the wrap file corrupt), we fall back to wipe-and-recreate: the unreadable database is deleted
 * and a fresh encrypted one is provisioned. The same wipe runs on first encryption to drop any
 * pre-existing plaintext `knit.db` (which SQLCipher cannot open).
 */
class DatabaseKey(
    private val context: Context,
) {
    private val keyFile: File get() = File(context.filesDir, KEY_FILE)

    /** Returns the 32-byte SQLCipher passphrase, generating and persisting it on first use. */
    @Synchronized
    fun getOrCreate(): ByteArray {
        if (keyFile.exists()) {
            runCatching { decryptPassphrase() }
                .onSuccess { return it }
                .onFailure { Log.w(TAG, "DB passphrase unrecoverable; wiping and regenerating", it) }
        }
        // First encryption (no wrap file yet) or recovery after a failed decrypt: the on-disk DB is
        // either plaintext (old build) or encrypted-but-unopenable, so drop it and start fresh.
        wipeDatabase()
        return createPassphrase()
    }

    private fun createPassphrase(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val ciphertext = cipher.doFinal(passphrase)
        keyFile.writeBytes(cipher.iv + ciphertext)
        return passphrase
    }

    private fun decryptPassphrase(): ByteArray {
        val blob = keyFile.readBytes()
        require(blob.size > IV_LENGTH) { "wrapped passphrase too short" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        val key = existingKeystoreKey() ?: throw GeneralSecurityException("Keystore key missing")
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        return cipher.doFinal(ciphertext).also {
            require(it.size == PASSPHRASE_BYTES) { "unexpected passphrase length ${it.size}" }
        }
    }

    private fun keystoreKey(): SecretKey = existingKeystoreKey() ?: generateKeystoreKey()

    private fun existingKeystoreKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    // Prefer a StrongBox (secure-element) backed key; fall back to a TEE-backed key on devices without
    // one. We deliberately do NOT set setUnlockedDeviceRequired: the passphrase is unwrapped during DB
    // construction, which can happen while the device is locked (the mesh foreground service is
    // START_STICKY and may be restarted screen-off) — and since an unrecoverable unwrap here triggers a
    // destructive wipe, an unlocked-device requirement would risk wiping the database on a routine
    // background restart.
    private fun generateKeystoreKey(): SecretKey =
        runCatching { generateKeystoreKey(strongBox = true) }
            .getOrElse { e ->
                if (e is StrongBoxUnavailableException) generateKeystoreKey(strongBox = false) else throw e
            }

    private fun generateKeystoreKey(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
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

    /** Deletes the database and its WAL/SHM/journal sidecars so it can be recreated encrypted. */
    private fun wipeDatabase() {
        val dbPath = context.getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(dbPath.parentFile, dbPath.name + suffix)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete stale DB file ${file.name}")
            }
        }
    }

    private companion object {
        const val TAG = "DatabaseKey"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "knit_db_key"
        const val KEY_FILE = "db.key"
        const val DB_NAME = "knit.db"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PASSPHRASE_BYTES = 32
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val IV_LENGTH = 12
    }
}
