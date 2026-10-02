package app.getknit.knit.data.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.Unwrapped
import app.getknit.knit.data.settings.SettingsKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The stager is the one gate in front of two readers that fail destructively — [DatabaseKey] wipes the
 * database on a passphrase it cannot unwrap, [IdentityKeyStore] mints a fresh identity on a file it cannot
 * parse — so every way a backup can be wrong is pinned here to the [BackupProblem] the restore screen names,
 * and to the one thing that must hold for all of them: no READY marker and no staging directory left behind.
 *
 * On Robolectric there is no AndroidKeyStore, so the wrap is a stand-in ([WRAP_TAG] ‖ plain) written where
 * the real one would be; the database is framework SQLite opened with the unkeyed driver. The keyed path
 * end to end is `BackupRoundTripTest` in `androidTest`.
 */
@RunWith(AndroidJUnit4::class)
class RestoreStagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = BackupKeys.generate()
    private val staging: File get() = RestoreApplier.stagingDir(context)
    private val passphrase = ByteArray(DatabaseKey.PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }

    /** A wrap that throws, for the case where the Keystore itself refuses. */
    private var wrapThrows: Throwable? = null

    /** A wrap that reads back something other than what it was handed. */
    private var wrapReadsBackWrong = false

    /** A wrap whose read-back the Keystore refuses. */
    private var readBackRefused = false

    private val stager =
        RestoreStager(
            context,
            openDatabase = { file, _ -> AndroidSQLiteDriver().open(file.absolutePath) },
            secretAt = { _, fileName, dir -> fakeSecret(fileName, dir) },
        )

    @Before
    @After
    fun clean() {
        staging.deleteRecursively()
        wrapThrows = null
        wrapReadsBackWrong = false
        readBackRefused = false
    }

    private fun fakeSecret(
        fileName: String,
        dir: File,
    ): KeystoreSecret {
        val file = File(dir, fileName)
        return mockk {
            every { store(any(), any()) } answers {
                wrapThrows?.let { throw it }
                file.writeBytes(byteArrayOf(WRAP_TAG) + firstArg<ByteArray>())
            }
            every { read() } answers {
                val plain = file.readBytes().copyOfRange(1, file.length().toInt())
                when {
                    readBackRefused -> Unwrapped.Unavailable(GeneralSecurityException("keystore unavailable"))
                    wrapReadsBackWrong -> Unwrapped.Present(plain.also { it[0] = (it[0] + 1).toByte() })
                    else -> Unwrapped.Present(plain)
                }
            }
        }
    }

    // --- building backups ---

    private fun database(userVersion: Int = KnitDatabase.SCHEMA_VERSION): ByteArray {
        val file = File(context.cacheDir, "source-${System.nanoTime()}.db")
        AndroidSQLiteDriver().open(file.absolutePath).use { c ->
            c.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, body TEXT)")
            c.execSQL("INSERT INTO messages VALUES ('m1', 'hello')")
            c.execSQL("PRAGMA user_version = $userVersion")
        }
        return file.readBytes().also { file.delete() }
    }

    private fun settings(): ByteArray =
        ByteArrayOutputStream()
            .also { runBlocking { SettingsSnapshot.export(preferencesOf(DISPLAY_NAME to "Ada"), it) } }
            .toByteArray()

    private fun entries(): MutableMap<String, ByteArray> =
        linkedMapOf(
            BackupFormat.ENTRY_IDENTITY to identity,
            BackupFormat.ENTRY_DB_PASSPHRASE to passphrase,
            BackupFormat.ENTRY_SETTINGS to settings(),
            BackupFormat.ENTRY_DATABASE to database(),
        )

    private fun backup(
        entries: Map<String, ByteArray> = entries(),
        nodeId: String = identityNodeId,
        schemaVersion: Int = KnitDatabase.SCHEMA_VERSION,
    ): ByteArray {
        val manifest =
            BackupManifest(
                v = BackupFormat.FORMAT_VERSION,
                schemaVersion = schemaVersion,
                appVersionCode = 1,
                appVersionName = "test",
                createdAt = CREATED_AT,
                nodeId = nodeId,
                displayName = "Ada",
                entries = emptyList(),
            )
        val out = ByteArrayOutputStream()
        BackupArchive.write(
            out,
            key,
            BackupHeader(salt = BackupKeys.newSalt(), createdAt = CREATED_AT),
            manifest,
            entries.map { (name, bytes) -> BackupSource(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) } },
        )
        return out.toByteArray()
    }

    private suspend fun stage(
        bytes: ByteArray,
        recoveryKey: String = key,
    ) = stager.stage(ByteArrayInputStream(bytes), recoveryKey)

    /** Staging [bytes] is refused with [expected], and nothing is left for the restart to install. */
    private suspend fun assertRefused(
        expected: BackupProblem,
        bytes: ByteArray,
        recoveryKey: String = key,
    ) {
        val e = runCatching { stage(bytes, recoveryKey) }.exceptionOrNull()
        assertTrue("expected a BackupException($expected), got $e", e is BackupException)
        assertEquals(expected, (e as BackupException).problem)
        assertFalse("READY must not survive a refusal", stager.isStaged())
        assertFalse("the staging directory must not survive a refusal", staging.exists())
    }

    // --- the happy path ---

    @Test
    fun aGoodBackupIsStagedWrappedAndMarkedReady() =
        runTest {
            var lastProgress = 0L to 0L
            val manifest = stager.stage(ByteArrayInputStream(backup()), key) { done, total -> lastProgress = done to total }

            assertEquals(identityNodeId, manifest.nodeId)
            assertTrue(stager.isStaged())
            assertEquals("${KnitDatabase.SCHEMA_VERSION}\n", File(staging, RestoreApplier.MANIFEST).readText())
            // The secrets went through the wrap, under the live file names the app's readers open.
            assertArrayEquals(byteArrayOf(WRAP_TAG) + identity, File(staging, IdentityKeyStore.FILE_NAME).readBytes())
            assertArrayEquals(byteArrayOf(WRAP_TAG) + passphrase, File(staging, DatabaseKey.KEY_FILE).readBytes())
            // The plaintext passphrase never touches the staging directory under its archive name (the
            // identity's archive name is its live file name, asserted wrapped above).
            assertFalse(File(staging, BackupFormat.ENTRY_DB_PASSPHRASE).exists())
            AndroidSQLiteDriver().open(File(staging, BackupFormat.ENTRY_DATABASE).absolutePath).use { c ->
                c.prepare("SELECT body FROM messages").use {
                    assertTrue(it.step())
                    assertEquals("hello", it.getText(0))
                }
            }
            // The settings carry the backup's keys plus the two a restore must set.
            val prefs =
                File(staging, "${SettingsKeys.DATASTORE_NAME}.preferences_pb")
                    .inputStream()
                    .use { PreferencesFileSerializer.readFrom(it) }
            assertEquals("Ada", prefs[DISPLAY_NAME])
            assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.ONBOARDING_SEEN)])
            assertEquals(true, prefs[booleanPreferencesKey(SettingsKeys.RESTORE_PENDING)])
            assertTrue(lastProgress.second > 0)
            assertEquals(lastProgress.second, lastProgress.first)
        }

    @Test
    fun aPeekReadsTheManifestAndStagesNothing() {
        val manifest = stager.peek(ByteArrayInputStream(backup()), key)
        assertEquals(identityNodeId, manifest.nodeId)
        assertEquals("Ada", manifest.displayName)
        assertEquals(KnitDatabase.SCHEMA_VERSION, manifest.schemaVersion)
        assertFalse(stager.isStaged())
    }

    @Test
    fun aRestageClearsWhatAnEarlierAttemptLeft() =
        runTest {
            staging.mkdirs()
            File(staging, "leftover").writeText("from a crashed attempt")
            stage(backup())
            assertFalse(File(staging, "leftover").exists())
            assertTrue(stager.isStaged())
        }

    @Test
    fun discardDropsAStagedRestore() =
        runTest {
            stage(backup())
            stager.discard()
            assertFalse(stager.isStaged())
            assertFalse(staging.exists())
        }

    // --- the archive and its manifest ---

    @Test
    fun theWrongRecoveryKeyIsRefused() = runTest { assertRefused(BackupProblem.WRONG_KEY_OR_DAMAGED, backup(), BackupKeys.generate()) }

    @Test
    fun aBackupFromANewerSchemaIsRefused() =
        runTest { assertRefused(BackupProblem.NEWER_APP, backup(schemaVersion = KnitDatabase.SCHEMA_VERSION + 1)) }

    @Test
    fun aBackupMissingAnyRequiredEntryIsNotABackup() =
        runTest {
            for (name in listOf(
                BackupFormat.ENTRY_IDENTITY,
                BackupFormat.ENTRY_DB_PASSPHRASE,
                BackupFormat.ENTRY_SETTINGS,
                BackupFormat.ENTRY_DATABASE,
            )) {
                assertRefused(BackupProblem.NOT_A_BACKUP, backup(entries().apply { remove(name) }))
            }
        }

    @Test
    fun anUnknownEntryIsNotABackup() =
        runTest { assertRefused(BackupProblem.NOT_A_BACKUP, backup(entries().apply { put("photos.zip", byteArrayOf(1)) })) }

    @Test
    fun anOversizedSecretEntryIsNotABackup() =
        runTest {
            val oversized = entries().apply { put(BackupFormat.ENTRY_IDENTITY, ByteArray(64 * 1024 + 1)) }
            assertRefused(BackupProblem.NOT_A_BACKUP, backup(oversized))
        }

    // --- the pieces, each checked before READY ---

    @Test
    fun aPassphraseOfTheWrongLengthIsAMismatch() =
        runTest { assertRefused(BackupProblem.MISMATCH, backup(entries().apply { put(BackupFormat.ENTRY_DB_PASSPHRASE, ByteArray(31)) })) }

    @Test
    fun anIdentityThatDoesNotParseIsAMismatch() =
        runTest {
            assertRefused(BackupProblem.MISMATCH, backup(entries().apply { put(BackupFormat.ENTRY_IDENTITY, byteArrayOf(1, 2, 3)) }))
        }

    @Test
    fun anIdentityThatIsNotTheManifestsIsAMismatch() = runTest { assertRefused(BackupProblem.MISMATCH, backup(nodeId = "someone-else")) }

    @Test
    fun aDatabaseAtAnotherSchemaThanTheManifestIsAMismatch() =
        runTest {
            val older = database(userVersion = KnitDatabase.SCHEMA_VERSION - 1)
            assertRefused(BackupProblem.MISMATCH, backup(entries().apply { put(BackupFormat.ENTRY_DATABASE, older) }))
        }

    @Test
    fun aDatabaseThatIsNotOneIsAMismatch() =
        runTest {
            val garbage = ByteArray(8192).also { SecureRandom().nextBytes(it) }
            assertRefused(BackupProblem.MISMATCH, backup(entries().apply { put(BackupFormat.ENTRY_DATABASE, garbage) }))
        }

    @Test
    fun settingsThatDoNotParseAreAMismatch() =
        runTest {
            val garbage = ByteArray(64) { 0x7F }
            assertRefused(BackupProblem.MISMATCH, backup(entries().apply { put(BackupFormat.ENTRY_SETTINGS, garbage) }))
        }

    @Test
    fun aWrapThatDoesNotReadBackIsAMismatch() =
        runTest {
            wrapReadsBackWrong = true
            assertRefused(BackupProblem.MISMATCH, backup())
        }

    @Test
    fun aKeystoreThatRefusesTheWrapIsTheKeystoreNotTheFile() =
        runTest {
            wrapThrows = GeneralSecurityException("keystore unavailable")
            assertRefused(BackupProblem.KEYSTORE_UNAVAILABLE, backup())
        }

    @Test
    fun aKeystoreThatRefusesTheReadBackIsTheKeystoreNotTheFile() =
        runTest {
            readBackRefused = true
            assertRefused(BackupProblem.KEYSTORE_UNAVAILABLE, backup())
        }

    @Test
    fun aRefusalAfterAGoodStageLeavesNothingReady() =
        runTest {
            stage(backup())
            assertTrue(stager.isStaged())
            assertRefused(BackupProblem.MISMATCH, backup(nodeId = "someone-else"))
            assertThrows(BackupException::class.java) { stager.peek(ByteArrayInputStream(backup()), BackupKeys.generate()) }
        }

    private companion object {
        const val WRAP_TAG: Byte = 0x57
        const val CREATED_AT = 1_700_000_000_000L
        val DISPLAY_NAME = stringPreferencesKey("display_name")

        /** One real identity for the class: Tink keygen is the slow part of a case. */
        val identity: ByteArray by lazy {
            var stored: ByteArray? = null
            val secret =
                mockk<KeystoreSecret> {
                    every { read() } answers { stored?.let { Unwrapped.Present(it.copyOf()) } ?: Unwrapped.Absent }
                    every { store(any(), any()) } answers { stored = firstArg<ByteArray>().copyOf() }
                }
            IdentityKeyStore(secret).keys()
            checkNotNull(stored)
        }
        val identityNodeId: String by lazy { IdentityKeyStore.nodeIdOf(identity) }
    }
}
