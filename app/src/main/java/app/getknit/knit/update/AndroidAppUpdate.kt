package app.getknit.knit.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.AppUpdateRecord
import app.getknit.knit.legal.InstallSource
import app.getknit.knit.legal.installSourceOf
import app.getknit.knit.legal.installerPackage
import app.getknit.knit.mesh.protocol.AppPin
import app.getknit.knit.transfer.AppSource
import app.getknit.knit.transfer.OfferReading
import app.getknit.knit.transfer.TransferAppUpdate
import app.getknit.knit.transfer.TransferSink
import app.getknit.knit.transfer.TransferSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * This phone's own Knit as a peer update reads it (ADR 2026-11.29m7): its build, its signers, and whether it may serve
 * or take an update. Read once and kept; an update replaces the process, so nothing here changes under it.
 *
 * Serving needs a single-APK install: a Play App Bundle install is split, and the merged copy `ui/invite/ApkMerger`
 * makes is re-signed with this phone's own key, which updates nobody. A Play install neither serves nor installs:
 * Play forbids an app updating itself outside Play, and this build is not the Play build anyway ([BuildConfig.PEER_UPDATE]
 * is off in a bundle).
 */
class AndroidAppBuild(
    context: Context,
    /** Whether this phone may host a Wi-Fi Direct group at all (ADR 2026-10.w3dw): one that may not, cannot serve. */
    private val mayHost: () -> Boolean,
) {
    private val app = context.applicationContext

    private class Facts(
        val build: AppBuild,
        val signers: Set<String>,
        val apk: File,
        val playOrSplit: Boolean,
    )

    @Volatile private var facts: Facts? = null

    @Volatile private var hashed: Pair<Long, ByteArray>? = null

    private suspend fun facts(): Facts? =
        facts ?: withContext(Dispatchers.IO) {
            runCatching { read() }.getOrNull()?.also { facts = it }
        }

    private fun read(): Facts? {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = currentSigners(info).takeIf { it.isNotEmpty() } ?: return null
        val appInfo = app.applicationInfo
        val apk = File(appInfo.sourceDir)
        val name = BuildConfig.VERSION_NAME
        val build =
            AppBuild(
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                versionName = name,
                certPin = pinOf(signers.min()),
                size = apk.length(),
                minSdk = appInfo.minSdkVersion,
                slim = VersionNames.isSlim(name),
            )
        val play = installSourceOf(installerPackage(app)) == InstallSource.PLAY
        return Facts(build, signers, apk, playOrSplit = play || !appInfo.splitSourceDirs.isNullOrEmpty())
    }

    /** This phone's build and what it may do, or null when its signature could not be read. */
    suspend fun local(): LocalBuild? {
        val f = facts() ?: return null
        val allowed = BuildConfig.PEER_UPDATE && !f.playOrSplit
        return LocalBuild(f.build, canServe = allowed && mayHost(), canInstall = allowed)
    }

    /** The digests of the certificates this install is signed with now. Empty when they could not be read. */
    suspend fun signers(): Set<String> = facts()?.signers.orEmpty()

    /** This install's APK as a transfer source, with what its offer pins; null when this phone cannot serve. */
    suspend fun source(): AppSource? {
        val f = facts() ?: return null
        if (local()?.canServe != true) return null
        val sha = withContext(Dispatchers.IO) { sha256Of(f.apk) } ?: return null
        val source =
            TransferSource(
                name = "Knit-${f.build.versionName}.apk",
                size = f.apk.length(),
                mime = OfferReading.APK_MIME,
                opener = { FileInputStream(f.apk) },
            )
        return AppSource(source, f.build.versionCode, sha)
    }

    // The installed APK never changes under this process (an update replaces it), so its hash is taken once.
    private fun sha256Of(apk: File): ByteArray? {
        hashed?.let { (length, sha) -> if (length == apk.length()) return sha }
        val sha =
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(apk).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                digest.digest()
            }.getOrNull() ?: return null
        hashed = apk.length() to sha
        return sha
    }

    /** What the platform's archive parser reads from [apk]: its package, version and signers. Null when it cannot. */
    fun inspect(apk: File): ArchiveFacts? =
        runCatching {
            val info = archiveInfo(apk) ?: return null
            ArchiveFacts(info.packageName, info.longVersionCode, info.versionName, provenSigners(info))
        }.getOrNull()

    private fun archiveInfo(apk: File): PackageInfo? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageArchiveInfo(apk.path, flags)
        }
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val PIN_BYTES = 8

        /** The certificates [info] is signed with now: every signer, or the newest of a single signer's lineage. */
        private fun currentSigners(info: PackageInfo): Set<String> {
            val si = info.signingInfo ?: return emptySet()
            val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            return certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        }

        /** Every certificate [info] proves it may sign with: every signer, or a single signer's whole lineage. */
        private fun provenSigners(info: PackageInfo): Set<String> {
            val si = info.signingInfo ?: return emptySet()
            val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        }

        private fun sha256Hex(bytes: ByteArray): String = AppUpdateRecord.hexOf(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** The first eight bytes of a certificate digest [hex], big-endian: the announce's certificate pin. */
        internal fun pinOf(hex: String): Long = hex.take(PIN_BYTES * HEX_PER_BYTE).toULong(HEX_RADIX).toLong()

        private const val HEX_PER_BYTE = 2
        private const val HEX_RADIX = 16
    }
}

