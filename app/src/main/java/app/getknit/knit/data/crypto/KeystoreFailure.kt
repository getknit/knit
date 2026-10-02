package app.getknit.knit.data.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException

/**
 * What a failed unwrap proves. Only two throwables are proof that a wrapped secret is gone for good, and both
 * are the Keystore's own verdict, not a refusal to give one:
 * - [AEADBadTagException] — `doFinal`'s KM_ERROR_VERIFICATION_FAILED: the key exists and the tag does not
 *   verify, so the wrap is corrupt or someone else's.
 * - [KeyPermanentlyInvalidatedException] — `Cipher.init`'s KEY_NOT_FOUND / KEY_PERMANENTLY_INVALIDATED: the
 *   key went between the lookup and the operation. keystore2 can also raise it late, as the cause of an
 *   `IllegalBlockSizeException` from `doFinal`, which is why the whole cause chain is read.
 *
 * Everything else is a refusal the next attempt may not repeat: the P3's `InvalidKeyException("Keystore
 * operation failed")` (KM_ERROR_UNKNOWN_ERROR in a keyblob upgrade), `UserNotAuthenticatedException` (keystore2's
 * LOCKED), an `UnrecoverableKeyException` from the lookup (keystore2 wraps every backend error in one), an
 * `IllegalBlockSizeException`, a `ProviderException`, an I/O error. Unrecognised means transient: a wipe needs
 * proof. `android.security.KeyStoreException` is never named — it is hidden below API 33, and nothing here needs
 * its codes. ADR 2026-10.47rw.
 */
internal object KeystoreFailure {
    /** Bounds the walk down a cause chain; Koin alone nests one wrapper per single on the way. */
    const val MAX_CAUSE_DEPTH = 16

    /** The [Loss] [failure] proves, or null when it proves nothing (a refusal). */
    fun classify(failure: Throwable): Loss? = causes(failure).firstNotNullOfOrNull { lossOf(it) }

    fun causes(failure: Throwable): Sequence<Throwable> = generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH)

    private fun lossOf(failure: Throwable): Loss? =
        when (failure) {
            is AEADBadTagException -> Loss.TAG_MISMATCH
            is KeyPermanentlyInvalidatedException -> Loss.KEY_INVALIDATED
            else -> null
        }
}

/**
 * The Keystore refused to unwrap (or wrap) a secret that may well be intact, so the open failed **without**
 * touching a file. Thrown by [DatabaseKey] and [IdentityKeyStore] where they used to wipe or mint; caught by name
 * — down the cause chain, past Koin's wrappers — by `MeshService` (which stands down instead of crashing) and by
 * `ui/StorageGate` (which shows Try again). Not an `IllegalStateException` on purpose: the service's foreground
 * claim catches that type for its own reason. ADR 2026-10.47rw.
 */
class KeystoreUnavailableException(
    what: String,
    cause: Throwable?,
) : Exception("Keystore refused the $what; nothing was wiped", cause)

/** The [KeystoreUnavailableException] somewhere down this throwable's cause chain, or null. */
fun Throwable.keystoreUnavailable(): KeystoreUnavailableException? =
    KeystoreFailure.causes(this).filterIsInstance<KeystoreUnavailableException>().firstOrNull()
