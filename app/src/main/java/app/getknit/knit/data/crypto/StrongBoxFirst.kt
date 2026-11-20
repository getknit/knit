package app.getknit.knit.data.crypto

import android.security.keystore.StrongBoxUnavailableException
import android.util.Log

/**
 * How a new AndroidKeyStore key is made: in the StrongBox first, and in the TEE when the StrongBox attempt fails
 * for **any** reason. ADR 2026-10.47rw's 2026-11-19 amendment.
 *
 * Android says a phone has no StrongBox with [StrongBoxUnavailableException], and that was once the only failure
 * that fell back. On 2026-11-19 a Xiaomi 14 (`houji`, API 35) answered every StrongBox key generation with a plain
 * `ProviderException` over keystore2's UNIMPLEMENTED: the secure element is there and makes nothing. That refusal
 * never clears, so a fresh install could mint neither its database passphrase nor its identity, and sat on "Waiting
 * for secure storage" for good (#351). The TEE is the store every phone without a StrongBox already uses.
 *
 * It decides only *where* a key is made. *Whether* one may be made at all — generating replaces the alias, and
 * every wrap under it — stays with the caller ([KeystoreSecret]'s `store`).
 */
internal object StrongBoxFirst {
    private const val TAG = "StrongBoxFirst"
    private const val NANOS_PER_MILLI = 1_000_000L

    /**
     * [attempt]`(true)`'s key, or [attempt]`(false)`'s when that threw. Between the two, [delete] clears the alias:
     * the platform already does after most failed generations, and a vendor stack that left a husk there would
     * otherwise have the TEE attempt meet it. A [delete] that throws is not a reason to skip the TEE.
     *
     * When the TEE attempt throws too, its exception is the one thrown, as it would be on a phone with no StrongBox,
     * and the StrongBox's rides along suppressed. An [Error] is never a reason to fall back.
     *
     * [label] names the key in the one warning a refusal logs. It is a constant (a wrap's file name), never an alias
     * derived from anything the user or a peer holds: `Log.w` reaches a release build's logcat.
     */
    @Suppress("TooGenericExceptionCaught") // any failure of the StrongBox attempt is a reason to ask the TEE
    fun <K> generate(
        label: String,
        attempt: (strongBox: Boolean) -> K,
        delete: () -> Unit,
    ): K {
        val started = System.nanoTime()
        val refusal =
            try {
                return attempt(true)
            } catch (e: Exception) {
                e
            }
        // No StrongBox at all is most phones, and says nothing a log needs.
        if (refusal !is StrongBoxUnavailableException) {
            val waited = (System.nanoTime() - started) / NANOS_PER_MILLI
            Log.w(TAG, "StrongBox refused $label after $waited ms; making it in the TEE", refusal)
        }
        try {
            delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not clear the alias of $label before the TEE attempt", e)
        }
        return try {
            attempt(false)
        } catch (e: Exception) {
            if (e !== refusal) e.addSuppressed(refusal)
            throw e
        }
    }
}
