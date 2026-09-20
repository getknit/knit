package app.getknit.knit.data.backup

import app.getknit.knit.mesh.crypto.cryptoCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Why a backup could not be read; the UI maps each to a sentence. */
enum class BackupProblem {
    /** Wrong magic, a header we cannot parse, or a container that is not shaped like ours. */
    NOT_A_BACKUP,

    /** A format or KDF id from a newer Knit. */
    NEWER_FORMAT,

    /**
     * The seal did not open: a wrong recovery key, bytes changed since the file was written, or a file
     * cut short — Tink reports all three the same way, and the app cannot tell them apart.
     */
    WRONG_KEY_OR_DAMAGED,

    /** The file ends inside its plaintext prefix. */
    TRUNCATED,

    /** An entry's size or hash disagrees with the manifest, or the entry set does — or a staged piece failed its check. */
    MISMATCH,

    /** The backup's database is at a schema newer than this build's; Knit must be updated first. */
    NEWER_APP,

    /** Not enough free space on this phone to stage the backup. */
    NO_SPACE,
}

class BackupException(
    val problem: BackupProblem,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** One thing the writer puts in the container: its bytes, opened when its turn comes. */
class BackupSource(
    val name: String,
    val size: Long,
    val open: () -> InputStream,
)

/**
 * The container inside the seal and the plaintext prefix around it — pure, so the round trip, a wrong
 * key, a truncation and a flipped byte are all JVM tests.
 *
 * Container: `u8 containerVersion` then entries, each `u16be nameLen ‖ name ‖ u64be size ‖ bytes`; the
 * first entry is always [BackupFormat.ENTRY_MANIFEST], whose [BackupManifest] lists every entry that
 * follows with its size and SHA-256. The reader checks each entry against the manifest as it streams it
 * out, and at the end that the manifest named nothing the file did not carry. The AEAD already
 * authenticates every byte; the per-entry hash is what lets a restore check the bytes it *staged on disk*
 * before it moves them into place.
 */
object BackupArchive {
    private const val CONTAINER_VERSION = 1
    private const val COPY_BUFFER = 64 * 1024

    /**
     * Writes one backup: the plaintext prefix for [header], then the sealed container carrying [manifest]
     * (rebuilt here with the sizes and hashes of [sources], in their order — the rebuilt one is returned)
     * and the sources' bytes. [onProgress] is called with the sealed plaintext bytes written so far.
     * Closes nothing but its own encrypting stream; the caller owns [out].
     */
    fun write(
        out: OutputStream,
        recoveryKey: String,
        header: BackupHeader,
        manifest: BackupManifest,
        sources: List<BackupSource>,
        onProgress: (Long) -> Unit = {},
    ): BackupManifest {
        val prefix = encodePrefix(header)
        out.write(prefix)
        val entries = sources.map { BackupEntry(it.name, it.size, sha256(it.open())) }
        val built = manifest.withEntries(entries)
        val manifestBytes = encodeManifest(built)
        val sealed = BackupKeys.streamingAead(recoveryKey, header.salt).newEncryptingStream(out, prefix)
        val data = DataOutputStream(sealed)
        var written = 0L
        data.writeByte(CONTAINER_VERSION)
        writeEntryHeader(data, BackupFormat.ENTRY_MANIFEST, manifestBytes.size.toLong())
        data.write(manifestBytes)
        val buffer = ByteArray(COPY_BUFFER)
        for (source in sources) {
            writeEntryHeader(data, source.name, source.size)
            source.open().use { input ->
                var remaining = source.size
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    expect(n >= 0, BackupProblem.MISMATCH) { "${source.name} shorter than its declared size" }
                    data.write(buffer, 0, n)
                    remaining -= n
                    written += n
                    onProgress(written)
                }
            }
        }
        data.flush()
        // Tink writes the final, tagged segment on close — an archive whose encrypting stream was never
        // closed reads as truncated.
        sealed.close()
        return built
    }

    /** The plaintext prefix and, parsed, its header; reads exactly the prefix's bytes from [input]. */
    fun readPrefix(input: InputStream): Pair<ByteArray, BackupHeader> {
        val data = DataInputStream(input)
        val magic = ByteArray(BackupFormat.MAGIC.size)
        readFully(data, magic, BackupProblem.NOT_A_BACKUP, "too short for a backup")
        expect(magic.contentEquals(BackupFormat.MAGIC), BackupProblem.NOT_A_BACKUP) { "not a Knit backup" }
        val format = data.readUnsignedByte()
        val kdf = data.readUnsignedByte()
        expect(format == BackupFormat.FORMAT_VERSION && kdf == BackupFormat.KDF_RECOVERY_KEY, BackupProblem.NEWER_FORMAT) {
            "backup format $format / kdf $kdf"
        }
        val headerLen = data.readUnsignedShort()
        expect(headerLen in 1..BackupFormat.MAX_HEADER_BYTES, BackupProblem.NOT_A_BACKUP) { "header of $headerLen bytes" }
        val headerBytes = ByteArray(headerLen)
        readFully(data, headerBytes, BackupProblem.TRUNCATED, "header cut short")
        val header =
            runCatching { decodeHeader(headerBytes) }
                .getOrElse { fail(BackupProblem.NOT_A_BACKUP, "header does not parse", it) }
        expect(header.salt.size == BackupFormat.SALT_BYTES, BackupProblem.NOT_A_BACKUP) { "salt of ${header.salt.size} bytes" }
        val prefix =
            BackupFormat.MAGIC +
                byteArrayOf(format.toByte(), kdf.toByte(), (headerLen ushr Byte.SIZE_BITS).toByte(), headerLen.toByte()) +
                headerBytes
        return prefix to header
    }

    /**
     * Reads one backup from [input] under [recoveryKey]. [onManifest] sees the manifest first and returns
     * whether to go on; then every entry the manifest names arrives, in order, through the stream [sink]
     * returns for it (closed here after the entry's bytes, verified against the manifest). [onProgress]
     * is called with the sealed plaintext bytes read so far.
     */
    fun read(
        input: InputStream,
        recoveryKey: String,
        onManifest: (BackupManifest) -> Boolean,
        sink: (BackupEntry) -> OutputStream,
        onProgress: (Long) -> Unit = {},
    ) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val (prefix, header) = readPrefix(buffered)
        val opened =
            try {
                BackupKeys.streamingAead(recoveryKey, header.salt).newDecryptingStream(buffered, prefix)
            } catch (e: IOException) {
                fail(BackupProblem.WRONG_KEY_OR_DAMAGED, "seal did not open", e)
            }
        val data = DataInputStream(opened)
        try {
            val manifest = readManifest(data)
            if (!onManifest(manifest)) return
            readEntries(data, manifest, sink, onProgress)
        } catch (e: EOFException) {
            fail(BackupProblem.TRUNCATED, "backup ends early", e)
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            // Tink reports an authentication failure as an IOException from read(); nothing else in
            // here reads from anywhere but the sealed stream.
            fail(BackupProblem.WRONG_KEY_OR_DAMAGED, "seal did not open", e)
        }
    }

    private fun readManifest(data: DataInputStream): BackupManifest {
        expect(data.readUnsignedByte() == CONTAINER_VERSION, BackupProblem.NEWER_FORMAT) { "container version" }
        val (name, size) = readEntryHeader(data)
        expect(name == BackupFormat.ENTRY_MANIFEST && size <= BackupFormat.MAX_MANIFEST_BYTES, BackupProblem.NOT_A_BACKUP) {
            "manifest is not the first entry"
        }
        val bytes = ByteArray(size.toInt()).also { data.readFully(it) }
        return runCatching { decodeManifest(bytes) }.getOrElse { fail(BackupProblem.NOT_A_BACKUP, "manifest does not parse", it) }
    }

    private fun readEntries(
        data: DataInputStream,
        manifest: BackupManifest,
        sink: (BackupEntry) -> OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(COPY_BUFFER)
        var read = 0L
        for (expected in manifest.entries) {
            val (name, size) = readEntryHeader(data)
            expect(name == expected.name && size == expected.size, BackupProblem.MISMATCH) {
                "entry $name ($size B) where the manifest names ${expected.name} (${expected.size} B)"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            sink(expected).use { out ->
                var remaining = size
                while (remaining > 0) {
                    val n = data.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException()
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    remaining -= n
                    read += n
                    onProgress(read)
                }
            }
            expect(digest.digest().contentEquals(expected.sha256), BackupProblem.MISMATCH) { "$name hash" }
        }
        // The stream must end exactly here: Tink verifies the final segment's tag on the read that
        // finds it, so this is also where a truncated file is refused.
        expect(data.read() == -1, BackupProblem.MISMATCH) { "bytes past the last entry" }
    }

    private fun readFully(
        data: DataInputStream,
        into: ByteArray,
        problem: BackupProblem,
        message: String,
    ) {
        try {
            data.readFully(into)
        } catch (e: EOFException) {
            fail(problem, message, e)
        }
    }

    /** Throws the [BackupException] a reader reports; typed `Nothing` so it reads as one refusal, not a branch. */
    internal fun fail(
        problem: BackupProblem,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw BackupException(problem, message, cause)

    internal inline fun expect(
        condition: Boolean,
        problem: BackupProblem,
        message: () -> String,
    ) {
        if (!condition) fail(problem, message())
    }

    private fun writeEntryHeader(
        data: DataOutputStream,
        name: String,
        size: Long,
    ) {
        val nameBytes = name.encodeToByteArray()
        data.writeShort(nameBytes.size)
        data.write(nameBytes)
        data.writeLong(size)
    }

    private fun readEntryHeader(data: DataInputStream): Pair<String, Long> {
        val nameLen = data.readUnsignedShort()
        val name = ByteArray(nameLen).also { data.readFully(it) }.decodeToString()
        val size = data.readLong()
        expect(size >= 0, BackupProblem.NOT_A_BACKUP) { "negative entry size" }
        return name to size
    }

    private fun sha256(input: InputStream): ByteArray =
        input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodePrefix(header: BackupHeader): ByteArray {
        val headerBytes = cryptoCbor.encodeToByteArray(header)
        require(headerBytes.size <= BackupFormat.MAX_HEADER_BYTES)
        return BackupFormat.MAGIC +
            byteArrayOf(
                BackupFormat.FORMAT_VERSION.toByte(),
                BackupFormat.KDF_RECOVERY_KEY.toByte(),
                (headerBytes.size ushr Byte.SIZE_BITS).toByte(),
                headerBytes.size.toByte(),
            ) +
            headerBytes
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeHeader(bytes: ByteArray): BackupHeader = cryptoCbor.decodeFromByteArray(bytes)

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeManifest(manifest: BackupManifest): ByteArray = cryptoCbor.encodeToByteArray(manifest)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeManifest(bytes: ByteArray): BackupManifest = cryptoCbor.decodeFromByteArray(bytes)
}
