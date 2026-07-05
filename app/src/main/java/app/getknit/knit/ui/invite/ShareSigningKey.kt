package app.getknit.knit.ui.invite

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The per-install key that re-signs offline-shared universal APKs (see `signApk` in [ApkMerger]).
 *
 * Generated once in AndroidKeyStore on first share and reused for this install's lifetime. The private
 * key is a non-extractable hardware key handle (StrongBox-preferred, TEE fallback); apksig signs through
 * the JCA [java.security.Signature] API, so the key material never leaves the keystore. AndroidKeyStore
 * auto-creates the self-signed certificate the signer needs, so no cert-builder dependency (Bouncy
 * Castle) is required — EC P-256 signs as ECDSA-SHA256, accepted for APK v2/v3 signatures at minSdk 33.
 *
 * This replaces the old repo-checked-in RSA key, whose public private-half let anyone sign a trojaned
 * "Knit" that share-installed devices accepted as a **legitimate in-place update** (see
 * `docs/ARCHITECTURE_REVIEW.md` #7). Now every install signs with its own device-private identity, so
 * no attacker holds a key any victim's device would honor.
 *
 * Tradeoff (accepted, by design): because each install — really each share-tree root that first merged
 * an APK — signs with a distinct key, a share-installed Knit can be updated in place only by the device
 * that produced its APK; a differently-keyed shared APK is rejected as a signature mismatch and needs an
 * uninstall/reinstall. Offline share is primarily first-install distribution, which is unaffected, and
 * this fully closes the forge-an-update attack the shared key opened.
 */
object ShareSigningKey {
    /**
     * An apksig signer config backed by this install's key, generating the key on first use.
     * Synchronized so two concurrent shares can't race the one-time keygen.
     */
    @Synchronized
    fun signerConfig(): ApkSigner.SignerConfig {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate()
        val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        // KeyConfig.Jca wraps the (non-extractable) keystore handle; apksig signs it through the JCA
        // Signature API. This is apksig's current API — the plain-PrivateKey Builder overload is deprecated.
        return ApkSigner.SignerConfig.Builder(SIGNER_NAME, KeyConfig.Jca(privateKey), listOf(cert)).build()
    }

    // Prefer a StrongBox (secure-element) backed key; fall back to a TEE-backed key on devices without
    // one, mirroring KeystoreSecret. Deliberately no user-auth / unlocked-device requirement: a share can
    // be driven headlessly (the debug bridge) or while the device is locked, which such a requirement
    // would make throw.
    private fun generate() =
        runCatching { generate(strongBox = true) }
            .getOrElse { e ->
                if (e is StrongBoxUnavailableException) generate(strongBox = false) else throw e
            }

    private fun generate(strongBox: Boolean) {
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256) // apksig signs P-256 as ECDSA-SHA256
                .setCertificateSubject(X500Principal(CERT_SUBJECT))
                .setCertificateSerialNumber(BigInteger.ONE)
                // Cert validity is cosmetic for a self-signed APK signer; a wide window (epoch → far
                // future) avoids any "not yet valid" edge on a device with a skewed clock.
                .setCertificateNotBefore(Date(0L))
                .setCertificateNotAfter(Date(CERT_NOT_AFTER_MILLIS))
                .setIsStrongBoxBacked(strongBox)
                .build()
        KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "knit_apk_sign_key"
    private const val SIGNER_NAME = "knit"
    private const val EC_CURVE = "secp256r1"
    private const val CERT_SUBJECT = "CN=Knit Offline Share, O=Knit"

    /** 2100-01-01 UTC — far past any realistic install lifetime. */
    private const val CERT_NOT_AFTER_MILLIS = 4_102_444_800_000L
}
