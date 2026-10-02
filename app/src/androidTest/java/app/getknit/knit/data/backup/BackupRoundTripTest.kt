package app.getknit.knit.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.SqlCipherKey
import app.getknit.knit.data.crypto.Unwrapped
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsKeys
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A backup, end to end on a device, with nothing faked: the real [BackupWriter] seals a SQLCipher database,
 * the Keystore-wrapped identity and passphrase and the settings; the real [RestoreStager] opens it, re-wraps
 * the secrets under the live aliases and opens the database under its passphrase with the driver
 * ([BackupWriter.openSqlCipher]); and [RestoreApplier] moves the staged files into place. What comes out the
 * other end must be the same phone: the same node id from the identity file, the same passphrase from the key
 * file, the same message from the database. The JVM half (every refusal) is `RestoreStagerTest`.
 *
 * Every directory is a sandbox ([SandboxContext]); the installed app's files are never read or written.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val root =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "backup-roundtrip-${System.nanoTime()}")
    private val context = SandboxContext(InstrumentationRegistry.getInstrumentation().targetContext, root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun aBackupRestoresTheSamePhone() =
        runBlocking {
            // The phone being backed up: a live keyed database with one message, an identity, a name.
            val databaseKey = DatabaseKey(context)
            val passphrase = databaseKey.getOrCreate()
            val live = KnitDatabase.build(context, passphrase, context.getDatabasePath(KnitDatabase.DB_NAME).absolutePath)
            live.messageDao().upsert(MessageEntity(id = "m1", senderId = "bob", conversationId = "bob", body = "still here", sentAt = 1L))
            val identitySecret = BackupWriter.identitySecret(context)
            val identity = Identity(IdentityKeyStore(identitySecret)) { "sandbox-device" }
            val nodeId = identity.nodeId()
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }
            val settings = SettingsStore(dataStore).also { it.setDisplayName("Ada") }

            val key = BackupKeys.generate()
            val archive = ByteArrayOutputStream()
            val written =
                try {
                    BackupWriter(context, identity, identitySecret, databaseKey, settings, dataStore).write(archive, key)
                } finally {
                    live.close()
                }
            assertEquals(nodeId, written.nodeId)
            assertEquals("Ada", written.displayName)
            assertFalse("the scratch copy is gone", File(context.noBackupFilesDir, BackupWriter.SCRATCH_DIR).exists())

            val stager = RestoreStager(context, BackupWriter::openSqlCipher)
            val staged = stager.stage(ByteArrayInputStream(archive.toByteArray()), key)
            assertEquals(nodeId, staged.nodeId)
            assertTrue(stager.isStaged())

            // Install into a second, empty phone's layout.
            val restored = File(root, "restored")
            val targets =
                RestoreApplier.Targets(
                    database = File(restored, "databases/${KnitDatabase.DB_NAME}"),
                    identity = File(restored, "files/${IdentityKeyStore.FILE_NAME}"),
                    databaseKey = File(restored, "files/${DatabaseKey.KEY_FILE}"),
                    settings = File(restored, "files/datastore/${SettingsKeys.DATASTORE_NAME}.preferences_pb"),
                )
            assertTrue(RestoreApplier.apply(RestoreApplier.stagingDir(context), targets, KnitDatabase.SCHEMA_VERSION))
            assertFalse(stager.isStaged())

            // The same passphrase and the same identity come back through the live aliases' wraps...
            val filesDir = targets.identity.parentFile!!
            val restoredPassphrase = KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, filesDir).read()
            assertArrayEquals(passphrase, (restoredPassphrase as Unwrapped.Present).bytes)
            val restoredIdentity = KeystoreSecret(context, IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, filesDir).read()
            assertEquals(nodeId, IdentityKeyStore.nodeIdOf((restoredIdentity as Unwrapped.Present).bytes))
            // ...and the database opens under it with the message in it.
            SqlCipherKey.open(targets.database, passphrase).use { c ->
                c.prepare("SELECT body FROM messages WHERE id = 'm1'").use {
                    assertTrue(it.step())
                    assertEquals("still here", it.getText(0))
                }
            }
            assertTrue(targets.settings.length() > 0)
        }
}
