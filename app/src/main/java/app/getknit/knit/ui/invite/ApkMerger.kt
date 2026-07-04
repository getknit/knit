package app.getknit.knit.ui.invite

import android.content.Context
import app.getknit.knit.R
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.function.Predicate

// When Knit is installed from the Play Store it lands as an App Bundle: a base APK plus per-config
// (abi/density/language) split APKs on disk. A stock receiver phone can only install a *single* APK by
// tapping a file, so to keep the one-tap offline-share flow working we merge the on-disk splits into one
// universal APK here (ARSCLib), strip the split metadata that makes the OS refuse a standalone install,
// and re-sign it (apksig) — the merge rewrites the manifest, which breaks the Play signature, so a fresh
// self-signed key is required. The result is cached per app version. See ShareApk.prepareKnitApk for the
// monolithic (already-single-APK) fast path. minSdk is 33, so v1 (JAR) signing is never needed.

private const val APK_CACHE_DIR = "apk"

/** Bumped when the merge/sanitize logic changes, so a stale cached merge from an old build is discarded. */
private const val MERGE_FORMAT_REV = 1

/** Require this many times the input size free before merging (stage copy + unsigned + signed churn). */
private const val STORAGE_HEADROOM_FACTOR = 3

/** apksig target: the receiver must be >= Knit's minSdk to install at all, so v1 signing is unnecessary. */
private const val SIGN_MIN_SDK = 33

/** Play/Vending-injected `<meta-data>` name prefixes; none are needed once the app is a single APK. */
private val SPLIT_META_PREFIXES = listOf("com.android.vending.", "com.android.stamp.")

/**
 * Absolute paths of the APKs that make up this install — the base ([sourceDir]) first, then every config
 * split ([splitSourceDirs]). Pure (no Android types) so it is JVM-unit-testable; blanks/dupes are dropped.
 */
internal fun collectSplitPaths(sourceDir: String?, splitSourceDirs: Array<String>?): List<File> =
    buildList {
        sourceDir?.let(::add)
        splitSourceDirs?.let(::addAll)
    }.filter { it.isNotBlank() }.distinct().map(::File)

/**
 * Cache file name for the merged+signed APK, keyed on the app version (so a rebuild reuses it) and on
 * [MERGE_FORMAT_REV] (so a logic change invalidates it). Pure → JVM-unit-testable.
 */
internal fun mergedApkFileName(versionName: String, versionCode: Long): String =
    "Knit-$versionName-$versionCode-merged-r$MERGE_FORMAT_REV.apk"

/**
 * Merges the [splitPaths] of a Play App Bundle install into one standalone-installable, re-signed APK and
 * returns it, caching the result per app version under `cacheDir/apk`. Heavy (seconds + hundreds of MB of
 * heap for a large app); the caller must be off the main thread. Throws [ShareStorageException] when the
 * cache dir is too small, and propagates any merge/sign failure (IO, apksig, OOM) to the caller.
 */
fun buildSharableSplitApk(
    context: Context,
    splitPaths: List<File>,
    versionName: String,
    versionCode: Long,
): File {
    val dir = File(context.cacheDir, APK_CACHE_DIR).apply { mkdirs() }
    val dest = File(dir, mergedApkFileName(versionName, versionCode))
    if (dest.exists() && dest.length() > 0L) return dest

    // Bound cache growth: an app update changes the file name, so drop any older shareable APK.
    dir.listFiles { f -> f.isFile && f.name.startsWith("Knit-") && f.name != dest.name }
        ?.forEach { it.delete() }

    val inputBytes = splitPaths.sumOf { it.length() }
    if (dir.usableSpace < inputBytes * STORAGE_HEADROOM_FACTOR) {
        throw ShareStorageException("need ~${inputBytes * STORAGE_HEADROOM_FACTOR} B free to merge splits")
    }

    val unsigned = File(dir, "${dest.name}.unsigned")
    val signedTmp = File(dir, "${dest.name}.tmp")
    unsigned.delete()
    signedTmp.delete()
    try {
        buildUniversalApk(context, splitPaths, unsigned)
        signApk(context, unsigned, signedTmp)
        // Publish atomically so a killed/OOM merge never leaves a truncated file that later reads as cached.
        if (!signedTmp.renameTo(dest)) throw IOException("could not publish merged APK to ${dest.name}")
        return dest
    } finally {
        unsigned.delete()
        signedTmp.delete()
    }
}

