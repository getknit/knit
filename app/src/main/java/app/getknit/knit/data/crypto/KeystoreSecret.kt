package app.getknit.knit.data.crypto

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

/** Why a wrapped secret is gone for good — each one proof, never a guess (ADR 2026-10.47rw). */
enum class Loss {
    /** The file is too short to be a wrap: no IV and tag around it. */
    MALFORMED,

    /** The Keystore reported no key under the alias, on every look. */
    KEY_MISSING,

    /** The Keystore reported the key permanently invalidated (or gone between the lookup and the operation). */
    KEY_INVALIDATED,

    /** The key is there and the GCM tag does not verify: the wrap is corrupt or not ours. */
    TAG_MISMATCH,
}

/** What [KeystoreSecret.read] found. */
sealed interface Unwrapped {
    /** The secret, unwrapped. */
    class Present(
        val bytes: ByteArray,
    ) : Unwrapped

    /** No wrap file: a first run, or a phone signed out. Nothing was asked of the Keystore. */
    data object Absent : Unwrapped

    /** Proven gone, for [reason] — the same verdict on every attempt. [cause] is the last attempt's, if it threw. */
    class Lost(
        val reason: Loss,
        val cause: Throwable?,
    ) : Unwrapped

    /** The Keystore refused at least once and never proved a loss: the secret may well be intact. */
    class Unavailable(
        val cause: Throwable,
    ) : Unwrapped
}

/**
 * A small secret persisted on disk wrapped (AES-256-GCM) by a hardware-backed AndroidKeyStore key. The
 * Keystore key never leaves the device and is excluded from cloud backup, so the wrapped blob is useless if
 * copied elsewhere. [DatabaseKey] (the SQLCipher passphrase) and [IdentityKeyStore] (the E2E identity) both keep
 * their secret in one.
 *
 * Each instance owns its own Keystore [alias] and on-disk [fileName] under [dir] — `filesDir` unless a caller says
 * otherwise, which only the backup restore does: it wraps the secrets it is about to install into its staging
 * directory under the *live* aliases, so the files it later moves into place are exactly what [IdentityKeyStore]
 * and [DatabaseKey] read (`iv ‖ ciphertext`, one alias per file). Opening is transparent (no user-auth
 * requirement on the Keystore key).
 *
 * A failed unwrap is classified, never just dropped (ADR 2026-10.47rw): [read] answers [Unwrapped.Lost] only when
 * every attempt proves the loss ([KeystoreFailure]), and [Unwrapped.Unavailable] whenever the Keystore merely
 * refused — the callers wipe and mint on the first, and on the second fail the open with every file left as it
 * was. On 2026-10-01 a keystore2 KM_ERROR_UNKNOWN_ERROR on a locked Pixel 3 took the old "any failure is a
 * missing key" path and replaced the phone's database and identity.
 */
class KeystoreSecret(
    context: Context,
    private val alias: String,
    private val fileName: String,
    private val dir: File = context.filesDir,
    private val cipher: KeystoreCipher = AndroidKeystoreCipher,
    private val lostDir: File = context.noBackupFilesDir,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val file: File get() = File(dir, fileName)

    /** The wrap the app itself reads under [alias]; a staging instance's [file] is a different one. */
    private val liveFile = File(context.filesDir, fileName)

    /**
     * Unwraps the stored secret. A file too short to be a wrap is [Loss.MALFORMED] at once; otherwise the unwrap
     * is tried `1 + retryDelaysMs.size` times, and a loss counts only when every attempt proves one — a
     * StrongBox that answers a tag mismatch once and the right bytes a second later is a glitch, not a loss.
     */
    @Synchronized
    fun read(): Unwrapped {
        if (!file.exists()) return Unwrapped.Absent
        val blob = runCatching { file.readBytes() }.getOrElse { return Unwrapped.Unavailable(it) }
        if (blob.size < KeystoreCipher.MIN_WRAP_BYTES) return Unwrapped.Lost(Loss.MALFORMED, null)
        var refusal: Throwable? = null
        var lost: Unwrapped.Lost? = null
        for (attempt in 0..retryDelaysMs.size) {
            if (attempt > 0) sleep(retryDelaysMs[attempt - 1])
            when (val once = unwrapOnce(blob)) {
                is Unwrapped.Present -> return once
                is Unwrapped.Lost -> lost = once
                is Unwrapped.Unavailable -> refusal = once.cause
                Unwrapped.Absent -> error("unwrapOnce never answers Absent")
            }
        }
        return refusal?.let { Unwrapped.Unavailable(it) } ?: checkNotNull(lost)
    }

    /**
     * Wraps [plain] under the Keystore key and writes it, replacing any prior value. [freshKey] generates a new
     * key first — only for a key the Keystore reported invalidated, which would refuse every wrap under it.
     */
    @Synchronized
    fun store(
        plain: ByteArray,
        freshKey: Boolean = false,
    ) {
        val key = if (freshKey) cipher.generate(alias) else keyForStore()
        file.writeBytesAtomically(cipher.seal(key, plain))
    }

    /**
     * Moves the wrap file to the one `.lost` slot under `noBackupFilesDir`, replacing whatever was there — kept
     * rather than overwritten by the mint that follows a [Unwrapped.Lost], so a misjudged loss can still be read
     * back by hand. Best effort: a failed move is logged and the mint overwrites the file as it always did.
     */
    @Synchronized
    fun keepAside() {
        if (!file.exists()) return
        runCatching {
            lostDir.mkdirs()
            Files.move(
                file.toPath(),
                File(lostDir, fileName + LOST_SUFFIX).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { Log.w(TAG, "could not keep $fileName aside", it) }
    }

    private fun unwrapOnce(blob: ByteArray): Unwrapped =
        runCatching {
            val key = cipher.key(alias) ?: return Unwrapped.Lost(Loss.KEY_MISSING, null)
            Unwrapped.Present(cipher.open(key, blob))
        }.getOrElse { failure ->
            KeystoreFailure.classify(failure)?.let { Unwrapped.Lost(it, failure) } ?: Unwrapped.Unavailable(failure)
        }

    /**
     * The key to wrap under. Generating *replaces* the alias, and every wrap under it — the live file, and during a
     * restore the staged one too — dies with the old key; and a lookup can answer "none" for a Keystore that is
     * only unreachable (the legacy keystore below API 31 reads a dead daemon that way). So while the live wrap
     * exists, absence must hold on every look before a key is generated; with no live wrap nothing depends on it.
     * A lookup that throws propagates: a refusal never becomes a new key.
     */
    private fun keyForStore(): SecretKey {
        cipher.key(alias)?.let { return it }
        if (liveFile.exists()) {
            for (delay in retryDelaysMs) {
                sleep(delay)
                cipher.key(alias)?.let { return it }
            }
        }
        return cipher.generate(alias)
    }

    companion object {
        private const val TAG = "KeystoreSecret"

        /** The suffix of the one kept-aside copy of a lost wrap, under `noBackupFilesDir`. */
        const val LOST_SUFFIX = ".lost"

        /** Three attempts over about two seconds: the waits between them. ADR 2026-10.47rw. */
        val RETRY_DELAYS_MS = listOf(500L, 1_500L)
    }
}
