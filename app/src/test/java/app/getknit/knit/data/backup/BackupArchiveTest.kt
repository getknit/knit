package app.getknit.knit.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The container and the seal around it, on the JVM: a round trip, then every way a file can be wrong —
 * the wrong key, a flipped byte, a cut-off tail, a foreign file, a newer format — each pinned to the
 * [BackupProblem] the UI will name, on the read alone.
 */
class BackupArchiveTest {
    private val key = BackupKeys.generate()
    private val header = BackupHeader(salt = BackupKeys.newSalt(), createdAt = 1_700_000_000_000L)
    private val identity = ByteArray(200).also { SecureRandom().nextBytes(it) }
    private val db = ByteArray(3 * 1024 * 1024 + 17).also { SecureRandom().nextBytes(it) } // spans several 1 MiB segments
    private val manifest =
        BackupManifest(
            v = 1,
            schemaVersion = 14,
            appVersionCode = 42,
            appVersionName = "2.7.0",
            createdAt = header.createdAt,
            nodeId = "abcdefghijklmnopqrstuvwxyz",
            displayName = "Ada Lovelace of Nowhere-in-Particular",
            entries = emptyList(),
        )

    private fun write(recoveryKey: String = key): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchive.write(
            out,
            recoveryKey,
            header,
            manifest,
            listOf(
                BackupSource(BackupFormat.ENTRY_IDENTITY, identity.size.toLong()) { ByteArrayInputStream(identity) },
                BackupSource(BackupFormat.ENTRY_DATABASE, db.size.toLong()) { ByteArrayInputStream(db) },
            ),
        )
        return out.toByteArray()
    }

    private fun read(
        bytes: ByteArray,
        recoveryKey: String = key,
    ): Pair<BackupManifest, Map<String, ByteArray>> {
        var seen: BackupManifest? = null
        val sinks = mutableMapOf<String, ByteArrayOutputStream>()
        BackupArchive.read(
            ByteArrayInputStream(bytes),
            recoveryKey,
            onManifest = {
                seen = it
                true
            },
            sink = { entry -> ByteArrayOutputStream().also { sinks[entry.name] = it } },
        )
        return seen!! to sinks.mapValues { it.value.toByteArray() }
    }

    private fun problemOf(
        bytes: ByteArray,
        recoveryKey: String = key,
    ): BackupProblem = assertThrows(BackupException::class.java) { read(bytes, recoveryKey) }.problem

    @Test
    fun roundTripsEveryEntryAndTheManifest() {
        val (seen, entries) = read(write())
        assertEquals("Ada Lovelace of Nowhere-in-Particular", seen.displayName)
        assertEquals(14, seen.schemaVersion)
        assertEquals(listOf(BackupFormat.ENTRY_IDENTITY, BackupFormat.ENTRY_DATABASE), seen.entries.map { it.name })
        assertEquals(db.size.toLong(), seen.entry(BackupFormat.ENTRY_DATABASE)!!.size)
        assertArrayEquals(identity, entries[BackupFormat.ENTRY_IDENTITY])
        assertArrayEquals(db, entries[BackupFormat.ENTRY_DATABASE])
    }

    @Test
    fun thePrefixCarriesNothingButSaltAndDate() {
        val bytes = write()
        val (prefix, parsed) = BackupArchive.readPrefix(ByteArrayInputStream(bytes))
        assertArrayEquals(header.salt, parsed.salt)
        assertEquals(header.createdAt, parsed.createdAt)
        assertTrue("prefix is ${prefix.size} B", prefix.size < 96)
        // The node id and the name are inside the seal: the file's bytes never contain them in the clear
        // (both are long enough that a chance match inside megabytes of ciphertext is not a concern).
        val plain = bytes.decodeToString(throwOnInvalidSequence = false)
        assertFalse(plain.contains(manifest.nodeId))
        assertFalse(plain.contains("Ada Lovelace of Nowhere"))
    }

    @Test
    fun theManifestCallbackCanStopBeforeAnyEntryIsRead() {
        var sinksOpened = 0
        BackupArchive.read(
            ByteArrayInputStream(write()),
            key,
            onManifest = { false },
            sink = {
                sinksOpened++
                OutputStream.nullOutputStream()
            },
        )
        assertEquals(0, sinksOpened)
    }

    @Test
    fun theWrongKeyIsRefusedBeforeAnyEntryIsWritten() {
        var sinksOpened = 0
        val e =
            assertThrows(BackupException::class.java) {
                BackupArchive.read(
                    ByteArrayInputStream(write()),
                    BackupKeys.generate(),
                    onManifest = { true },
                    sink = {
                        sinksOpened++
                        OutputStream.nullOutputStream()
                    },
                )
            }
        assertEquals(BackupProblem.WRONG_KEY_OR_DAMAGED, e.problem)
        assertEquals(0, sinksOpened)
    }

    @Test
    fun aFlippedByteInsideTheSealIsRefused() {
        val bytes = write()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
        assertEquals(BackupProblem.WRONG_KEY_OR_DAMAGED, problemOf(bytes))
    }

    @Test
    fun aFlippedByteInThePrefixIsRefusedAsTheAad() {
        val bytes = write()
        // Inside the header CBOR (past the magic + version + length), so the prefix still parses.
        val at = BackupFormat.MAGIC.size + 4 + 3
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        val problem = problemOf(bytes)
        assertTrue(problem == BackupProblem.WRONG_KEY_OR_DAMAGED || problem == BackupProblem.NOT_A_BACKUP)
    }

    @Test
    fun aTruncatedFileIsRefused() {
        val bytes = write()
        // Inside the seal, a cut is indistinguishable from damage: Tink's tag on the (now unfinished) last
        // segment is what refuses it, whether the cut took a segment or three bytes.
        assertEquals(BackupProblem.WRONG_KEY_OR_DAMAGED, problemOf(bytes.copyOf(bytes.size - 1000)))
        assertEquals(BackupProblem.WRONG_KEY_OR_DAMAGED, problemOf(bytes.copyOf(bytes.size - 3)))
        // Inside the prefix, it is a truncation.
        assertEquals(BackupProblem.TRUNCATED, problemOf(bytes.copyOf(BackupFormat.MAGIC.size + 4 + 5)))
    }

    @Test
    fun aForeignFileIsNotABackup() {
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf("PK\u0003\u0004 definitely a zip".encodeToByteArray()))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf(ByteArray(0)))
        assertEquals(BackupProblem.NOT_A_BACKUP, problemOf(BackupFormat.MAGIC + byteArrayOf(1, 1, 0x7F, 0x7F)))
    }

    @Test
    fun aNewerFormatIsRefusedBeforeTheSeal() {
        val bytes = write()
        bytes[BackupFormat.MAGIC.size] = (BackupFormat.FORMAT_VERSION + 1).toByte()
        assertEquals(BackupProblem.NEWER_FORMAT, problemOf(bytes))
        val kdf = write()
        kdf[BackupFormat.MAGIC.size + 1] = (BackupFormat.KDF_RECOVERY_KEY + 1).toByte()
        assertEquals(BackupProblem.NEWER_FORMAT, problemOf(kdf))
    }

    @Test
    fun recoveryKeysAreThirtyDigitsAndParseBackFromTheirDisplayForm() {
        val generated = BackupKeys.generate()
        assertEquals(BackupKeys.DIGITS, generated.length)
        assertTrue(generated.all { it.isDigit() })
        val shown = BackupKeys.display(generated)
        assertEquals(6, shown.split(' ').size)
        assertEquals(generated, BackupKeys.parse(shown))
        assertEquals(generated, BackupKeys.parse(shown.replace(' ', '-')))
        assertEquals(generated, BackupKeys.parse(" $shown\n"))
        assertNull(BackupKeys.parse(generated.dropLast(1)))
        assertNull(BackupKeys.parse(generated + "1"))
    }

    @Test
    fun recoveryKeysUseEveryDigit() {
        // A generator stuck on a sub-range would be a silent loss of entropy; 300 draws cover 0-9 comfortably.
        val digits = (1..10).joinToString("") { BackupKeys.generate() }.toSet()
        assertEquals(('0'..'9').toSet(), digits)
    }
}