/**
 * Runs the ARSCLib merge: stages [splitPaths] into a scratch dir, merges them into one [ApkModule],
 * sanitizes its manifest for a standalone install, and writes an (unsigned) universal APK to [out].
 */
private fun buildUniversalApk(context: Context, splitPaths: List<File>, out: File) {
    // ApkBundle.loadApkDirectory reads every *.apk in a directory; stage exactly our splits so we merge
    // the intended set (not whatever else lives in the install dir) and read from a path we control.
    val stage = File(context.cacheDir, "$APK_CACHE_DIR/merge-src").apply {
        deleteRecursively()
        mkdirs()
    }
    try {
        splitPaths.forEachIndexed { index, src ->
            src.copyTo(File(stage, "${index}_${src.name}"), overwrite = true)
        }
        val bundle = ApkBundle()
        try {
            bundle.loadApkDirectory(stage, false)
            val merged = bundle.mergeModules()
            try {
                sanitizeForStandaloneInstall(merged)
                // Keep native libs uncompressed so apksig can 16 KB-page-align them on signing.
                merged.setExtractNativeLibs(false)
                merged.refreshTable()
                merged.refreshManifest()
                merged.writeApk(out)
            } finally {
                merged.close()
            }
        } finally {
            bundle.close()
        }
    } finally {
        stage.deleteRecursively()
    }
}

/**
 * Strips the split-only manifest metadata that otherwise makes the package manager reject a base-alone
 * install (`INSTALL_FAILED_MISSING_SPLIT`): the `requiredSplitTypes`/`splitTypes` attributes on `<manifest>`,
 * `isSplitRequired` on `<application>`, and the Play/Vending `<meta-data>` split records. The referenced
 * split-config resources stay in the merged table (harmless — they resolve inside the base now).
 */
private fun sanitizeForStandaloneInstall(module: ApkModule) {
    val manifest = module.getAndroidManifest() ?: return
    manifest.getManifestElement()?.apply {
        removeAttributesWithId(AndroidManifest.ID_requiredSplitTypes)
        removeAttributesWithId(AndroidManifest.ID_splitTypes)
        removeAttributesWithId(AndroidManifest.ID_isFeatureSplit)
        removeAttributesWithName(AndroidManifest.NAME_requiredSplitTypes)
        removeAttributesWithName(AndroidManifest.NAME_splitTypes)
        removeAttributesWithName(AndroidManifest.NAME_split)
    }
    manifest.getApplicationElement()?.apply {
        removeAttributesWithId(AndroidManifest.ID_isSplitRequired)
        removeAttributesWithName(AndroidManifest.NAME_isSplitRequired)
        removeElementsIf(splitMetaDataPredicate())
    }
}

/** Matches `<meta-data>` children whose `android:name` is a Play/Vending split record (see [SPLIT_META_PREFIXES]). */
private fun splitMetaDataPredicate(): Predicate<ResXmlElement> =
    Predicate { element ->
        if (element.name != AndroidManifest.TAG_meta_data) {
            false
        } else {
            val name = element.searchAttributeByResourceId(AndroidManifest.ID_name)?.valueString.orEmpty()
            SPLIT_META_PREFIXES.any(name::startsWith)
        }
    }

/**
 * Re-signs [input] into [output] with the fixed self-signed key bundled in `res/raw` (v2+v3; v1 off since
 * the receiver is always >= minSdk 33). apksig re-aligns as it writes, 16 KB-page-aligning the uncompressed
 * native libs. [input] and [output] must be different files.
 */
private fun signApk(context: Context, input: File, output: File) {
    val privateKey = context.resources.openRawResource(R.raw.knit_share_key).use { it.readBytes() }
        .let { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it)) }
    val cert = context.resources.openRawResource(R.raw.knit_share_cert).use { stream ->
        CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
    }
    val signerConfig = ApkSigner.SignerConfig.Builder("knit", privateKey, listOf(cert)).build()
    ApkSigner.Builder(listOf(signerConfig))
        .setInputApk(input)
        .setOutputApk(output)
        .setMinSdkVersion(SIGN_MIN_SDK)
        .setV1SigningEnabled(false)
        .setV2SigningEnabled(true)
        .setV3SigningEnabled(true)
        .build()
        .sign()
}
