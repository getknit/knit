package app.getknit.knit.data.crypto

import android.util.Log
import java.io.File
import java.security.InvalidKeyException
import java.security.ProviderException
import javax.crypto.SecretKey

/**
 * Debug only: [AndroidKeystoreCipher], except that while [marker] holds a count N > 0 the next N [open]s throw what
 * keystore2 threw on the lab Pixel 3 on 2026-10-01 — `InvalidKeyException("Keystore operation failed")` — each one
 * counting the file down. A large N holds the refusal; a small one is a refusal [KeystoreSecret.read]'s own retries
 * outlast. Staged from the shell with `run-as app.getknit.knit sh -c 'echo 3 > files/keystore-fault'`; no file, no
 * fault. ADR 2026-10.47rw.
 *
 * While [alterMarker] holds a count, the next N [open]s of anything longer than a database passphrase lose a span from
 * the middle: an identity that opens with its tag verified and does not parse, the Play crash of the 2026-11-05
 * amendment, staged the same way with `files/keystore-alter`.
 */
class FaultyKeystoreCipher(
    private val marker: File,
    private val alterMarker: File = File(marker.parentFile, ALTER_MARKER),
    private val delegate: KeystoreCipher = AndroidKeystoreCipher,
) : KeystoreCipher by delegate {
    override fun open(
        key: SecretKey,
        wrapped: ByteArray,
    ): ByteArray {
        if (take(marker)) {
            throw InvalidKeyException("Keystore operation failed", ProviderException("injected by ${marker.name}"))
        }
        val plain = delegate.open(key, wrapped)
        if (plain.size <= ALTER_ABOVE_BYTES || !take(alterMarker)) return plain
        return plain.copyOfRange(0, ALTER_FROM) + plain.copyOfRange(ALTER_TO, plain.size)
    }

    @Synchronized
    private fun take(file: File): Boolean {
        val left = runCatching { file.readText().trim().toInt() }.getOrNull() ?: return false
        if (left <= 0) return false
        file.writeText("${left - 1}")
        Log.w(TAG, "injecting ${file.name} (${left - 1} left)")
        return true
    }

    companion object {
        private const val TAG = "FaultyKeystoreCipher"

        /** The refusal marker file's name under `filesDir`. */
        const val MARKER = "keystore-fault"

        /** The altered-bytes marker file's name under `filesDir`. */
        const val ALTER_MARKER = "keystore-alter"

        /** `DatabaseKey`'s passphrase is 32 bytes: left alone, or the open would stop there and never reach the identity. */
        private const val ALTER_ABOVE_BYTES = 64
        private const val ALTER_FROM = 40
        private const val ALTER_TO = 60
    }
}
