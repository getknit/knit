package app.getknit.knit.data

/**
 * What a set of bytes actually *is*, as opposed to what whoever handed them over says they are.
 *
 * Arbitrary-file attachments (ADR 2026-09.qq2r) widened a gap that voice notes opened: screening is skipped
 * by MIME, so before this a picker that reported `application/octet-stream` for a JPEG would have carried it
 * past the NSFW classifier untouched. [imageMimeOf] closes that on the send side by reading the bytes' own
 * header — a file that *is* an image goes through the image pipeline (downscale, re-encode, screen) whatever
 * it was called, and the opaque file path is reached only by bytes no image decoder claims.
 *
 * The receive side does not need this and deliberately does not use it: `InboundPipeline.onObtained` already
 * decrypts and screens every keyed attachment MIME-blind, so a mislabelled image is caught there by the
 * recipient's own decoder rather than by a signature list this file would have to keep current.
 *
 * [isInstallable] and [isRisky] are the two refusals: an app package is never sent, and the wider set is
 * confirmed before it is saved. Both read the name as well as the MIME, since either can be the honest one.
 *
 * Pure Kotlin, no Android — JVM-tested in `FileTypesTest`.
 */
object FileTypes {
    /**
     * The image MIME [bytes] actually carry, or null if no signature matches.
     *
     * Only formats a platform decoder plausibly handles are listed, because the caller's next move is to
     * *try* to decode. A guess that turns out wrong is not fatal — the ingest path falls back to storing the
     * bytes as an ordinary file — so this errs toward claiming an image, which is the direction that gets
     * content screened rather than the direction that skips it.
     */
    @Suppress("MagicNumber", "ReturnCount") // file signatures are byte constants; each match is its own exit
    fun imageMimeOf(bytes: ByteArray): String? {
        if (bytes.size < MIN_SIGNATURE) return null
        if (bytes.startsWith(JPEG)) return "image/jpeg"
        if (bytes.startsWith(PNG)) return "image/png"
        if (bytes.startsWith(GIF87A) || bytes.startsWith(GIF89A)) return "image/gif"
        if (bytes.startsWith(BMP)) return "image/bmp"
        if (bytes.startsWith(RIFF) && bytes.matchesAt(WEBP, 8)) return "image/webp"
        if (bytes.matchesAt(FTYP, 4)) return brandMime(bytes)
        return null
    }

    /** The ISO base-media brand at offset 8 mapped to an image type, or null for a video/unknown brand. */
    @Suppress("MagicNumber") // the brand is a fixed four-byte field at a fixed offset
    private fun brandMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        return when (String(bytes, 8, 4, Charsets.US_ASCII)) {
            in HEIF_BRANDS -> "image/heif"
            in AVIF_BRANDS -> "image/avif"
            else -> null
        }
    }

    /**
     * Whether this is an installable Android package. Knit refuses to send one: a mesh that moves app
     * packages between strangers is a sideloading channel, and the app already has a deliberate, separate
     * flow for sharing its *own* APK (`ui/invite/ShareApk.kt`) that this must not be confused with.
     */
    fun isInstallable(
        mime: String?,
        name: String?,
    ): Boolean = mime == APK_MIME || extensionOf(name) in APK_EXTENSIONS

    /**
     * Whether saving this file deserves a word first. Nothing on the device can look inside an executable or
     * an archive, so the recipient is told that rather than being handed it silently — the honest complement
     * to the fact that Knit never opens one for them after the save (ADR 2026-09.7ad3).
     */
    fun isRisky(
        mime: String?,
        name: String?,
    ): Boolean = isInstallable(mime, name) || extensionOf(name) in RISKY_EXTENSIONS

    /** The lowercase extension in [name] without its dot, or "" when it has none. */
    fun extensionOf(name: String?): String {
        val dot = name?.lastIndexOf('.') ?: -1
        return if (dot <= 0) "" else name!!.substring(dot + 1).lowercase()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(prefix, 0)

    private fun ByteArray.matchesAt(
        prefix: ByteArray,
        offset: Int,
    ): Boolean = size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /** Shortest byte count any signature below needs; anything shorter cannot be an image. */
    private const val MIN_SIGNATURE = 4

    private const val APK_MIME = "application/vnd.android.package-archive"

    @Suppress("MagicNumber") // raw file-signature bytes
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    @Suppress("MagicNumber") // raw file-signature bytes
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val GIF87A = ascii("GIF87a")
    private val GIF89A = ascii("GIF89a")
    private val BMP = ascii("BM")
    private val RIFF = ascii("RIFF")
    private val WEBP = ascii("WEBP")
    private val FTYP = ascii("ftyp")

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")

    private val APK_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm", "aab")

    private val RISKY_EXTENSIONS =
        setOf(
            // Runs on something, given the chance.
            "exe",
            "msi",
            "dmg",
            "bat",
            "cmd",
            "com",
            "scr",
            "ps1",
            "sh",
            "bash",
            "jar",
            "dex",
            "so",
            "deb",
            "rpm",
            "appimage",
            "app",
            "pkg",
            "msix",
            // Hides its contents from anything that might have looked.
            "zip",
            "7z",
            "rar",
            "tar",
            "gz",
            "tgz",
            "bz2",
            "xz",
            "iso",
            "img",
        )
}
