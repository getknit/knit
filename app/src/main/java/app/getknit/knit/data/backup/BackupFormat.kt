package app.getknit.knit.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * The backup file's shape, as constants and the two records it carries in the clear and under the seal.
 * The normative description is `docs/BACKUP_FORMAT.md`; this file is what the writer and the reader
 * ([BackupArchive]) agree on.
 *
 * ```
 * "KNITBK" ‖ u8 format ‖ u8 kdf ‖ u16be headerLen ‖ CBOR BackupHeader      (plaintext, the AEAD's AAD)
 * ‖ Tink StreamingAead(AES-256-GCM-HKDF, 1 MiB segments) over the container (BackupArchive)
 * ```
 *
 * Nothing that identifies the phone or its owner sits outside the seal: the header carries a salt and a
 * date, and the manifest — node id, name, the entry list — is the first thing inside. Additions to either
 * record are nullable fields (the `docs/WIRE_COMPAT.md` rule, applied to a file), and a reader refuses a
 * [FORMAT_VERSION] or a KDF it does not know before it touches the seal.
 */
object BackupFormat {
    /** The six magic bytes every backup starts with. */
    val MAGIC: ByteArray = "KNITBK".encodeToByteArray()

    /** The layout version of the plaintext prefix and the container; bumped only for a breaking change. */
    const val FORMAT_VERSION = 1

    /**
     * KDF id 1: the secret is a generated 30-digit recovery key (uniform, ~100 bits), so a single HKDF
     * step is the whole derivation. A passphrase mode would take the next id with a slow KDF, and its
     * parameters would ride [BackupHeader] as nullable fields.
     */
    const val KDF_RECOVERY_KEY = 1

    /** Bytes of random salt in the header; the HKDF salt for the file key. */
    const val SALT_BYTES = 32

    /** The plaintext prefix is bounded — a header longer than this is not ours. */
    const val MAX_HEADER_BYTES = 4096

    /** The manifest is a handful of entries; anything past this is not ours either. */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    /** The suggested file name's extension; the MIME on the picker is `application/octet-stream`. */
    const val FILE_EXTENSION = "knitbackup"

    const val MIME = "application/octet-stream"

    /** The container's entry names, in the order the writer emits them (largest last). */
    const val ENTRY_MANIFEST = "manifest.cbor"
    const val ENTRY_IDENTITY = "identity.key"
    const val ENTRY_DB_PASSPHRASE = "db.passphrase"
    const val ENTRY_SETTINGS = "settings.preferences_pb"
    const val ENTRY_DATABASE = "knit.db"
}

/** The plaintext header: what a reader needs to derive the key, and nothing that names the owner. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class BackupHeader(
    @ByteString val salt: ByteArray,
    val createdAt: Long,
)

/**
 * The first sealed entry: what the backup is, so a restore can refuse or describe it before streaming the
 * database. [schemaVersion] is the Room version the carried database is at; [entries] lists every
 * following entry with its size and SHA-256, which the reader checks as it goes.
 */
@Serializable
class BackupManifest(
    val v: Int,
    val schemaVersion: Int,
    val appVersionCode: Long,
    val appVersionName: String,
    val createdAt: Long,
    val nodeId: String,
    val displayName: String? = null,
    val entries: List<BackupEntry>,
) {
    fun entry(name: String): BackupEntry? = entries.firstOrNull { it.name == name }

    fun withEntries(entries: List<BackupEntry>): BackupManifest =
        BackupManifest(v, schemaVersion, appVersionCode, appVersionName, createdAt, nodeId, displayName, entries)
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class BackupEntry(
    val name: String,
    val size: Long,
    @ByteString val sha256: ByteArray,
)
