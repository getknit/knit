package app.getknit.knit.data.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * What a failed unwrap proves (ADR 2026-10.47rw), one shape per row of the AOSP-verified table: only the Keystore's own
 * verdicts — a tag that does not verify, a key permanently invalidated — are a loss; every refusal, including the
 * lab Pixel 3's `InvalidKeyException("Keystore operation failed")`, proves nothing. Robolectric, so the
 * `android.security.keystore` exceptions are the real classes.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreFailureTest {
    /** What keystore2 wraps a backend error in; the hidden `android.security.KeyStoreException` stands in. */
    private val backend = ProviderException("KM_ERROR_UNKNOWN_ERROR (-1000)")

    @Test
    fun aTagThatDoesNotVerifyIsATagMismatch() {
        assertEquals(Loss.TAG_MISMATCH, KeystoreFailure.classify(AEADBadTagException()))
    }

    @Test
    fun anInvalidatedKeyIsAKeyInvalidatedEvenRaisedLateFromDoFinal() {
        assertEquals(Loss.KEY_INVALIDATED, KeystoreFailure.classify(KeyPermanentlyInvalidatedException()))
        val late = IllegalBlockSizeException().apply { initCause(KeyPermanentlyInvalidatedException()) }
        assertEquals(Loss.KEY_INVALIDATED, KeystoreFailure.classify(late))
    }

    @Test
    fun everyRefusalProvesNothing() {
        val refusals =
            listOf(
                InvalidKeyException("Keystore operation failed", backend),
                UserNotAuthenticatedException(),
                UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(backend) },
                UnrecoverableKeyException("User changed or deleted their auth credentials"),
                IllegalBlockSizeException().apply { initCause(backend) },
                BadPaddingException(),
                backend,
                KeyStoreException("keystore unavailable"),
                IOException("read failed"),
                IllegalStateException("anything else"),
            )
        for (refusal in refusals) assertNull(refusal.toString(), KeystoreFailure.classify(refusal))
    }

    @Test
    fun aRefusalIsFoundUnderKoinsWrappers() {
        val refusal = KeystoreUnavailableException("database passphrase", backend)
        val wrapped = (1..5).fold<Int, Throwable>(refusal) { inner, n -> RuntimeException("level $n", inner) }
        assertSame(refusal, wrapped.keystoreUnavailable())
        assertNull(RuntimeException("no keystore here", backend).keystoreUnavailable())
    }

    @Test
    fun aCauseCycleEndsTheWalk() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertNull(KeystoreFailure.classify(a))
        assertNull(a.keystoreUnavailable())
    }
}
