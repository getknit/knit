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
 */
class FaultyKeystoreCipher(
    private val marker: File,
    private val delegate: KeystoreCipher = AndroidKeystoreCipher,
) : KeystoreCipher by delegate {
    override fun open(
        key: SecretKey,
        wrapped: ByteArray,
    ): ByteArray {
        if (takeFault()) {
            throw InvalidKeyException("Keystore operation failed", ProviderException("injected by ${marker.name}"))
        }
        return delegate.open(key, wrapped)
    }

    @Synchronized
    private fun takeFault(): Boolean {
        val left = runCatching { marker.readText().trim().toInt() }.getOrNull() ?: return false
        if (left <= 0) return false
        marker.writeText("${left - 1}")
        Log.w(TAG, "injecting a Keystore refusal (${left - 1} left)")
        return true
    }

    companion object {
        private const val TAG = "FaultyKeystoreCipher"

        /** The marker file's name under `filesDir`. */
        const val MARKER = "keystore-fault"
    }
}