/**
 * [TransferAppUpdate] on Android (ADR 2026-11.29m7): this install's APK as the source, and a staging sink under
 * [staging] whose [TransferSink.check] confirms the bytes' SHA-256 against the offer's pin as they were written, then
 * the archive against this install ([UpdateEligibility.archiveRefusal]). Only after the person on this phone asked:
 * [expects] is the coordinator's binding, so the parser never runs over bytes nobody requested.
 */
class AndroidAppUpdateFiles(
    private val build: AndroidAppBuild,
    private val staging: UpdateStaging,
    private val updates: () -> PeerUpdates,
    private val ownPackage: String,
    private val log: (String) -> Unit = {},
) : TransferAppUpdate {
    override suspend fun source(): AppSource? = build.source()

    override fun expects(
        peerId: String,
        pin: AppPin,
        size: Long,
    ): Boolean = updates().expects(peerId, pin, size)

    override suspend fun sink(
        transferId: String,
        pin: AppPin,
        size: Long,
    ): TransferSink? =
        withContext(Dispatchers.IO) {
            val apk = staging.incomingApk(transferId) ?: return@withContext null
            // A transfer id is the sender's: one already staged here is never written over, checked or not.
            if (apk.parentFile?.exists() == true) return@withContext null
            val installed = build.local()?.build ?: return@withContext null
            if (apk.parentFile?.mkdirs() != true && apk.parentFile?.isDirectory != true) return@withContext null
            StagingSink(transferId, apk, pin) { file ->
                val facts = build.inspect(file)
                val why =
                    if (facts == null) {
                        "an archive the platform could not read"
                    } else {
                        UpdateEligibility.archiveRefusal(facts, ownPackage, installed, build.signers(), pin.versionCode)
                    }
                if (why != null) log("update: refused the staged APK for $transferId: $why")
                why == null
            }
        }

    private inner class StagingSink(
        private val transferId: String,
        private val apk: File,
        private val pin: AppPin,
        private val archiveOk: suspend (File) -> Boolean,
    ) : TransferSink {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var out: OutputStream? = null

        // Nothing outside this process can address it: the staging is app-private and not a provider path.
        override val uri: String get() = apk.toURI().toString()

        override fun stream(): OutputStream = out ?: DigestOutputStream(FileOutputStream(apk), digest).also { out = it }

        override suspend fun check(): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    out?.close()
                    out = null
                    digest.digest().contentEquals(pin.sha256) && archiveOk(apk)
                } catch (e: IOException) {
                    log("update: staged APK for $transferId unreadable: $e")
                    false
                }
            }

        override suspend fun commit() {
            withContext(Dispatchers.IO) {
                out?.close()
                out = null
                if (!staging.markCommitted(transferId)) throw IOException("could not mark the update staged")
            }
        }

        override suspend fun discard() {
            withContext(Dispatchers.IO) {
                runCatching { out?.close() }
                out = null
                staging.discard(transferId)
            }
        }
    }
}
